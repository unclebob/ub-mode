from pathlib import Path

from dryer.clojure_read import Coll, Sym, is_candidate_form, normalize, read_source
from dryer.model import Duplicate, Span
from dryer.report import format_text, render_edn
from dryer.scan import find_duplicates, scan_files
from dryer.shape import K, node_count, pr


def write_source(root: Path, name: str, text: str) -> None:
    path = root / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def duplicates(root: Path, **options):
    files = sorted(path for path in root.rglob("*") if path.is_file())
    entries, warnings = scan_files(
        files,
        root,
        options.get("min_lines", 4),
        options.get("min_nodes", 20),
    )
    assert warnings == []
    return find_duplicates(entries, options.get("threshold", 0.82))


def test_edn_report_matches_the_text_fields():
    candidate = Duplicate(
        0.875,
        "clojure",
        Span("a.clj", 10, 14),
        Span("b.clj", 20, 24),
        30,
        31,
    )
    assert render_edn([candidate]) == (
        "{:candidates [\n"
        " {:score 0.875\n"
        '  :language "clojure"\n'
        '  :left {:file "a.clj", :start-line 10, :end-line 14}\n'
        '  :right {:file "b.clj", :start-line 20, :end-line 24}\n'
        "  :left-nodes 30\n"
        "  :right-nodes 31}\n"
        "]}\n"
    )


def test_text_report_matches_dry4clj():
    candidate = Duplicate(
        0.875,
        "clojure",
        Span("a.clj", 10, 14),
        Span("b.clj", 20, 24),
        30,
        31,
    )
    assert (
        format_text([candidate])
        == "DUPLICATE score=0.88\n  a.clj:10-14\n  b.clj:20-24\n"
    )
    assert format_text([]) == "No duplicate candidates found.\n"


def test_reports_structural_duplicates_with_line_ranges(tmp_path):
    write_source(
        tmp_path,
        "left.cljc",
        "(ns sample.left)\n\n(defn alpha [xs]\n  (let [ys (filter odd? xs)]\n    (map inc ys)))\n",
    )
    write_source(
        tmp_path,
        "right.cljc",
        "(ns sample.right)\n\n(defn beta [items]\n  (let [kept (filter even? items)]\n    (map dec kept)))\n",
    )
    found = duplicates(tmp_path, threshold=0.80, min_lines=3, min_nodes=8)
    assert len(found) == 1
    assert found[0].score == 1.0
    assert found[0].language == "clojure"
    assert found[0].left.file == "left.cljc"
    assert (found[0].left.start_line, found[0].left.end_line) == (3, 5)
    assert found[0].right.file == "right.cljc"
    assert (found[0].right.start_line, found[0].right.end_line) == (3, 5)


def test_matches_maps_sets_and_keyword_calls(tmp_path):
    write_source(
        tmp_path,
        "left.cljc",
        "(ns sample.left)\n\n(defn gamma [m]\n  (when (#{:a :b} (:kind m))\n    {:left (:a m) :right (:b m)}))\n",
    )
    write_source(
        tmp_path,
        "right.cljc",
        "(ns sample.right)\n\n(defn delta [row]\n  (when (#{:c :d} (:kind row))\n    {:left (:c row) :right (:d row)}))\n",
    )
    found = duplicates(tmp_path, threshold=0.80, min_lines=3, min_nodes=8)
    assert len(found) == 1
    assert found[0].score == 1.0


def test_reads_cljc_reader_conditionals(tmp_path):
    write_source(
        tmp_path,
        "left.cljc",
        "(ns sample.left)\n\n(defn alpha [x]\n  #?(:clj (when (pos? x)\n            (inc x))))\n",
    )
    write_source(
        tmp_path,
        "right.cljc",
        "(ns sample.right)\n\n(defn beta [y]\n  #?(:clj (when (pos? y)\n            (inc y))))\n",
    )
    found = duplicates(tmp_path, threshold=0.50, min_lines=1, min_nodes=1)
    assert len(found) == 1
    assert found[0].score == 1.0


def test_filters_forms_shorter_than_the_minimum_line_count(tmp_path):
    write_source(tmp_path, "one.clj", "(ns one)\n(defn a [x] (+ x 1))\n")
    write_source(tmp_path, "two.clj", "(ns two)\n(defn b [y] (+ y 2))\n")
    assert duplicates(tmp_path, threshold=0.80, min_lines=3, min_nodes=1) == []


