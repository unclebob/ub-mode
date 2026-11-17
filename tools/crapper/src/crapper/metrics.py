"""Write the crap4clj snapshot uml-viewer reads."""

from pathlib import Path

from crapper.model import Entry


def metrics_path(root: Path) -> Path:
    return root / ".metrics" / "crap.edn"


def _edn_string(value: str) -> str:
    escaped = value.replace("\\", "\\\\").replace('"', '\\"')
    return f'"{escaped}"'


def _edn_number(value: float) -> str:
    text = f"{value:.4f}".rstrip("0").rstrip(".")
    if "." not in text:
        text += ".0"
    return text


def _edn_value(value: float | None) -> str:
    if value is None:
        return "nil"
    return _edn_number(value)


def render_edn(entries: list[Entry]) -> str:
    """EDN map `{:entries [...]}` with crap4clj's keys, in crap4clj's order.

    uml-viewer groups this file by `:namespace` and joins each operation on
    `:name`. Coverage is a percentage. `nil` coverage and CRAP mean `--no-coverage`.
    A function absent from the report is `0.0`, not `nil`.
    """

    rows = []
    for entry in entries:
        rows.append(
            "  {"
            f":name {_edn_string(entry.name)}, "
            f":namespace {_edn_string(entry.namespace)}, "
            f":complexity {entry.complexity}, "
            f":coverage {_edn_value(entry.coverage)}, "
            f":crap {_edn_value(entry.crap)}"
            "}"
        )
    body = "\n".join(rows)
    if body:
        return "{:entries [\n" + body + "\n]}\n"
    return "{:entries []}\n"


def write_metrics(entries: list[Entry], root: Path) -> Path:
    path = metrics_path(root)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(render_edn(entries), encoding="utf-8")
    return path
