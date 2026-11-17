from pathlib import Path

from dryer.extract import entries_in_source
from dryer.model import Entry
from dryer.scan import _keep, find_duplicates, scan_files
from dryer.treesitter import descendants, end_line, parse


def write_source(root: Path, name: str, text: str) -> None:
    path = root / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def pairs(root: Path):
    files = sorted(path for path in root.rglob("*") if path.is_file())
    entries, warnings = scan_files(files, root, min_lines=1, min_nodes=1)
    assert warnings == []
    return find_duplicates(entries, threshold=0.99)


JAVA_LEFT = """\
class Left {
  int alpha(int[] xs) {
    int[] ys = filter(xs, odd);
    return map(ys, inc);
  }
}
"""

JAVA_RIGHT = """\
class Right {
  int beta(int[] items) {
    int[] kept = filter(items, even);
    return map(kept, dec);
  }
}
"""

GO_LEFT = """\
package demo

func (b *Board) alpha(xs []int) []int {
	ys := filter(xs, odd)
	return mapFn(ys, inc)
}
"""

GO_RIGHT = """\
package demo

func (p *Panel) beta(items []int) []int {
	kept := filter(items, even)
	return mapFn(kept, dec)
}
"""

PY_LEFT = """\
class Board:
    def alpha(self, xs):
        ys = filter(xs, odd)
        return map(ys, inc)
"""

PY_RIGHT = """\
class Panel:
    def beta(self, items):
        kept = filter(items, even)
        return map(kept, dec)
"""

TS_LEFT = """\
export function alpha(xs: number[]): number[] {
  const ys = filter(xs, odd);
  return map(ys, inc);
}
"""

TS_RIGHT = """\
export function beta(items: number[]): number[] {
  const kept = filter(items, even);
  return map(kept, dec);
}
"""

TSX_LEFT = """\
export const alpha = (xs: number[]): number[] => {
  const ys = filter(xs, odd);
  return map(ys, inc);
};
"""

TSX_RIGHT = """\
export const beta = (items: number[]): number[] => {
  const kept = filter(items, even);
  return map(kept, dec);
};
"""

RUST_LEFT = """\
pub fn alpha(xs: Vec<i32>) -> Vec<i32> {
    let ys = filter(xs, odd);
    map(ys, inc)
}
"""

RUST_RIGHT = """\
pub fn beta(items: Vec<i32>) -> Vec<i32> {
    let kept = filter(items, even);
    map(kept, dec)
}
"""


def test_each_language_matches_renamed_locals(tmp_path):
    samples = {
        "left.java": JAVA_LEFT,
        "right.java": JAVA_RIGHT,
        "left.go": GO_LEFT,
        "right.go": GO_RIGHT,
        "left.py": PY_LEFT,
        "right.py": PY_RIGHT,
        "left.ts": TS_LEFT,
        "right.ts": TS_RIGHT,
        "left.tsx": TSX_LEFT,
        "right.tsx": TSX_RIGHT,
        "left.rs": RUST_LEFT,
        "right.rs": RUST_RIGHT,
    }
    for name, source in samples.items():
        write_source(tmp_path, name, source)
    found = pairs(tmp_path)
    by_language = {}
    for item in found:
        by_language.setdefault(item.language, []).append(item)
    assert set(by_language) == {"java", "go", "python", "typescript", "rust"}
    assert [(item.left.file, item.right.file) for item in by_language["typescript"]] == [
        ("left.ts", "right.ts"),
        ("left.tsx", "right.tsx"),
    ]
    for language, group in by_language.items():
        assert all(item.score == 1.0 for item in group), language


def test_different_callee_is_not_the_same_structure(tmp_path):
    write_source(
        tmp_path,
        "a.py",
        "def alpha(xs):\n    return filter(xs, odd)\n",
    )
    write_source(
        tmp_path,
        "b.py",
        "def beta(xs):\n    return select(xs, odd)\n",
    )
    files = sorted(tmp_path.glob("*.py"))
    entries, warnings = scan_files(files, tmp_path, min_lines=1, min_nodes=1)
    assert warnings == []
    found = find_duplicates(entries, threshold=0.0)
    assert len(found) == 1
    assert found[0].score < 1.0


