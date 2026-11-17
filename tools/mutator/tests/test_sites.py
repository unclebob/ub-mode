from pathlib import Path

from mutator.functions import form_id, is_private, project_functions, sites_in_file
from mutator.sites import discover_raw


def _sites(tmp_path: Path, relative: str, source: str):
    path = tmp_path / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(source, encoding="utf-8")
    return sites_in_file(source, path, tmp_path, relative)


def test_clojure_rules_follow_clj_mutate_and_mark_private_defns(tmp_path):
    source = """(ns demo.core)
(defn place [x]
  (if (> x 0)
    (+ x 1)
    0))
(defn- hide []
  false)
"""
    sites = _sites(tmp_path, "src/demo/core.clj", source)
    described = {(site.form_id, site.original, site.mutant) for site in sites}
    assert ("defn/place", "if", "if-not") in described
    assert ("defn/place", ">", ">=") in described
    assert ("defn/place", "+", "-") in described
    assert ("defn/place", "0", "1") in described
    assert ("defn/place", "1", "0") in described
    assert ("defn-/hide", "false", "true") in described
    assert {site.namespace for site in sites} == {"demo.core"}
    functions = project_functions(source, tmp_path / "src/demo/core.clj", tmp_path)
    assert form_id("hide", True) == "defn-/hide"
    assert is_private("clojure", "hide", source, 6, 7)
    assert {fn.namespace for fn in functions} == {"demo.core"}


def test_clojure_ignores_strings_comments_and_quotes(tmp_path):
    source = """(ns demo.core)
(defn place []
  (comment (+ 1 0))
  (quote (+ 1 0))
  (str "true + 1"))
"""
    sites = _sites(tmp_path, "src/demo/core.clj", source)
    assert sites == []


def test_java_rules_follow_mutate4java(tmp_path):
    source = """package demo;
class Board {
  int place(int x) {
    if (x > 0 && ready)
      return x + 1;
    return !ok ? -x : 0;
  }
  private int hide() { return 1; }
}
"""
    sites = _sites(tmp_path, "src/demo/Board.java", source)
    public = [site for site in sites if site.form_id == "defn/place"]
    private = [site for site in sites if site.form_id == "defn-/hide"]
    assert ("+", "-") in {(site.original, site.mutant) for site in public}
    assert ("/", "*") not in {(site.original, site.mutant) for site in public}
    assert ("*", "/") not in {(site.original, site.mutant) for site in public}
    assert (">", ">=") in {(site.original, site.mutant) for site in public}
    assert ("&&", "||") in {(site.original, site.mutant) for site in public}
    assert ("!", "") in {(site.original, site.mutant) for site in public}
    assert ("-", "") in {(site.original, site.mutant) for site in public}
    assert ("0", "1") in {(site.original, site.mutant) for site in public}
    assert private == [] or {site.original for site in private} == {"1"}
    assert {site.namespace for site in sites} == {"demo.Board"}


def test_java_swaps_division_both_ways(tmp_path):
    source = """package demo;
class Math {
  int place(int a, int b) { return a * b + a / b; }
}
"""
    sites = _sites(tmp_path, "src/demo/Math.java", source)
    pairs = {(site.original, site.mutant) for site in sites}
    assert ("*", "/") in pairs
    assert ("/", "*") in pairs
    assert ("+", "-") in pairs


def test_go_matches_mutate4go_and_splits_receivers(tmp_path):
    source = """package demo
func Run(a int, b int) int {
    if a > 0 && b == 1 {
        return a * b
    }
    return a / b
}
type Widget struct{}
func (w Widget) Run() bool { return true }
"""
    sites = _sites(tmp_path, "demo.go", source)
    free = [site for site in sites if site.namespace == "demo"]
    method = [site for site in sites if site.namespace == "demo.Widget"]
    pairs = {(site.original, site.mutant) for site in free}
    assert ("*", "/") in pairs
    assert ("/", "*") not in pairs
    assert ("&&", "||") in pairs
    assert ("==", "!=") in pairs
    assert (">", ">=") in pairs
    assert ("0", "1") in pairs
    assert ("1", "0") in pairs
    assert {site.form_id for site in method} == {"defn/Run"}
    assert ("true", "false") in {(site.original, site.mutant) for site in method}
    assert {site.form_id for site in free} == {"defn/Run"}


def test_go_unexported_function_is_private(tmp_path):
    source = """package demo
func hide() bool { return false }
"""
    sites = _sites(tmp_path, "demo.go", source)
    assert sites[0].form_id == "defn-/hide"


