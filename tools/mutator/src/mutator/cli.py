"""Command line for the multi-language mutation tool."""

from __future__ import annotations

import os
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

from mutator.crapper_link import ensure_crapper
from mutator.coverage import covered_lines
from mutator.engine import mutate_file, restore_backups, scan_file
from mutator.report import format_results, format_site_log
from mutator.runner import CommandRunner


def _skipped_directory_text() -> str:
    """The directories the walker skips, named the same way in --help."""

    names = {
        ".clj-kondo",
        ".git",
        ".hg",
        ".idea",
        ".metrics",
        ".svn",
        ".venv",
        "__pycache__",
        "__tests__",
        "build",
        "coverage",
        "dist",
        "node_modules",
        "out",
        "spec",
        "specs",
        "target",
        "test",
        "testdata",
        "tests",
        "venv",
        "vendor",
    }
    try:
        crapper = ensure_crapper()
        names = set(crapper.discover.SKIP_DIRS) | set(crapper.discover.TEST_DIRS)
    except ImportError:
        pass
    ordered = sorted(names)
    return ", ".join(ordered[:-1]) + ", and " + ordered[-1]


HELP = f"""\
Usage: mutator [options] [path-or-filter ...]

Discover mutation sites in Clojure, Java, Go, TypeScript, Rust, and Python,
run the project's tests against each one, and write `.metrics/mutate` for
uml-viewer. Namespaces and function names are the ones crapper writes into
`.metrics/crap.edn`.

A form id is `defn/<name>` or, for a private operation, `defn-/<name>`.
uml-viewer joins that name to the operation and joins `:namespace` to the
class.

Options:
  -h, --help                    Print this help and exit.
  --root <path>                 Project root. Metrics are written here.
                                Default: the current directory.
  -s, --source-root <path>      Walk this tree instead of the project root.
                                May be repeated.
  --changed                     Mutate added and modified source files from
                                git status.
  --scan                        List mutation sites. Do not run tests or
                                write metrics.
  --mutate-all                  Run every covered site, including ones the
                                last snapshot already killed.
  --since-last-run              Run survivors and sites in new or rewritten
                                functions. This is the default when a snapshot
                                exists.
  --lines <n,n,...>             Run only mutations on these source lines.
  --no-coverage                 Treat every site as covered.
  --use-existing-coverage       Read coverage already on disk. Same as
                                --reuse-coverage.
  --reuse-coverage              Read coverage already on disk.
  --coverage-command <cmd>      Run this command instead of the per-language
                                coverage tools, then read the reports it wrote.
  --test-command <cmd>          Run this command for the baseline and every
                                mutant. The working directory is the project
                                root.
  --timeout-factor <number>     Mutant timeout, as a multiple of the baseline
                                duration. Default: 10. The timeout is at least
                                2 seconds.
  --mutation-warning <number>   Warn when a file selects more covered sites
                                than this. Default: 50.
  --max-workers <number>        Run at most this many mutants of one file at
                                once. Default: one per core. The run uses the
                                smaller of this limit, the cores, and the
                                number of selected sites.
  --verbose                     Print each test command and each mutant.

Arguments:
  path              File or directory to mutate. Test paths are skipped.
  filter            When the argument is not a path, only source files whose
                    path contains this text are mutated.

With no paths, source files under the project root are mutated. Directories
named {_skipped_directory_text()} are skipped.

The default, once a snapshot exists, reruns survivors and sites in functions
whose text changed. Killed mutants in unchanged functions are kept.

Selected mutants of one file run at the same time, one worker per core unless
--max-workers says otherwise. A worker is a symlink overlay under
target/mutation-workers with its own copy of the mutated file. The project
tree is left unchanged. A copy left under target/mutator-backup/ by an
interrupted older run is restored before a non-scan run.

Exit codes:
  0  every executed mutant was killed, or there was nothing to run
  1  usage error
  2  baseline tests failed
  3  at least one mutant survived

Coverage, when it is produced, uses the same commands as crapper:
  Clojure      clj -M:cov --lcov
  Java         Maven JaCoCo
  Go           go test ./... -coverprofile=...
  TypeScript   npm run coverage, Vitest, or c8
  Rust         cargo llvm-cov or cargo tarpaulin
  Python       coverage.py LCOV

A site on a line the report does not mark as hit is uncovered and is not run.
"""


