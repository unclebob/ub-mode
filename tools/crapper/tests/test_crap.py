from crapper.crap import crap_score, sort_entries
from crapper.metrics import render_edn
from crapper.model import Entry
from crapper.report import format_report


def entry(**kwargs):
    defaults = dict(
        name="score",
        namespace="demo.core",
        complexity=1,
        coverage=100.0,
        crap=1.0,
        path="src/demo/core.clj",
        language="clojure",
    )
    defaults.update(kwargs)
    return Entry(**defaults)


def test_formula_matches_crap4clj():
    assert crap_score(5, 100.0) == 5.0
    assert crap_score(5, 0.0) == 30.0
    assert crap_score(1, 100.0) == 1.0
    assert abs(crap_score(8, 45.0) - 18.648) < 0.01
    assert crap_score(3, None) is None


def test_unscored_rows_sort_after_scored_rows():
    rows = sort_entries(
        [
            entry(name="missing", crap=None, coverage=None),
            entry(name="mid", crap=5.0),
            entry(name="worst", crap=30.0),
        ]
    )
    assert [row.name for row in rows] == ["worst", "mid", "missing"]


def test_a_low_score_still_sorts_ahead_of_an_unscored_row():
    rows = sort_entries(
        [
            entry(name="plain", crap=1.0, complexity=1),
            entry(name="large", crap=None, coverage=None, complexity=8),
        ]
    )
    assert [row.name for row in rows] == ["plain", "large"]


def test_unscored_rows_sort_by_complexity():
    rows = sort_entries(
        [
            entry(name="small", crap=None, coverage=None, complexity=1),
            entry(name="large", crap=None, coverage=None, complexity=8),
        ]
    )
    assert [row.name for row in rows] == ["large", "small"]


def test_report_shows_na_for_missing_coverage():
    text = format_report([entry(coverage=None, crap=None, complexity=3)])
    assert "CRAP Report" in text
    assert "N/A" in text
    assert "demo.core" in text


def test_edn_uses_crap4clj_keys():
    text = render_edn(
        [entry(name="layout", namespace="uml-viewer.layout", complexity=8, coverage=82.0, crap=3.4)]
    )
    assert text.startswith("{:entries [")
    assert ':name "layout"' in text
    assert ':namespace "uml-viewer.layout"' in text
    assert ":complexity 8" in text
    assert ":coverage 82.0" in text
    assert ":crap 3.4" in text
    assert text.endswith("}\n")


def test_edn_of_no_entries_is_an_empty_vector():
    assert render_edn([]) == "{:entries []}\n"


def test_metrics_directory_can_be_written_twice(tmp_path):
    from crapper.metrics import write_metrics

    entry_row = entry()
    first = write_metrics([entry_row], tmp_path)
    second = write_metrics([entry_row], tmp_path)
    assert first == second
    assert first.is_file()


def test_edn_writes_nil_for_missing_coverage():
    text = render_edn([entry(coverage=None, crap=None)])
    assert ":coverage nil" in text
    assert ":crap nil" in text
