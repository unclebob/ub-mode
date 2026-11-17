"""Run mutants in symlink overlays, the way clj-mutate runs its workers.

Each worker is a directory under target/mutation-workers. Project files are
linked in. The file under test is a private copy, so workers can mutate it at
the same time without touching the original tree. The overlay is removed when
the file's mutants finish.
"""

from __future__ import annotations

import os
import re
import sys
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from queue import Empty, Queue
from threading import Lock

from mutator.model import Site
from mutator.runner import Command
from mutator.sites import apply_site

# Build output and the worker tree itself must not be shared. A link to
# ``target`` would point a worker at the directory that contains the worker.
SKIP_LINK = frozenset(
    {
        ".git",
        ".hg",
        ".svn",
        ".idea",
        ".venv",
        "venv",
        "target",
        "dist",
        "build",
        "out",
        "coverage",
        ".metrics",
        "__pycache__",
        ".pytest_cache",
        ".mypy_cache",
        ".clj-kondo",
        ".gradle",
    }
)

CONFIGS = (
    "deps.edn",
    "bb.edn",
    "pom.xml",
    "build.gradle",
    "build.gradle.kts",
    "settings.gradle",
    "go.mod",
    "go.sum",
    "package.json",
    "package-lock.json",
    "pnpm-lock.yaml",
    "yarn.lock",
    "tsconfig.json",
    "Cargo.toml",
    "Cargo.lock",
    "pyproject.toml",
    "pytest.ini",
    "setup.cfg",
    "setup.py",
    "conftest.py",
    "requirements.txt",
    "Pipfile",
    "poetry.lock",
)


def worker_count(site_count: int, requested: int | None) -> int:
    """The smaller of the sites, the cores, and an explicit --max-workers."""

    processors = os.cpu_count() or 1
    limit = processors if requested is None else requested
    return max(1, min(site_count, processors, limit))


def new_run_dir(root: Path) -> Path:
    directory = root / "target" / "mutation-workers" / f"run-{uuid.uuid4()}"
    directory.mkdir(parents=True)
    return directory


def symlink(link: Path, target: Path) -> None:
    link.parent.mkdir(parents=True, exist_ok=True)
    if link.is_symlink() or link.exists():
        link.unlink()
    link.symlink_to(target.resolve())


def delete_tree(path: Path) -> None:
    """Remove a worker tree without following a link into the real project."""

    if path.is_symlink():
        path.unlink()
        return
    if not path.exists():
        return
    if path.is_dir():
        for child in list(path.iterdir()):
            delete_tree(child)
        path.rmdir()
        return
    path.unlink()


def _copy_configs(worker: Path, root: Path, relative: str) -> None:
    for name in CONFIGS:
        source = root / name
        if not source.is_file():
            continue
        if name == relative:
            continue
        (worker / name).write_bytes(source.read_bytes())


def _link_children(worker_dir: Path, real_dir: Path, skip: set[str]) -> None:
    if not real_dir.is_dir():
        return
    for child in real_dir.iterdir():
        if child.name in skip or child.name in SKIP_LINK:
            continue
        destination = worker_dir / child.name
        if destination.exists() or destination.is_symlink():
            continue
        symlink(destination, child)


# Node, and Vite in front of it, resolve a relative import from the real path
# of the module. A symlinked test therefore loads the original source.
_SPECIFIER = re.compile(
    r"""(?:from|import|require)\s*\(?\s*['"](\.[^'"]+)['"]""",
)
_EXTENSIONS = (".ts", ".tsx", ".mts", ".cts", ".js", ".jsx", ".mjs", ".cjs")


def _specifiers(text: str) -> list[str]:
    found = []
    for match in _SPECIFIER.finditer(text):
        specifier = match.group(1).split("?", 1)[0].split("#", 1)[0]
        if specifier.startswith("."):
            found.append(specifier)
    return found


def _stem(base: Path) -> Path:
    if base.suffix in _EXTENSIONS:
        return Path(str(base)[: -len(base.suffix)])
    return base


def _candidates(directory: Path, specifier: str) -> list[Path]:
    base = directory / specifier
    stem = _stem(base)
    names = [base]
    for ext in _EXTENSIONS:
        names.append(Path(str(stem) + ext))
    for ext in _EXTENSIONS:
        names.append(stem / f"index{ext}")
    return names


def _inside(root: Path, path: Path) -> Path | None:
    try:
        relative = path.resolve().relative_to(root.resolve())
    except ValueError:
        return None
    return root / relative


def _imported_files(importer: Path, root: Path) -> set[Path]:
    try:
        text = importer.read_text(encoding="utf-8")
    except (OSError, UnicodeError):
        return set()
    found = set()
    for specifier in _specifiers(text):
        for candidate in _candidates(importer.parent, specifier):
            local = _inside(root, candidate)
            if local is not None and local.is_file():
                found.add(local.resolve())
    return found


def _keep_dir(name: str) -> bool:
    return name not in SKIP_LINK and name != "node_modules"


def _source_files(root: Path):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [name for name in dirnames if _keep_dir(name)]
        for name in filenames:
            if Path(name).suffix in _EXTENSIONS:
                yield Path(dirpath) / name


