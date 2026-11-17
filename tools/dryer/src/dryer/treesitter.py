"""Shared tree-sitter helpers."""

from functools import lru_cache


@lru_cache(maxsize=None)
def parser_for(language: str):
    from tree_sitter_language_pack import download, get_parser

    download([language])
    return get_parser(language)


def parse(source: str, language: str):
    data = source.encode("utf-8")
    tree = parser_for(language).parse(data)
    return data, tree


def node_text(data: bytes, node) -> str:
    return data[node.start_byte : node.end_byte].decode("utf-8")


def start_line(node) -> int:
    return node.start_point[0] + 1


def end_line(node) -> int:
    row, col = node.end_point
    if col == 0:
        return max(start_line(node), row)
    return row + 1


def descendants(node):
    stack = [node]
    while stack:
        current = stack.pop()
        yield current
        stack.extend(reversed(current.children))


def child_of_type(node, *types: str):
    for child in node.children:
        if child.type in types:
            return child
    return None
