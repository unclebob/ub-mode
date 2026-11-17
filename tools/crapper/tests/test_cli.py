import os
import subprocess
import sys
from pathlib import Path

import pytest

from crapper.cli import GitError, _changed_files, _positionals, _take, main, parse_args, run
from crapper.discover import is_test_file, language_of


def test_language_detection():
    assert language_of("src/app/core.clj") == "clojure"
    assert language_of("src/app/core.cljc") == "clojure"
    assert language_of("Widget.java") == "java"
    assert language_of("board.go") == "go"
    assert language_of("ui/view.tsx") == "typescript"
    assert language_of("src/app.js") == "typescript"
    assert language_of("src/app.mjs") == "typescript"
    assert language_of("src/app.cjs") == "typescript"
    assert language_of("src/app.jsx") == "typescript"
    assert language_of("src/lib.rs") == "rust"
    assert language_of("src/crapper/cli.py") == "python"
    assert language_of("types.d.ts") is None
    assert language_of("notes.md") is None
    assert language_of("app.cts") == "typescript"
    assert language_of("app.test.cts") == "typescript"
    assert is_test_file("app.test.cts") is True
    assert is_test_file("app.spec.cts") is True
    assert is_test_file("app.cts") is False


def _write(root: Path, relative: str, source: str) -> None:
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(source, encoding="utf-8")


class _Completed:
    def __init__(self, returncode: int, stdout: str, stderr: str = "") -> None:
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr


def _git_status(root: Path, status: str):
    def fake(args, **_kwargs):
        if "rev-parse" in args:
            return _Completed(0, f"{root.resolve()}\n")
        return _Completed(0, status)

    return fake


def test_project_snapshot_is_readable_as_crap4clj_edn(tmp_path):
    _write(
        tmp_path,
        "src/demo/core.clj",
        "(ns demo.core)\n\n(defn choose [x]\n  (if x 1 0))\n",
    )
    _write(
        tmp_path,
        "src/demo/Board.java",
        "package demo;\npublic class Board {\n  public int place(int x) {\n    if (x > 0 && ready) return 1;\n    return 0;\n  }\n}\n",
    )
    _write(
        tmp_path,
        "board.go",
        "package demo\n\nfunc Place(x int) int {\n\tif x > 0 {\n\t\treturn x\n\t}\n\treturn 0\n}\n",
    )
    _write(
        tmp_path,
        "src/ui/view.ts",
        "export function view(ok: boolean): number {\n  return ok ? 1 : 0;\n}\n",
    )
    _write(tmp_path, "Cargo.toml", '[package]\nname = "demo"\nversion = "0.1.0"\n')
    _write(
        tmp_path,
        "src/lib.rs",
        "pub fn open(ok: bool) -> i32 { if ok { 1 } else { 0 } }\n",
    )
    _write(tmp_path, "src/demo/core_test.clj", "(ns demo.core-test)\n(defn should-skip [] 1)\n")
    _write(tmp_path, "board_test.go", "package demo\nfunc TestPlace(t *testing.T) {}\n")

    code = run(["--root", str(tmp_path), "--no-coverage"])
    assert code == 0
    text = (tmp_path / ".metrics" / "crap.edn").read_text(encoding="utf-8")
    assert ':namespace "demo.core"' in text
    assert ':name "choose"' in text
    assert ':namespace "demo.Board"' in text
    assert ':name "place"' in text
    assert ':namespace "demo"' in text and ':name "Place"' in text
    assert ':namespace "ui.view"' in text
    assert ':namespace "demo"' in text and ':name "open"' in text
    assert "should-skip" not in text
    assert "TestPlace" not in text

    reader = subprocess.run(
        [
            "bb",
            "-e",
            (
                "(require '[clojure.edn :as edn])"
                "(let [data (edn/read-string (slurp *in*))]"
                " (doseq [entry (:entries data)]"
                "   (println (:namespace entry) (:name entry)"
                "            (:complexity entry) (pr-str (:coverage entry)))))"
            ),
        ],
        input=text,
        text=True,
        capture_output=True,
        check=False,
    )
    assert reader.returncode == 0, reader.stderr
    assert "demo.core choose 2 nil" in reader.stdout
    assert "demo.Board place 3 nil" in reader.stdout


