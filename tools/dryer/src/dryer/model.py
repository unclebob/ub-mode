from dataclasses import dataclass


@dataclass(frozen=True)
class Span:
    """Where one candidate form sits in a source file."""

    file: str
    start_line: int
    end_line: int


@dataclass(frozen=True)
class Duplicate:
    """One pair of forms whose normalized structure is similar enough to report."""

    score: float
    language: str
    left: Span
    right: Span
    left_nodes: int
    right_nodes: int


@dataclass(frozen=True)
class Entry:
    """A normalized form ready to compare. Forms are only compared within one language."""

    language: str
    file: str
    start_line: int
    end_line: int
    nodes: int
    fingerprints: frozenset[str]
