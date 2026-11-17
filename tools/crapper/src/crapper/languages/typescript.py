"""TypeScript, TSX, and JavaScript functions, methods, and complexity.

Decision points follow the same structural rule as crap4java: `if`, loops,
`catch`, `?:`, each `switch` case (including `default`), `&&` / `||`, and the
nullish operators `??` and `?.`. Top-level functions and class methods are
entries. An inline Express route callback (`app.get("/users", handler)` and
the same methods on a router, including `use` and `route().get`) is its own
entry, named `GET /users`. Other nested callbacks stay inside the enclosing
function. A class method's namespace is `module.Class`, which is the
uml-viewer class key. `.js`, `.mjs`, `.cjs`, and `.jsx` use the JavaScript
grammar and these same rules.
"""

from crapper.languages.treesitter import (
    binary_logic,
    child_of_type,
    complexity,
    descendants,
    end_line,
    node_text,
    parse,
    start_line,
)
from crapper.model import Function

_DECISIONS = {
    "if_statement",
    "for_statement",
    "for_in_statement",
    "while_statement",
    "do_statement",
    "catch_clause",
    "ternary_expression",
    "switch_case",
    "switch_default",
}
_SKIP = {
    "class_declaration",
    "abstract_class_declaration",
    "interface_declaration",
}
_FUNCTION_TYPES = {
    "function_declaration",
    "method_definition",
    "arrow_function",
    "function_expression",
}
_ROUTE_METHODS = {
    "get",
    "post",
    "put",
    "patch",
    "delete",
    "head",
    "options",
    "all",
    "use",
}
_CALLBACKS = {"arrow_function", "function_expression"}
_JS_SUFFIXES = (".jsx", ".mjs", ".cjs", ".js")


def _is_decision(node) -> bool:
    if node.type in _DECISIONS or binary_logic(node):
        return True
    if node.type == "binary_expression" and any(child.type == "??" for child in node.children):
        return True
    # `a?.b` and `a?.[0]` wrap `?.` in optional_chain. `a?.()` is a bare `?.`
    # in TypeScript and an optional_chain in JavaScript. Count the wrapper
    # once, and a bare token only when nothing wraps it.
    if node.type == "optional_chain":
        return True
    return node.type == "?." and (node.parent is None or node.parent.type != "optional_chain")


def _skip(node) -> bool:
    return node.type in _SKIP


def _grammar(path: str) -> str:
    if path.endswith(".tsx"):
        return "tsx"
    if path.endswith(_JS_SUFFIXES):
        return "javascript"
    return "typescript"


def _strip_root_prefix(relative: str, source_root: str | None) -> str:
    root = (source_root or "").replace("\\", "/").rstrip("/")
    if root and root != "." and relative.startswith(root + "/"):
        return relative[len(root) + 1 :]
    if relative.startswith("./"):
        return relative[2:]
    return relative


def _strip_src_dir(relative: str) -> str:
    if relative.startswith("src/"):
        return relative[4:]
    if "/src/" in relative:
        return relative.split("/src/", 1)[1]
    return relative


def _strip_script_suffix(relative: str) -> str:
    for suffix in (".tsx", ".mts", ".cts", ".jsx", ".mjs", ".cjs", ".ts", ".js"):
        if relative.endswith(suffix):
            return relative[: -len(suffix)]
    return relative


def _module_namespace(path: str, source_root: str | None) -> str:
    relative = path.replace("\\", "/")
    relative = _strip_root_prefix(relative, source_root)
    relative = _strip_src_dir(relative)
    return _strip_script_suffix(relative).replace("/", ".")


def _inside_function(node) -> bool:
    current = node.parent
    while current is not None:
        if current.type in _FUNCTION_TYPES:
            return True
        current = current.parent
    return False


def _class_names(data: bytes, node) -> list[str]:
    names: list[str] = []
    current = node.parent
    while current is not None:
        if current.type in {"class_declaration", "abstract_class_declaration"}:
            ident = child_of_type(current, "type_identifier", "identifier")
            if ident is not None:
                names.append(node_text(data, ident))
        current = current.parent
    names.reverse()
    return names


def _has_body(node) -> bool:
    return child_of_type(node, "statement_block") is not None


def _expression(node):
    current = node
    while current is not None and current.type == "parenthesized_expression":
        current = next(
            (child for child in current.children if child.type not in {"(", ")"}),
            None,
        )
    return current


def _call_callee(call):
    for child in call.children:
        if child.type != "arguments":
            return child
    return None


def _property_name(data: bytes, node) -> str | None:
    if node is None or node.type != "member_expression":
        return None
    ident = child_of_type(node, "property_identifier")
    if ident is None:
        return None
    return node_text(data, ident)


def _route_method(data: bytes, call) -> str | None:
    name = _property_name(data, _call_callee(call))
    if name is None:
        return None
    lowered = name.lower()
    if lowered in _ROUTE_METHODS:
        return lowered
    return None


def _literal_text(data: bytes, node) -> str | None:
    if node.type == "string":
        return "".join(
            node_text(data, child) for child in node.children if child.type == "string_fragment"
        )
    if node.type == "template_string":
        text = node_text(data, node)
        if len(text) >= 2 and text[0] == "`" and text[-1] == "`":
            return text[1:-1]
    return None


