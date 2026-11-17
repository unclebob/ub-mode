from dataclasses import dataclass


@dataclass(frozen=True)
class Function:
    """One scored unit: a function or method."""

    name: str
    namespace: str
    complexity: int
    start_line: int
    end_line: int
    path: str
    language: str
    jacoco_class: str | None = None
    # UTF-8 byte span of the function. -1 when the language does not record it.
    # Mutator uses the span to give a nested handler the sites inside it.
    start_byte: int = -1
    end_byte: int = -1


@dataclass(frozen=True)
class Entry:
    """One row of the CRAP report and of `.metrics/crap.edn`."""

    name: str
    namespace: str
    complexity: int
    coverage: float | None
    crap: float | None
    path: str
    language: str
