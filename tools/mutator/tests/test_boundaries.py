"""Boundaries the first mutation run left uncovered or alive.

A site on a line coverage.py never emits stays uncovered; these tests do not
reformat production code to expose those lines. Equivalent mutants are left
alone when no input can tell the operators apart.
"""

import hashlib
import subprocess
import sys
import threading
from pathlib import Path

import pytest

from mutator.cli import (
    GitStatusError,
    _changed_files,
    _is_source,
    _prepare_coverage,
    _take,
    parse_args,
    run,
    select_files,
)
from mutator.coverage import _candidates, _jacoco_index, covered_lines
from mutator.crapper_link import ensure_crapper
from mutator.edn import loads
from mutator.engine import (
    _backup,
    _carry_forward,
    _covered,
    _drop_bytecode,
    _forms,
    _restore_if_dirty,
    mutate_file,
    restore_backups,
    scan_file,
)
from mutator.functions import (
    _mutation_field,
    _owner,
    digest,
    form_spans,
    is_private,
    mutation_file,
)
from mutator.metrics import (
    _drop_stale,
    _form_file,
    _form_file_only,
    _mentions_file,
    _outcomes_of,
    _remove_empty_dirs,
    _write_namespace,
    load_history,
    snapshot_path,
    write_results,
)
from mutator.model import FormResult, History, PriorForm, Site
from mutator.report import format_results, format_scan
from mutator.runner import CommandResult, CommandRunner, _clojure_command
from mutator.runner import test_plan as command_for
from mutator.sites import _grammar


def _form(namespace, name, file_name, digest_text, killed=1, private=False, line=2):
    form_id = f"defn-/{name}" if private else f"defn/{name}"
    return FormResult(
        id=form_id,
        namespace=namespace,
        name=name,
        private=private,
        file=file_name,
        line=line,
        end_line=line + 2,
        digest=digest_text,
        killed=killed,
        survived=0,
        uncovered=0,
        sites=1,
    )


def _site(**overrides) -> Site:
    values = dict(
        file="src/demo.py",
        namespace="demo",
        form_id="defn/place",
        name="place",
        line=2,
        start=0,
        end=1,
        original=">",
        mutant=">=",
        category="comparison",
        mutation_id="m",
    )
    values.update(overrides)
    return Site(**values)


def _pairs(tmp_path: Path, relative: str, source: str):
    from mutator.functions import sites_in_file

    path = tmp_path / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(source, encoding="utf-8")
    sites = sites_in_file(source, path, tmp_path, relative)
    return sites, {(site.original, site.mutant) for site in sites}


class _Fn:
    def __init__(self, name, start, end):
        self.name = name
        self.start_line = start
        self.end_line = end


class _Ok:
    def __init__(self, seconds=0.01):
        self.verbose = False
        self.seconds = seconds
        self.timeouts = []
        self._lock = threading.Lock()

    def run(self, command, cwd, timeout):
        with self._lock:
            self.timeouts.append(timeout)
        code = 0 if timeout is None else 1
        return CommandResult(code=code, timed_out=False, seconds=self.seconds, output="")


def test_score_and_description_follow_the_counts():
    even = _form("demo", "place", "a.py", "d", killed=1)
    even.survived = 1
    assert even.score == 0.5
    blank = _form("demo", "place", "a.py", "d", killed=0)
    assert blank.score is None
    perfect = _form("demo", "place", "a.py", "d", killed=1)
    assert perfect.score == 1.0
    assert _site(original=">", mutant=">=").description == "> -> >="
    assert _site(original="not", mutant="").description == "delete not"


def test_report_text_states_the_score_the_total_and_coverage():
    assert format_results([]) == "No functions to mutate.\n"
    mixed = _form("demo", "place", "a.py", "d", killed=1)
    mixed.survived = 1
    mixed.sites = 2
    text = format_results([mixed])
    assert "50.0%" in next(line for line in text.splitlines() if "place" in line)
    total = next(line for line in text.splitlines() if line.startswith("Total"))
    assert total.split() == ["Total", "1", "1", "0", "2", "50.0%"]

    perfect = _form("demo", "place", "a.py", "d", killed=1)
    perfect_total = next(line for line in format_results([perfect]).splitlines() if line.startswith("Total"))
    assert perfect_total.split()[-1] == "100.0%"
    idle = _form("demo", "place", "a.py", "d", killed=0)
    idle.sites = 2
    idle.uncovered = 2
    idle_total = next(line for line in format_results([idle]).splitlines() if line.startswith("Total"))
    assert idle_total.split()[-1] == "n/a"

    covered = format_scan("src/demo.py", [_site()], set(), {"m": True})
    missing = format_scan("src/demo.py", [_site()], set(), {})
    bare = format_scan("src/demo.py", [_site()], set(), {"m": False})
    assert "uncovered" not in covered
    assert "uncovered" not in missing
    assert "uncovered" in bare


