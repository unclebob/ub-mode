"""Command line for the multi-language duplication finder."""

from __future__ import annotations

import os
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

from dryer.discover import SKIP_DIRS, TEST_DIRS, is_test_file, iter_source_files, language_of
from dryer.report import format_text, render_edn, write_metrics
from dryer.scan import find_duplicates, scan_files


def _skipped_directory_text() -> str:
    ordered = sorted(set(SKIP_DIRS) | set(TEST_DIRS))
    return ", ".join(ordered[:-1]) + ", and " + ordered[-1]


HELP = f"""\
Usage: dryer [options] [path-or-filter ...]

Find candidate duplicate code in Clojure, Java, Go, TypeScript, Rust, and
Python. Each form is normalized — call and method names, operators, and
tree shape stay; local names, field names, and literals do not — and compared
with the other forms of the same language. The score is Jaccard similarity of
those structural fingerprints, the same score as dry4clj.

Prints a report and writes .metrics/dry.edn.

Languages: Clojure (.clj .cljc .cljs .cljd .bb), Java (.java), Go (.go),
TypeScript (.ts .tsx .mts .cts), Rust (.rs), Python (.py).

Options:
  -h, --help                    Print this help and exit.
  --root <path>                 Project root. Metrics are written here.
                                Default: the current directory.
  -s, --source-root <path>      Walk this tree instead of the project root.
                                May be repeated.
  --changed                     Compare added and modified source files from
                                git status.
  --threshold N                 Minimum similarity score, default 0.82.
  --min-lines N                 Minimum source lines in a candidate form,
                                default 4.
  --min-nodes N                 Minimum normalized syntax nodes, default 20.
  --format F                    text or edn, default text.
  --edn                         Same as --format edn.
  --text                        Same as --format text.

Arguments:
  path              File or directory to compare. Test paths are included when
                    you name them explicitly.
  filter            When the argument is not a path, only source files whose
                    path contains this text are compared.

With no paths, source files under the project root are compared. Directories
named {_skipped_directory_text()} are skipped, as are *_test.go, *.test.cts,
*.spec.cts, *.spec.ts, test_*.py, and *_test.py files.

Clojure compares every top-level form except ns. The other languages compare
functions and methods. A form is only compared with forms in the same language.

Exit codes:
  0  the report was written
  1  usage error
  2  unknown output format
"""


@dataclass
class Options:
    action: str
    message: str = ""
    exit_code: int = 0
    project_root: Path = field(default_factory=lambda: Path("."))
    source_roots: list[str] = field(default_factory=list)
    positionals: list[str] = field(default_factory=list)
    threshold: float = 0.82
    min_lines: int = 4
    min_nodes: int = 20
    format: str = "text"
    changed: bool = False


def _take(args: list[str], index: int, option: str) -> str:
    if index + 1 >= len(args) or not args[index + 1] or args[index + 1].startswith("-"):
        raise ValueError(f"{option} requires a value")
    return args[index + 1]


def _number(value: str, option: str) -> float:
    try:
        return float(value)
    except ValueError as exc:
        raise ValueError(f"{option} requires a number") from exc


def _count(value: str, option: str) -> int:
    try:
        parsed = int(value)
    except ValueError as exc:
        raise ValueError(f"{option} requires an integer") from exc
    if parsed < 0:
        raise ValueError(f"{option} requires a non-negative integer")
    return parsed


def parse_args(argv: list[str] | None = None) -> Options:
    args = list(sys.argv[1:] if argv is None else argv)
    if any(arg in {"-h", "--help"} for arg in args):
        return Options(action="help", message=HELP, exit_code=0)
    options = Options(action="scan")
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
            if arg == "--threshold":
                options.threshold = _number(_take(args, index, arg), arg)
                index += 2
                continue
            if arg == "--min-lines":
                options.min_lines = _count(_take(args, index, arg), arg)
                index += 2
                continue
            if arg == "--min-nodes":
                options.min_nodes = _count(_take(args, index, arg), arg)
                index += 2
                continue
            if arg == "--format":
                options.format = _take(args, index, arg)
                index += 2
                continue
            if arg == "--edn":
                options.format = "edn"
                index += 1
                continue
            if arg == "--text":
                options.format = "text"
                index += 1
                continue
            if arg == "--changed":
                options.changed = True
                index += 1
                continue
            if arg.startswith("-"):
                raise ValueError(f"Unknown option: {arg}")
            options.positionals.append(arg)
            index += 1
    except ValueError as exc:
        return Options(action="help", message=f"{exc}\n\n{HELP}", exit_code=1)
    if options.format not in {"text", "edn"}:
        return Options(
            action="format",
            message=f"Unknown format: {options.format}\n",
            exit_code=2,
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


def _tracked_source(path: Path, root: Path | None = None) -> bool:
    parts = path.parts
    if root is not None:
        try:
            parts = path.resolve().relative_to(root.resolve()).parts
        except ValueError:
            return False
    if not SKIP_DIRS.isdisjoint(parts):
        return False
    if language_of(path) is None:
        return False
    return not is_test_file(path)


def _changed_source(root: Path) -> list[Path]:
    return [path for path in _changed_files(root) if _tracked_source(path, root)]


def _explicit_files(existing: list[Path]) -> list[Path]:
    files: list[Path] = []
    for path in existing:
        if path.is_dir():
            files.extend(iter_source_files([path]))
        elif language_of(path) is not None:
            files.append(path)
    return files


def _selected(options: Options, root: Path, existing: list[Path]) -> list[Path]:
    if options.changed:
        return _changed_source(root)
    if options.source_roots:
        return iter_source_files([(root / path).resolve() for path in options.source_roots])
    if existing:
        return _explicit_files(existing)
    return iter_source_files([root])


def _apply_filters(files: list[Path], filters: list[str]) -> list[Path]:
    if not filters:
        return files
    return [path for path in files if any(item in path.as_posix() for item in filters)]


def select_files(options: Options) -> list[Path]:
    root = options.project_root.resolve()
    existing, filters = _positionals(root, options.positionals)
    files = _apply_filters(_selected(options, root, existing), filters)
    return sorted({path.resolve() for path in files}, key=lambda path: path.as_posix())


def run(argv: list[str] | None = None) -> int:
    options = parse_args(argv)
    if options.action == "help":
        stream = sys.stdout if options.exit_code == 0 else sys.stderr
        print(options.message, file=stream, end="" if options.message.endswith("\n") else "\n")
        return options.exit_code
    if options.action == "format":
        print(options.message, file=sys.stderr, end="")
        return options.exit_code

    root = options.project_root.resolve()
    try:
        files = select_files(options)
    except GitStatusError as exc:
        print(exc.message, file=sys.stderr)
        return exc.code
    if not files:
        print("No source files to analyze.")
        return 0

    entries, warnings = scan_files(files, root, options.min_lines, options.min_nodes)
    for warning in warnings:
        print(f"dryer: {warning}", file=sys.stderr)
    candidates = find_duplicates(entries, options.threshold)
    metrics = write_metrics(candidates, root)
    if options.format == "edn":
        print(render_edn(candidates), end="")
    else:
        print(format_text(candidates), end="")
    print(f"Wrote {metrics}", file=sys.stderr)
    return 0


def main(argv: list[str] | None = None) -> None:
    sys.exit(run(argv))