def test_existing_coverage_feeds_the_snapshot(tmp_path):
    _write(
        tmp_path,
        "src/demo/core.clj",
        "(ns demo.core)\n\n(defn choose [x]\n  (if x 1 0))\n",
    )
    lcov = tmp_path / "target" / "coverage" / "lcov.info"
    lcov.parent.mkdir(parents=True)
    lcov.write_text(
        "SF:src/demo/core.clj\nDA:3,1\nDA:4,1\nend_of_record\n",
        encoding="utf-8",
    )
    code = run(
        ["--root", str(tmp_path), "--use-existing-coverage", "src/demo/core.clj"]
    )
    assert code == 0
    text = (tmp_path / ".metrics" / "crap.edn").read_text(encoding="utf-8")
    assert ":coverage 100.0" in text
    assert ":crap 2.0" in text


def test_threshold_exits_when_the_worst_score_is_too_high(tmp_path):
    _write(tmp_path, "src/demo/core.clj", "(ns demo.core)\n\n(defn choose [x]\n  (if x 1 0))\n")
    lcov = tmp_path / "target" / "coverage" / "lcov.info"
    lcov.parent.mkdir(parents=True)
    lcov.write_text("SF:src/demo/core.clj\nDA:3,0\nDA:4,0\nend_of_record\n", encoding="utf-8")
    code = run(
        [
            "--root",
            str(tmp_path),
            "--use-existing-coverage",
            "--threshold",
            "1",
        ]
    )
    assert code == 2


def test_help_and_unknown_option(capsys):
    assert run(["--help"]) == 0
    help_text = capsys.readouterr().out
    assert "uml-viewer" in help_text
    assert ".clj-kondo" in help_text
    assert ".test.cts" in help_text
    assert run(["--not-an-option"]) == 1
    assert run(["--root"]) == 1
    assert run(["--threshold", "nope"]) == 1
    assert run(["--no-coverage", "--coverage-command", "true"]) == 1


def test_selects_directories_filters_and_changed_files(tmp_path, monkeypatch, capsys):
    _write(tmp_path, "src/demo/core.clj", "(ns demo.core)\n\n(defn choose [x]\n  (if x 1 0))\n")
    _write(tmp_path, "notes.md", "not source\n")
    assert run(["--root", str(tmp_path), "--no-coverage", "--source-root", "src"]) == 0
    assert run(["--root", str(tmp_path), "--no-coverage", "src"]) == 0
    assert run(["--root", str(tmp_path), "--no-coverage", "src/demo/core.clj", "core"]) == 0
    assert run(["--root", str(tmp_path), "--no-coverage", "notes.md"]) == 0
    assert "No source files" in capsys.readouterr().out

    monkeypatch.setattr(
        "crapper.cli.subprocess.run",
        _git_status(tmp_path, " M src/demo/core.clj\0"),
    )
    assert run(["--root", str(tmp_path), "--no-coverage", "--changed"]) == 0
    assert ':name "choose"' in (tmp_path / ".metrics" / "crap.edn").read_text(encoding="utf-8")

    monkeypatch.setattr(
        "crapper.cli.subprocess.run",
        lambda *_args, **_kwargs: _Completed(128, "", "fatal: not a git repository"),
    )
    assert run(["--root", str(tmp_path), "--no-coverage", "--changed"]) == 128
    captured = capsys.readouterr()
    assert "not a git repository" in captured.err
    assert "No source files" not in captured.out


def test_run_asks_for_coverage_when_it_is_enabled(tmp_path, monkeypatch):
    _write(tmp_path, "src/demo/core.clj", "(ns demo.core)\n\n(defn choose [x]\n  x)\n")
    called = []
    monkeypatch.setattr("crapper.cli.run_coverage", lambda *args: called.append(args))
    assert run(["--root", str(tmp_path), "--coverage-command", "true"]) == 0
    assert called


def test_empty_option_value_is_rejected():
    with pytest.raises(ValueError, match="requires a value"):
        _take(["ok", "--opt", ""], 1, "--opt")
    with pytest.raises(ValueError, match="requires a value"):
        _take(["--opt", "-1"], 0, "--opt")


def test_parse_args_reads_the_process_arguments_after_the_program(monkeypatch):
    monkeypatch.setattr(sys, "argv", ["crapper", "--threshold", "5"])
    options = parse_args()
    assert options.threshold == 5.0
    assert options.positionals == []
    assert options.action == "analyze"


