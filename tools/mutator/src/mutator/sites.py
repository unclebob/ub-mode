"""Find mutation sites.

Clojure follows clj-mutate's symbol rules. Go follows mutate4go, including
one-way `*` to `/`. Java follows mutate4java. TypeScript, Rust, and Python
use the same decisions as Java, spelled in that language. TypeScript also
mutates `??` to `||` and `?.` to `.` (removed on a call or index). JavaScript
files use the TypeScript rules.
"""

from __future__ import annotations

from mutator.crapper_link import ensure_crapper

_BINARY = {
    "+": "-",
    "-": "+",
    "*": "/",
    "/": "*",
    ">": ">=",
    ">=": ">",
    "<": "<=",
    "<=": "<",
    "==": "!=",
    "!=": "==",
    "===": "!==",
    "!==": "===",
    "&&": "||",
    "||": "&&",
    "??": "||",
    "and": "or",
    "or": "and",
}

_BINARY_PARENTS = {
    "binary_expression",
    "binary_operator",
    "comparison_operator",
    "boolean_operator",
}

_UNARY_PARENTS = {"unary_expression", "unary_operator", "not_operator"}

_BOOLEANS = {"true": "false", "false": "true", "True": "False", "False": "True"}

_NUMBERS = {
    "decimal_integer_literal",
    "integer_literal",
    "int_literal",
    "integer",
    "number",
    "num_lit",
}

_CLOJURE_HEAD = {
    "+": ("-", "arithmetic"),
    "-": ("+", "arithmetic"),
    "*": ("/", "arithmetic"),
    "inc": ("dec", "arithmetic"),
    "dec": ("inc", "arithmetic"),
    ">": (">=", "comparison"),
    ">=": (">", "comparison"),
    "<": ("<=", "comparison"),
    "<=": ("<", "comparison"),
    "=": ("not=", "equality"),
    "not=": ("=", "equality"),
    "if": ("if-not", "conditional"),
    "if-not": ("if", "conditional"),
    "when": ("when-not", "conditional"),
    "when-not": ("when", "conditional"),
    "and": ("or", "logical"),
    "or": ("and", "logical"),
    "double": ("int", "coercion"),
    "int": ("double", "coercion"),
    "first": ("second", "seq"),
    "second": ("first", "seq"),
    "filter": ("remove", "seq"),
    "remove": ("filter", "seq"),
    "take": ("drop", "seq"),
    "drop": ("take", "seq"),
    "rest": ("next", "seq"),
    "next": ("rest", "seq"),
    "every?": ("some", "seq"),
    "some": ("every?", "seq"),
    "min": ("max", "numeric"),
    "max": ("min", "numeric"),
    "pos?": ("neg?", "predicate"),
    "neg?": ("pos?", "predicate"),
    "even?": ("odd?", "predicate"),
    "odd?": ("even?", "predicate"),
    "nil?": ("some?", "predicate"),
    "some?": ("nil?", "predicate"),
}


class RawSite:
    def __init__(
        self, line: int, start: int, end: int, original: str, mutant: str, category: str, anchor_line: int = 0
    ):
        self.line = line
        # The line its statement starts on. LCOV marks only that line of a multi-line statement.
        self.anchor_line = anchor_line or line
        self.start = start
        self.end = end
        self.original = original
        self.mutant = mutant
        self.category = category


def _grammar(language: str, path: str) -> str:
    if language == "typescript":
        if path.endswith(".tsx"):
            return "tsx"
        if path.endswith((".js", ".jsx", ".mjs", ".cjs")):
            return "javascript"
        return "typescript"
    if language == "go":
        return "go"
    return language


def _text(data: bytes, node) -> str:
    return data[node.start_byte : node.end_byte].decode("utf-8")


def _category(token: str) -> str:
    if token in {"+", "-", "*", "/"}:
        return "arithmetic"
    if token in {">", ">=", "<", "<="}:
        return "comparison"
    if token in {"==", "!=", "===", "!=="}:
        return "equality"
    if token in {"&&", "||", "??", "and", "or"}:
        return "logical"
    return "arithmetic"


def _binary_mutant(language: str, token: str) -> str | None:
    if language == "go" and token == "/":
        return None
    if token == "??" and language != "typescript":
        return None
    return _BINARY.get(token)


def _optional_mutant(node) -> str | None:
    """`a?.b` becomes `a.b`. A call or index drops `?.`, so `a?.()` becomes `a()`."""

    parent = node.parent
    if parent is None:
        return None
    host = parent.parent if parent.type == "optional_chain" else parent
    if host is None:
        return None
    if host.type == "member_expression":
        return "."
    if host.type in {"call_expression", "subscript_expression"}:
        return ""
    return None


def _add(found: list[RawSite], node, original: str, mutant: str, category: str) -> None:
    if mutant == original:
        return
    found.append(
        RawSite(
            line=node.start_point[0] + 1,
            start=node.start_byte,
            end=node.end_byte,
            original=original,
            mutant=mutant,
            category=category,
            anchor_line=_statement_line(node),
        )
    )


def _is_statement(node_type: str) -> bool:
    return node_type.endswith("_statement") or node_type.endswith("_declaration")


def _statement_line(node) -> int:
    current = node.parent
    while current is not None:
        if _is_statement(current.type):
            return current.start_point[0] + 1
        current = current.parent
    return node.start_point[0] + 1


# A literal in a TypeScript type, such as `{ ok: true }` in a return type, never runs.
_TYPE_NODES = {
    "type_annotation",
    "type_alias_declaration",
    "interface_declaration",
    "type_arguments",
    "type_parameters",
    "literal_type",
}