def test_edn_errors_comments_sets_and_nil_keys():
    with pytest.raises(ValueError, match="empty EDN"):
        loads("")
    with pytest.raises(ValueError, match="empty EDN"):
        loads("; hi")
    with pytest.raises(ValueError, match="unexpected end of EDN"):
        loads("{")
    with pytest.raises(ValueError, match="unterminated string"):
        loads('"hi')
    with pytest.raises(ValueError, match="unterminated string escape"):
        loads('"\\')
    with pytest.raises(ValueError, match="empty keyword"):
        loads(":")
    assert loads("; kept\n12") == 12
    assert loads("#{1 2}") == {1, 2}
    assert loads("{nil 1}") == {None: 1}


def test_privacy_headers_digests_owners_and_mutation_fields(tmp_path):
    assert is_private("python", "__init__", "def __init__(self):\n    pass\n", 1, 2) is False
    assert is_private("python", "_hide", "def _hide():\n    return 1\n", 1, 2) is True
    assert is_private("python", "__x", "def __x():\n    return 1\n", 1, 2) is True
    assert is_private("rust", "place", "fn place() {}\n", 1, 1) is True
    assert is_private("rust", "place", "#[inline]\npub fn place() {}\n", 1, 2) is False
    assert is_private("typescript", "#place", "#place() {}\n", 1, 1) is True
    assert is_private("typescript", "place", "private place() {}\n", 1, 1) is True
    assert is_private("typescript", "place", "function place() {}\n", 1, 1) is False
    assert is_private("cobol", "place", "PLACE.\n", 1, 1) is False

    source = "def place(x):\n    return x > 0\n"
    chunk = "def place(x):\n    return x > 0"
    assert digest(source, 1, 2) == hashlib.sha256(chunk.encode("utf-8")).hexdigest()

    assert _owner([_Fn("outer", 1, 10), _Fn("inner", 8, 9)], 8).name == "inner"

    path = tmp_path / "src" / "demo.py"
    path.parent.mkdir(parents=True)
    hidden = "def _hide():\n    return 1\n"
    path.write_text(hidden, encoding="utf-8")
    assert list(form_spans(hidden, path, tmp_path).values())[0][3] is True

    assert _mutation_field("not-json", 0) is None
    assert _mutation_field('"hello"', 0) is None
    assert _mutation_field("[1]", 0) is None
    assert _mutation_field('["only"]', 1) is None
    assert mutation_file('["src/a.py","demo","defn/place",1,2,">",">="]') == "src/a.py"


def test_grammar_guards_and_non_head_clojure_symbols(tmp_path):
    assert _grammar("typescript", "src/app.tsx") == "tsx"
    assert _grammar("typescript", "src/app.ts") == "typescript"
    assert _grammar("typescript", "src/app.js") == "javascript"
    assert _grammar("typescript", "src/app.mjs") == "javascript"
    assert _grammar("typescript", "src/app.cjs") == "javascript"
    assert _grammar("typescript", "src/app.jsx") == "javascript"
    assert _grammar("python", "src/app.py") == "python"

    sites, pairs = _pairs(tmp_path, "src/demo/core.clj", "(ns demo.core)\n(defn place []\n  [inc])\n")
    assert "inc" not in {site.original for site in sites}
    sites, _pairs_ignored = _pairs(tmp_path, "src/demo/add.clj", "(ns demo.core)\n(defn place []\n  (+ + 1))\n")
    assert sum(site.original == "+" and site.mutant == "-" for site in sites) == 1
    sites, _pairs_ignored = _pairs(tmp_path, "src/demo/two.clj", "(ns demo.core)\n(defn place []\n  2)\n")
    assert sites == []

    _sites, pairs = _pairs(tmp_path, "src/demo/app.py", "def place(a, b):\n    return a - b\n")
    assert ("-", "+") in pairs
    assert ("-", "") not in pairs
    _sites, pairs = _pairs(tmp_path, "src/demo/name.py", "def place(true):\n    return true\n")
    assert ("true", "false") not in pairs
    _sites, pairs = _pairs(tmp_path, "src/demo/two.py", "def place():\n    return 2\n")
    assert pairs == set()
    sites, _pairs_ignored = _pairs(tmp_path, "src/lib.rs", "fn place() -> bool { true }\n")
    assert sum(site.original == "true" and site.mutant == "false" for site in sites) == 1


