from mutator.coverage import covered_lines


def test_jacoco_marks_lines_with_covered_instructions(tmp_path):
    report = tmp_path / "target" / "site" / "jacoco" / "jacoco.xml"
    report.parent.mkdir(parents=True)
    report.write_text(
        """<?xml version="1.0"?>
<report name="demo">
  <package name="demo">
    <sourcefile name="Board.java">
      <line nr="3" mi="0" ci="2" mb="0" cb="0"/>
      <line nr="4" mi="4" ci="0" mb="1" cb="0"/>
      <line nr="5" mi="0" ci="0" mb="0" cb="1"/>
    </sourcefile>
  </package>
</report>
""",
        encoding="utf-8",
    )
    source = tmp_path / "src" / "demo" / "Board.java"
    source.parent.mkdir(parents=True)
    source.write_text("package demo;\nclass Board {}\n", encoding="utf-8")
    assert covered_lines(tmp_path, source, "java") == {3, 5}


def test_go_profile_marks_hit_lines(tmp_path):
    profile = tmp_path / "target" / "coverage" / "go" / "coverage.out"
    profile.parent.mkdir(parents=True)
    profile.write_text(
        "mode: set\n"
        "demo/widget.go:2.1,4.2 1 1\n"
        "demo/widget.go:5.1,6.2 1 0\n",
        encoding="utf-8",
    )
    source = tmp_path / "demo" / "widget.go"
    source.parent.mkdir()
    source.write_text("package demo\nfunc Run() {}\n", encoding="utf-8")
    assert covered_lines(tmp_path, source, "go") == {2, 3, 4}
    assert covered_lines(tmp_path, tmp_path / "other" / "missing.go", "go") is None
    empty = tmp_path / "empty"
    empty.mkdir()
    assert covered_lines(empty, empty / "demo" / "widget.go", "go") is None


def test_lcov_marks_executed_lines(tmp_path):
    report = tmp_path / "target" / "coverage" / "python" / "lcov.info"
    report.parent.mkdir(parents=True)
    report.write_text(
        "SF:src/demo/app.py\nDA:1,3\nDA:2,0\nend_of_record\n",
        encoding="utf-8",
    )
    source = tmp_path / "src" / "demo" / "app.py"
    source.parent.mkdir(parents=True)
    source.write_text("def place():\n    return 1\n", encoding="utf-8")
    assert covered_lines(tmp_path, source, "python") == {1}
    assert covered_lines(tmp_path, source, "clojure") == {1}


def test_lcov_does_not_let_a_shorter_path_steal_hits(tmp_path):
    report = tmp_path / "target" / "coverage" / "python" / "lcov.info"
    report.parent.mkdir(parents=True)
    report.write_text(
        "SF:b/app.py\nDA:2,1\nend_of_record\n"
        "SF:a/b/app.py\nDA:1,1\nend_of_record\n",
        encoding="utf-8",
    )
    short = tmp_path / "b" / "app.py"
    long = tmp_path / "a" / "b" / "app.py"
    short.parent.mkdir(parents=True)
    long.parent.mkdir(parents=True)
    short.write_text("def place():\n    return 1\n", encoding="utf-8")
    long.write_text("def place():\n    return 1\n", encoding="utf-8")
    assert covered_lines(tmp_path, long, "python") == {1}
    assert covered_lines(tmp_path, short, "python") == {2}


def test_go_profile_does_not_cross_files(tmp_path):
    profile = tmp_path / "target" / "coverage" / "go" / "coverage.out"
    profile.parent.mkdir(parents=True)
    profile.write_text(
        "mode: set\n"
        "b/widget.go:2.1,2.2 1 1\n"
        "a/b/widget.go:3.1,3.2 1 0\n",
        encoding="utf-8",
    )
    short = tmp_path / "b" / "widget.go"
    long = tmp_path / "a" / "b" / "widget.go"
    short.parent.mkdir(parents=True)
    long.parent.mkdir(parents=True)
    short.write_text("package b\nfunc Run() {}\n", encoding="utf-8")
    long.write_text("package b\nfunc Run() {}\n", encoding="utf-8")
    assert covered_lines(tmp_path, short, "go") == {2}
    assert covered_lines(tmp_path, long, "go") == set()

    profile.write_text("mode: set\na/b/widget.go:2.1,2.2 1 1\n", encoding="utf-8")
    assert covered_lines(tmp_path, short, "go") is None
    assert covered_lines(tmp_path, long, "go") == {2}


def _go_module(module_dir, module, profile_text):
    module_dir.mkdir(parents=True, exist_ok=True)
    (module_dir / "go.mod").write_text(f"module {module}\n\ngo 1.21\n", encoding="utf-8")
    profile = module_dir / "target" / "coverage" / "go" / "coverage.out"
    profile.parent.mkdir(parents=True)
    profile.write_text(profile_text, encoding="utf-8")


def _go_source(path):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("package main\nfunc main() {}\n", encoding="utf-8")
    return path


def test_go_profile_keyed_by_import_path_marks_hit_lines(tmp_path):
    # `go test -coverprofile` names files by module import path, not by disk path.
    _go_module(
        tmp_path,
        "github.com/acme/tool",
        "mode: set\n"
        "github.com/acme/tool/cmd/tool/main.go:2.1,3.2 1 1\n"
        "github.com/acme/tool/cmd/tool/main.go:4.1,4.2 1 0\n"
        "github.com/acme/tool/main.go:7.1,7.2 1 1\n",
    )
    nested = _go_source(tmp_path / "cmd" / "tool" / "main.go")
    top = _go_source(tmp_path / "main.go")
    assert covered_lines(tmp_path, nested, "go") == {2, 3}
    assert covered_lines(tmp_path, top, "go") == {7}


def test_go_profile_of_a_module_in_a_subdirectory(tmp_path):
    _go_module(
        tmp_path / "svc",
        "example.com/api",
        "mode: set\nexample.com/api/calc/calc.go:4.2,6.1 1 1\n",
    )
    source = _go_source(tmp_path / "svc" / "calc" / "calc.go")
    assert covered_lines(tmp_path, source, "go") == {4, 5, 6}