def _in_type(node) -> bool:
    current = node.parent
    while current is not None:
        if current.type in _TYPE_NODES:
            return True
        current = current.parent
    return False


def _clojure_head(node, data: bytes) -> str | None:
    for child in node.children:
        if child.type == "sym_lit":
            return _text(data, child)
    return None


def _is_head(node) -> bool:
    parent = node.parent
    if parent is None or parent.type != "list_lit":
        return False
    for child in parent.children:
        if child.type == "sym_lit":
            return child.id == node.id
    return False


def _clojure_skipped(node, data: bytes) -> bool:
    current = node
    while current is not None:
        if any(part in current.type for part in ("comment", "quot", "string", "str_lit", "char_lit")):
            return True
        if current.type == "list_lit":
            head = _clojure_head(current, data)
            if head in {"comment", "quote"}:
                return True
        current = current.parent
    return False


def _clojure_text(node, data: bytes) -> str:
    if node.child_count == 0 or node.type in {"sym_lit", "bool_lit", "num_lit"}:
        return _text(data, node)
    return ""


def _flip01(text: str) -> str:
    if text == "0":
        return "1"
    return "0"


def _clojure_node(node, data: bytes, found: list[RawSite]) -> None:
    if _clojure_skipped(node, data):
        return
    text = _clojure_text(node, data)
    if node.type == "sym_lit" and _is_head(node) and text in _CLOJURE_HEAD:
        mutant, category = _CLOJURE_HEAD[text]
        _add(found, node, text, mutant, category)
        return
    if node.type == "bool_lit" and text in _BOOLEANS:
        _add(found, node, text, _BOOLEANS[text], "boolean")
        return
    if node.type == "num_lit" and text in {"0", "1"}:
        _add(found, node, text, _flip01(text), "constant")


def _visit_clojure(node, data: bytes, found: list[RawSite]) -> None:
    # An explicit stack: a 1,200-deep form must not raise RecursionError.
    stack = [node]
    while stack:
        current = stack.pop()
        _clojure_node(current, data, found)
        stack.extend(reversed(current.children))


def _parent_type(node) -> str:
    if node.parent is None:
        return ""
    return node.parent.type


def _visit_binary(node, token: str, parent: str, language: str, found: list[RawSite]) -> None:
    if parent not in _BINARY_PARENTS:
        return
    mutant = _binary_mutant(language, token)
    if mutant is not None:
        _add(found, node, token, mutant, _category(token))


def _visit_optional(node, token: str, language: str, found: list[RawSite]) -> None:
    if language != "typescript" or token != "?.":
        return
    optional = _optional_mutant(node)
    if optional is not None:
        _add(found, node, "?.", optional, "optional")


def _visit_not(node, token: str, parent: str, found: list[RawSite]) -> None:
    if token in {"!", "not"} and parent in _UNARY_PARENTS | {"comparison_operator"}:
        _add(found, node, token, "", "unary")


def _visit_negation(node, token: str, parent: str, found: list[RawSite]) -> None:
    if token == "-" and parent in _UNARY_PARENTS:
        _add(found, node, token, "", "unary")


def _visit_boolean_token(node, token: str, found: list[RawSite]) -> None:
    if node.type in {"true", "false"} and token in _BOOLEANS:
        _add(found, node, token, _BOOLEANS[token], "boolean")


def _visit_constant(node, token: str, found: list[RawSite]) -> None:
    if node.type in _NUMBERS and token in {"0", "1"}:
        _add(found, node, token, _flip01(token), "constant")


def _visit_bool_lit(node, data: bytes, found: list[RawSite]) -> None:
    if node.type != "bool_lit":
        return
    text = _text(data, node)
    if text in _BOOLEANS:
        _add(found, node, text, _BOOLEANS[text], "boolean")


def _visit_leaf(node, data: bytes, language: str, found: list[RawSite]) -> None:
    # A literal sits inside the same expression as an operator, so these
    # checks are independent. `0` is a child of `a > 0`, not an operator.
    token = _text(data, node)
    parent = _parent_type(node)
    _visit_binary(node, token, parent, language, found)
    _visit_optional(node, token, language, found)
    _visit_not(node, token, parent, found)
    _visit_negation(node, token, parent, found)
    _visit_boolean_token(node, token, found)
    _visit_constant(node, token, found)


def _visit_node(node, data: bytes, language: str, found: list[RawSite]) -> None:
    if language == "typescript" and _in_type(node):
        return
    if node.child_count == 0:
        _visit_leaf(node, data, language, found)
    else:
        _visit_bool_lit(node, data, found)


def _visit(node, data: bytes, language: str, found: list[RawSite]) -> None:
    # An explicit stack: a 1,200-term expression must not raise RecursionError.
    stack = [node]
    while stack:
        current = stack.pop()
        _visit_node(current, data, language, found)
        stack.extend(reversed(current.children))


def discover_raw(source: str, language: str, path: str) -> list[RawSite]:
    """Mutation sites in source order. Offsets are UTF-8 byte offsets."""

    crapper = ensure_crapper()
    data = source.encode("utf-8")
    grammar = _grammar(language, path)
    _data, tree = crapper.languages.treesitter.parse(source, grammar)
    found: list[RawSite] = []
    if language == "clojure":
        _visit_clojure(tree.root_node, data, found)
    else:
        _visit(tree.root_node, data, language, found)
    found.sort(key=lambda site: (site.line, site.start, site.end))
    return found


def apply_site(source: str, start: int, end: int, mutant: str) -> str:
    data = source.encode("utf-8")
    updated = data[:start] + mutant.encode("utf-8") + data[end:]
    return updated.decode("utf-8")
