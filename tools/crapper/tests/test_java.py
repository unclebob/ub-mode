from crapper.languages.java import functions_in_source

SCORE = """
class Sample {
    int score(boolean a, boolean b, int[] values) {
        for (int i = 0; i < values.length; i++) {
        }
        for (int value : values) {
        }
        while (a) {
            a = false;
        }
        do {
            b = false;
        } while (b);
        if (a && b || values.length > 0) {
        }
        try {
            return a ? 1 : 0;
        } catch (RuntimeException ex) {
            return 2;
        }
    }
}
"""

NESTED = """
class Sample {
    int nested(boolean a, boolean b, int[] values) {
        for (int i = 0; i < values.length; i++) {
            if (a) {
            }
        }
        for (int value : values) {
            if (b) {
            }
        }
        while (a) {
            if (b) {
            }
            a = false;
        }
        do {
            if (a) {
            }
            b = false;
        } while (b);
        try {
            return a ? (b ? 1 : 0) : 2;
        } catch (RuntimeException ex) {
            if (values.length > 0) {
                return values[0];
            }
            return 3;
        }
    }

    int switched(int value) {
        switch (value) {
            case 1:
                if (value > 0) {
                    return 1;
                }
                return 0;
            default:
                return 2;
        }
    }
}
"""

ANONYMOUS = """
class Sample {
    int outer() {
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                if (true) {
                }
            }
        };
        return 1;
    }
}
"""


def test_counts_the_same_decisions_as_crap4java():
    methods = functions_in_source(SCORE, "Sample.java")
    assert [(method.name, method.complexity) for method in methods] == [("score", 10)]

    methods = functions_in_source(NESTED, "Sample.java")
    assert [(method.name, method.complexity) for method in methods] == [
        ("nested", 13),
        ("switched", 4),
    ]


def test_anonymous_class_methods_are_not_entries_and_do_not_raise_complexity():
    methods = functions_in_source(ANONYMOUS, "Sample.java")
    assert [(method.name, method.complexity) for method in methods] == [("outer", 1)]


def test_ignores_constructors_and_names_the_class_for_uml_viewer():
    source = """
    package demo.pkg;
    public class Board {
        Board() { if (true) {} }
        public void place(int x) { if (x > 0 && ready) return; }
        class Inner { void tick() { return; } }
    }
    """
    methods = functions_in_source(source, "src/demo/pkg/Board.java")
    assert [(method.namespace, method.name, method.complexity) for method in methods] == [
        ("demo.pkg.Board", "place", 3),
        ("demo.pkg.Board.Inner", "tick", 1),
    ]
    assert methods[0].jacoco_class == "demo.pkg.Board"
    assert methods[1].jacoco_class == "demo.pkg.Board$Inner"


def test_keywords_in_comments_and_strings_are_not_decisions():
    source = """
    class Sample {
        int stable() {
            String text = "if && || ? case default catch";
            // if && || ? case default catch
            /* if && || ? case default catch */
            return 1;
        }
    }
    """
    methods = functions_in_source(source, "Sample.java")
    assert [(method.name, method.complexity) for method in methods] == [("stable", 1)]