@dataclass
class Options:
    action: str
    message: str = ""
    exit_code: int = 0
    project_root: Path = field(default_factory=lambda: Path("."))
    source_roots: list[str] = field(default_factory=list)
    positionals: list[str] = field(default_factory=list)
    scan: bool = False
    mutate_all: bool = False
    since_last_run: bool = False
    lines: set[int] | None = None
    no_coverage: bool = False
    use_existing_coverage: bool = False
    coverage_command: str | None = None
    test_command: str | None = None
    timeout_factor: float = 10.0
    mutation_warning: int = 50
    max_workers: int | None = None
    changed: bool = False
    verbose: bool = False


def _take(args: list[str], index: int, option: str) -> str:
    if index + 1 >= len(args) or not args[index + 1] or args[index + 1].startswith("-"):
        raise ValueError(f"{option} requires a value")
    return args[index + 1]


def _workers(value: str) -> int:
    try:
        count = int(value)
    except ValueError:
        raise ValueError("--max-workers requires a positive integer") from None
    if count < 1:
        raise ValueError("--max-workers requires a positive integer")
    return count


def _lines(value: str) -> set[int]:
    found = set()
    for piece in value.split(","):
        piece = piece.strip()
        if not piece:
            continue
        number = int(piece)
        if number < 1:
            raise ValueError("--lines requires positive line numbers")
        found.add(number)
    if not found:
        raise ValueError("--lines requires a line number")
    return found


def parse_args(argv: list[str] | None = None) -> Options:
    args = list(sys.argv[1:] if argv is None else argv)
    if any(arg in {"-h", "--help"} for arg in args):
        return Options(action="help", message=HELP, exit_code=0)
    options = Options(action="mutate")
    index = 0
    try:
        while index < len(args):
            arg = args[index]
            if arg in {"-s", "--source-root"}:
                options.source_roots.append(_take(args, index, arg))
                index += 2
                continue
            if arg == "--root":
                options.project_root = Path(_take(args, index, arg))
                index += 2
                continue
            if arg == "--coverage-command":
                options.coverage_command = _take(args, index, arg)
                index += 2
                continue
            if arg == "--test-command":
                options.test_command = _take(args, index, arg)
                index += 2
                continue
            if arg == "--timeout-factor":
                options.timeout_factor = float(_take(args, index, arg))
                if options.timeout_factor <= 0:
                    raise ValueError("--timeout-factor requires a positive number")
                index += 2
                continue
            if arg == "--mutation-warning":
                options.mutation_warning = int(_take(args, index, arg))
                index += 2
                continue
            if arg == "--max-workers":
                options.max_workers = _workers(_take(args, index, arg))
                index += 2
                continue
            if arg == "--lines":
                options.lines = _lines(_take(args, index, arg))
                index += 2
                continue
            if arg == "--no-coverage":
                options.no_coverage = True
                index += 1
                continue
            if arg in {"--use-existing-coverage", "--reuse-coverage"}:
                options.use_existing_coverage = True
                index += 1
                continue
            if arg == "--changed":
                options.changed = True
                index += 1
                continue
            if arg == "--scan":
                options.scan = True
                index += 1
                continue
            if arg == "--mutate-all":
                options.mutate_all = True
                index += 1
                continue
            if arg == "--since-last-run":
                options.since_last_run = True
                index += 1
                continue
            if arg == "--verbose":
                options.verbose = True
                index += 1
                continue
            if arg.startswith("-"):
                raise ValueError(f"Unknown option: {arg}")
            options.positionals.append(arg)
            index += 1
    except ValueError as exc:
        return Options(action="help", message=f"{exc}\n\n{HELP}", exit_code=1)
    if options.mutate_all and options.since_last_run:
        return Options(
            action="help",
            message=f"--mutate-all cannot be combined with --since-last-run\n\n{HELP}",
            exit_code=1,
        )
    if options.no_coverage and options.coverage_command:
        return Options(
            action="help",
            message=f"--no-coverage cannot be combined with --coverage-command\n\n{HELP}",
            exit_code=1,
        )
    if options.scan and options.mutate_all:
        return Options(
            action="help",
            message=f"--scan cannot be combined with --mutate-all\n\n{HELP}",
            exit_code=1,
        )
    if options.scan and options.max_workers is not None:
        return Options(
            action="help",
            message=f"--scan cannot be combined with --max-workers\n\n{HELP}",
            exit_code=1,
        )
    return options