def test_coverage_indexes_use_package_names_form_counts_and_exact_paths(tmp_path):
    assert _candidates(lambda value: value, "") == []
    assert _candidates(lambda _value: "same", "src/app.py") == ["same"]

    report = tmp_path / "target" / "site" / "jacoco" / "jacoco.xml"
    report.parent.mkdir(parents=True)
    report.write_text(
        """<?xml version="1.0"?>
<report name="demo">
  <package name="demo">
    <sourcefile name="Board.java">
      <line nr="6" mi="0" ci="1" mb="0" cb="0"/>
    </sourcefile>
  </package>
</report>
""",
        encoding="utf-8",
    )
    source = tmp_path / "src" / "demo" / "Board.java"
    source.parent.mkdir(parents=True)
    source.write_text("package demo;\nclass Board {}\n", encoding="utf-8")
    assert "demo/Board.java" in _jacoco_index(tmp_path)
    assert covered_lines(tmp_path, source, "java") == {6}

    html = tmp_path / "target" / "coverage" / "src" / "demo" / "core.clj.html"
    html.parent.mkdir(parents=True)
    html.write_text(
        '<span title="1 out of 1 forms covered">4&nbsp;</span>\n',
        encoding="utf-8",
    )
    lcov = tmp_path / "target" / "coverage" / "clojure" / "lcov.info"
    lcov.parent.mkdir(parents=True)
    lcov.write_text("SF:src/demo/core.clj\nDA:9,1\nend_of_record\n", encoding="utf-8")
    clojure = tmp_path / "src" / "demo" / "core.clj"
    clojure.write_text("(ns demo.core)\n(defn place [] 1)\n", encoding="utf-8")
    assert covered_lines(tmp_path, clojure, "clojure") == {4}


def test_commands_follow_the_nearest_project_and_a_killed_process(tmp_path):
    assert CommandRunner().verbose is False
    both = tmp_path / "both"
    both.mkdir()
    (both / "bb.edn").write_text("{:tasks {test (clojure)}}\n", encoding="utf-8")
    (both / "deps.edn").write_text("{:deps {}}\n", encoding="utf-8")
    assert _clojure_command(both) == ["clj", "-M:test"]

    root = tmp_path / "proj"
    sub = root / "svc"
    sub.mkdir(parents=True)
    (sub / "bb.edn").write_text("{:tasks {test (clojure)}}\n", encoding="utf-8")
    clojure = sub / "src" / "a.clj"
    clojure.parent.mkdir(parents=True)
    clojure.write_text("(defn place [] 1)\n", encoding="utf-8")
    command, directory = command_for(root, clojure, "clojure", None)
    assert directory == sub
    assert command == ["bb", "test"]

    (sub / "pom.xml").write_text("<project/>\n", encoding="utf-8")
    java = sub / "src" / "A.java"
    java.write_text("class A { int place(){ return 1; } }\n", encoding="utf-8")
    command, directory = command_for(root, java, "java", None)
    assert directory == sub
    assert command == ["mvn", "-q", "test", "-DexcludeTags=no-mutate"]

    (sub / "package.json").write_text("{}\n", encoding="utf-8")
    typescript = sub / "src" / "a.ts"
    typescript.write_text("export function place(){ return 1; }\n", encoding="utf-8")
    command, directory = command_for(root, typescript, "typescript", None)
    assert directory == sub
    assert command == ["npm", "test"]

    (sub / "Cargo.toml").write_text("[package]\nname='demo'\n", encoding="utf-8")
    rust = sub / "src" / "lib.rs"
    rust.write_text("fn place() -> i32 { 1 }\n", encoding="utf-8")
    command, directory = command_for(root, rust, "rust", None)
    assert directory == sub
    assert command == ["cargo", "test"]

    (sub / "go.mod").write_text("module example.com/demo\n", encoding="utf-8")
    go = sub / "pkg" / "widget.go"
    go.parent.mkdir()
    go.write_text("package pkg\nfunc Run() int { return 1 }\n", encoding="utf-8")
    command, directory = command_for(root, go, "go", None)
    assert directory == sub
    assert command == ["go", "test", "-count=1", "./pkg"]

    (sub / "pyproject.toml").write_text("[project]\ndependencies=['pytest']\n", encoding="utf-8")
    python = sub / "app.py"
    python.write_text("def place():\n    return 1\n", encoding="utf-8")
    command, directory = command_for(root, python, "python", None)
    assert directory == sub
    assert command[-2:] == ["-m", "pytest"]

    result = CommandRunner().run(f"{sys.executable} -c 'import time; time.sleep(30)'", tmp_path, 0.4)
    assert result.timed_out is True
    assert result.code == 124


