"""Branches the first mutation run left unexecuted."""

import runpy
from pathlib import Path

import pytest

from crapper.analyze import analyze_files
from crapper.coverage import (
    CoverageBundle,
    coverage_percent,
    go_percent,
    load_bundle,
    normalize_path,
    parse_form_coverage,
    parse_go_profile,
    parse_jacoco_index,
    parse_lcov,
    percent_for_range,
    suffix_match,
)
from crapper.discover import is_test_file, iter_source_files
from crapper.languages.clojure import _extract_top_level_defns, extract_functions
from crapper.languages.golang import functions_in_source as go_functions
from crapper.languages.java import functions_in_source as java_functions
from crapper.languages.python import functions_in_source as python_functions
from crapper.languages.rust import functions_in_source as rust_functions
from crapper.languages.treesitter import child_of_type, end_line, parse
from crapper.languages.typescript import functions_in_source as ts_functions
from crapper.model import Function


def function(**kwargs):
    defaults = dict(
        name="place",
        namespace="demo.Board",
        complexity=1,
        start_line=4,
        end_line=6,
        path="board.go",
        language="go",
        jacoco_class=None,
    )
    defaults.update(kwargs)
    return Function(**defaults)


def test_normalize_path_strips_url_and_dot_prefixes():
    assert normalize_path("file:./src//demo/core.clj") == "src/demo/core.clj"
    assert normalize_path("%2E%2Fsrc%2Fcore.clj") == "src/core.clj"


def test_suffix_match_rejects_a_longer_suffix():
    assert suffix_match("src/demo/core.clj", "demo/core.clj") is True
    assert suffix_match("core.clj", "src/demo/core.clj") is False
    assert suffix_match("src/demo/core.clj", "") is False


def test_coverage_percent_is_zero_when_there_is_nothing_to_count():
    assert coverage_percent(0, 0) == 0.0
    assert percent_for_range({}, 1, 3) == 0.0
    assert percent_for_range({1: (1, 1)}, 4, 5) == 0.0


def test_form_coverage_ignores_a_span_with_no_forms():
    html = '<span title="0 out of 0 forms covered">   4&nbsp;</span>'
    assert parse_form_coverage(html) == {}


def test_lcov_keeps_a_file_that_has_no_end_record():
    parsed = parse_lcov("SF:a.clj\nDA:1,1\nSF:b.clj\nDA:2,0\n")
    assert parsed["a.clj"][1] == (1, 1)
    assert parsed["b.clj"][2] == (0, 1)


def test_go_profile_rejects_a_broken_line():
    with pytest.raises(ValueError, match="invalid coverage segment"):
        parse_go_profile("mode: set\nnot a segment\n")


def test_go_percent_is_zero_when_no_segment_overlaps_the_function():
    profile = parse_go_profile("mode: set\nboard.go:1.1,2.2 1 1\n")
    assert go_percent(profile, "board.go", 10, 12) == 0.0


def test_go_percent_does_not_accept_the_first_unrelated_file():
    profile = parse_go_profile(
        "mode: set\nother.go:4.1,6.2 2 0\nboard.go:4.1,6.2 2 1\n"
    )
    assert go_percent(profile, "board.go", 4, 6) == 100.0


def test_jacoco_miss_is_none_and_a_missing_counter_field_is_zero():
    xml = """<report><class name="demo/Board">
      <method name="place" line="4">
        <counter type="INSTRUCTION" covered="4"/>
      </method>
    </class></report>"""
    found = parse_jacoco_index(xml)
    assert found["demo.Board#place"][0].missed == 0
    assert found["demo.Board#place"][0].percent == 100.0
    bundle = CoverageBundle(jacoco=found)
    assert bundle.percent_for(function(language="java", name="missing", namespace="demo.Board")) is None
    assert CoverageBundle().percent_for(function(language="java", namespace="demo.Board")) is None


def test_jacoco_uses_the_nearest_method_when_the_line_does_not_match():
    xml = """<report><class name="demo/Board">
      <method name="place" line="4">
        <counter type="INSTRUCTION" missed="0" covered="4"/>
      </method>
      <method name="place" line="20">
        <counter type="INSTRUCTION" missed="4" covered="0"/>
      </method>
    </class></report>"""
    bundle = CoverageBundle(jacoco=parse_jacoco_index(xml))
    assert bundle.percent_for(function(language="java", name="place", namespace="demo.Board", start_line=18)) == 0.0


def test_go_coverage_is_read_from_the_bundle():
    profile = parse_go_profile("mode: set\nboard.go:4.1,6.2 2 1\n")
    bundle = CoverageBundle(go_profile=profile)
    assert bundle.percent_for(function()) == 100.0


def test_the_most_specific_prefix_wins_and_a_tie_matches_nothing():
    specific = parse_go_profile(
        "mode: set\n"
        "other/proj/src/a.go:1.1,2.2 1 0\n"
        "proj/src/a.go:1.1,2.2 1 1\n"
    )
    assert go_percent(specific, "src/a.go", 1, 2) == 100.0
    tied = parse_go_profile(
        "mode: set\nproj/a.go:1.1,2.2 1 1\nother/a.go:1.1,2.2 1 0\n"
    )
    assert go_percent(tied, "a.go", 1, 2) is None
    assert go_percent(specific, "/", 1, 2) is None