def importers_of(root: Path, relative: str) -> set[str]:
    """Modules that reach ``relative`` through a relative import.

    Those modules have to be real files in the worker. A symlink would send
    Node back to the original tree.
    """

    target = (root / relative).resolve()
    reverse: dict[Path, set[Path]] = {}
    for source in _source_files(root):
        for imported in _imported_files(source, root):
            reverse.setdefault(imported, set()).add(source.resolve())
    found: set[Path] = set()
    pending = [target]
    while pending:
        current = pending.pop()
        for importer in reverse.get(current, ()):
            if importer in found or importer == target:
                continue
            found.add(importer)
            pending.append(importer)
    root_resolved = root.resolve()
    return {path.relative_to(root_resolved).as_posix() for path in found}


def _expand_directory(link: Path, real_dir: Path) -> None:
    """Replace a linked directory so a copied module is not written through it."""

    if not real_dir.is_dir():
        return
    link.unlink()
    link.mkdir()
    for child in real_dir.iterdir():
        if child.name in SKIP_LINK:
            continue
        symlink(link / child.name, child)


def _copy_importer(worker: Path, root: Path, relative: str) -> None:
    real = root
    current = worker
    for segment in relative.split("/")[:-1]:
        real = real / segment
        current = current / segment
        if current.is_symlink():
            _expand_directory(current, real)
        elif not current.exists():
            current.mkdir(parents=True)
    destination = worker / relative
    if destination.is_symlink():
        destination.unlink()
    elif destination.exists():
        return
    destination.write_bytes((root / relative).read_bytes())


def _overlay(worker: Path, root: Path, relative: str, original: bytes) -> None:
    """Real directories along the source path. Every sibling is a link."""

    segments = tuple(relative.split("/"))
    for index, _segment in enumerate(segments[:-1]):
        rel_dir = Path(*segments[: index + 1])
        _link_children(worker / rel_dir, root / rel_dir, {segments[index + 1]})
    destination = worker.joinpath(*segments)
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(original)


def create_workers(
    base: Path, root: Path, relative: str, original: bytes, count: int
) -> list[Path]:
    created = []
    first = relative.split("/", 1)[0]
    importers = importers_of(root, relative)
    for index in range(count):
        worker = base / f"worker-{index}"
        worker.mkdir(parents=True, exist_ok=True)
        _copy_configs(worker, root, relative)
        _link_children(worker, root, {first})
        _overlay(worker, root, relative, original)
        for importer in importers:
            _copy_importer(worker, root, importer)
        created.append(worker)
    return created


def mapped_cwd(worker: Path, root: Path, cwd: Path) -> Path:
    try:
        relative = cwd.resolve().relative_to(root.resolve())
    except ValueError:
        return worker
    return (worker / relative).resolve()


def _execute(
    directory: Path,
    site: Site,
    root: Path,
    relative: str,
    original: bytes,
    runner,
    command: Command,
    cwd: Path,
    timeout: float,
    file_key: str,
) -> str:
    current = original[site.start : site.end].decode("utf-8")
    if current != site.original:
        print(
            f"Skipped {file_key}:{site.line} {site.description}; source bytes moved",
            file=sys.stderr,
        )
        return "survived"
    destination = directory / relative
    mutated = apply_site(original.decode("utf-8"), site.start, site.end, site.mutant)
    destination.write_text(mutated, encoding="utf-8")
    try:
        result = runner.run(command, mapped_cwd(directory, root, cwd), timeout)
    finally:
        destination.write_bytes(original)
    if result.timed_out or result.code != 0:
        return "killed"
    return "survived"


def _drain(
    directory: Path,
    pending: Queue,
    lock: Lock,
    outcomes: dict[str, str],
    root: Path,
    relative: str,
    original: bytes,
    runner,
    command: Command,
    cwd: Path,
    timeout: float,
    file_key: str,
) -> None:
    while True:
        try:
            site = pending.get_nowait()
        except Empty:
            return
        if runner.verbose:
            with lock:
                _report(file_key, site)
        status = _execute(
            directory,
            site,
            root,
            relative,
            original,
            runner,
            command,
            cwd,
            timeout,
            file_key,
        )
        with lock:
            outcomes[site.mutation_id] = status


def _report(file_key: str, site: Site) -> None:
    print(f"{file_key}:{site.line} {site.description}", file=sys.stderr)


def _run_all(
    directories: list[Path],
    sites: list[Site],
    outcomes: dict[str, str],
    root: Path,
    relative: str,
    original: bytes,
    runner,
    command: Command,
    cwd: Path,
    timeout: float,
    file_key: str,
) -> None:
    pending: Queue = Queue()
    for site in sites:
        pending.put(site)
    lock = Lock()
    with ThreadPoolExecutor(max_workers=len(directories)) as pool:
        futures = [
            pool.submit(
                _drain,
                directory,
                pending,
                lock,
                outcomes,
                root,
                relative,
                original,
                runner,
                command,
                cwd,
                timeout,
                file_key,
            )
            for directory in directories
        ]
        for future in futures:
            future.result()


def run_mutants(
    root: Path,
    source: Path,
    original: bytes,
    sites: list[Site],
    max_workers: int | None,
    runner,
    command: Command,
    cwd: Path,
    timeout: float,
    file_key: str,
    outcomes: dict[str, str],
) -> None:
    """Mutate ``sites`` in parallel overlays of ``root``. The source stays put."""

    if not sites:
        return
    base = new_run_dir(root)
    try:
        relative = source.resolve().relative_to(root.resolve()).as_posix()
        directories = create_workers(
            base, root, relative, original, worker_count(len(sites), max_workers)
        )
        _run_all(
            directories,
            sites,
            outcomes,
            root,
            relative,
            original,
            runner,
            command,
            cwd,
            timeout,
            file_key,
        )
    finally:
        delete_tree(base)