def test_flags_that_only_store_a_boolean():
    assert parse_args(["--use-existing-coverage"]).use_existing_coverage is True
    assert parse_args(["--changed"]).changed is True
    assert parse_args(["--no-coverage"]).no_coverage is True
    conflict = parse_args(["--no-coverage", "--coverage-command", "true"])
    assert conflict.exit_code == 1


def test_changed_files_follow_git_status(tmp_path, monkeypatch, capsys):
    seen = {}
    named = tmp_path / "x"
    named.write_text("x", encoding="utf-8")
    keep = tmp_path / "sources" / "keep.py"
    keep.parent.mkdir()
    keep.write_text("x = 1\n", encoding="utf-8")

    def fake(args, **kwargs):
        seen["args"] = args
        seen.update(kwargs)
        if "rev-parse" in args:
            return _Completed(0, f"{tmp_path.resolve()}\n")
        return _Completed(0, " M x\0?? sources/keep.py\0")

    monkeypatch.setattr("crapper.cli.subprocess.run", fake)
    found = _changed_files(tmp_path)
    assert seen["check"] is False
    assert seen["capture_output"] is True
    assert seen["text"] is True
    assert seen["encoding"] == "utf-8"
    assert "-z" in seen["args"]
    assert "--no-renames" in seen["args"]
    assert "--untracked-files=all" in seen["args"]
    assert found == [named.resolve(), keep.resolve()]

    monkeypatch.setattr(
        "crapper.cli.subprocess.run",
        lambda *_a, **_k: _Completed(1, "", "git failed"),
    )
    with pytest.raises(GitError, match="git failed") as failed:
        _changed_files(tmp_path)
    assert failed.value.code == 1
    assert capsys.readouterr().err == ""

    monkeypatch.setattr(
        "crapper.cli.subprocess.run",
        lambda *_a, **_k: _Completed(128, "", ""),
    )
    with pytest.raises(GitError, match="git status failed") as silent:
        _changed_files(tmp_path)
    assert silent.value.code == 128


def test_relative_paths_are_resolved_under_the_project_root(tmp_path):
    _write(tmp_path, "src/demo/core.clj", "(ns demo.core)\n\n(defn choose [x]\n  (if x 1 0))\n")
    existing, filters = _positionals(tmp_path, ["src/demo/core.clj"])
    assert existing == [(tmp_path / "src/demo/core.clj").resolve()]
    assert filters == []
    assert run(["--root", str(tmp_path), "--no-coverage", "src/demo/core.clj"]) == 0
    text = (tmp_path / ".metrics" / "crap.edn").read_text(encoding="utf-8")
    assert ':name "choose"' in text
    assert "parse_args" not in text


def test_changed_selection_ignores_files_git_did_not_name(tmp_path, monkeypatch, capsys):
    _write(tmp_path, "src/kept.clj", "(ns demo.kept)\n\n(defn kept [] 1)\n")
    _write(tmp_path, "src/other.clj", "(ns demo.other)\n\n(defn other [] 1)\n")

    monkeypatch.setattr(
        "crapper.cli.subprocess.run",
        _git_status(tmp_path, "?? src/kept.clj\0"),
    )
    assert run(["--root", str(tmp_path), "--no-coverage", "--changed"]) == 0
    text = (tmp_path / ".metrics" / "crap.edn").read_text(encoding="utf-8")
    assert ':name "kept"' in text
    assert "other" not in text
    assert "No source files" not in capsys.readouterr().out


def test_threshold_equal_to_the_score_is_allowed(tmp_path):
    _write(tmp_path, "src/demo/core.clj", "(ns demo.core)\n\n(defn choose [] 1)\n")
    lcov = tmp_path / "target" / "coverage" / "lcov.info"
    lcov.parent.mkdir(parents=True)
    lcov.write_text("SF:src/demo/core.clj\nDA:1,1\nDA:3,1\nend_of_record\n", encoding="utf-8")
    code = run(
        ["--root", str(tmp_path), "--use-existing-coverage", "--threshold", "1", "src/demo/core.clj"]
    )
    assert code == 0


def test_threshold_with_no_scores_does_not_call_max_on_an_empty_list(tmp_path):
    _write(tmp_path, "src/demo/core.clj", "(ns demo.core)\n\n(defn choose [] 1)\n")
    assert run(["--root", str(tmp_path), "--no-coverage", "--threshold", "1"]) == 0


