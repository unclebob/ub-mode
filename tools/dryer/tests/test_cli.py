import subprocess
from pathlib import Path

import pytest

from dryer.cli import (
    GitStatusError,
    _changed_files,
    _count,
    _tracked_source,
    main,
    parse_args,
    run,
    select_files,
)
from dryer.discover import is_test_file, iter_source_files, language_of


def write_source(root: Path, name: str, text: str) -> None:
    path = root / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def test_language_detection():
    assert language_of("src/app/core.clj") == "clojure"
    assert language_of("src/app/core.cljd") == "clojure"
    assert language_of("Widget.java") == "java"
    assert language_of("board.go") == "go"
    assert language_of("ui/view.tsx") == "typescript"
    assert language_of("src/lib.rs") == "rust"
    assert language_of("src/dryer/cli.py") == "python"
    assert language_of("types.d.ts") is None
    assert language_of("notes.md") is None


def test_help_does_not_scan(capsys):
    assert run(["--help"]) == 0
    out = capsys.readouterr().out
    assert "Usage: dryer" in out
    assert "__pycache__" in out
    assert ".test.cts" in out
    assert "--threshold" in out


def test_unknown_format(capsys):
    assert run(["--format", "csv"]) == 2
    err = capsys.readouterr().err
    assert err == "Unknown format: csv\n"


def test_unknown_option(capsys):
    assert run(["--nope"]) == 1
    assert "Unknown option: --nope" in capsys.readouterr().err


def test_parse_defaults_and_flags():
    options = parse_args(
        ["--threshold", "0.9", "--min-lines", "5", "--min-nodes", "30", "--edn", "spec"]
    )
    assert options.threshold == 0.9
    assert options.min_lines == 5
    assert options.min_nodes == 30
    assert options.format == "edn"
    assert options.positionals == ["spec"]


def test_project_report_and_snapshot(tmp_path, capsys):
    write_source(
        tmp_path,
        "src/left.py",
        "def alpha(xs):\n    ys = filter(xs, odd)\n    zs = map(ys, inc)\n    return list(zs)\n",
    )
    write_source(
        tmp_path,
        "src/right.py",
        "def beta(items):\n    kept = filter(items, even)\n    out = map(kept, dec)\n    return list(out)\n",
    )
    write_source(
        tmp_path,
        "tests/test_left.py",
        "def alpha(xs):\n    ys = filter(xs, odd)\n    zs = map(ys, inc)\n    return list(zs)\n",
    )
    code = run(["--root", str(tmp_path), "--min-lines", "3", "--min-nodes", "1"])
    captured = capsys.readouterr()
    assert code == 0
    assert "DUPLICATE score=1.00" in captured.out
    assert "src/left.py:1-" in captured.out
    assert "src/right.py:1-" in captured.out
    assert "test_left.py" not in captured.out
    text = (tmp_path / ".metrics" / "dry.edn").read_text(encoding="utf-8")
    assert ':language "python"' in text
    assert ':file "src/left.py"' in text
    assert "Wrote" in captured.err


def test_edn_stdout(tmp_path, capsys):
    write_source(tmp_path, "one.py", "def alpha(xs):\n    return xs\n")
    assert run(["--root", str(tmp_path), "--edn", "--min-lines", "1", "--min-nodes", "1"]) == 0
    out = capsys.readouterr().out
    assert out == "{:candidates []}\n"


def test_empty_project(tmp_path, capsys):
    assert run(["--root", str(tmp_path)]) == 0
    assert capsys.readouterr().out == "No source files to analyze.\n"


def test_path_filter(tmp_path, capsys):
    body_a = "def alpha(xs):\n    ys = filter(xs, odd)\n    zs = map(ys, inc)\n    return list(zs)\n"
    body_b = "def beta(items):\n    kept = filter(items, even)\n    out = map(kept, dec)\n    return list(out)\n"
    write_source(tmp_path, "src/board/a.py", body_a)
    write_source(tmp_path, "src/board/b.py", body_b)
    write_source(tmp_path, "src/other/a.py", body_a)
    write_source(tmp_path, "src/other/b.py", body_b)
    assert run(["--root", str(tmp_path), "--min-lines", "3", "--min-nodes", "1", "board"]) == 0
    out = capsys.readouterr().out
    assert "src/board/a.py" in out
    assert "src/other/a.py" not in out


