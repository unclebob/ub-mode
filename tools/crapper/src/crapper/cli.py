"""Command line for the multi-language CRAP tool."""

from __future__ import annotations

import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

from crapper.analyze import analyze_files
from crapper.coverage import CoverageBundle, load_bundle
from crapper.discover import (
    CLOJURE_TEST_SUFFIXES,
    GO_TEST_SUFFIX,
    JS_TEST_SUFFIXES,
    SKIP_DIRS,
    TEST_DIRS,
    is_test_file,
    iter_source_files,
    language_of,
)
from crapper.metrics import write_metrics
from crapper.report import format_report
from crapper.runners import run_coverage

_SKIPPED_DIRS = ", ".join(sorted(SKIP_DIRS | TEST_DIRS))
_TEST_PATTERNS = ", ".join(
    [
        f"*{GO_TEST_SUFFIX}",
        *[f"*{suffix}" for suffix in CLOJURE_TEST_SUFFIXES],
        *[f"*{suffix}" for suffix in JS_TEST_SUFFIXES],
        "test_*.py",
        "*_test.py",
        "conftest.py",
    ]
)

HELP = f"""\
Usage: crapper [options] [path-or-filter ...]

Detect the language of each source file and score it with the CRAP metric
CRAP = CC² × (1 − coverage)³ + CC. Writes .metrics/crap.edn for uml-viewer
and prints a report sorted worst first.

Languages: Clojure (.clj .cljc .cljs .bb), Java (.java), Go (.go),
TypeScript and JavaScript (.ts .tsx .mts .cts .js .jsx .mjs .cjs),
Rust (.rs), Python (.py).

Options:
  -h, --help                    Print this help and exit.
  --root <path>                 Project root. Metrics are written here.
                                Default: the current directory.
  -s, --source-root <path>      Walk this tree instead of the project root.
                                May be repeated.
  --changed                     Analyze added and modified source files from
                                git status.
  --no-coverage                 Score complexity only. Coverage and CRAP are N/A.
  --use-existing-coverage       Read coverage already on disk. Do not rerun tests.
  --coverage-command <cmd>      Run this command instead of the per-language
                                coverage tools, then read the reports it wrote.
  --threshold <number>          Exit 2 when the worst CRAP score is above this.

Arguments:
  path              File or directory to analyze. Test paths are included when
                    you name them explicitly.
  filter            When the argument is not a path, only source files whose
                    path contains this text are analyzed.

With no paths, source files under the project root are analyzed. Directories
named {_SKIPPED_DIRS} are skipped, as are {_TEST_PATTERNS}.

Coverage, when it is produced:
  Clojure      clj -M:cov --lcov, then Cloverage form counts or LCOV
  Java         Maven JaCoCo instruction coverage, for each pom.xml module
  Go           go test ./... -coverprofile=...
  TypeScript   npm run coverage, Vitest's own LCOV, or c8 around npm test
  Rust         cargo llvm-cov, or cargo tarpaulin, in the nearest Cargo.toml
  Python       coverage.py LCOV, via pytest or unittest, per project

uml-viewer joins .metrics/crap.edn on :namespace and :name.
  Clojure      the ns, and the defn name
  Java         package.Class, and the method name
  Go           import path, or import.path.Receiver for methods
  TypeScript   dotted module path, or module.Class for methods
  Rust         crate::module, or crate::module::Type for methods
  Python       dotted module path, or module.Class for methods
"""


@dataclass
class Options:
    action: str
    message: str = ""
    exit_code: int = 0
    project_root: Path = field(default_factory=lambda: Path("."))
    source_roots: list[str] = field(default_factory=list)
    positionals: list[str] = field(default_factory=list)
    no_coverage: bool = False
    use_existing_coverage: bool = False
    coverage_command: str | None = None
    threshold: float | None = None
    changed: bool = False


def _take(args: list[str], index: int, option: str) -> str:
    if index + 1 >= len(args) or not args[index + 1] or args[index + 1].startswith("-"):
        raise ValueError(f"{option} requires a value")
    return args[index + 1]


