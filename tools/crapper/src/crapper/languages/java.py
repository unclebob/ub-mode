"""Java methods and cyclomatic complexity, following crap4java.

Constructors, abstract methods, and methods inside anonymous or local classes
are ignored. Nested member types become their own namespace (`pkg.Outer.Inner`)
so uml-viewer can attach scores to that class. JaCoCo's binary name
(`pkg.Outer$Inner`) is kept for coverage lookup.
"""

import re

from crapper.languages.treesitter import (
    binary_logic,
    child_of_type,
    complexity,
    end_line,
    node_text,
    parse,
    start_line,
)
from crapper.model import Function

_PACKAGE = re.compile(r"(?m)^\s*package\s+([a-zA-Z_][\w.]*)\s*;")
_NESTED_TYPES = {
    "class_declaration",
    "interface_declaration",
    "enum_declaration",
    "record_declaration",
    "annotation_type_declaration",
}
_DECISIONS = {
    "if_statement",
    "for_statement",
    "enhanced_for_statement",
    "while_statement",
    "do_statement",
    "catch_clause",
    "ternary_expression",
    "switch_label",
}


def _is_decision(node) -> bool:
    return node.type in _DECISIONS or binary_logic(node)


def _skip(node) -> bool:
    return node.type in _NESTED_TYPES or node.type == "class_body"


def _inside_executable(node) -> bool:
    current = node.parent
    while current is not None:
        if current.type in {
            "method_declaration",
            "constructor_declaration",
            "lambda_expression",
        }:
            return True
        current = current.parent
    return False


def _package_name(source: str) -> str | None:
    match = _PACKAGE.search(source)
    return match.group(1) if match else None


def _type_chain(data: bytes, node) -> list[str]:
    names: list[str] = []
    current = node.parent
    while current is not None:
        if current.type in _NESTED_TYPES:
            ident = child_of_type(current, "identifier")
            if ident is not None:
                names.append(node_text(data, ident))
        current = current.parent
    names.reverse()
    return names


def _qualify(package: str | None, names: list[str], nested: str) -> str:
    body = nested.join(names)
    if package:
        return f"{package}.{body}" if body else package
    return body


def functions_in_source(
    source: str, path: str, project_root: str | None = None
) -> list[Function]:
    del project_root
    data, tree = parse(source, "java")
    package = _package_name(source)
    found: list[Function] = []
    stack = [tree.root_node]
    while stack:
        node = stack.pop()
        if node.type == "method_declaration" and not _inside_executable(node):
            if child_of_type(node, "block") is not None:
                ident = child_of_type(node, "identifier")
                names = _type_chain(data, node)
                if ident is not None and names:
                    found.append(
                        Function(
                            name=node_text(data, ident),
                            namespace=_qualify(package, names, "."),
                            complexity=complexity(node, _is_decision, _skip),
                            start_line=start_line(node),
                            end_line=end_line(node),
                            path=path,
                            language="java",
                            jacoco_class=_qualify(package, names, "$"),
                        )
                    )
            continue
        stack.extend(reversed(node.children))
    return found
