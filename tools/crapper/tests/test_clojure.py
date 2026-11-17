from crapper.languages.clojure import (
    cyclomatic_complexity,
    extract_functions,
    functions_in_source,
    namespace_from_path,
)


def test_base_and_branch_forms():
    assert cyclomatic_complexity("(defn foo [])") == 1
    assert cyclomatic_complexity("(defn foo [x]\n  (+ x 1))") == 1
    assert cyclomatic_complexity("(defn foo [x]\n  (if x 1 0))") == 2
    assert cyclomatic_complexity("(defn foo [x]\n  (if-not x 1 0))") == 2
    assert cyclomatic_complexity("(defn foo [x]\n  (if-let [y x] y 0))") == 2
    assert cyclomatic_complexity("(defn foo [x]\n  (when x 1))") == 2
    assert cyclomatic_complexity("(defn foo [x]\n  (when-first [y x] y))") == 2
    assert cyclomatic_complexity("(defn foo [x y]\n  (and x y))") == 2
    assert cyclomatic_complexity("(defn foo [x y]\n  (or x y))") == 2
    assert cyclomatic_complexity("(defn foo [x]\n  (loop [i 0] (recur (inc i))))") == 2


def test_cond_case_and_threading():
    assert (
        cyclomatic_complexity("(defn foo [x]\n  (cond\n    (= x 1) :one\n    :else :other))")
        == 3
    )
    assert cyclomatic_complexity("(defn foo [x]\n  (condp = x\n    1 :one\n    2 :two))") == 3
    assert cyclomatic_complexity("(defn foo [x]\n  (case x\n    1 :one\n    :other))") == 3
    assert (
        cyclomatic_complexity(
            "(defn foo [x]\n  (cond-> x\n    (pos? x) inc\n    (even? x) (* 2)))"
        )
        == 3
    )
    assert cyclomatic_complexity("(defn foo [x]\n  (some-> x inc dec))") == 3
    assert (
        cyclomatic_complexity("(defn foo [x]\n  (some->> x (map inc) (filter pos?)))") == 3
    )


def test_ignores_decisions_in_strings_and_comments():
    assert cyclomatic_complexity('(defn foo [x]\n  "if when cond and or"\n  x)') == 1
    assert cyclomatic_complexity('(defn foo [x]\n  "(if true 1 0)"\n  x)') == 1
    assert cyclomatic_complexity('(defn foo [x]\n  ;; if when cond\n  x)') == 1
    assert cyclomatic_complexity('(defn foo [x]\n  (if x :yes :no) ; and or when cond)') == 2


def test_map_literals_inside_cond_are_not_extra_clauses():
    source = """(defn foo [cell]
  (let [contents (:contents cell)]
    (cond
      (pred-a? contents)
      {:type :army :mode :awake :owner (:owner contents) :aboard true}

      (pred-b? contents)
      {:type :fighter :mode :awake :owner (:owner contents) :fuel 20 :from-carrier true}

      (pred-c? contents) contents

      (pred-d? cell)
      {:type :fighter :mode :awake :owner :player :fuel 20 :from-airport true}

      :else nil)))"""
    assert cyclomatic_complexity(source) == 6


def test_extracts_defn_names_lines_and_namespace():
    source = "(ns demo.core)\n\n(defn choose [x]\n  (if x 1 0))\n\n(defn- hidden [y]\n  y)\n"
    found = functions_in_source(source, "src/demo/core.clj", "/proj")
    assert [(item.name, item.complexity, item.start_line) for item in found] == [
        ("choose", 2, 3),
        ("hidden", 1, 6),
    ]
    assert {item.namespace for item in found} == {"demo.core"}


def test_top_level_def_does_not_attach_to_the_previous_function():
    source = (
        "(defn alpha [x]\n"
        "  (if x 1 0))\n\n"
        "(def rules\n"
        "  [{:pred (fn [v]\n"
        "            (if v (when (pos? v) true) false))}])\n\n"
        "(defn omega [y]\n"
        "  y)"
    )
    found = {item["name"]: item["complexity"] for item in extract_functions(source)}
    assert found == {"alpha": 2, "omega": 1}


def test_namespace_falls_back_to_the_path():
    assert namespace_from_path("src/demo/core_extra.clj", None) == "demo.core-extra"
    assert namespace_from_path("/proj/src/demo/core.cljc", "/proj") == "demo.core"
    assert namespace_from_path("pkg/src/demo/core.bb", None) == "demo.core"
    found = functions_in_source("(defn foo [] 1)\n", "src/demo/core_extra.clj", "/proj")
    assert found[0].namespace == "demo.core-extra"


def test_strings_comments_and_character_literals_stay_inside_one_form():
    source = '(defn foo [x]\n  "say \\"hi\\"\n  (if x 1)"\n  \\space\n  ;; (defn hidden [])\n  x)\n'
    assert [item["name"] for item in extract_functions(source)] == ["foo"]


def test_discards_and_metadata_cover_each_reader_form():
    source = r"""
#_
; gone
(defn ghost [] (if x 1 0))
#_ "string"
#_ \x
#_ symbol
#_ #_(defn inner [] (if x 1 0))
#_(defn boxed []
  "str"
  \;
  ; comment
  (if x 1 0))
(defn ^{:doc "hi" :private true} kept [] 1)
(defn ^{:flag \; :private true} also [] 1)
(defn ^{:private true ; note
  } third [] 1)
( defn spaced [] 1)
(defn)
(defn ^)
"""
    assert [item["name"] for item in extract_functions(source)] == [
        "kept",
        "also",
        "third",
        "spaced",
    ]
    assert extract_functions("(defn ^{:private kept [] 1)") == []


def test_character_literals_do_not_swallow_the_next_defn():
    source = '(def x #{\\"})\n(defn foo [] 1)'
    assert [item["name"] for item in extract_functions(source)] == ["foo"]


def test_reader_keeps_the_real_namespace_name_and_decisions():
    source = r""";; (ns demo.wrong)
(ns demo.core)
#_(defn dropped [x]
  (if x 1 0))
(defn ^:private kept [x]
  #_(if x 1 0)
  1)
(defn ^{:private true} also [] 1)
(defn quoted []
  \"
  (if x 1 0))
(defn semi []
  \;
  (if true 1 2))
"""
    found = functions_in_source(source, "src/demo/core.clj", "/proj")
    assert [(item.name, item.complexity) for item in found] == [
        ("kept", 1),
        ("also", 1),
        ("quoted", 2),
        ("semi", 2),
    ]
    assert {item.namespace for item in found} == {"demo.core"}
    assert cyclomatic_complexity(r'(defn semi [] \; (if true 1 2))') == 2
    assert cyclomatic_complexity(r'(defn quoted [] \" (if x 1 0))') == 2
    assert extract_functions("#_(defn dropped [] (if x 1 0))\n(defn kept [] 1)\n") == [
        {"name": "kept", "start_line": 2, "end_line": 2, "complexity": 1}
    ]
