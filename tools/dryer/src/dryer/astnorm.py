"""Normalize a tree-sitter tree the way dry4clj normalizes a Clojure form.

The name in call position stays: `filter`, `Println`, `map`, an operator, the
type constructed by `new`. Local names, field names, type names in argument
position, and literals become generic markers. Two functions that call the
same operations in the same shape therefore share a fingerprint set even when
their locals differ.
"""

from __future__ import annotations

from dryer.shape import K
from dryer.treesitter import node_text

_SKIP = {
    "comment",
    "line_comment",
    "block_comment",
    "documentation_comment",
    "doc_comment",
}

_IDENTIFIERS = {
    "identifier",
    "type_identifier",
    "field_identifier",
    "property_identifier",
    "shorthand_property_identifier",
    "shorthand_property_identifier_pattern",
    "package_identifier",
}

_LITERALS = {
    "string",
    "string_literal",
    "interpreted_string_literal",
    "raw_string_literal",
    "char_literal",
    "rune_literal",
    "character_literal",
    "number",
    "integer",
    "float",
    "decimal_integer_literal",
    "hex_integer_literal",
    "octal_integer_literal",
    "binary_integer_literal",
    "decimal_floating_point_literal",
    "hex_floating_point_literal",
    "int_literal",
    "float_literal",
    "imaginary_literal",
    "integer_literal",
    "true",
    "false",
    "null",
    "nil",
    "none",
    "undefined",
    "null_literal",
    "boolean_literal",
    "template_string",
    "regex",
    "regex_literal",
}

_OPERATORS = {
    "+",
    "-",
    "*",
    "/",
    "%",
    "**",
    "==",
    "!=",
    "<",
    ">",
    "<=",
    ">=",
    "===",
    "!==",
    "&&",
    "||",
    "&",
    "|",
    "^",
    "<<",
    ">>",
    ">>>",
    "+=",
    "-=",
    "*=",
    "/=",
    "%=",
    "&=",
    "|=",
    "^=",
    "<<=",
    ">>=",
    ">>>=",
    "!",
    "~",
    "++",
    "--",
    "and",
    "or",
    "not",
    "??",
    "?.",
    "=",
    ":=",
    "=>",
    "?",
}

_CALLS = {
    "call",
    "call_expression",
    "method_invocation",
    "object_creation_expression",
    "new_expression",
}

_ATTRIBUTES = {
    "attribute",
    "member_expression",
    "selector_expression",
    "field_access",
    "field_expression",
}

_SCOPED = {
    "scoped_identifier",
    "scoped_type_identifier",
}

_ARG_LISTS = {"argument_list", "arguments"}
_TYPE_ARGS = {"type_arguments", "type_parameters"}

_DATA: bytes = b""


def normalize(node, data: bytes):
    """Normalized tree for `node`, or None when the node is only punctuation."""

    global _DATA
    _DATA = data
    return _normalize(node)


def _text(node) -> str:
    return node_text(_DATA, node)


_PENDING = object()


def _is_literal(node) -> bool:
    if node.type not in _LITERALS:
        return False
    if node.type == "string" and any(child.type == "interpolation" for child in node.children):
        return False
    return True


def _named_before_args(node):
    incoming = []
    for child in node.named_children:
        if child.type in _ARG_LISTS:
            return incoming, child
        incoming.append(child)
    return incoming, None


def _without_type_args(nodes):
    type_args = [child for child in nodes if child.type in _TYPE_ARGS]
    rest = [child for child in nodes if child.type not in _TYPE_ARGS]
    return rest, type_args


def _callee_leaf(node):
    if node.type in _IDENTIFIERS:
        return [K("symbol"), _text(node)]
    return _PENDING


def _named_leaf(node, head: bool):
    if node.type in _IDENTIFIERS:
        if head:
            return [K("symbol"), _text(node)]
        return K("symbol")
    if _is_literal(node):
        return K("literal")
    return _PENDING


def _try_leaf(node, mode: str, head: bool):
    if mode == "callee":
        return _callee_leaf(node)
    if mode == "path":
        return _PENDING
    if node.type in _SKIP:
        return None
    if node.type in _OPERATORS:
        return [K("symbol"), node.type]
    if not node.is_named:
        return None
    return _named_leaf(node, head)


def _call_jobs(node):
    incoming, args = _named_before_args(node)
    rest, type_args = _without_type_args(incoming)
    jobs = []
    if rest:
        *receivers, callee = rest
        jobs.extend((child, "norm", False) for child in receivers)
        jobs.append((callee, "callee", False))
    jobs.extend((child, "norm", False) for child in type_args)
    if args is not None:
        jobs.append((args, "norm", False))
    return jobs


