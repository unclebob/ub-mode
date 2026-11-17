from crapper.coverage import (
    CoverageBundle,
    go_percent,
    load_bundle,
    parse_form_coverage,
    parse_go_profile,
    parse_jacoco_index,
    parse_lcov,
)
from crapper.model import Function


def function(**kwargs):
    defaults = dict(
        name="choose",
        namespace="demo.core",
        complexity=2,
        start_line=3,
        end_line=5,
        path="src/demo/core.clj",
        language="clojure",
        jacoco_class=None,
    )
    defaults.update(kwargs)
    return Function(**defaults)


def test_cloverage_form_counts_and_lcov_lines():
    html = '<span class="pc" title="1 out of 2 forms covered">   4&nbsp;&nbsp;(if x 1)</span>'
    assert parse_form_coverage(html) == {4: (1, 2)}
    lcov = "SF:src/demo/core.clj\nDA:3,1\nDA:4,0\nDA:9,3\nend_of_record\n"
    parsed = parse_lcov(lcov)
    bundle = CoverageBundle(lcov=parsed, form_html={"src/demo/core.clj": {4: (1, 2)}})
    # Form HTML wins over LCOV for Clojure, matching crap4clj.
    assert bundle.percent_for(function(start_line=3, end_line=5)) == 50.0


def test_lcov_percentage_when_there_is_no_html():
    lcov = parse_lcov("SF:src/demo/core.clj\nDA:3,1\nDA:4,0\nend_of_record\n")
    bundle = CoverageBundle(lcov=lcov)
    assert bundle.percent_for(function(start_line=3, end_line=4)) == 50.0
    assert bundle.percent_for(function(path="src/missing.clj")) is None


def test_lcov_branch_records_override_line_hits():
    text = "SF:src/demo/app.ts\nDA:2,1\nDA:3,1\nBRDA:3,0,0,1\nBRDA:3,0,1,-\nend_of_record\n"
    parsed = parse_lcov(text)
    assert parsed["src/demo/app.ts"][2] == (1, 1)
    assert parsed["src/demo/app.ts"].branches[3] == (1, 2)
    bundle = CoverageBundle(lcov=parsed)
    scored = function(
        path="src/demo/app.ts",
        language="typescript",
        start_line=2,
        end_line=3,
        namespace="demo.app",
    )
    assert bundle.percent_for(scored) == 50.0


def test_a_branchless_span_keeps_line_coverage():
    text = "SF:src/demo/app.ts\nDA:2,1\nDA:3,0\nBRDA:8,0,0,1\nBRDA:8,0,1,0\nend_of_record\n"
    bundle = CoverageBundle(lcov=parse_lcov(text))
    scored = function(path="src/demo/app.ts", language="typescript", start_line=2, end_line=3)
    assert bundle.percent_for(scored) == 50.0


def test_go_profile_matches_crap4go_statement_ranges():
    profile = parse_go_profile(
        "mode: set\ngithub.com/acme/demo/board.go:4.1,6.2 2 1\ngithub.com/acme/demo/board.go:8.1,9.2 1 0\n"
    )
    assert go_percent(profile, "board.go", 4, 6) == 100.0
    assert go_percent(profile, "board.go", 8, 9) == 0.0
    assert go_percent(profile, "other.go", 1, 3) is None


def test_jacoco_instruction_coverage_joins_on_class_and_method():
    xml = """<?xml version="1.0"?>
    <report>
      <package>
        <class name="demo/pkg/Board">
          <method name="place" line="4">
            <counter type="INSTRUCTION" missed="1" covered="3"/>
          </method>
        </class>
        <class name="demo/pkg/Board$Inner">
          <method name="tick" line="9">
            <counter type="INSTRUCTION" missed="0" covered="2"/>
          </method>
        </class>
      </package>
    </report>
    """
    bundle = CoverageBundle(jacoco=parse_jacoco_index(xml))
    outer = function(
        name="place",
        namespace="demo.pkg.Board",
        language="java",
        jacoco_class="demo.pkg.Board",
        start_line=4,
        path="src/demo/pkg/Board.java",
    )
    inner = function(
        name="tick",
        namespace="demo.pkg.Board.Inner",
        language="java",
        jacoco_class="demo.pkg.Board$Inner",
        start_line=9,
        path="src/demo/pkg/Board.java",
    )
    assert bundle.percent_for(outer) == 75.0
    assert bundle.percent_for(inner) == 100.0