class GitStatusError(Exception):
    """git status could not be read. `code` is git's own status."""

    def __init__(self, code: int, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


def _git(root: Path, *args: str) -> bytes:
    result = subprocess.run(
        ["git", "-C", os.fspath(root), *args],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode != 0:
        message = os.fsdecode(result.stderr).strip() or "git status failed"
        raise GitStatusError(result.returncode, message)
    return result.stdout


def _changed_files(root: Path) -> list[Path]:
    """Added and modified files under `root`, as paths from the repo root.

    `-z` keeps spaces and non-ASCII names intact. A missing path, including a
    deletion, is left out. A rename is the new file only.
    """

    toplevel = Path(os.fsdecode(_git(root, "rev-parse", "--show-toplevel")).strip())
    status = _git(
        root,
        "status",
        "--porcelain",
        "-z",
        "--no-renames",
        "--untracked-files=all",
        "--",
        ".",
    )
    root_resolved = root.resolve()
    found: list[Path] = []
    for entry in status.split(b"\0"):
        if len(entry) <= 3:
            continue
        path = (toplevel / os.fsdecode(entry[3:])).resolve()
        if not path.is_file() or not path.is_relative_to(root_resolved):
            continue
        found.append(path)
    return found


def _positionals(root: Path, args: list[str]) -> tuple[list[Path], list[str]]:
    existing: list[Path] = []
    filters: list[str] = []
    for arg in args:
        candidate = Path(arg)
        if not candidate.is_absolute():
            candidate = root / arg
        if candidate.exists():
            existing.append(candidate.resolve())
        else:
            filters.append(arg)
    return existing, filters


def _changed_sources(root: Path, crapper) -> list[Path]:
    skip = set(crapper.discover.SKIP_DIRS) | set(crapper.discover.TEST_DIRS)
    root_resolved = root.resolve()
    files = []
    for path in _changed_files(root):
        relative = path.relative_to(root_resolved)
        if not skip.isdisjoint(relative.parts):
            continue
        if crapper.discover.language_of(path) is None:
            continue
        if crapper.discover.is_test_file(path):
            continue
        files.append(path)
    return files


def _is_source(path: Path, crapper) -> bool:
    if crapper.discover.language_of(path) is None:
        return False
    return not crapper.discover.is_test_file(path)


def _explicit_sources(existing: list[Path], crapper) -> list[Path]:
    files = []
    for path in existing:
        if path.is_dir():
            files.extend(crapper.discover.iter_source_files([path]))
            continue
        if _is_source(path, crapper):
            files.append(path)
            continue
        if crapper.discover.is_test_file(path):
            print(f"Skipping test file {path}", file=sys.stderr)
    return files


def _choose_files(options: Options, root: Path, existing: list[Path], crapper) -> list[Path]:
    if options.changed:
        return _changed_sources(root, crapper)
    if options.source_roots:
        return crapper.discover.iter_source_files([(root / path).resolve() for path in options.source_roots])
    if existing:
        return _explicit_sources(existing, crapper)
    return crapper.discover.iter_source_files([root])


def _matching(files: list[Path], filters: list[str]) -> list[Path]:
    if not filters:
        return files
    return [path for path in files if any(item in path.as_posix() for item in filters)]


def _inside_root(files: list[Path], root: Path) -> list[Path]:
    inside = []
    for path in files:
        try:
            path.resolve().relative_to(root)
        except ValueError:
            print(f"Skipping {path}; it is outside {root}", file=sys.stderr)
            continue
        inside.append(path.resolve())
    return inside


def select_files(options: Options) -> list[Path]:
    crapper = ensure_crapper()
    root = options.project_root.resolve()
    existing, filters = _positionals(root, options.positionals)
    files = _choose_files(options, root, existing, crapper)
    return sorted(set(_inside_root(_matching(files, filters), root)), key=lambda item: item.as_posix())


def _prepare_coverage(options: Options, root: Path, files: list[Path]) -> int:
    """Run coverage generation. A failed command's code is returned as-is.

    Reports already on disk are left unread when the command fails, so a stale
    100% report cannot score the run.
    """

    if options.no_coverage or options.use_existing_coverage or options.scan:
        return 0
    if options.coverage_command:
        result = CommandRunner(verbose=options.verbose).run(options.coverage_command, root, None)
        if result.code != 0:
            print(
                f"Coverage command exited {result.code}. Reports already on disk will not be read.",
                file=sys.stderr,
            )
            tail = "\n".join(result.output.splitlines()[-20:])
            if tail:
                print(tail, file=sys.stderr)
            return result.code
        return 0
    crapper = ensure_crapper()
    code = crapper.runners.run_coverage(root, files, None)
    if isinstance(code, int) and code != 0:
        return code
    return 0


def _coverage_for(options: Options, root: Path, path: Path) -> set[int] | None:
    if options.no_coverage:
        return None
    crapper = ensure_crapper()
    language = crapper.discover.language_of(path)
    if language is None:
        return None
    return covered_lines(root, path, language)


def _print_help(options: Options) -> int:
    stream = sys.stdout if options.exit_code == 0 else sys.stderr
    ending = "" if options.message.endswith("\n") else "\n"
    print(options.message, file=stream, end=ending)
    return options.exit_code


def _require_crapper() -> str | None:
    try:
        ensure_crapper()
    except ImportError as exc:
        return str(exc)
    return None


def _restore(root: Path) -> None:
    for path in restore_backups(root):
        print(f"Restored {path} from an interrupted mutation.", file=sys.stderr)


def _scan(options: Options, root: Path, files: list[Path]) -> int:
    for path in files:
        print(
            scan_file(
                path,
                root,
                covered_lines=_coverage_for(options, root, path),
                ignore_coverage=options.no_coverage,
                lines=options.lines,
            ),
            end="",
        )
    return 0


def _record(result, forms: list, written: list[str]) -> str:
    if result.skipped:
        print(f"Skipping {result.path}: {result.skipped}", file=sys.stderr)
        return "skip"
    if result.baseline_failed:
        print(result.baseline_message, file=sys.stderr)
        return "baseline"
    print(format_site_log(result.sites, result.statuses), end="")
    forms.extend(result.forms)
    written.extend(result.written)
    if any(form.survived for form in result.forms):
        return "survived"
    return "ok"


def _finish(baseline_failed: bool, survived: bool) -> int:
    if baseline_failed:
        return 2
    if survived:
        return 3
    return 0


def _mutate_files(options: Options, root: Path, files: list[Path]) -> int:
    runner = CommandRunner(verbose=options.verbose)
    baselines: dict[tuple[tuple[str, ...], str], tuple[bool, float, str]] = {}
    forms = []
    written: list[str] = []
    baseline_failed = False
    survived = False
    for path in files:
        result = mutate_file(
            path,
            root,
            runner=runner,
            covered_lines=_coverage_for(options, root, path),
            ignore_coverage=options.no_coverage,
            mutate_all=options.mutate_all,
            lines=options.lines,
            test_command=options.test_command,
            timeout_factor=options.timeout_factor,
            mutation_warning=options.mutation_warning,
            baselines=baselines,
            max_workers=options.max_workers,
        )
        outcome = _record(result, forms, written)
        if outcome == "baseline":
            baseline_failed = True
        elif outcome == "survived":
            survived = True
    if forms:
        print(format_results(forms), end="")
    for path in written:
        print(f"Wrote {path}", file=sys.stderr)
    return _finish(baseline_failed, survived)


def run(argv: list[str] | None = None) -> int:
    options = parse_args(argv)
    if options.action == "help":
        return _print_help(options)
    missing = _require_crapper()
    if missing:
        print(missing, file=sys.stderr)
        return 2
    root = options.project_root.resolve()
    try:
        files = select_files(options)
    except GitStatusError as exc:
        print(exc.message, file=sys.stderr)
        return exc.code
    if not files:
        print("No source files to mutate.")
        return 0
    if not options.scan:
        _restore(root)
    coverage = _prepare_coverage(options, root, files)
    if coverage != 0:
        return coverage
    if options.scan:
        return _scan(options, root, files)
    return _mutate_files(options, root, files)


def main(argv: list[str] | None = None) -> None:
    sys.exit(run(argv))
