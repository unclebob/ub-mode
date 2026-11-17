"""Python functions, methods, and cyclomatic complexity.

Decision points follow the same structural rule as the other languages:
`if`, `elif`, `for`, `while`, `except`, each `match` case, a comprehension
filter, a conditional expression, and each `and` / `or`. Module-level
functions and class methods are entries. A nested function stays inside the
enclosing function. A class method's namespace is `module.Class`.
"""

from crapper.languages.treesitter import (
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
    "elif_clause",
    "for_statement",
    "while_statement",
    "except_clause",
    "conditional_expression",
    "case_clause",
    "if_clause",
}


def _is_decision(node) -> bool:
    if node.type in _DECISIONS:
        return True
    return node.type == "boolean_operator" and any(
        child.type in {"and", "or"} for child in node.children
    )


def _skip(node) -> bool:
    return node.type == "class_definition"


def _strip_root(relative: str, source_root: str | None) -> str:
    root = (source_root or "").replace("\\", "/").rstrip("/")
    if root and root != "." and relative.startswith(root + "/"):
        return relative[len(root) + 1 :]
    if relative.startswith("./"):
        return relative[2:]
    return relative


def _strip_src(relative: str) -> str:
    if relative.startswith("src/"):
        return relative[4:]
    if "/src/" in relative:
        return relative.split("/src/", 1)[1]
    return relative


def _strip_module_suffix(relative: str) -> str:
    if relative.endswith(".py"):
        relative = relative[: -len(".py")]
    if relative.endswith("/__init__"):
        return relative[: -len("/__init__")]
    if relative == "__init__":
        return ""
    return relative


def _module_namespace(path: str, source_root: str | None) -> str:
    relative = _strip_module_suffix(_strip_src(_strip_root(path.replace("\\", "/"), source_root)))
    if not relative:
        return "__init__"
    return relative.replace("/", ".")


def _is_nested_function(node) -> bool:
    """A function inside another function, unless it is a method of a class."""

    current = node.parent
    while current is not None:
        if current.type == "class_definition":
            return False
        if current.type == "function_definition":
            return True
        current = current.parent
    return False


def _class_names(data: bytes, node) -> list[str]:
    names: list[str] = []
    current = node.parent
    while current is not None:
        if current.type == "class_definition":
            ident = child_of_type(current, "identifier")
            if ident is not None:
                names.append(node_text(data, ident))
        current = current.parent
    names.reverse()
    return names


def functions_in_source(
    source: str, path: str, source_root: str | None = None
) -> list[Function]:
    data, tree = parse(source, "python")
    module = _module_namespace(path, source_root)
    found: list[Function] = []
    for node in descendants(tree.root_node):
        if node.type != "function_definition" or _is_nested_function(node):
            continue
        ident = child_of_type(node, "identifier")
        if ident is None:
            continue
        classes = _class_names(data, node)
        if classes:
            namespace = ".".join([module, *classes]) if module else ".".join(classes)
        else:
            namespace = module
        found.append(
            Function(
                name=node_text(data, ident),
                namespace=namespace,
                complexity=complexity(node, _is_decision, _skip),
                start_line=start_line(node),
                end_line=end_line(node),
                path=path,
                language="python",
            )
        )
    return found