def test_a_failed_coverage_command_ignores_a_stale_report(tmp_path, monkeypatch):
    _write(tmp_path, "src/demo/core.clj", "(ns demo.core)\n\n(defn choose [x]\n  (if x 1 0))\n")
    lcov = tmp_path / "target" / "coverage" / "lcov.info"
    lcov.parent.mkdir(parents=True)
    lcov.write_text("SF:src/demo/core.clj\nDA:3,1\nDA:4,1\nend_of_record\n", encoding="utf-8")
    monkeypatch.setattr("crapper.cli.run_coverage", lambda *_args, **_kwargs: 3)
    code = run(
        ["--root", str(tmp_path), "--coverage-command", "exit 3", "src/demo/core.clj"]
    )
    assert code == 0
    text = (tmp_path / ".metrics" / "crap.edn").read_text(encoding="utf-8")
    assert ":coverage 0.0" in text
    assert ":crap 6.0" in text

    monkeypatch.setattr("crapper.cli.run_coverage", lambda *_args, **_kwargs: 0)
    assert run(
        ["--root", str(tmp_path), "--coverage-command", "true", "src/demo/core.clj"]
    ) == 0
    text = (tmp_path / ".metrics" / "crap.edn").read_text(encoding="utf-8")
    assert ":coverage 100.0" in text


def test_changed_files_from_a_real_git_status(tmp_path):
    _git = ["git", "-C", str(tmp_path)]
    subprocess.run([*_git, "init"], check=True, capture_output=True)
    subprocess.run([*_git, "config", "user.email", "t@example.com"], check=True)
    subprocess.run([*_git, "config", "user.name", "t"], check=True)
    keep = tmp_path / "keep.go"
    keep.write_text("package demo\nfunc Keep() int { return 1 }\n", encoding="utf-8")
    subprocess.run([*_git, "add", "keep.go"], check=True)
    subprocess.run([*_git, "commit", "-m", "init"], check=True, capture_output=True)
    keep.unlink()
    cafe = tmp_path / "café.go"
    cafe.write_text("package demo\nfunc Cafe() int { return 1 }\n", encoding="utf-8")
    fresh = tmp_path / "fresh"
    fresh.mkdir()
    (fresh / "new.go").write_text("package demo\nfunc New() int { return 1 }\n", encoding="utf-8")

    assert run(["--root", str(tmp_path), "--no-coverage", "--changed"]) == 0
    text = (tmp_path / ".metrics" / "crap.edn").read_text(encoding="utf-8")
    assert ':name "Cafe"' in text
    assert ':name "New"' in text
    assert "Keep" not in text

    pkg = tmp_path / "pkg"
    pkg.mkdir()
    (pkg / "inside.go").write_text("package pkg\nfunc Inside() int { return 1 }\n", encoding="utf-8")
    (tmp_path / "outside.go").write_text(
        "package demo\nfunc Outside() int { return 1 }\n",
        encoding="utf-8",
    )
    assert run(["--root", str(pkg), "--no-coverage", "--changed"]) == 0
    nested = (pkg / ".metrics" / "crap.edn").read_text(encoding="utf-8")
    assert ':name "Inside"' in nested
    assert "Outside" not in nested
    assert "Cafe" not in nested
    assert "New" not in nested


def test_changed_outside_a_repository_returns_gits_status(tmp_path, capsys):
    _write(tmp_path, "src/app.py", "def run():\n    return 1\n")
    code = run(["--root", str(tmp_path), "--no-coverage", "--changed"])
    assert code != 0
    captured = capsys.readouterr()
    assert "No source files" not in captured.out
    assert captured.err.strip()


def test_uml_loader_rejects_a_missing_viewer(tmp_path):
    script = Path(__file__).resolve().parents[1] / "uml"
    completed = subprocess.run(
        [str(script)],
        env={**os.environ, "UML_VIEWER_ROOT": str(tmp_path / "missing")},
        capture_output=True,
        text=True,
        check=False,
    )
    assert completed.returncode == 1
    assert "uml-viewer checkout not found" in completed.stderr


def test_main_exits_with_the_status(monkeypatch):
    monkeypatch.setattr("crapper.cli.run", lambda _argv=None: 4)
    try:
        main(["--help"])
    except SystemExit as exc:
        assert exc.code == 4
    else:
        raise AssertionError("main did not exit")