def _attr_jobs(node, head: bool):
    named = list(node.named_children)
    if not named:
        return []
    *objects, name = named
    jobs = [(obj, "norm", False) for obj in objects]
    if name.type in _IDENTIFIERS:
        return jobs
    if head:
        jobs.append((name, "callee", False))
    else:
        jobs.append((name, "norm", False))
    return jobs


def _path_jobs(node):
    jobs = []
    for child in node.named_children:
        if child.type in _IDENTIFIERS:
            continue
        if child.type in _SCOPED or child.type == "generic_type":
            jobs.append((child, "path", False))
        else:
            jobs.append((child, "norm", False))
    return jobs


def _callee_jobs(node):
    if node.type in _ATTRIBUTES:
        return _attr_jobs(node, True)
    if node.type in _SCOPED or node.type == "generic_type":
        return _path_jobs(node)
    return [(node, "norm", True)]


def _jobs(node, mode: str, head: bool):
    if mode == "callee":
        return _callee_jobs(node)
    if mode == "path":
        return _path_jobs(node)
    if node.type == "parenthesized_expression" and len(node.named_children) == 1:
        return [(node.named_children[0], "norm", head)]
    if node.type in _CALLS:
        return _call_jobs(node)
    if node.type in _ATTRIBUTES:
        return _attr_jobs(node, head)
    return [(child, "norm", False) for child in node.children]


def _name_value(name, head: bool, cache):
    """Keep a member's spelling when it is the thing being called."""

    if name.type in _IDENTIFIERS:
        if head:
            return [K("symbol"), _text(name)]
        return K("symbol")
    if head:
        return cache[(id(name), "callee", False)]
    return cache[(id(name), "norm", False)]


def _assemble_call(node, cache):
    incoming, args = _named_before_args(node)
    rest, type_args = _without_type_args(incoming)
    out = [K(node.type)]
    if rest:
        *receivers, callee = rest
        for child in receivers:
            norm = cache[(id(child), "norm", False)]
            if norm is not None:
                out.append(norm)
        out.append(cache[(id(callee), "callee", False)])
    for child in type_args:
        norm = cache[(id(child), "norm", False)]
        if norm is not None:
            out.append(norm)
    if args is not None:
        norm = cache[(id(args), "norm", False)]
        if norm is not None:
            out.append(norm)
    return out


def _assemble_attr(node, head: bool, cache):
    named = list(node.named_children)
    if not named:
        return [K(node.type)]
    *objects, name = named
    parts = [K(node.type)]
    for obj in objects:
        norm = cache[(id(obj), "norm", False)]
        if norm is not None:
            parts.append(norm)
    nested = _name_value(name, head, cache)
    if nested is not None:
        parts.append(nested)
    return parts


def _assemble_path(node, cache):
    """A callee path keeps each name in the path. Type arguments stay structural."""

    parts = [K(node.type)]
    for child in node.named_children:
        if child.type in _IDENTIFIERS:
            parts.append([K("symbol"), _text(child)])
        elif child.type in _SCOPED or child.type == "generic_type":
            parts.append(cache[(id(child), "path", False)])
        else:
            norm = cache[(id(child), "norm", False)]
            if norm is not None:
                parts.append(norm)
    return parts


def _callee_value(node, cache):
    if node.type in _ATTRIBUTES:
        return _assemble_attr(node, True, cache)
    if node.type in _SCOPED or node.type == "generic_type":
        return _assemble_path(node, cache)
    return cache[(id(node), "norm", True)]


def _assemble_children(node, cache):
    children = []
    for child in node.children:
        norm = cache[(id(child), "norm", False)]
        if norm is not None:
            children.append(norm)
    return [K(node.type), *children]


def _assemble(node, mode: str, head: bool, cache):
    if mode == "callee":
        return _callee_value(node, cache)
    if mode == "path":
        return _assemble_path(node, cache)
    if node.type == "parenthesized_expression" and len(node.named_children) == 1:
        child = node.named_children[0]
        return cache[(id(child), "norm", head)]
    if node.type in _CALLS:
        return _assemble_call(node, cache)
    if node.type in _ATTRIBUTES:
        return _assemble_attr(node, head, cache)
    return _assemble_children(node, cache)


def _normalize(node, head: bool = False):
    # Post-order. A 1,200-deep expression must not raise RecursionError.
    cache = {}
    stack = [(node, "norm", head, False)]
    while stack:
        current, mode, is_head, expanded = stack.pop()
        key = (id(current), mode, is_head)
        if not expanded:
            if key in cache:
                continue
            leaf = _try_leaf(current, mode, is_head)
            if leaf is not _PENDING:
                cache[key] = leaf
                continue
            stack.append((current, mode, is_head, True))
            for child, child_mode, child_head in reversed(_jobs(current, mode, is_head)):
                stack.append((child, child_mode, child_head, False))
            continue
        cache[key] = _assemble(current, mode, is_head, cache)
    return cache[(id(node), "norm", head)]