def test_a_process_without_a_return_code_counts_as_failure(monkeypatch, tmp_path):
    class Proc:
        returncode = None
        pid = 1

        def communicate(self, timeout=None):
            return (None, None)

    monkeypatch.setattr("mutator.runner.subprocess.Popen", lambda *args, **kwargs: Proc())
    result = CommandRunner().run("true", tmp_path, 5)
    assert result.code == 1
    assert result.output == ""
    assert result.timed_out is False


def test_options_reject_bad_values_and_keep_flag_defaults(monkeypatch):
    assert _take(["--root", "/tmp", "extra"], 0, "--root") == "/tmp"
    assert _take(["--root", "value", ""], 0, "--root") == "value"
    with pytest.raises(ValueError):
        _take(["--root"], 0, "--root")
    with pytest.raises(ValueError):
        _take(["--root", ""], 0, "--root")
    with pytest.raises(ValueError):
        _take(["--lines", "-1"], 0, "--lines")

    assert parse_args(["--lines", "1"]).lines == {1}
    assert parse_args(["--lines", "0"]).exit_code == 1
    assert parse_args(["--timeout-factor", "1"]).timeout_factor == 1
    assert parse_args(["--timeout-factor", "0"]).exit_code == 1
    assert parse_args(["--timeout-factor", "-1"]).exit_code == 1
    assert parse_args(["--use-existing-coverage"]).use_existing_coverage is True
    assert parse_args(["--reuse-coverage"]).use_existing_coverage is True
    assert parse_args(["--verbose"]).verbose is True
    assert parse_args(["--changed"]).changed is True
    assert parse_args(["--max-workers", "2"]).max_workers == 2
    assert parse_args(["--max-workers", "0"]).exit_code == 1
    assert parse_args(["--max-workers", "nope"]).exit_code == 1
    assert parse_args(["--scan", "--max-workers", "2"]).exit_code == 1
    assert parse_args(["--not-a-flag"]).exit_code == 1
    monkeypatch.setattr(sys, "argv", ["prog", "--verbose"])
    options = parse_args(None)
    assert options.verbose is True
    assert options.positionals == []


def test_help_and_conflicts_use_different_streams(capsys):
    assert run(["--help"]) == 0
    captured = capsys.readouterr()
    assert "uml-viewer" in captured.out
    assert captured.err == ""
    assert run(["--mutate-all", "--since-last-run"]) == 1
    captured = capsys.readouterr()
    assert "cannot be combined" in captured.err
    assert captured.out == ""


def _init_repo(path: Path) -> None:
    subprocess.run(["git", "init"], cwd=path, check=True, capture_output=True)
    subprocess.run(["git", "config", "user.email", "dev@example.com"], cwd=path, check=True)
    subprocess.run(["git", "config", "user.name", "Dev"], cwd=path, check=True)


def _commit(path: Path, message: str) -> None:
    subprocess.run(["git", "add", "-A"], cwd=path, check=True, capture_output=True)
    subprocess.run(["git", "commit", "-m", message], cwd=path, check=True, capture_output=True)


def test_git_status_keeps_real_names_and_fails_closed(tmp_path, capsys):
    outside = tmp_path / "outside"
    outside.mkdir()
    code = run(["--changed", "--root", str(outside)])
    captured = capsys.readouterr()
    assert code == 128
    assert "not a git repository" in captured.err
    assert "No source files" not in captured.out

    repo = tmp_path / "repo"
    repo.mkdir()
    _init_repo(repo)
    source = "def place(x):\n    return x > 0\n"
    keep = repo / "src" / "café.py"
    keep.parent.mkdir()
    keep.write_text(source, encoding="utf-8")
    nested = repo / "fresh" / "nested" / "new.py"
    nested.parent.mkdir(parents=True)
    nested.write_text(source, encoding="utf-8")
    spaced = repo / "src" / "my file.py"
    spaced.write_text(source, encoding="utf-8")
    gone = repo / "gone.py"
    gone.write_text(source, encoding="utf-8")
    old = repo / "src" / "old.py"
    old.write_text(source, encoding="utf-8")
    _commit(repo, "base")
    keep.write_text(source + "\n", encoding="utf-8")
    nested.write_text(source + "\n", encoding="utf-8")
    spaced.write_text(source + "\n", encoding="utf-8")
    gone.unlink()
    subprocess.run(
        ["git", "mv", "src/old.py", "src/new.py"], cwd=repo, check=True, capture_output=True
    )
    skipped = repo / "target" / "skip.py"
    skipped.parent.mkdir()
    skipped.write_text(source, encoding="utf-8")

    found = set(_changed_files(repo))
    assert keep.resolve() in found
    assert nested.resolve() in found
    assert spaced.resolve() in found
    assert (repo / "src" / "new.py").resolve() in found
    assert gone.resolve() not in found
    assert old.resolve() not in found

    chosen = select_files(parse_args(["--root", str(repo), "--changed"]))
    assert skipped.resolve() not in chosen
    assert keep.resolve() in chosen
    code = run(["--scan", "--no-coverage", "--root", str(repo), "--changed"])
    assert code == 0
    assert "café.py" in capsys.readouterr().out


