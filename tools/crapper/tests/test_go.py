from crapper.languages.golang import functions_in_source

BRANCHY = """package sample

func Branchy(x int, ch chan int) int {
	if x > 0 && x < 10 {
		return 1
	}
	switch x {
	case 1, 2:
		return 2
	default:
		return 3
	}
}
"""


def test_counts_the_same_decisions_as_crap4go():
    functions = functions_in_source(BRANCHY, "sample.go")
    assert [(fn.name, fn.namespace, fn.complexity) for fn in functions] == [
        ("Branchy", "sample", 5)
    ]


def test_methods_are_namespaced_by_receiver_type():
    source = """package sample

type Widget struct{}

func (w *Widget) Run() {
	for i := 0; i < 10; i++ {
	}
	for _, item := range items {
	}
}

func Simple() int { return 1 }
"""
    functions = functions_in_source(source, "widget.go")
    assert [(fn.namespace, fn.name, fn.complexity) for fn in functions] == [
        ("sample.Widget", "Run", 3),
        ("sample", "Simple", 1),
    ]


def test_import_path_comes_from_go_mod(tmp_path):
    (tmp_path / "go.mod").write_text("module github.com/acme/demo\n", encoding="utf-8")
    source_path = tmp_path / "internal" / "game" / "board.go"
    source_path.parent.mkdir(parents=True)
    source_path.write_text(
        "package game\n\ntype Board struct{}\n\nfunc (b *Board) Place() {\n\tif ready && ok {\n\t\treturn\n\t}\n}\n",
        encoding="utf-8",
    )
    functions = functions_in_source(
        source_path.read_text(encoding="utf-8"),
        "internal/game/board.go",
        str(tmp_path),
    )
    assert [(fn.namespace, fn.name, fn.complexity) for fn in functions] == [
        ("github.com/acme/demo/internal/game.Board", "Place", 3)
    ]
