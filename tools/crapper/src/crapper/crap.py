"""CRAP formula shared by crap4clj, crap4java, and crap4go."""

from crapper.model import Entry, Function


def crap_score(complexity: int, coverage_pct: float | None) -> float | None:
    """CRAP = CC² × (1 − coverage)³ + CC.

    `coverage_pct` is a percentage from 0 to 100. Missing coverage yields no
    score, which the report shows as N/A.
    """

    if coverage_pct is None:
        return None
    cc = float(complexity)
    uncovered = 1.0 - (float(coverage_pct) / 100.0)
    return (cc * cc * uncovered * uncovered * uncovered) + cc


def round_metric(value: float | None) -> float | None:
    if value is None:
        return None
    return round(float(value), 4)


def make_entry(function: Function, coverage: float | None) -> Entry:
    coverage = round_metric(coverage)
    return Entry(
        name=function.name,
        namespace=function.namespace,
        complexity=function.complexity,
        coverage=coverage,
        crap=round_metric(crap_score(function.complexity, coverage)),
        path=function.path,
        language=function.language,
    )


def sort_entries(entries: list[Entry]) -> list[Entry]:
    """Worst CRAP first. Unscored rows follow scored rows."""

    def key(entry: Entry) -> tuple:
        if entry.crap is None:
            return (1, -entry.complexity, entry.namespace, entry.name, entry.path)
        return (0, -entry.crap, entry.namespace, entry.name, entry.path)

    return sorted(entries, key=key)