def _string_argument(data: bytes, call) -> str | None:
    arguments = child_of_type(call, "arguments")
    if arguments is None:
        return None
    for child in arguments.children:
        expr = _expression(child)
        if expr is None:
            continue
        text = _literal_text(data, expr)
        if text is not None:
            return text
    return None


def _path_from_route(data: bytes, node) -> str | None:
    """Path from a preceding `.route("/path")` in a chained call."""

    current = node
    while current is not None:
        if current.type == "member_expression":
            current = current.children[0] if current.children else None
            continue
        if current.type != "call_expression":
            return None
        callee = _call_callee(current)
        if _property_name(data, callee) == "route":
            return _string_argument(data, current)
        current = callee
    return None


def _callbacks(call) -> list:
    arguments = child_of_type(call, "arguments")
    if arguments is None:
        return []
    found = []
    for child in arguments.children:
        expr = _expression(child)
        if expr is not None and expr.type in _CALLBACKS:
            found.append(expr)
    return found


def _is_route_callback(data: bytes, node) -> bool:
    if node.type not in _CALLBACKS:
        return False
    current = node.parent
    while current is not None and current.type == "parenthesized_expression":
        current = current.parent
    if current is None or current.type != "arguments":
        return False
    call = current.parent
    if call is None or call.type != "call_expression":
        return False
    return _route_method(data, call) is not None


def _complexity(data: bytes, node) -> int:
    root_id = node.id

    def skip(current) -> bool:
        if current.id == root_id:
            return False
        return _skip(current) or _is_route_callback(data, current)

    return complexity(node, _is_decision, skip)


def _append(found: list, node, **kwargs) -> None:
    found.append(
        (
            node.start_byte,
            Function(start_byte=node.start_byte, end_byte=node.end_byte, **kwargs),
        )
    )


def _append_function(found, data, node, module, path) -> None:
    if _inside_function(node) or not _has_body(node):
        return
    ident = child_of_type(node, "identifier")
    if ident is None:
        return
    _append(
        found,
        node,
        name=node_text(data, ident),
        namespace=module,
        complexity=_complexity(data, node),
        start_line=start_line(node),
        end_line=end_line(node),
        path=path,
        language="typescript",
    )


def _append_method(found, data, node, module, path) -> None:
    if not _has_body(node):
        return
    ident = child_of_type(node, "property_identifier", "identifier")
    classes = _class_names(data, node)
    if ident is None or not classes:
        return
    _append(
        found,
        node,
        name=node_text(data, ident),
        namespace=f"{module}.{'.'.join(classes)}",
        complexity=_complexity(data, node),
        start_line=start_line(node),
        end_line=end_line(node),
        path=path,
        language="typescript",
    )


def _append_arrows(found, data, node, module, path) -> None:
    if _inside_function(node):
        return
    for declarator in node.children:
        if declarator.type != "variable_declarator":
            continue
        ident = child_of_type(declarator, "identifier")
        value = child_of_type(declarator, "arrow_function", "function_expression")
        if ident is None or value is None:
            continue
        _append(
            found,
            declarator,
            name=node_text(data, ident),
            namespace=module,
            complexity=_complexity(data, value),
            start_line=start_line(declarator),
            end_line=end_line(declarator),
            path=path,
            language="typescript",
        )


def _route_label(method: str, path: str | None) -> str:
    label = method.upper()
    if path:
        return f"{label} {path}"
    return label


def _claim(used: set[str], name: str) -> str:
    if name not in used:
        used.add(name)
        return name
    number = 2
    while f"{name}#{number}" in used:
        number += 1
    chosen = f"{name}#{number}"
    used.add(chosen)
    return chosen


def _collect_routes(routes: list, data: bytes, call) -> None:
    method = _route_method(data, call)
    if method is None:
        return
    callbacks = _callbacks(call)
    if not callbacks:
        return
    route = _string_argument(data, call)
    if not route:
        route = _path_from_route(data, _call_callee(call))
    for callback in callbacks:
        routes.append((callback.start_byte, callback, method, route))


def _append_routes(found, routes, data, module, path) -> None:
    used: set[str] = set()
    for _start, callback, method, route in sorted(routes, key=lambda item: item[0]):
        _append(
            found,
            callback,
            name=_claim(used, _route_label(method, route)),
            namespace=module,
            complexity=_complexity(data, callback),
            start_line=start_line(callback),
            end_line=end_line(callback),
            path=path,
            language="typescript",
        )


def functions_in_source(
    source: str, path: str, source_root: str | None = None
) -> list[Function]:
    data, tree = parse(source, _grammar(path))
    module = _module_namespace(path, source_root)
    found: list[tuple[int, Function]] = []
    routes: list[tuple[int, object, str, str | None]] = []
    for node in descendants(tree.root_node):
        if node.type == "function_declaration":
            _append_function(found, data, node, module, path)
        elif node.type == "method_definition":
            _append_method(found, data, node, module, path)
        elif node.type == "lexical_declaration":
            _append_arrows(found, data, node, module, path)
        elif node.type == "call_expression":
            _collect_routes(routes, data, node)
    _append_routes(found, routes, data, module, path)
    found.sort(key=lambda item: item[0])
    return [function for _, function in found]