def test_nested_functions_stay_inside_the_enclosing_function(tmp_path):
    write_source(
        tmp_path,
        "a.py",
        """\
def alpha(xs):
    def inner(ys):
        return filter(ys, odd)
    return inner(xs)
""",
    )
    write_source(
        tmp_path,
        "b.py",
        """\
def beta(items):
    def inner(kept):
        return filter(kept, even)
    return inner(items)
""",
    )
    found = pairs(tmp_path)
    assert len(found) == 1
    assert found[0].score == 1.0
    assert found[0].left.start_line == 1


def test_rust_tests_module_is_not_production_code(tmp_path):
    write_source(
        tmp_path,
        "a.rs",
        """\
pub fn alpha(xs: Vec<i32>) -> Vec<i32> {
    let ys = filter(xs, odd);
    map(ys, inc)
}

mod tests {
    pub fn helper(xs: Vec<i32>) -> Vec<i32> {
        let ys = filter(xs, odd);
        map(ys, inc)
    }
}
""",
    )
    write_source(tmp_path, "b.rs", RUST_RIGHT)
    found = pairs(tmp_path)
    assert len(found) == 1
    assert found[0].left.file == "a.rs"
    assert found[0].left.start_line == 1


def test_an_extra_statement_lowers_the_score(tmp_path):
    write_source(tmp_path, "a.java", JAVA_LEFT)
    write_source(
        tmp_path,
        "b.java",
        """\
class Right {
  int beta(int[] items) {
    int[] kept = filter(items, even);
    int[] extra = filter(kept, even);
    return map(extra, dec);
  }
}
""",
    )
    files = sorted(tmp_path.glob("*.java"))
    entries, warnings = scan_files(files, tmp_path, min_lines=1, min_nodes=1)
    assert warnings == []
    found = find_duplicates(entries, threshold=0.5)
    assert len(found) == 1
    assert found[0].score < 1.0


def _suffix(language: str) -> str:
    return {"python": "py", "go": "go", "rust": "rs", "java": "java", "typescript": "ts"}[language]


def test_callee_names_stay_and_member_names_do_not(tmp_path):
    samples = {
        "left.py": """\
def alpha(xs):
    ys = xs.filter(odd)
    return (len)(xs.total)
""",
        "right.py": """\
def beta(items):
    kept = items.filter(even)
    return (len)(items.count)
""",
        "left.go": """\
package demo

func alpha(xs []int) {
	fmt.Println(xs)
}
""",
        "right.go": """\
package demo

func beta(items []int) {
	log.Println(items)
}
""",
        "left.rs": """\
pub fn alpha(xs: Vec<i32>) -> Vec<i32> {
    let ys = Vec::new();
    let zs = HashMap::<String, i32>::new();
    xs.iter()
}
""",
        "right.rs": """\
pub fn beta(items: Vec<i32>) -> Vec<i32> {
    let kept = Vec::new();
    let rows = HashMap::<Integer, i32>::new();
    items.iter()
}
""",
        "left.java": """\
class Left {
  int alpha(List xs) {
    List ys = new ArrayList<String>();
    ys.add(xs);
    return xs.total;
  }
}
""",
        "right.java": """\
class Right {
  int beta(List items) {
    List kept = new ArrayList<Integer>();
    kept.add(items);
    return items.count;
  }
}
""",
        "left.ts": """\
export function alpha(xs: number[]): number {
  return foo<string>(xs.bar);
}
""",
        "right.ts": """\
export function beta(items: number[]): number {
  return foo<number>(items.baz);
}
""",
    }
    for name, source in samples.items():
        write_source(tmp_path, name, source)
    found = pairs(tmp_path)
    by_language = {}
    for item in found:
        by_language.setdefault(item.language, []).append(item)
    assert set(by_language) == {"python", "go", "rust", "java", "typescript"}
    for language, group in by_language.items():
        assert [(item.left.file, item.right.file, item.score) for item in group] == [
            (f"left.{_suffix(language)}", f"right.{_suffix(language)}", 1.0)
        ], language