def test_exact_profile_key_wins_over_a_suffix():
    profile = parse_go_profile(
        "mode: set\n"
        "a/b/main.go:1.1,2.2 1 1\n"
        "b/main.go:1.1,2.2 1 0\n"
    )
    assert go_percent(profile, "b/main.go", 1, 2) == 0.0
    assert go_percent(profile, "a/b/main.go", 1, 2) == 100.0


def test_a_shorter_file_does_not_take_a_longer_files_coverage():
    bundle = CoverageBundle(
        go_profile=parse_go_profile("mode: set\na/b/main.go:1.1,2.2 1 1\n")
    )
    bundle.bind_sources(["b/main.go", "a/b/main.go"])
    short = function(path="b/main.go", start_line=1, end_line=2)
    long = function(path="a/b/main.go", start_line=1, end_line=2)
    assert bundle.percent_for(short) is None
    assert bundle.percent_for(long) == 100.0


def test_a_short_report_key_matches_one_longer_source():
    bundle = CoverageBundle(
        go_profile=parse_go_profile("mode: set\nboard.go:4.1,6.2 2 1\n")
    )
    bundle.bind_sources(["cmd/board.go"])
    assert bundle.percent_for(function(path="cmd/board.go")) == 100.0
    bundle.bind_sources(["cmd/board.go", "other/board.go"])
    assert bundle.percent_for(function(path="cmd/board.go")) is None
    assert bundle.percent_for(function(path="other/board.go")) is None


def test_suffix_lookup_finds_a_report_keyed_by_a_longer_path():
    bundle = CoverageBundle(lcov={"proj/src/demo/core.clj": {3: (1, 1), 4: (0, 1)}})
    fn = function(
        language="clojure",
        path="src/demo/core.clj",
        start_line=3,
        end_line=4,
        namespace="demo.core",
        name="choose",
    )
    assert bundle.percent_for(fn) == 50.0


def test_load_bundle_on_an_empty_tree_has_no_profiles(tmp_path):
    bundle = load_bundle(tmp_path)
    assert bundle.go_profile is None
    assert bundle.jacoco is None
    assert bundle.lcov == {}


def test_conftest_and_a_tests_directory_are_tests(tmp_path):
    assert is_test_file("conftest.py") is True
    assert is_test_file("src/app.test.js") is True
    assert is_test_file("src/app.spec.mjs") is True
    assert is_test_file(tmp_path / "tests" / "test_board.py") is True
    source = tmp_path / "src" / "app.py"
    source.parent.mkdir(parents=True)
    source.write_text("def run():\n    return 1\n", encoding="utf-8")
    hidden = tmp_path / "tests" / "hidden.py"
    hidden.parent.mkdir()
    hidden.write_text("def hidden():\n    return 1\n", encoding="utf-8")
    skipped = tmp_path / "target" / "gen.py"
    skipped.parent.mkdir()
    skipped.write_text("def gen():\n    return 1\n", encoding="utf-8")
    assert iter_source_files([tmp_path]) == [source.resolve()]
    assert iter_source_files([tmp_path / "missing"]) == []
    assert iter_source_files([source]) == [source.resolve()]


def test_missing_coverage_scores_zero_and_sorts_ahead_of_a_covered_function(tmp_path):
    low = tmp_path / "src" / "low.ts"
    high = tmp_path / "src" / "high.ts"
    low.parent.mkdir(parents=True)
    body = "export function {name}(x: number) {{\n  if (x) return 1;\n  return 0;\n}}\n"
    low.write_text(body.format(name="low"), encoding="utf-8")
    high.write_text(body.format(name="high"), encoding="utf-8")
    bundle = CoverageBundle(lcov={"src/low.ts": {1: (1, 1), 2: (1, 1), 3: (1, 1)}})
    entries = analyze_files([low, high], tmp_path, bundle)
    assert [(entry.name, entry.coverage, entry.crap) for entry in entries] == [
        ("high", 0.0, 6.0),
        ("low", 100.0, 2.0),
    ]
    unscored = analyze_files([high], tmp_path, None)
    assert unscored[0].coverage is None
    assert unscored[0].crap is None


def test_files_outside_the_project_keep_an_absolute_path(tmp_path):
    outside = tmp_path / "board.go"
    outside.write_text("package demo\nfunc Place() int { return 1 }\n", encoding="utf-8")
    entries = analyze_files([outside], tmp_path / "other", None)
    assert entries[0].name == "Place"
    assert entries[0].path == str(outside.resolve())
    assert analyze_files([tmp_path / "notes.md"], tmp_path, None) == []


def test_module_entry_point_calls_main(monkeypatch):
    called = {}
    monkeypatch.setattr("crapper.cli.main", lambda: called.setdefault("ran", True))
    runpy.run_module("crapper.__main__", run_name="__main__")
    assert called["ran"] is True