def test_metadata_comments_and_discards_are_not_structure(tmp_path):
    write_source(
        tmp_path,
        "left.clj",
        "(ns left)\n\n(defn alpha [xs] ; keep\n  #_(println xs)\n  (let [ys (filter odd? xs)]\n    (map inc ys)))\n",
    )
    write_source(
        tmp_path,
        "right.clj",
        "(ns right)\n\n(defn ^String beta [items]\n  (let [kept (filter even? items)]\n    (map dec kept)))\n",
    )
    found = duplicates(tmp_path, threshold=0.80, min_lines=3, min_nodes=8)
    assert len(found) == 1
    assert found[0].score == 1.0


def test_extra_binding_scores_below_one(tmp_path):
    write_source(
        tmp_path,
        "invoice.clj",
        """(ns billing.invoice)

(defn invoice-summary [orders]
  (let [paid (filter paid? orders), domestic (filter domestic? paid)
        sorted (sort-by :date domestic), amounts (map :amount sorted)
        taxes (map tax amounts), ids (map :id sorted)
        customers (map :customer sorted), regions (group-by :region sorted)
        flagged (filter flagged? sorted)]
    {:count (count sorted)
     :first-id (first ids), :last-id (last ids)
     :customers (set customers), :regions (keys regions)
     :flagged (count flagged), :total (reduce + 0 amounts)
     :tax (reduce + 0 taxes)}))
""",
    )
    write_source(
        tmp_path,
        "receipt.clj",
        """(ns billing.receipt)

(defn receipt-summary [rows]
  (let [closed (filter closed? rows), local (filter local? closed)
        ordered (sort-by :date local), amounts (map :amount ordered)
        taxable (filter taxable? ordered), taxes (map tax amounts)
        ids (map :id ordered), customers (map :customer ordered)
        regions (group-by :region ordered)
        flagged (filter flagged? ordered)]
    {:count (count ordered)
     :first-id (first ids), :last-id (last ids)
     :customers (set customers), :regions (keys regions)
     :flagged (count flagged), :total (reduce + 0 amounts)
     :tax (reduce + 0 taxes)}))
""",
    )
    found = duplicates(tmp_path)
    assert len(found) == 1
    assert 0.85 <= found[0].score < 1.0


def test_three_copies_make_three_pairs(tmp_path):
    body = "(let [ys (filter odd? xs)]\n    (map inc ys))"
    write_source(
        tmp_path,
        "same.clj",
        f"(ns same)\n\n(defn a [xs]\n  {body})\n\n(defn b [xs]\n  {body})\n\n(defn c [xs]\n  {body})\n",
    )
    found = duplicates(tmp_path, threshold=0.80, min_lines=3, min_nodes=8)
    assert len(found) == 3
    assert {item.score for item in found} == {1.0}


def test_unreadable_tail_does_not_drop_an_earlier_form(tmp_path):
    write_source(
        tmp_path,
        "left.clj",
        "(ns left)\n\n(defn alpha [xs]\n  (let [ys (filter odd? xs)]\n    (map inc ys)))\n(defn broken [x]\n",
    )
    write_source(
        tmp_path,
        "right.clj",
        "(ns right)\n\n(defn beta [items]\n  (let [kept (filter even? items)]\n    (map dec kept)))\n",
    )
    files = sorted(tmp_path.glob("*.clj"))
    entries, warnings = scan_files(files, tmp_path, 3, 8)
    assert warnings == ["left.clj:6: unterminated list"]
    found = find_duplicates(entries, 0.80)
    assert len(found) == 1


def test_reads_escaped_strings_and_characters():
    forms, warning = read_source('(defn alpha [x]\n  (str "a\\\\nb" \\newline \\a))\n')
    assert warning is None
    assert [form.items[0].name for form in forms] == ["defn"]


def test_reports_a_broken_string_or_character():
    _, warning = read_source('(defn alpha [x]\n  "abc)\n')
    assert warning == "2: unterminated string"
    _, warning = read_source('(defn alpha [x] "abc\\')
    assert warning == "1: unterminated string"
    _, warning = read_source("(defn alpha [x] \\")
    assert warning == "1: incomplete character"


def test_bom_and_shebang_are_not_forms():
    forms, warning = read_source("\ufeff#!/usr/bin/env bb\n(defn alpha [x]\n  (inc x))\n")
    assert warning is None
    assert [form.items[0].name for form in forms] == ["defn"]


