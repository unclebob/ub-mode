"""Go functions and cyclomatic complexity, following crap4go.

Decision points are `if`, `for`, `range`, each `switch` / type-switch case,
each `select` clause, and `&&` / `||`. Coverage later uses Go's statement
profile. The namespace is the package import path; methods are namespaced by
their receiver type (`import/path.Widget`) and named `Run`, which is the join
uml-viewer makes between a class and its operations.
"""

from pathlib import Path

from crapper.languages.treesitter import (
    absolute_path as _absolute,
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
    "expression_case",
    "type_case",
    "communication_case",
    "default_case",
}


def _is_decision(node) -> bool:
    return node.type in _DECISIONS or binary_logic(node)


def _skip(_node) -> bool:
    return False


def _package_clause(data: bytes, root) -> str:
    for node in descendants(root):
        if node.type == "package_identifier":
            return node_text(data, node)
    return ""


def _type_identifiers(node) -> list[str]:
    found = []
    for item in descendants(node):
        if item.type == "type_identifier":
            found.append(item)
    return found


def _receiver_type(data: bytes, method) -> str | None:
    params = child_of_type(method, "parameter_list")
    if params is None:
        return None
    idents = _type_identifiers(params)
    if not idents:
        return None
    return node_text(data, idents[0])


def _method_name(data: bytes, method) -> str | None:
    ident = child_of_type(method, "field_identifier")
    if ident is None:
        return None
    return node_text(data, ident)


def _function_name(data: bytes, fn) -> str | None:
    ident = child_of_type(fn, "identifier")
    if ident is None:
        return None
    return node_text(data, ident)


def package_namespace(path: str, package_name: str) -> str:
    """Import path of the package, or the package clause when no go.mod exists."""

    current = Path(path).resolve().parent
    module = None
    module_dir = None
    while True:
        candidate = current / "go.mod"
        if candidate.is_file():
            for line in candidate.read_text(encoding="utf-8").splitlines():
                stripped = line.strip()
                if stripped.startswith("module "):
                    module = stripped.split(None, 1)[1].strip()
                    module_dir = current
                    break
            break
        if current.parent == current:
            break
        current = current.parent
    if not module or module_dir is None:
        return package_name
    relative = Path(path).resolve().parent.relative_to(module_dir).as_posix()
    if relative == ".":
        return module
    return f"{module}/{relative}"


def functions_in_source(
    source: str, path: str, project_root: str | None = None
) -> list[Function]:
    data, tree = parse(source, "go")
    package_name = _package_clause(data, tree.root_node)
    package_ns = package_namespace(_absolute(path, project_root), package_name)
    found: list[Function] = []
    for node in descendants(tree.root_node):
        if node.type not in {"function_declaration", "method_declaration"}:
            continue
        if child_of_type(node, "block") is None:
            continue
        if node.type == "method_declaration":
            name = _method_name(data, node)
            receiver = _receiver_type(data, node)
            if not name or not receiver:
                continue
            namespace = f"{package_ns}.{receiver}"
        else:
            name = _function_name(data, node)
            if not name:
                continue
            namespace = package_ns
        found.append(
            Function(
                name=name,
                namespace=namespace,
                complexity=complexity(node, _is_decision, _skip),
                start_line=start_line(node),
                end_line=end_line(node),
                path=path,
                language="go",
            )
        )
    return found
