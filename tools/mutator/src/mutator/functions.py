"""Bind mutation sites to the functions crapper reports."""

from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path

from mutator.crapper_link import ensure_crapper
from mutator.model import Site
from mutator.sites import discover_raw


def _lines(source: str) -> list[str]:
    return source.splitlines()


def _slice(source: str, start: int, end: int) -> str:
    lines = _lines(source)
    return "\n".join(lines[start - 1 : end])


def digest(source: str, start: int, end: int) -> str:
    chunk = "\n".join(line.rstrip() for line in _lines(source)[start - 1 : end])
    return hashlib.sha256(chunk.encode("utf-8")).hexdigest()


def _header(source: str, start: int, end: int) -> str:
    text = _slice(source, start, min(end, start + 6)).lstrip()
    while text.startswith("#["):
        close = text.find("]")
        if close < 0:
            break
        text = text[close + 1 :].lstrip()
    return text


def _python_private(name: str) -> bool:
    if name.startswith("__") and name.endswith("__"):
        return False
    return name.startswith("_")


def _typescript_private(name: str, header: str) -> bool:
    return name.startswith("#") or bool(re.search(r"\bprivate\b", header))


def is_private(language: str, name: str, source: str, start: int, end: int) -> bool:
    """True when uml-viewer should treat the operation as private."""

    header = _header(source, start, end)
    if language == "clojure":
        return bool(re.match(r"\(defn-", header))
    if language == "java":
        return bool(re.search(r"\bprivate\b", header))
    if language == "go":
        return name[:1].islower()
    if language == "python":
        return _python_private(name)
    if language == "rust":
        return re.match(r"pub\s", header) is None
    if language == "typescript":
        return _typescript_private(name, header)
    return False


def form_id(name: str, private: bool) -> str:
    """The id uml-viewer splits into an operation name.

    `defn/place` is public. `defn-/hide` is private. The viewer checks the
    private prefix first.
    """

    prefix = "defn-" if private else "defn"
    return f"{prefix}/{name}"


def mutation_id(
    file: str, namespace: str, form: str, start: int, end: int, original: str, mutant: str
) -> str:
    return json.dumps(
        [file, namespace, form, start, end, original, mutant], separators=(",", ":")
    )


def _mutation_field(mutation: str, index: int) -> str | None:
    try:
        value = json.loads(mutation)
    except json.JSONDecodeError:
        return None
    if isinstance(value, list) and len(value) > index and isinstance(value[index], str):
        return value[index]
    return None


def mutation_file(mutation: str) -> str | None:
    return _mutation_field(mutation, 0)


def mutation_namespace(mutation: str) -> str | None:
    return _mutation_field(mutation, 1)


def project_functions(source: str, path: Path, root: Path):
    crapper = ensure_crapper()
    language = crapper.discover.language_of(path)
    if language is None:
        return []
    return crapper.languages.functions_in_file(
        language,
        source,
        path.resolve().as_posix(),
        root.resolve().as_posix(),
    )


def _contains(fn, line: int, byte: int | None) -> bool:
    if fn.start_line > line or fn.end_line < line:
        return False
    start = getattr(fn, "start_byte", -1)
    end = getattr(fn, "end_byte", -1)
    if byte is None or start < 0 or end < 0:
        return True
    return start <= byte < end


def _tightness(fn) -> int:
    start = getattr(fn, "start_byte", -1)
    end = getattr(fn, "end_byte", -1)
    if start >= 0 and end >= start:
        return end - start
    return (fn.end_line - fn.start_line) * 1_000_000


def _owner(functions, line: int, byte: int | None = None):
    matches = [fn for fn in functions if _contains(fn, line, byte)]
    if not matches:
        return None
    return min(matches, key=lambda fn: (_tightness(fn), fn.start_line))


def sites_in_file(source: str, path: Path, root: Path, file_key: str) -> list[Site]:
    crapper = ensure_crapper()
    language = crapper.discover.language_of(path)
    if language is None:
        return []
    functions = project_functions(source, path, root)
    privacy = {
        id(fn): is_private(language, fn.name, source, fn.start_line, fn.end_line) for fn in functions
    }
    found: list[Site] = []
    for raw in discover_raw(source, language, path.as_posix()):
        owner = _owner(functions, raw.line, raw.start)
        if owner is None:
            continue
        private = privacy[id(owner)]
        form = form_id(owner.name, private)
        found.append(
            Site(
                file=file_key,
                namespace=owner.namespace,
                form_id=form,
                name=owner.name,
                line=raw.line,
                start=raw.start,
                end=raw.end,
                original=raw.original,
                mutant=raw.mutant,
                category=raw.category,
                mutation_id=mutation_id(
                    file_key, owner.namespace, form, raw.start, raw.end, raw.original, raw.mutant
                ),
                anchor_line=raw.anchor_line,
            )
        )
    return found


def form_digests(source: str, path: Path, root: Path) -> dict[tuple[str, str], str]:
    """Map `(namespace, form id)` to a hash of the function text."""

    crapper = ensure_crapper()
    language = crapper.discover.language_of(path)
    if language is None:
        return {}
    grouped: dict[tuple[str, str], list] = {}
    for fn in project_functions(source, path, root):
        private = is_private(language, fn.name, source, fn.start_line, fn.end_line)
        key = (fn.namespace, form_id(fn.name, private))
        grouped.setdefault(key, []).append(fn)
    digests = {}
    for key, fns in grouped.items():
        ordered = sorted(fns, key=lambda fn: (fn.start_line, fn.end_line))
        parts = [digest(source, fn.start_line, fn.end_line) for fn in ordered]
        digests[key] = hashlib.sha256("".join(parts).encode("utf-8")).hexdigest()
    return digests


def form_spans(source: str, path: Path, root: Path) -> dict[tuple[str, str], tuple[int, int, str, bool]]:
    """`(namespace, form id)` -> start line, end line, name, private."""

    crapper = ensure_crapper()
    language = crapper.discover.language_of(path)
    spans = {}
    for fn in project_functions(source, path, root):
        private = is_private(language or "", fn.name, source, fn.start_line, fn.end_line)
        key = (fn.namespace, form_id(fn.name, private))
        if key not in spans:
            spans[key] = (fn.start_line, fn.end_line, fn.name, private)
            continue
        start, end, name, private_flag = spans[key]
        spans[key] = (min(start, fn.start_line), max(end, fn.end_line), name, private_flag)
    return spans