def test_changed_from_a_subdirectory_stays_inside_it(tmp_path):
    repo = tmp_path / "repo"
    sub = repo / "sub"
    (sub / "src").mkdir(parents=True)
    (repo / "src").mkdir()
    _init_repo(repo)
    below = sub / "src" / "below.py"
    above = repo / "src" / "above.py"
    body = "def place(x):\n    return x > 0\n"
    below.write_text(body, encoding="utf-8")
    above.write_text(body, encoding="utf-8")
    chosen = select_files(parse_args(["--root", str(sub), "--changed"]))
    assert chosen == [below.resolve()]


def test_an_empty_git_error_uses_the_fallback_text(monkeypatch, tmp_path):
    def fail(args, **kwargs):
        return subprocess.CompletedProcess(args, 128, b"", b"   \n")

    monkeypatch.setattr("mutator.cli.subprocess.run", fail)
    with pytest.raises(GitStatusError, match="git status failed") as caught:
        _changed_files(tmp_path)
    assert caught.value.code == 128


def test_selection_joins_the_root_and_skips_unknown_files(tmp_path, capsys):
    path = tmp_path / "src" / "demo.py"
    path.parent.mkdir()
    path.write_text("def place(x):\n    return x > 0\n", encoding="utf-8")
    note = tmp_path / "note.txt"
    note.write_text("hello\n", encoding="utf-8")
    crapper = ensure_crapper()
    assert _is_source(note, crapper) is False
    options = parse_args(["--root", str(tmp_path), "src/demo.py"])
    assert select_files(options) == [path.resolve()]

    cwd = tmp_path / "cwd"
    project = tmp_path / "proj"
    decoy = cwd / "src" / "demo.py"
    real = project / "src" / "demo.py"
    decoy.parent.mkdir(parents=True)
    real.parent.mkdir(parents=True)
    decoy.write_text("def other(x):\n    return x > 1\n", encoding="utf-8")
    real.write_text("def place(x):\n    return x > 0\n", encoding="utf-8")
    monkeypatch = pytest.MonkeyPatch()
    monkeypatch.chdir(cwd)
    try:
        chosen = select_files(parse_args(["--root", str(project), "src/demo.py"]))
    finally:
        monkeypatch.undo()
    assert chosen == [real.resolve()]

    code = run(["--scan", "--no-coverage", "--root", str(tmp_path), "--source-root", "src"])
    captured = capsys.readouterr()
    assert code == 0
    assert "demo.py" in captured.out


def test_existing_coverage_is_not_regenerated(monkeypatch, tmp_path):
    crapper = ensure_crapper()
    calls = []
    monkeypatch.setattr(crapper.runners, "run_coverage", lambda *args, **kwargs: calls.append(args))
    root = tmp_path
    _prepare_coverage(parse_args(["--no-coverage", "--root", str(root)]), root, [])
    _prepare_coverage(parse_args(["--use-existing-coverage", "--root", str(root)]), root, [])
    assert calls == []
    _prepare_coverage(parse_args(["--root", str(root)]), root, [])
    assert len(calls) == 1


def test_cli_exit_codes_follow_kills_baseline_failure_and_an_empty_tree(tmp_path, capsys):
    empty = run(["--no-coverage", "--root", str(tmp_path)])
    assert empty == 0
    assert "No source files" in capsys.readouterr().out

    path = tmp_path / "src" / "demo.py"
    path.parent.mkdir()
    path.write_text("def add(a, b):\n    return a + b\n", encoding="utf-8")
    command = (
        f"{sys.executable} -c \"import sys; sys.path.insert(0, 'src'); "
        "from demo import add; raise SystemExit(0 if add(2, 3) == 5 else 1)\""
    )
    code = run(
        [
            "--no-coverage",
            "--mutate-all",
            "--root",
            str(tmp_path),
            "--test-command",
            command,
            "src/demo.py",
        ]
    )
    assert code == 0
    assert "KILLED" in capsys.readouterr().out
    snapshot = snapshot_path(tmp_path, "demo")
    recorded = snapshot.read_text(encoding="utf-8")

    path.write_text("def place(x):\n    return x > 0\n", encoding="utf-8")
    failing = f"{sys.executable} -c 'raise SystemExit(1)'"
    code = run(
        [
            "--no-coverage",
            "--mutate-all",
            "--root",
            str(tmp_path),
            "--test-command",
            failing,
            "src/demo.py",
        ]
    )
    assert code == 2
    assert snapshot.read_text(encoding="utf-8") == recorded