def test_explicit_test_file_is_included(tmp_path, capsys):
    body = "def alpha(xs):\n    ys = filter(xs, odd)\n    zs = map(ys, inc)\n    return list(zs)\n"
    other = "def beta(items):\n    kept = filter(items, even)\n    out = map(kept, dec)\n    return list(out)\n"
    write_source(tmp_path, "src/a.py", body)
    write_source(tmp_path, "tests/test_a.py", other)
    assert (
        run(
            [
                "--root",
                str(tmp_path),
                "--min-lines",
                "3",
                "--min-nodes",
                "1",
                "src/a.py",
                "tests/test_a.py",
            ]
        )
        == 0
    )
    out = capsys.readouterr().out
    assert "src/a.py" in out
    assert "tests/test_a.py" in out


def test_main_exits(monkeypatch):
    codes = []
    monkeypatch.setattr("dryer.cli.sys.exit", lambda code: codes.append(code))
    main(["--help"])
    assert codes == [0]


def test_names_that_are_tests():
    names = [
        "pkg/foo_test.go",
        "src/app_test.clj",
        "src/app_test.cljc",
        "src/app_test.cljs",
        "src/app_test.cljd",
        "src/app_test.bb",
        "ui/a.test.ts",
        "ui/a.spec.ts",
        "ui/a.test.tsx",
        "ui/a.spec.tsx",
        "ui/a.test.mts",
        "ui/a.spec.mts",
        "ui/a.test.cts",
        "ui/a.spec.cts",
        "tests/conftest.py",
        "pkg/foo_test.py",
        "pkg/test_foo.py",
        "tests/app.py",
    ]
    for name in names:
        assert is_test_file(name), name
    assert not is_test_file("src/app.py")


def _init_repo(path: Path) -> None:
    subprocess.run(["git", "init"], cwd=path, check=True, capture_output=True)
    subprocess.run(["git", "config", "user.email", "dev@example.com"], cwd=path, check=True)
    subprocess.run(["git", "config", "user.name", "Dev"], cwd=path, check=True)


def _commit(path: Path, message: str) -> None:
    subprocess.run(["git", "add", "-A"], cwd=path, check=True, capture_output=True)
    subprocess.run(["git", "commit", "-m", message], cwd=path, check=True, capture_output=True)


def test_changed_files_reads_real_status(tmp_path):
    _init_repo(tmp_path)
    write_source(tmp_path, "src/a.py", "x = 1\n")
    write_source(tmp_path, "src/old.py", "x = 1\n")
    write_source(tmp_path, "gone.py", "x = 1\n")
    _commit(tmp_path, "base")
    (tmp_path / "gone.py").unlink()
    subprocess.run(["git", "mv", "src/old.py", "src/b.py"], cwd=tmp_path, check=True, capture_output=True)
    write_source(tmp_path, "src/c d.py", "x = 1\n")
    write_source(tmp_path, "src/café.py", "x = 1\n")
    write_source(tmp_path, "fresh/nested/new.py", "x = 1\n")
    found = set(_changed_files(tmp_path))
    assert (tmp_path / "src/b.py").resolve() in found
    assert (tmp_path / "src/c d.py").resolve() in found
    assert (tmp_path / "src/café.py").resolve() in found
    assert (tmp_path / "fresh/nested/new.py").resolve() in found
    assert (tmp_path / "gone.py").resolve() not in found
    assert (tmp_path / "src/old.py").resolve() not in found


def test_changed_files_reports_git_failure(tmp_path, capsys):
    with pytest.raises(GitStatusError) as caught:
        _changed_files(tmp_path)
    assert caught.value.code == 128
    assert "not a git repository" in caught.value.message
    assert capsys.readouterr().err == ""

    code = run(["--changed", "--root", str(tmp_path)])
    captured = capsys.readouterr()
    assert code == 128
    assert "not a git repository" in captured.err
    assert "No source files" not in captured.out


def test_an_empty_git_error_uses_the_fallback_text(monkeypatch, tmp_path):
    def fail(args, **kwargs):
        return subprocess.CompletedProcess(args, 128, b"", b"   \n")

    monkeypatch.setattr("dryer.cli.subprocess.run", fail)
    with pytest.raises(GitStatusError, match="git status failed") as caught:
        _changed_files(tmp_path)
    assert caught.value.code == 128


def test_changed_limits_the_report(tmp_path, capsys):
    body = "def alpha(xs):\n    ys = filter(xs, odd)\n    zs = map(ys, inc)\n    return list(zs)\n"
    other = "def beta(items):\n    kept = filter(items, even)\n    out = map(kept, dec)\n    return list(out)\n"
    _init_repo(tmp_path)
    write_source(tmp_path, "src/c.py", body)
    _commit(tmp_path, "base")
    write_source(tmp_path, "src/a.py", body)
    write_source(tmp_path, "src/b.py", other)
    write_source(tmp_path, "tests/test_a.py", other)
    write_source(tmp_path, "README.md", "notes\n")
    assert run(["--root", str(tmp_path), "--changed", "--min-lines", "3", "--min-nodes", "1"]) == 0
    out = capsys.readouterr().out
    assert "src/a.py" in out
    assert "src/b.py" in out
    assert "src/c.py" not in out
    assert "test_a.py" not in out