def test_jacoco_skips_methods_without_an_instruction_counter():
    xml = """<report>
      <class name="demo/pkg/Board">
        <method name="skip" line="2">
          <counter type="LINE" missed="1" covered="0"/>
        </method>
        <method name="bad" line="nope">
          <counter type="INSTRUCTION" missed="1" covered="1"/>
        </method>
        <counter type="INSTRUCTION" missed="0" covered="1"/>
      </class>
    </report>"""
    found = parse_jacoco_index(xml)
    assert list(found) == ["demo.pkg.Board#bad"]
    assert found["demo.pkg.Board#bad"][0].line == 0


def test_missing_go_profile_is_na():
    assert go_percent(None, "board.go", 1, 3) is None


def test_load_bundle_merges_each_report(tmp_path):
    lcov = tmp_path / "target" / "coverage" / "lcov.info"
    lcov.parent.mkdir(parents=True)
    lcov.write_text("SF:src/demo/core.clj\nDA:3,1\nDA:4,0\nend_of_record\n", encoding="utf-8")
    go = tmp_path / "target" / "coverage" / "go" / "coverage.out"
    go.parent.mkdir(parents=True)
    go.write_text("mode: set\nboard.go:1.1,2.2 1 1\n", encoding="utf-8")
    jacoco = tmp_path / "target" / "site" / "jacoco" / "jacoco.xml"
    jacoco.parent.mkdir(parents=True)
    jacoco.write_text(
        '<report><class name="demo/Board"><method name="place" line="4">'
        '<counter type="INSTRUCTION" missed="0" covered="1"/></method></class></report>',
        encoding="utf-8",
    )
    html = tmp_path / "target" / "coverage" / "src" / "demo" / "core.clj.html"
    html.parent.mkdir(parents=True)
    html.write_text(
        '<span title="1 out of 2 forms covered">   4&nbsp;&nbsp;(if x 1)</span>',
        encoding="utf-8",
    )
    (tmp_path / "target" / "coverage" / "index.html").write_text("<html></html>", encoding="utf-8")
    bundle = load_bundle(tmp_path)
    assert bundle.percent_for(function(start_line=3, end_line=4)) == 50.0
    assert bundle.go_profile is not None
    assert bundle.jacoco is not None


def _go_module(module_dir, module_line, profile_text):
    module_dir.mkdir(parents=True, exist_ok=True)
    (module_dir / "go.mod").write_text(f"{module_line}\n\ngo 1.21\n", encoding="utf-8")
    profile = module_dir / "target" / "coverage" / "go" / "coverage.out"
    profile.parent.mkdir(parents=True)
    profile.write_text(profile_text, encoding="utf-8")


def test_go_coverage_names_a_nested_file_by_its_module_import_path(tmp_path):
    # `go test` keys a profile by import path. The module is `example.com/api`
    # but its directory is `svc`, so no suffix of `svc/calc/calc.go` names it.
    _go_module(
        tmp_path / "svc",
        "module example.com/api // the service",
        "mode: set\n"
        "example.com/api/calc/calc.go:4.2,4.11 1 1\n"
        "example.com/api/calc/calc.go:5.3,6.1 1 0\n",
    )
    bundle = load_bundle(tmp_path)
    scored = function(language="go", path="svc/calc/calc.go", start_line=3, end_line=7)
    assert bundle.percent_for(scored) == 50.0


def test_go_coverage_reads_a_quoted_module_path(tmp_path):
    _go_module(
        tmp_path / "cli",
        'module "github.com/acme/tool"',
        "mode: set\n"
        "github.com/acme/tool/main.go:1.1,2.2 1 1\n"
        "github.com/acme/tool/cmd/main.go:1.1,2.2 1 0\n",
    )
    bundle = load_bundle(tmp_path)
    assert bundle.percent_for(function(language="go", path="cli/main.go", start_line=1, end_line=2)) == 100.0
    assert bundle.percent_for(function(language="go", path="cli/cmd/main.go", start_line=1, end_line=2)) == 0.0


def test_go_coverage_falls_back_to_the_path_without_a_module_name(tmp_path):
    profile = tmp_path / "target" / "coverage" / "go" / "coverage.out"
    profile.parent.mkdir(parents=True)
    profile.write_text("mode: set\nproj/board.go:1.1,2.2 1 1\n", encoding="utf-8")
    scored = function(language="go", path="board.go", start_line=1, end_line=2)
    assert load_bundle(tmp_path).percent_for(scored) == 100.0
    (tmp_path / "go.mod").write_text("go 1.21\n", encoding="utf-8")
    assert load_bundle(tmp_path).percent_for(scored) == 100.0