def test_end_line_of_a_trailing_newline_stays_on_the_last_source_line():
    _data, tree = parse("def choose():\n    return 1\n", "python")
    assert end_line(tree.root_node) == 2
    assert child_of_type(tree.root_node, "not_a_node") is None


def test_clojure_scanner_survives_end_of_file():
    assert extract_functions('(defn foo [] \\') == []
    assert extract_functions("(defn foo [] \\abc") == []
    assert [item["name"] for item in extract_functions("(defn foo [] ;; comment")] == []
    assert extract_functions('(defn foo [] "hello') == []
    found = _extract_top_level_defns('(defn foo []\n  ;; comment\n  "hello")')
    assert found[0]["name"] == "foo"
    assert found[0]["text"].endswith(")")


def test_go_edges(tmp_path):
    assert [(fn.namespace, fn.name) for fn in go_functions("func Orphan() int { return 1 }\n", "board.go")] == [
        ("", "Orphan")
    ]
    source = "package p\nfunc Declared(x int)\nfunc Real() int { return 1 }\nfunc () Run() {}\nfunc (w *Widget) () {}\n"
    assert [(fn.namespace, fn.name) for fn in go_functions(source, "board.go")] == [("p", "Real")]
    (tmp_path / "go.mod").write_text("module example.com/demo\n", encoding="utf-8")
    path = tmp_path / "board.go"
    path.write_text("package demo\nfunc Place() int { return 1 }\n", encoding="utf-8")
    rooted = go_functions(path.read_text(encoding="utf-8"), str(path), str(tmp_path))
    assert rooted[0].namespace == "example.com/demo"


def test_a_method_inside_a_lambda_is_not_an_entry():
    source = """
    class Sample {
        Runnable r = () -> {
            class Local {
                int score() { if (true) return 1; return 0; }
            }
            return null;
        };
        int outer() { return 1; }
    }
    """
    assert [method.name for method in java_functions(source, "Sample.java")] == ["outer"]


def test_typescript_namespaces_and_declarations_without_bodies():
    body = "export function choose(): number { return 1 }\n"
    assert ts_functions(body, "/app/src/demo/box.ts", "/app")[0].namespace == "demo.box"
    assert ts_functions(body, "./demo/box.ts", None)[0].namespace == "demo.box"
    assert ts_functions(body, "/app/pkg/src/demo/box.mts", None)[0].namespace == "demo.box"
    source = """
    function choose(): void;
    class Box { open(): void; }
    const plain = 1;
    const arrow = (n: number) => n;
    """
    functions = ts_functions(source, "src/demo/box.ts", "/proj")
    assert [(fn.namespace, fn.name) for fn in functions] == [("demo.box", "arrow")]
    object_method = "const obj = { open() { return 1 } }\n"
    assert ts_functions(object_method, "src/demo/box.ts", "/proj") == []
    computed = "class Box { [name]() { return 1 } }\n"
    assert ts_functions(computed, "src/demo/box.ts", "/proj") == []


def test_python_function_without_a_name_is_skipped():
    source = "def choose(x):\n    return x\n"
    assert [fn.name for fn in python_functions(source, "src/app.py", "/proj")] == ["choose"]


def test_a_deep_expression_does_not_recurse_off_the_stack():
    expr = " + ".join(["1"] * 1200)
    python = python_functions(f"def huge():\n    return {expr}\n", "huge.py", None)
    assert [fn.name for fn in python] == ["huge"]
    java = java_functions(
        f"class Huge {{\n  int huge() {{ return {expr}; }}\n}}\n",
        "Huge.java",
    )
    assert [fn.name for fn in java] == ["huge"]


def test_analyze_does_not_give_a_short_path_the_longer_files_coverage(tmp_path):
    body = "package demo\nfunc Place() int { return 1 }\n"
    short = tmp_path / "b" / "main.go"
    long = tmp_path / "a" / "b" / "main.go"
    short.parent.mkdir(parents=True)
    long.parent.mkdir(parents=True)
    short.write_text(body, encoding="utf-8")
    long.write_text(body, encoding="utf-8")
    bundle = CoverageBundle(
        go_profile=parse_go_profile("mode: set\na/b/main.go:1.1,2.2 1 1\n")
    )
    entries = analyze_files([short, long], tmp_path, bundle)
    assert [(entry.name, entry.path, entry.coverage) for entry in entries] == [
        ("Place", "b/main.go", 0.0),
        ("Place", "a/b/main.go", 100.0),
    ]


def test_rust_relative_path_and_a_trait_method(tmp_path):
    (tmp_path / "Cargo.toml").write_text(
        '[package]\nname = "demo"\nversion = "0.1.0"\n',
        encoding="utf-8",
    )
    bare = rust_functions("pub fn bare() {}\n", "notes.rs", None)
    assert bare[0].name == "bare"
    assert bare[0].namespace == "crate::notes"
    source = "trait Foo {\n    fn draw(&self) {}\n}\n"
    path = tmp_path / "src" / "lib.rs"
    functions = rust_functions(source, str(path), str(tmp_path))
    assert [(fn.namespace, fn.name) for fn in functions] == [("demo", "draw")]
