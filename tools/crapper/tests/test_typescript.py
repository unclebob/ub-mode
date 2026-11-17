from crapper.languages.typescript import functions_in_source


def test_function_class_method_and_arrow_are_separate_entries():
    source = """
export function choose(x: number): number {
  if (x > 0 && x < 10) return 1;
  for (let i = 0; i < x; i++) {
    if (i === 2) return i;
  }
  try {
    return x;
  } catch (e) {
    return 0;
  }
  return x > 0 ? 1 : 0;
}

export class Box {
  open(flag?: boolean): number {
    return flag ? 1 : 0;
  }
}

const arrow = (n: number) => (n > 0 ? n : 0);

function outer() {
  function inner(n: number) {
    if (n) return 1;
    return 0;
  }
  return inner(1);
}
"""
    functions = functions_in_source(source, "src/demo/box.ts", "/proj")
    assert [(fn.namespace, fn.name, fn.complexity) for fn in functions] == [
        ("demo.box", "choose", 7),
        ("demo.box.Box", "open", 2),
        ("demo.box", "arrow", 2),
        ("demo.box", "outer", 2),
    ]


def test_tsx_counts_jsx_logic():
    source = """
export function View(ok: boolean, ready: boolean) {
  return ok && ready ? 1 : 0;
}
"""
    functions = functions_in_source(source, "src/ui/view.tsx", "/proj")
    assert [(fn.namespace, fn.name, fn.complexity) for fn in functions] == [
        ("ui.view", "View", 3)
    ]


def test_nullish_and_optional_chains_are_branches():
    source = """
export function choose(a, b, c) {
  return a ?? b?.c ?? c?.() ?? c?.[0];
}
export function text() {
  return "a ?? b?.c";
}
"""
    functions = functions_in_source(source, "src/demo/box.ts", "/proj")
    assert [(fn.name, fn.complexity) for fn in functions] == [
        ("choose", 7),
        ("text", 1),
    ]


def test_express_handlers_are_entries_and_leave_the_parent():
    source = """
export function mount(app) {
  const extra = [1].map((n) => (n ? 1 : 0));
  app.get("/users", (req, res) => {
    if (req.query.q) return 1;
    return 0;
  });
  app.post("/users", function (req, res) {
    return req.body ?? {};
  });
  app.use((req, res, next) => next());
  app.route("/items").get((req, res) => res.send(1)).post((req, res) => res.send(2));
  return extra;
}
app.get("/health", async (req, res) => res.send(req.query.ok ?? "no"));
app.get("/users", (req, res, next) => next(), (req, res) => res.send(req.id ?? 0));
app.delete(`/users/${id}`, ((req, res) => res.send(1)));
"""
    functions = functions_in_source(source, "src/demo/routes.ts", "/proj")
    assert [(fn.name, fn.complexity) for fn in functions] == [
        ("mount", 2),
        ("GET /users", 2),
        ("POST /users", 2),
        ("USE", 1),
        ("GET /items", 1),
        ("POST /items", 1),
        ("GET /health", 2),
        ("GET /users#2", 1),
        ("GET /users#3", 2),
        ("DELETE /users/${id}", 1),
    ]
    assert {fn.namespace for fn in functions} == {"demo.routes"}


def test_a_nested_callback_that_is_not_a_route_stays_inside_its_function():
    source = """
function outer() {
  const values = [1].map((n) => (n ? 1 : 0));
  return values;
}
"""
    functions = functions_in_source(source, "src/demo/box.ts", "/proj")
    assert [(fn.name, fn.complexity) for fn in functions] == [("outer", 2)]


def test_javascript_files_use_the_same_rules():
    source = """
export function choose(a, b, c) {
  return a ?? b?.c ?? c?.() ?? c?.[0];
}
app.get("/users", (req, res) => req.id ?? 0);
"""
    functions = functions_in_source(source, "src/demo/app.mjs", "/proj")
    assert [(fn.namespace, fn.name, fn.complexity) for fn in functions] == [
        ("demo.app", "choose", 7),
        ("demo.app", "GET /users", 2),
    ]
    js = functions_in_source(source, "src/demo/app.js", "/proj")
    assert [(fn.name, fn.complexity) for fn in js] == [
        ("choose", 7),
        ("GET /users", 2),
    ]