def test_python_typescript_and_rust_use_the_java_decisions(tmp_path):
    python = _sites(
        tmp_path,
        "src/demo/app.py",
        "def place(x):\n    if x > 0 and ready:\n        return x + 1\n    if not ok:\n        return 0\n",
    )
    typescript = _sites(
        tmp_path,
        "src/demo/app.ts",
        "export function place(x: number) {\n  if (x > 0 && ready) return x + 1;\n  return true === false;\n}\n",
    )
    rust = _sites(
        tmp_path,
        "src/lib.rs",
        "fn place(x: i32) -> i32 {\n    if x > 0 && ready { return x + 1; }\n    if !ok { return -x; }\n    0\n}\n",
    )
    assert ("and", "or") in {(site.original, site.mutant) for site in python}
    assert ("not", "") in {(site.original, site.mutant) for site in python}
    assert ("True", "False") not in {(site.original, site.mutant) for site in python}
    assert {site.namespace for site in python} == {"demo.app"}
    assert ("===", "!==") in {(site.original, site.mutant) for site in typescript}
    assert ("&&", "||") in {(site.original, site.mutant) for site in typescript}
    assert {site.namespace for site in typescript} == {"demo.app"}
    assert ("&&", "||") in {(site.original, site.mutant) for site in rust}
    assert ("!", "") in {(site.original, site.mutant) for site in rust}
    assert ("-", "") in {(site.original, site.mutant) for site in rust}


def test_python_private_and_class_namespace(tmp_path):
    source = """class Board:
    def place(self, x):
        return x > 0
    def _hide(self):
        return False
"""
    sites = _sites(tmp_path, "src/demo/board.py", source)
    assert {site.form_id for site in sites if site.name == "place"} == {"defn/place"}
    assert {site.form_id for site in sites if site.name == "_hide"} == {"defn-/_hide"}
    assert {site.namespace for site in sites} == {"demo.board.Board"}


def test_string_operators_are_not_sites(tmp_path):
    source = 'def place():\n    return "x > 0 and true"\n'
    assert _sites(tmp_path, "src/demo/app.py", source) == []


def test_typescript_mutates_nullish_and_optional_chaining(tmp_path):
    source = """
export function place(a, b) {
  return a ?? b?.c ?? b?.[0] ?? b?.();
}
"""
    sites = _sites(tmp_path, "src/demo/app.ts", source)
    assert sum(site.original == "??" and site.mutant == "||" for site in sites) == 3
    assert {site.mutant for site in sites if site.original == "?."} == {".", ""}
    assert {site.namespace for site in sites} == {"demo.app"}


def test_javascript_express_handler_owns_its_nullish_site(tmp_path):
    source = """
export function mount(app) {
  app.get("/users", (req, res) => {
    return req.body ?? false;
  });
}
"""
    sites = _sites(tmp_path, "src/demo/routes.js", source)
    assert {site.namespace for site in sites} == {"demo.routes"}
    assert ("GET /users", "??", "||") in {(site.name, site.original, site.mutant) for site in sites}
    assert all(site.name != "mount" for site in sites)


def test_a_quoted_nullish_operator_is_not_a_site(tmp_path):
    source = 'export function place() {\n  return "a ?? b?.c";\n}\n'
    assert _sites(tmp_path, "src/demo/app.ts", source) == []


def test_a_deep_expression_is_walked(tmp_path):
    expr = " + ".join(["1"] * 1200)
    python = f"def place():\n    return {expr}\n"
    assert discover_raw(python, "python", "app.py")
    go = f"package p\nfunc place() int {{ return {expr} }}\n"
    assert discover_raw(go, "go", "app.go")


def test_typescript_types_are_not_mutation_sites(tmp_path):
    source = """type Flag = { on: true };
interface Box { open: false }
export function check(n: number): { ok: true } | { ok: false; used?: true } {
  const seen: Promise<{ done: false }> | null = null;
  return { ok: n > 0 };
}
"""
    sites = _sites(tmp_path, "src/demo/check.ts", source)
    described = {(site.line, site.original, site.mutant) for site in sites}
    assert described == {(5, ">", ">="), (5, "0", "1")}


def test_a_site_remembers_where_its_statement_starts(tmp_path):
    source = """export function save(db: Db, now: number) {
  db
    .prepare("x")
    .bind(now + 1)
    .run();
  return {
    ok: true,
  };
}
"""
    sites = _sites(tmp_path, "src/demo/save.ts", source)
    anchors = {(site.line, site.original, site.anchor_line) for site in sites}
    assert (4, "+", 2) in anchors
    assert (4, "1", 2) in anchors
    assert (7, "true", 6) in anchors
