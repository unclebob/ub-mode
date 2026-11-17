"""Pick the forms each language compares.

Clojure compares every top-level list except `ns`, matching dry4clj. The other
languages compare the functions and methods crapper scores: bodies, not
signatures alone, and not callbacks nested inside another function. Rust
functions inside `mod tests` are test code and are skipped.
"""

from __future__ import annotations

from dryer.astnorm import normalize
from dryer.clojure_read import is_candidate_form, max_line, normalize as normalize_clj
from dryer.clojure_read import read_source
from dryer.model import Entry
from dryer.shape import fingerprints, node_count
from dryer.treesitter import child_of_type, descendants, end_line, node_text, parse, start_line

_TS_FUNCTION = {
    "function_declaration",
    "method_definition",
    "arrow_function",
    "function_expression",
}
_DEFINITION_PARENTS = {
    "variable_declarator",
    "public_field_definition",
    "property_definition",
}


def _grammar(path: str) -> str:
    if path.endswith(".tsx"):
        return "tsx"
    return "typescript"


def _has(node, *types: str) -> bool:
    return child_of_type(node, *types) is not None


def _inside(node, types: set[str]) -> bool:
    current = node.parent
    while current is not None:
        if current.type in types:
            return True
        current = current.parent
    return False


def _python_nested(node) -> bool:
    """A function inside another function. A class in between keeps the method."""

    current = node.parent
    while current is not None:
        if current.type == "class_definition":
            return False
        if current.type == "function_definition":
            return True
        current = current.parent
    return False


def _rust_in_tests(data: bytes, node) -> bool:
    current = node.parent
    while current is not None:
        if current.type == "mod_item":
            ident = child_of_type(current, "identifier")
            if ident is not None and node_text(data, ident) == "tests":
                return True
        current = current.parent
    return False


def _java_nodes(data: bytes, root) -> list:
    found = []
    stack = [root]
    while stack:
        node = stack.pop()
        if node.type == "method_declaration":
            nested = _inside(
                node,
                {"method_declaration", "constructor_declaration", "lambda_expression"},
            )
            if _has(node, "block") and not nested:
                found.append(node)
            continue
        stack.extend(reversed(node.children))
    return found


def _go_nodes(data: bytes, root) -> list:
    found = []
    for node in descendants(root):
        if node.type in {"function_declaration", "method_declaration"} and _has(node, "block"):
            found.append(node)
    return found


def _python_nodes(data: bytes, root) -> list:
    found = []
    for node in descendants(root):
        if node.type == "function_definition" and not _python_nested(node):
            found.append(node)
    return found


def _keep_typescript_function(node) -> bool:
    return _has(node, "statement_block") and not _inside(node, _TS_FUNCTION)


def _typescript_parent(node):
    parent = node.parent
    if parent is None or parent.type not in _DEFINITION_PARENTS:
        return None
    if _inside(parent, _TS_FUNCTION):
        return None
    return parent


def _typescript_nodes(data: bytes, root) -> list:
    found = []
    for node in descendants(root):
        if node.type in {"function_declaration", "method_definition"}:
            if _keep_typescript_function(node):
                found.append(node)
        elif node.type in {"arrow_function", "function_expression"}:
            parent = _typescript_parent(node)
            if parent is not None:
                found.append(parent)
    return found


def _rust_nodes(data: bytes, root) -> list:
    found = []
    for node in descendants(root):
        if node.type != "function_item" or not _has(node, "block"):
            continue
        if node.parent is not None and node.parent.type == "block":
            continue
        if _rust_in_tests(data, node):
            continue
        found.append(node)
    return found


_PICKERS = {
    "java": ("java", _java_nodes),
    "go": ("go", _go_nodes),
    "python": ("python", _python_nodes),
    "typescript": (_grammar, _typescript_nodes),
    "rust": ("rust", _rust_nodes),
}


def _tree_entries(language: str, source: str, path: str, file: str) -> list[Entry]:
    grammar_or_name, pick = _PICKERS[language]
    grammar = grammar_or_name(path) if callable(grammar_or_name) else grammar_or_name
    data, tree = parse(source, grammar)
    entries = []
    for node in pick(data, tree.root_node):
        normalized = normalize(node, data)
        if normalized is None:
            continue
        entries.append(
            Entry(
                language=language,
                file=file,
                start_line=start_line(node),
                end_line=end_line(node),
                nodes=node_count(normalized),
                fingerprints=fingerprints(normalized),
            )
        )
    return entries


def _clojure_entries(source: str, file: str) -> tuple[list[Entry], str | None]:
    forms, warning = read_source(source)
    entries = []
    for form in forms:
        if not is_candidate_form(form):
            continue
        normalized = normalize_clj(form)
        entries.append(
            Entry(
                language="clojure",
                file=file,
                start_line=form.line,
                end_line=max_line(form),
                nodes=node_count(normalized),
                fingerprints=fingerprints(normalized),
            )
        )
    return entries, warning


def entries_in_source(language: str, source: str, path: str, file: str) -> tuple[list[Entry], str | None]:
    if language == "clojure":
        return _clojure_entries(source, file)
    if language not in _PICKERS:
        return [], None
    return _tree_entries(language, source, path, file), None