def test_a_different_callee_name_lowers_the_score(tmp_path):
    write_source(tmp_path, "a.py", "def alpha(xs):\n    return xs.filter(odd)\n")
    write_source(tmp_path, "b.py", "def beta(items):\n    return items.select(even)\n")
    write_source(tmp_path, "a.rs", "fn alpha() {\n    Vec::new()\n}\n")
    write_source(tmp_path, "b.rs", "fn beta() {\n    Vec::with_capacity(1)\n}\n")
    files = sorted(path for path in tmp_path.rglob("*") if path.is_file())
    entries, warnings = scan_files(files, tmp_path, min_lines=1, min_nodes=1)
    assert warnings == []
    found = find_duplicates(entries, threshold=0.0)
    assert len(found) == 2
    assert all(item.score < 1.0 for item in found)


def forms(language: str, source: str, file: str) -> list[Entry]:
    entries, warning = entries_in_source(language, source, file, file)
    assert warning is None
    return entries


def spans(language: str, source: str, file: str) -> list[tuple[int, int]]:
    return [(entry.start_line, entry.end_line) for entry in forms(language, source, file)]


def test_a_parenthesized_callee_matches_the_bare_call():
    wrapped = forms("python", "def alpha(xs):\n    return (len)(xs)\n", "a.py")
    bare = forms("python", "def beta(items):\n    return len(items)\n", "b.py")
    assert any("len" in item for item in wrapped[0].fingerprints)
    found = find_duplicates(wrapped + bare, threshold=0.99)
    assert len(found) == 1
    assert found[0].score == 1.0


def test_a_string_and_a_number_normalize_to_the_same_literal():
    string = forms("python", 'def alpha(xs):\n    return "xs"\n', "a.py")
    number = forms("python", "def beta(items):\n    return 1\n", "b.py")
    assert any(item == ":literal" for item in number[0].fingerprints)
    found = find_duplicates(string + number, threshold=0.99)
    assert len(found) == 1
    assert found[0].score == 1.0


def test_an_interpolated_string_is_not_a_plain_literal():
    plain = forms("python", 'def alpha(xs):\n    return "xs"\n', "a.py")
    interpolated = forms("python", 'def beta(items):\n    return f"{items}"\n', "b.py")
    found = find_duplicates(plain + interpolated, threshold=0.0)
    assert len(found) == 1
    assert found[0].score < 1.0


def test_constructed_names_stay_in_the_fingerprint():
    rust = forms(
        "rust",
        """\
pub fn alpha() {
    let ys = Vec::new();
    let zs = HashMap::<String, i32>::new();
}
""",
        "a.rs",
    )
    java = forms(
        "java",
        """\
class Left {
  int alpha() {
    return new ArrayList<String>();
  }
}
""",
        "a.java",
    )
    rust_names = " ".join(rust[0].fingerprints)
    java_names = " ".join(java[0].fingerprints)
    assert "Vec" in rust_names
    assert "new" in rust_names
    assert "HashMap" in rust_names
    assert "ArrayList" in java_names


def test_a_class_contributes_its_method_only():
    assert spans(
        "python",
        "class Board:\n    def alpha(self, xs):\n        return xs\n",
        "a.py",
    ) == [(2, 3)]


def test_java_keeps_the_method_that_has_its_own_body():
    assert spans(
        "java",
        """\
abstract class Left {
  abstract int declared(int[] xs);
  int alpha(int[] xs) {
    class Inner {
      int run(int[] ys) {
        return ys.length;
      }
    }
    return xs.length;
  }
}
""",
        "a.java",
    ) == [(3, 10)]


def test_a_go_function_needs_a_body():
    assert spans(
        "go",
        """\
package demo

func declared(xs []int)

func alpha(xs []int) int {
	return len(xs)
}
""",
        "a.go",
    ) == [(5, 7)]


def test_a_closure_in_a_let_stays_inside_the_function():
    assert spans(
        "rust",
        """\
fn alpha() {
    let f = || { let x = 1; };
}
""",
        "a.rs",
    ) == [(1, 3)]


def test_a_method_nested_in_a_function_is_not_its_own_candidate():
    assert spans(
        "typescript",
        """\
export function outer(xs: number[]): number[] {
  class Hidden {
    run(ys: number[]): number[] {
      return ys;
    }
  }
  return xs;
}
""",
        "a.ts",
    ) == [(1, 8)]


