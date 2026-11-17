import pytest

from crapper.languages import functions_in_file

_SOURCES = [
    ("clojure", "(defn choose [x] x)\n", "src/demo/core.clj", "choose"),
    ("java", "class Board { void place() { if (true) return; } }\n", "Board.java", "place"),
    ("go", "package demo\nfunc Place() int { return 1 }\n", "board.go", "Place"),
    ("typescript", "export function view() { return 1 }\n", "src/ui.ts", "view"),
    ("python", "def run():\n    return 1\n", "src/app.py", "run"),
    ("rust", "pub fn open() {}\n", "src/lib.rs", "open"),
]


@pytest.mark.parametrize(("language", "source", "path", "name"), _SOURCES)
def test_each_language_answers_the_same_message(language, source, path, name):
    found = functions_in_file(language, source, path, "/proj")
    assert found[0].name == name
    assert found[0].language == language


def test_unknown_language_has_no_functions():
    assert functions_in_file("ruby", "def x\nend\n", "a.rb", "/proj") == []