def test_reader_conditional_keeps_clj_and_splices_a_collection():
    forms, warning = read_source("#?(:cljs (inc x) :default (dec x))\n")
    assert warning is None
    assert forms[0].items[0].name == "dec"

    forms, warning = read_source("#?((inc x) (dec x))\n")
    assert warning is None
    assert forms == []

    forms, warning = read_source("#?[:clj (inc x)]\n")
    assert warning == "1: reader conditional body must be a list"

    forms, warning = read_source("(defn alpha []\n  [#?@(:clj [:a :b])])\n")
    assert warning is None
    assert [item.name for item in forms[0].items[3].items] == ["a", "b"]

    forms, warning = read_source("(defn alpha [x]\n  (inc x))\n#?@(:clj {:a 1})\n")
    assert warning == "3: splicing reader conditional needs a collection"
    assert [form.items[0].name for form in forms] == ["defn"]


def test_empty_list_normalizes_to_a_literal_list():
    forms, warning = read_source("(defn alpha [x]\n  ())\n")
    assert warning is None
    assert "[:list :literal]" in pr(normalize(forms[0]))


def test_reader_macros_wrap_the_following_form():
    forms, warning = read_source("'x\n@x\n`x\n~x\n~@x\n")
    assert warning is None
    assert [form.items[0].name for form in forms] == [
        "quote",
        "deref",
        "syntax-quote",
        "unquote",
        "unquote-splicing",
    ]
    assert forms[0].items[1].name == "x"
    assert forms[4].items[1].name == "x"
    forms, warning = read_source("'#_x\n")
    assert warning is None
    assert [item.name for item in forms[0].items] == ["quote"]


def test_a_character_name_is_consumed():
    forms, warning = read_source("(defn alpha []\n  (str \\newline))\n")
    assert warning is None
    assert [type(item).__name__ for item in forms[0].items[3].items] == ["Sym", "Lit"]


def test_a_bare_colon_is_an_empty_keyword():
    forms, warning = read_source(":\n")
    assert warning is None
    assert forms[0].name == ""
    forms, warning = read_source("#!only")
    assert warning is None
    assert forms == []


def test_namespaced_maps_keep_the_map():
    for source in ("#:foo{:a 1}\n", "#::{:a 1}\n", "#:{:a 1}\n"):
        forms, warning = read_source(source)
        assert warning is None, source
        assert forms[0].kind == "map"
        assert forms[0].pairs[0][0].name == "a"


def test_map_pairs_keep_source_order():
    forms, warning = read_source("{:a 1 :b 2}\n")
    assert warning is None
    assert [pair[0].name for pair in forms[0].pairs] == ["a", "b"]
    left, warning = read_source("{foo 1}\n")
    right, _ = read_source("{bar 2}\n")
    assert warning is None
    assert "foo" not in pr(normalize(left[0]))
    assert pr(normalize(left[0])) == pr(normalize(right[0]))
    paired, warning = read_source("{:a 1\n :b 2}\n")
    assert warning is None
    assert [value.line for _key, value in paired[0].pairs] == [1, 2]
    symbols, warning = read_source("{foo bar}\n")
    assert warning is None
    printed = pr(normalize(symbols[0]))
    assert "foo" not in printed
    assert "bar" not in printed


def test_a_trailing_feature_without_an_expression_is_ignored():
    forms, warning = read_source("#?(:clj (inc x) :extra)\n")
    assert warning is None
    assert forms[0].items[0].name == "inc"
    forms, warning = read_source("#?(:cljs (dec x) :extra)\n")
    assert warning is None
    assert forms == []


def test_names_inside_a_set_are_erased():
    forms, warning = read_source("#{foo bar}\n")
    assert warning is None
    printed = pr(normalize(forms[0]))
    assert printed.startswith("[:set ")
    assert "foo" not in printed


def test_only_a_non_empty_list_that_is_not_ns_is_a_candidate():
    assert is_candidate_form(Coll("vector", 1, items=[Sym("a", 1)])) is False
    assert is_candidate_form(Coll("list", 1, items=[])) is False
    assert is_candidate_form(Sym("a", 1)) is False
    assert is_candidate_form(Coll("list", 1, items=[Sym("ns", 1), Sym("demo", 1)])) is False
    assert is_candidate_form(Coll("list", 1, items=[Sym("defn", 1)])) is True
    assert pr(normalize(Coll("bag", 1))) == "[:literal]"


def test_node_count_and_keyword_printing():
    assert repr(K("symbol")) == ":symbol"
    assert node_count(K("symbol")) == 1
    assert node_count([K("list"), K("symbol"), K("literal")]) == 4