def test_rust_keeps_helpers_and_skips_tests_and_signatures():
    assert spans(
        "rust",
        """\
fn declared();

fn alpha(xs: Vec<i32>) -> usize {
    xs.len()
}

mod helpers {
    fn beta(xs: Vec<i32>) -> usize {
        xs.len()
    }
}

mod tests {
    fn gamma(xs: Vec<i32>) -> usize {
        xs.len()
    }
}
""",
        "a.rs",
    ) == [(3, 5), (8, 10)]


def test_typescript_keeps_methods_and_skips_nested_functions():
    assert spans(
        "typescript",
        """\
export class Box {
  run(xs: number[]): number[] {
    return xs;
  }
}
export function outer(xs: number[]): number[] {
  function inner(ys: number[]): number[] {
    return ys;
  }
  const cb = (zs: number[]) => zs;
  return inner(xs);
}
xs.map((item: number) => item + 1);
""",
        "a.ts",
    ) == [(2, 4), (6, 12)]


def test_a_private_field_name_is_not_part_of_the_structure():
    entries = forms(
        "typescript",
        """\
export class Box {
  value(): number {
    return this.#total;
  }
  run(xs: number[]): number[] {
    return this.#hidden(xs);
  }
}
""",
        "a.ts",
    )
    assert [(entry.start_line, entry.end_line) for entry in entries] == [(2, 4), (5, 7)]
    printed = " ".join(item for entry in entries for item in entry.fingerprints)
    assert "#total" not in printed
    assert "#hidden" not in printed


def test_end_line_uses_the_last_occupied_row():
    source = "def alpha(xs):\n    return xs\n"
    _data, tree = parse(source, "python")
    function = next(node for node in descendants(tree.root_node) if node.type == "function_definition")
    assert end_line(tree.root_node) == 2
    assert end_line(function) == 2
    assert spans("python", source, "a.py") == [(1, 2)]


def _entry(file: str, start: int, end: int, names: set[str], language: str = "python", nodes: int = 20):
    return Entry(language, file, start, end, nodes, frozenset(names))


def test_the_same_span_is_not_a_pair():
    assert find_duplicates([
        _entry("a.py", 1, 4, {"q"}),
        _entry("a.py", 1, 4, {"q"}),
    ], 0.0) == []
    assert len(find_duplicates([
        _entry("a.py", 1, 4, {"q"}),
        _entry("b.py", 1, 4, {"q"}),
    ], 0.5)) == 1
    assert len(find_duplicates([
        _entry("a.py", 1, 5, {"q"}),
        _entry("a.py", 3, 5, {"q"}),
    ], 0.5)) == 1
    assert len(find_duplicates([
        _entry("a.py", 1, 4, {"q"}),
        _entry("a.py", 1, 8, {"q"}),
    ], 0.5)) == 1


def test_a_score_equal_to_the_threshold_is_kept():
    left = _entry("a.rs", 1, 4, {"x", "y"}, language="rust")
    right = _entry("b.rs", 1, 4, {"x", "z"}, language="rust")
    assert len(find_duplicates([left, right], 1 / 3)) == 1
    assert find_duplicates([left, right], 1 / 3 + 0.01) == []


def test_the_higher_score_is_reported_first():
    exact = [
        _entry("a.py", 1, 4, {"only"}),
        _entry("b.py", 1, 4, {"only"}),
    ]
    partial = [
        _entry("a.rs", 1, 4, {"x", "y"}, language="rust"),
        _entry("b.rs", 10, 14, {"x", "z"}, language="rust"),
    ]
    found = find_duplicates(partial + exact, 0.3)
    assert [item.score for item in found] == [1.0, 1 / 3]


def test_keep_uses_the_line_and_node_minimums():
    assert _keep(_entry("a.py", 1, 4, {"q"}, nodes=20), 4, 20) is True
    assert _keep(_entry("a.py", 1, 3, {"q"}, nodes=20), 4, 20) is False
    assert _keep(_entry("a.py", 1, 4, {"q"}, nodes=19), 4, 20) is False


def test_a_deep_expression_is_read():
    expr = " + ".join(["1"] * 1200)
    entries, warning = entries_in_source("python", f"def place():\n    return {expr}\n", "app.py", "app.py")
    assert warning is None
    assert entries
    nested = "(inc " * 1200 + "x" + ")" * 1200
    entries, warning = entries_in_source("clojure", f"(defn place [] {nested})\n", "app.clj", "app.clj")
    assert warning is None
    assert entries