def test_a_scan_does_not_restore_a_backup_and_a_run_does(tmp_path, capsys):
    source = tmp_path / "src" / "demo.py"
    source.parent.mkdir()
    dirty = "def place(x):\n    return x > 0\n"
    clean = "def place(x):\n    return x > 1\n"
    source.write_text(dirty, encoding="utf-8")
    backup = tmp_path / "target" / "mutator-backup" / "src" / "demo.py"
    backup.parent.mkdir(parents=True)
    backup.write_text(clean, encoding="utf-8")
    code = run(["--scan", "--no-coverage", "--root", str(tmp_path), "src/demo.py"])
    assert code == 0
    assert source.read_text(encoding="utf-8") == dirty
    scanned = capsys.readouterr().out
    assert "0 -> 1" in scanned
    assert "1 -> 0" not in scanned

    failing = f"{sys.executable} -c 'raise SystemExit(1)'"
    code = run(
        [
            "--no-coverage",
            "--mutate-all",
            "--root",
            str(tmp_path),
            "--test-command",
            failing,
            "src/demo.py",
        ]
    )
    assert code == 2
    assert source.read_text(encoding="utf-8") == clean


def test_backups_bytecode_and_a_dirty_file_are_put_back(tmp_path):
    backup = tmp_path / "target" / "mutator-backup" / "a" / "b" / "demo.py"
    backup.parent.mkdir(parents=True)
    backup.write_bytes(b"original\n")
    restored = restore_backups(tmp_path)
    assert (tmp_path / "a" / "b" / "demo.py").read_bytes() == b"original\n"
    assert restored == [tmp_path / "a" / "b" / "demo.py"]

    path = tmp_path / "src" / "demo.py"
    path.parent.mkdir()
    path.write_bytes(b"one")
    saved = _backup(tmp_path, path, b"one")
    assert saved.read_bytes() == b"one"
    assert _backup(tmp_path, path, b"two").read_bytes() == b"one"

    cache = path.parent / "__pycache__"
    cache.mkdir()
    compiled = cache / f"{path.stem}.cpython-314.pyc"
    compiled.write_bytes(b"pyc")
    _drop_bytecode(path)
    assert not compiled.exists()

    path.write_bytes(b"dirty")
    _restore_if_dirty(path, b"clean")
    assert path.read_bytes() == b"clean"