def test_an_option_without_a_value_is_a_usage_error(capsys):
    assert run(["--threshold"]) == 1
    assert "--threshold requires a value" in capsys.readouterr().err


def test_an_empty_value_is_rejected(capsys):
    assert run(["file.py", "--min-lines", ""]) == 1
    assert "--min-lines requires a value" in capsys.readouterr().err


def test_a_flag_is_not_a_value(capsys):
    assert run(["--min-lines", "--min-nodes", "1"]) == 1
    assert "--min-lines requires a value" in capsys.readouterr().err


def test_zero_is_a_valid_count():
    options = parse_args(["--min-lines", "0", "--min-nodes", "0"])
    assert options.action == "scan"
    assert options.min_lines == 0
    assert options.min_nodes == 0


def test_a_negative_count_is_rejected():
    with pytest.raises(ValueError, match="non-negative"):
        _count("-1", "--min-lines")


def test_a_count_must_be_an_integer(capsys):
    assert run(["--min-lines", "nope"]) == 1
    assert "--min-lines requires an integer" in capsys.readouterr().err


def test_a_threshold_must_be_a_number(capsys):
    assert run(["--threshold", "nope"]) == 1
    assert "--threshold requires a number" in capsys.readouterr().err


def test_parse_reads_the_process_arguments(monkeypatch):
    monkeypatch.setattr("dryer.cli.sys.argv", ["dryer", "only-this"])
    assert parse_args().positionals == ["only-this"]


def test_text_format_wins_when_it_comes_last(tmp_path, capsys):
    write_source(tmp_path, "one.py", "def alpha(xs):\n    return xs\n")
    assert run(["--root", str(tmp_path), "--edn", "--text", "--min-lines", "1", "--min-nodes", "1"]) == 0
    assert capsys.readouterr().out == "No duplicate candidates found.\n"


def test_source_root_limits_the_walk(tmp_path):
    write_source(tmp_path, "src/a.py", "x = 1\n")
    write_source(tmp_path, "extra/b.py", "x = 1\n")
    options = parse_args(["--root", str(tmp_path), "--source-root", "src"])
    files = [path.relative_to(tmp_path).as_posix() for path in select_files(options)]
    assert files == ["src/a.py"]


def test_changed_from_a_subdirectory_stays_inside_it(tmp_path):
    repo = tmp_path / "repo"
    sub = repo / "sub"
    repo.mkdir()
    _init_repo(repo)
    write_source(repo, "src/above.py", "x = 1\n")
    write_source(sub, "src/below.py", "x = 1\n")
    chosen = select_files(parse_args(["--root", str(sub), "--changed"]))
    assert chosen == [(sub / "src/below.py").resolve()]


def test_a_short_untracked_name_is_a_file(tmp_path):
    _init_repo(tmp_path)
    write_source(tmp_path, "a.py", "x = 1\n")
    assert _changed_files(tmp_path) == [(tmp_path / "a.py").resolve()]


def test_changed_source_skips_unknown_and_test_files(tmp_path):
    assert _tracked_source(tmp_path / "README.md") is False
    assert _tracked_source(tmp_path / "tests" / "test_a.py") is False
    assert _tracked_source(tmp_path / "src" / "a.py") is True
    assert _tracked_source(tmp_path / "target" / "app.py") is False


def test_test_files_and_skipped_directories_are_left_out(tmp_path):
    write_source(tmp_path, "src/app.py", "x = 1\n")
    write_source(tmp_path, "src/foo_test.py", "x = 1\n")
    write_source(tmp_path, "target/app.py", "x = 1\n")
    write_source(tmp_path, "tests/app.py", "x = 1\n")
    write_source(tmp_path, "src/app.test.cts", "export {}\n")
    write_source(tmp_path, "src/app.spec.cts", "export {}\n")
    write_source(tmp_path, "src/widget.cts", "export {}\n")
    relative = [path.relative_to(tmp_path).as_posix() for path in iter_source_files([tmp_path])]
    assert sorted(relative) == ["src/app.py", "src/widget.cts"]


def test_a_second_report_replaces_the_snapshot(tmp_path, capsys):
    write_source(tmp_path, "one.py", "def alpha(xs):\n    return xs\n")
    args = ["--root", str(tmp_path), "--min-lines", "1", "--min-nodes", "1"]
    assert run(args) == 0
    assert run(args) == 0
    assert (tmp_path / ".metrics" / "dry.edn").read_text(encoding="utf-8") == "{:candidates []}\n"
