"""Rust functions and cyclomatic complexity.

Decision points are `if` / `if let`, `for`, `while` / `while let`, `loop`,
each `match` arm, `?`, and `&&` / `||`. Free functions are namespaced by the
module path (`crate::foo::bar`). Methods are namespaced by the self type
(`crate::foo::Widget`) so uml-viewer can join them to that type. Bodies inside
`mod tests` are test code and are not scored.
"""

import re
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
    "if_expression",
    "for_expression",
    "while_expression",
    "loop_expression",
    "match_arm",
    "try_expression",
}
_PACKAGE_BLOCK = re.compile(r"(?ms)^\[package\](.*?)(?:^\[|\Z)")
_NAME = re.compile(r'(?m)^name\s*=\s*"([^"]+)"')


def _is_decision(node) -> bool:
    return node.type in _DECISIONS or binary_logic(node)


def _skip(_node) -> bool:
    return False


def _has_block(node) -> bool:
    return child_of_type(node, "block") is not None


def _ancestor_mods(data: bytes, node) -> list[str]:
    names: list[str] = []
    current = node.parent
    while current is not None:
        if current.type == "mod_item":
            ident = child_of_type(current, "identifier")
            if ident is not None:
                names.append(node_text(data, ident))
        current = current.parent
    names.reverse()
    return names


def _in_mod_named(data: bytes, node, name: str) -> bool:
    return name in _ancestor_mods(data, node)


def _first_type_name(data: bytes, node) -> str | None:
    if node.type == "type_identifier":
        return node_text(data, node)
    for item in descendants(node):
        if item.type == "type_identifier":
            return node_text(data, item)
    return None


def _impl_type(data: bytes, impl) -> str | None:
    saw_for = False
    chosen = None
    for child in impl.children:
        if child.type == "for":
            saw_for = True
            chosen = None
            continue
        if child.type in {"type_identifier", "generic_type", "scoped_type_identifier"}:
            chosen = _first_type_name(data, child)
            if saw_for:
                return chosen
    return chosen


def _crate_name(path: str) -> tuple[str, Path | None]:
    current = Path(path).resolve().parent
    while True:
        cargo = current / "Cargo.toml"
        if cargo.is_file():
            text = cargo.read_text(encoding="utf-8")
            block = _PACKAGE_BLOCK.search(text)
            if block:
                match = _NAME.search(block.group(1))
                name = match.group(1) if match else current.name
                return name.replace("-", "_"), current
        if current.parent == current:
            break
        current = current.parent
    return "crate", None


def _rs_parts(relative: str) -> list[str]:
    parts = relative.split("/")
    if parts[-1] == "mod.rs":
        return parts[:-1]
    if parts[-1].endswith(".rs"):
        parts[-1] = parts[-1][:-3]
    return parts


def _with_crate(crate_name: str, parts: list[str]) -> list[str]:
    if parts[:1] == ["bin"] and len(parts) >= 2:
        return parts[1:]
    return [crate_name, *parts]


def _file_modules(path: str, crate_name: str, crate_root: Path | None) -> list[str]:
    file_path = Path(path).resolve()
    if crate_root is None:
        if file_path.stem in {"lib", "main"}:
            return [crate_name]
        return [crate_name, file_path.stem]
    try:
        relative = file_path.relative_to(crate_root).as_posix()
    except ValueError:
        return [crate_name]
    if relative.startswith("src/"):
        relative = relative[4:]
    if relative in {"lib.rs", "main.rs"}:
        return [crate_name]
    return _with_crate(crate_name, _rs_parts(relative))


def _namespace(modules: list[str], extra: list[str], type_name: str | None) -> str:
    parts = [*modules, *extra]
    if type_name:
        parts.append(type_name)
    return "::".join(part for part in parts if part)


def _impl_owner(data: bytes, node):
    parent = node.parent
    if parent is None or parent.type != "declaration_list":
        return None
    owner = parent.parent
    if owner is None or owner.type != "impl_item":
        return None
    return _impl_type(data, owner)


def _record_function(data: bytes, node, modules: list[str], path: str) -> Function | None:
    if node.type != "function_item" or not _has_block(node):
        return None
    if node.parent is not None and node.parent.type == "block":
        return None
    if _in_mod_named(data, node, "tests"):
        return None
    ident = child_of_type(node, "identifier")
    if ident is None:
        return None
    return Function(
        name=node_text(data, ident),
        namespace=_namespace(modules, _ancestor_mods(data, node), _impl_owner(data, node)),
        complexity=complexity(node, _is_decision, _skip),
        start_line=start_line(node),
        end_line=end_line(node),
        path=path,
        language="rust",
    )


def functions_in_source(
    source: str, path: str, project_root: str | None = None
) -> list[Function]:
    data, tree = parse(source, "rust")
    located = _absolute(path, project_root)
    crate_name, crate_root = _crate_name(located)
    modules = _file_modules(located, crate_name, crate_root)
    found: list[Function] = []
    for node in descendants(tree.root_node):
        recorded = _record_function(data, node, modules, path)
        if recorded is not None:
            found.append(recorded)
    return found