def test_selection_coverage_and_timeouts_keep_their_boundaries(tmp_path, capsys, monkeypatch):
    path = tmp_path / "src" / "demo.py"
    path.parent.mkdir()
    path.write_text("def place(x):\n    return x > 0\n", encoding="utf-8")
    site = _site()
    assert _covered(site, None, False) is False
    assert _covered(site, {2}, False) is True

    spans = {
        ("demo.Zebra", "defn/long"): (1, 10, "long", False),
        ("demo", "defn/short"): (3, 4, "short", False),
    }
    monkeypatch.setattr("mutator.engine.form_spans", lambda *args: spans)
    monkeypatch.setattr("mutator.engine.form_digests", lambda *args: {key: "d" for key in spans})
    ordered = _forms("x", path, tmp_path, "src/demo.py", [], {}, {}, None)
    assert [form.name for form in ordered] == ["long", "short"]

    owned = _site(namespace="demo", form_id="defn/place", mutation_id="m", line=2)
    place_span = {("demo", "defn/place"): (1, 3, "place", False)}
    monkeypatch.setattr("mutator.engine.form_spans", lambda *args: place_span)
    monkeypatch.setattr("mutator.engine.form_digests", lambda *args: {("demo", "defn/place"): "d"})
    counted = _forms("x", path, tmp_path, "src/demo.py", [owned], {"m": True}, {"m": "survived"}, {2})
    assert counted[0].survived == 1
    assert counted[0].killed == 0
    missing = _forms("x", path, tmp_path, "src/demo.py", [owned], {"m": True}, {}, None)
    assert missing[0].survived == 1

    history = History(
        forms={("demo", "defn/place"): PriorForm("demo", "defn/place", "old", "src/demo.py")},
        outcomes={"missing": "killed", "m": "killed"},
    )
    carried = _carry_forward([owned], {"m": True}, history, {("demo", "defn/place"): "new"})
    assert carried == {}
    uncovered = _carry_forward([owned], {"m": False}, history, {("demo", "defn/place"): "old"})
    assert uncovered == {}

    warned = mutate_file(
        path,
        tmp_path,
        runner=_Ok(),
        covered_lines=None,
        ignore_coverage=False,
        mutate_all=True,
        lines=None,
        test_command="fake",
        timeout_factor=10,
        mutation_warning=50,
        baselines={},
    )
    assert warned.forms[0].uncovered == 2
    assert "No coverage data" in capsys.readouterr().err

    quiet = mutate_file(
        path,
        tmp_path,
        runner=_Ok(),
        covered_lines=None,
        ignore_coverage=True,
        mutate_all=True,
        lines=None,
        test_command="fake",
        timeout_factor=10,
        mutation_warning=2,
        baselines={},
    )
    assert quiet.forms[0].killed == 2
    quiet_err = capsys.readouterr().err
    assert "WARNING" not in quiet_err
    assert "No coverage data" not in quiet_err

    clock = _Ok(seconds=30)
    mutate_file(
        path,
        tmp_path,
        runner=clock,
        covered_lines=None,
        ignore_coverage=True,
        mutate_all=True,
        lines=None,
        test_command="fake",
        timeout_factor=10,
        mutation_warning=50,
        baselines={},
    )
    assert clock.timeouts[0] is None
    assert clock.timeouts[1] == 300

    class Red:
        verbose = False

        def run(self, command, cwd, timeout):
            lines = ["START"] + [f"M{i}" for i in range(1, 24)] + ["END"]
            return CommandResult(code=1, timed_out=False, seconds=0.01, output="\n".join(lines))

    failed = mutate_file(
        path,
        tmp_path,
        runner=Red(),
        covered_lines=None,
        ignore_coverage=True,
        mutate_all=True,
        lines=None,
        test_command="fake",
        timeout_factor=10,
        mutation_warning=50,
        baselines={},
    )
    assert "M5" in failed.baseline_message
    assert "START" not in failed.baseline_message

    scanned = scan_file(path, tmp_path, covered_lines={1}, ignore_coverage=False, lines=None)
    assert "uncovered" in scanned
    ignored = scan_file(path, tmp_path, covered_lines={1}, ignore_coverage=True, lines=None)
    assert "uncovered" not in ignored


def test_mutate_all_reruns_a_killed_mutant_and_clears_its_backup(tmp_path):
    path = tmp_path / "src" / "demo.py"
    path.parent.mkdir()
    path.write_text("def add(a, b):\n    if a > 0:\n        return a + b\n    return a + b\n", encoding="utf-8")

    class Exec:
        def __init__(self):
            self.verbose = False
            self.calls = 0
            self._lock = threading.Lock()

        def run(self, command, cwd, timeout):
            with self._lock:
                self.calls += 1
            text = (cwd / "src" / "demo.py").read_text(encoding="utf-8")
            namespace: dict = {}
            exec(text, namespace)
            ok = namespace["add"](1, 2) == 3
            return CommandResult(code=0 if ok else 1, timed_out=False, seconds=0.01, output="")

    runner = Exec()
    first = mutate_file(
        path,
        tmp_path,
        runner=runner,
        covered_lines=None,
        ignore_coverage=True,
        mutate_all=True,
        lines=None,
        test_command="fake",
        timeout_factor=10,
        mutation_warning=50,
        baselines={},
    )
    killed_once = first.forms[0].killed
    first_calls = runner.calls
    second = mutate_file(
        path,
        tmp_path,
        runner=runner,
        covered_lines=None,
        ignore_coverage=True,
        mutate_all=True,
        lines=None,
        test_command="fake",
        timeout_factor=10,
        mutation_warning=50,
        baselines={},
    )
    assert second.forms[0].killed == killed_once
    assert runner.calls - first_calls == first_calls
    assert [item for item in (tmp_path / "target").rglob("*") if item.is_file()] == []


def test_a_missing_backup_is_not_unlinked_again(tmp_path):
    path = tmp_path / "src" / "demo.py"
    path.parent.mkdir()
    path.write_text("def place(x):\n    return x > 0\n", encoding="utf-8")

    class Drop:
        verbose = False

        def run(self, command, cwd, timeout):
            if timeout is not None:
                for backup in (cwd / "target" / "mutator-backup").rglob("*"):
                    if backup.is_file():
                        backup.unlink()
            return CommandResult(code=0 if timeout is None else 1, timed_out=False, seconds=0.01, output="")

    mutate_file(
        path,
        tmp_path,
        runner=Drop(),
        covered_lines=None,
        ignore_coverage=True,
        mutate_all=True,
        lines=None,
        test_command="fake",
        timeout_factor=10,
        mutation_warning=50,
        baselines={},
    )
    assert path.read_text(encoding="utf-8").startswith("def place")