def parse_args(argv: list[str] | None = None) -> Options:
    args = list(sys.argv[1:] if argv is None else argv)
    if any(arg in {"-h", "--help"} for arg in args):
        return Options(action="help", message=HELP, exit_code=0)
    options = Options(action="analyze")
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
            if arg == "--threshold":
                raw = _take(args, index, arg)
                try:
                    options.threshold = float(raw)
                except ValueError as exc:
                    raise ValueError("--threshold requires a number") from exc
                index += 2
                continue
            if arg == "--no-coverage":
                options.no_coverage = True
                index += 1
                continue
            if arg == "--use-existing-coverage":
                options.use_existing_coverage = True
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
    if options.no_coverage and options.coverage_command:
        return Options(
            action="help",
            message=f"--no-coverage cannot be combined with --coverage-command\n\n{HELP}",
            exit_code=1,
        )
    return options


class GitError(Exception):
    """git could not describe the working tree. `code` is git's status."""

    def __init__(self, code: int, message: str) -> None:
        super().__init__(message)
        self.code = code


def _git(root: Path, args: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", "-C", str(root), *args],
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )


def _changed_files(root: Path) -> list[Path]:
    """Added and modified files from git, including files in new directories.

    Paths come from `git status -z`, which does not quote non-ASCII names.
    They are resolved from the repository root, then limited to `root`, so
    `--root` can be a subdirectory. Deleted files are left out.
    """

    top_result = _git(root, ["rev-parse", "--show-toplevel"])
    if top_result.returncode != 0:
        raise GitError(top_result.returncode, top_result.stderr.strip() or "git status failed")
    status = _git(
        root,
        [
            "status",
            "--porcelain",
            "-z",
            "--no-renames",
            "--untracked-files=all",
            "--",
            ".",
        ],
    )
    if status.returncode != 0:
        raise GitError(status.returncode, status.stderr.strip() or "git status failed")
    top = Path(top_result.stdout.strip())
    limit = root.resolve()
    found: list[Path] = []
    for entry in status.stdout.split("\0"):
        if len(entry) < 4:
            continue
        path = (top / entry[3:]).resolve()
        if path.is_file() and path.is_relative_to(limit):
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


def _changed_source(root: Path) -> list[Path]:
    return [
        path
        for path in _changed_files(root)
        if language_of(path) is not None and not is_test_file(path)
    ]


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


def _show_help(options: Options) -> int:
    stream = sys.stdout if options.exit_code == 0 else sys.stderr
    print(options.message, file=stream, end="" if options.message.endswith("\n") else "\n")
    return options.exit_code


def _coverage_bundle(options: Options, root: Path, files: list[Path]):
    if options.no_coverage:
        return None
    if options.use_existing_coverage:
        return load_bundle(root)
    status = run_coverage(root, files, options.coverage_command)
    if options.coverage_command and status not in (0, None):
        return CoverageBundle()
    return load_bundle(root)


def _threshold_status(options: Options, entries) -> int:
    if options.threshold is None:
        return 0
    scored = [entry.crap for entry in entries if entry.crap is not None]
    if scored and max(scored) > options.threshold:
        print(
            f"CRAP threshold exceeded: {max(scored):.1f} > {options.threshold:.1f}",
            file=sys.stderr,
        )
        return 2
    return 0


def run(argv: list[str] | None = None) -> int:
    options = parse_args(argv)
    if options.action == "help":
        return _show_help(options)

    root = options.project_root.resolve()
    try:
        files = select_files(options)
    except GitError as exc:
        print(str(exc), file=sys.stderr)
        return exc.code
    if not files:
        print("No source files to analyze.")
        return 0

    entries = analyze_files(files, root, _coverage_bundle(options, root, files))
    metrics = write_metrics(entries, root)
    print(format_report(entries), end="", flush=True)
    print(f"Wrote {metrics}", file=sys.stderr)
    return _threshold_status(options, entries)


def main(argv: list[str] | None = None) -> None:
    sys.exit(run(argv))