def test_an_unrelated_snapshot_is_left_in_place(tmp_path):
    from mutator.edn import dumps

    legacy = tmp_path / ".metrics" / "mutate" / "legacy.edn"
    legacy.parent.mkdir(parents=True)
    legacy.write_text(
        dumps({"namespace": "untouched", "source": "c.go", "forms": [], "outcomes": {}}) + "\n",
        encoding="utf-8",
    )
    write_results(tmp_path, "a.go", [_form("demo", "Run", "a.go", "h")], {})
    assert legacy.is_file()


def test_snapshots_keep_other_files_empty_directories_and_odd_records(tmp_path):
    assert _outcomes_of({"outcomes": {"id": ":killed"}}) == {"id": "killed"}
    assert _form_file({"file": ""}, {"source": "a.go"}) == "a.go"
    assert _mentions_file({"forms": [{"file": "a.go"}]}, "a.go") is True
    assert _mentions_file({"forms": [{"file": "b.go"}]}, "a.go") is False
    assert _form_file_only({"source": "a.go", "forms": []}, "a.go") is True
    assert _form_file_only({"source": "b.go", "forms": []}, "a.go") is False
    assert _form_file_only({"forms": [{"file": "a.go"}]}, "a.go") is True
    assert _form_file_only({"forms": [{"file": "b.go"}]}, "a.go") is False

    assert _write_namespace(tmp_path, "ghost", [], {}, [], {}, "a.go") is None
    missing = tmp_path / "missing.edn"
    _drop_stale([(missing, {"namespace": "demo"})], {"demo"}, set(), "a.go")
    assert not missing.exists()

    write_results(tmp_path, "b.go", [_form("demo", "Stop", "b.go", "hash-b")], {})
    write_results(tmp_path, "a.go", [], {})
    kept = loads(snapshot_path(tmp_path, "demo").read_text(encoding="utf-8"))
    assert kept["source"] == "b.go"
    assert kept["forms"][0]["id"] == "defn/Stop"

    from mutator.edn import dumps, keyword
    from mutator.functions import mutation_id

    other = tmp_path / ".metrics" / "mutate" / "other.edn"
    other.write_text(
        dumps({"namespace": "other", "source": "c.go", "forms": [{"id": "defn/Stay", "file": "c.go", "line": 1, "hash": "h"}], "outcomes": {}})
        + "\n",
        encoding="utf-8",
    )
    foreign = mutation_id("b.go", "demo", "defn/Stop", 1, 2, "+", "-")
    snapshot_path(tmp_path, "demo").write_text(
        dumps(
            {
                "namespace": "demo",
                "source": "a.go",
                "forms": [
                    {"id": "defn/m", "file": "b.go", "hash": "m"},
                    {"id": "defn/a", "file": "b.go", "line": 1, "hash": "a"},
                ],
                "outcomes": {foreign: keyword("killed")},
            }
        )
        + "\n",
        encoding="utf-8",
    )
    write_results(tmp_path, "a.go", [], {})
    rewritten = loads(snapshot_path(tmp_path, "demo").read_text(encoding="utf-8"))
    assert [form["id"] for form in rewritten["forms"]] == ["defn/m", "defn/a"]
    assert foreign in rewritten["outcomes"]
    assert other.exists()

    odd = tmp_path / ".metrics" / "mutate" / "odd.edn"
    odd.write_text(
        dumps({"namespace": "odd", "forms": [{"id": 1, "hash": "digest", "file": "a.go"}], "outcomes": {}}) + "\n",
        encoding="utf-8",
    )
    assert load_history(tmp_path, "a.go").forms == {}

    base = tmp_path / "empty-metrics"
    nested = base / "empty" / "nested"
    nested.mkdir(parents=True)
    full = base / "full"
    full.mkdir()
    (full / "keep.edn").write_text("x", encoding="utf-8")
    _remove_empty_dirs(base)
    assert not nested.exists()
    assert not (base / "empty").exists()
    assert (full / "keep.edn").is_file()
    _remove_empty_dirs(tmp_path / "missing-metrics")


def test_scan_counts_a_site_inside_a_covered_multi_line_statement(tmp_path):
    path = tmp_path / "src" / "demo.py"
    path.parent.mkdir()
    path.write_text("def add(a, b):\n    return (a\n            + b)\n", encoding="utf-8")
    scanned = scan_file(path, tmp_path, covered_lines={2}, ignore_coverage=False, lines=None)
    assert "uncovered" not in scanned
    assert "+ -> -" in scanned
