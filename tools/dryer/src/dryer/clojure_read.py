"""Read Clojure source into forms and normalize them the way dry4clj does.

Names in argument position, keywords, and literals become generic markers.
The symbol at the head of a list keeps its spelling, so `filter` and `map`
stay distinct while the locals passed to them do not.

Reader conditionals keep the `:clj` branch, which is what dry4clj selects.
Syntax-quote is kept as a `syntax-quote` list instead of being expanded into
`seq` and `concat` forms.
"""

from __future__ import annotations

import re

from dryer.shape import K

_WHITESPACE = set(" \t\n\r,")
_TERMINATORS = set("()[]{}\";'@^`~\\,#:")
_COLLECTIONS = {"(": (")", "list"), "[": ("]", "vector"), "{": ("}", "map")}
_WRAPPERS = {"'": "quote", "@": "deref", "`": "syntax-quote"}
_ATOMS = set("\"\\:")
_NUMBER = re.compile(
    r"^[+-]?(?:"
    r"\d+/\d+"
    r"|\d+\.\d+(?:[eE][+-]?\d+)?"
    r"|\.\d+(?:[eE][+-]?\d+)?"
    r"|\d+(?:[eE][+-]?\d+)?"
    r"|0[xX][0-9A-Fa-f]+"
    r")[MN]?$"
)


class ReadError(Exception):
    def __init__(self, line: int, message: str):
        super().__init__(message)
        self.line = line


class Sym:
    def __init__(self, name: str, line: int):
        self.name = name
        self.line = line


class Kw:
    def __init__(self, name: str, line: int):
        self.name = name
        self.line = line


class Lit:
    def __init__(self, line: int):
        self.line = line


class Coll:
    def __init__(self, kind: str, line: int, items=None, pairs=None):
        self.kind = kind
        self.line = line
        self.items = [] if items is None else items
        self.pairs = [] if pairs is None else pairs


class Splice:
    def __init__(self, items: list):
        self.items = items


_NEED = object()


def _close_coll(kind: str, line: int, items: list, fn_star: bool):
    if fn_star:
        return Coll("list", line, items=[Sym("fn*", line), *items])
    if kind == "map":
        if len(items) % 2:
            raise ReadError(line, "map literal has an odd number of forms")
        pairs = [(items[index], items[index + 1]) for index in range(0, len(items), 2)]
        return Coll("map", line, pairs=pairs)
    return Coll(kind, line, items=list(items))


class Reader:
    def __init__(self, text: str):
        self.text = text
        self.n = len(text)
        self.i = 0
        self.line = 1

    def peek(self) -> str | None:
        if self.i >= self.n:
            return None
        return self.text[self.i]

    def eof(self) -> bool:
        return self.i >= self.n

    def get(self) -> str:
        ch = self.text[self.i]
        self.i += 1
        if ch == "\n":
            self.line += 1
        return ch

    def skip_ws(self) -> None:
        while not self.eof():
            ch = self.peek()
            if ch in _WHITESPACE:
                self.get()
                continue
            if ch == ";":
                while not self.eof() and self.peek() != "\n":
                    self.get()
                continue
            break

    def read_token(self) -> str:
        start = self.i
        line = self.line
        while not self.eof():
            ch = self.peek()
            if ch in _WHITESPACE or ch in _TERMINATORS:
                break
            self.get()
        if self.i == start:
            raise ReadError(line, "expected a symbol")
        return self.text[start:self.i]

    def read_string(self) -> Lit:
        line = self.line
        self.get()
        while not self.eof():
            ch = self.get()
            if ch == "\\":
                if self.eof():
                    break
                self.get()
                continue
            if ch == '"':
                return Lit(line)
        raise ReadError(line, "unterminated string")

    def read_char(self) -> Lit:
        line = self.line
        self.get()
        if self.eof():
            raise ReadError(line, "incomplete character")
        self.get()
        while not self.eof():
            ch = self.peek()
            if ch in _WHITESPACE or ch in _TERMINATORS:
                break
            self.get()
        return Lit(line)

    def read_keyword(self) -> Kw:
        line = self.line
        self.get()
        if self.peek() == ":":
            self.get()
        if self.eof() or self.peek() in _WHITESPACE or self.peek() in _TERMINATORS:
            return Kw("", line)
        return Kw(self.read_token(), line)

    def read_form(self):
        """One form. None and Splice are real results.

        An explicit stack: a 1,200-deep form must not raise RecursionError.
        """

        stack: list[tuple] = []
        produced = _NEED
        while True:
            if produced is _NEED:
                produced = self._next(stack)
                continue
            if not stack:
                return produced
            produced = self._deliver(stack, produced)

    def _next(self, stack: list[tuple]):
        self.skip_ws()
        if stack and stack[-1][0] == "coll":
            _tag, end, kind, line, items, fn_star = stack[-1]
            if self.eof():
                raise ReadError(line, f"unterminated {kind}")
            if self.peek() == end:
                self.get()
                stack.pop()
                return _close_coll(kind, line, items, fn_star)
        if self.eof():
            raise ReadError(self.line, "unexpected end of file")
        return self._open(stack)

    def _deliver_coll(self, frame, produced):
        items = frame[4]
        if produced is None:
            return _NEED
        if isinstance(produced, Splice):
            items.extend(produced.items)
        else:
            items.append(produced)
        return _NEED

    def _deliver_wrap(self, frame, produced):
        _tag, name, line = frame
        items = [Sym(name, line)]
        if produced is not None and not isinstance(produced, Splice):
            items.append(produced)
        return Coll("list", line, items=items)

    def _deliver(self, stack: list[tuple], produced):
        frame = stack[-1]
        tag = frame[0]
        if tag == "coll":
            return self._deliver_coll(frame, produced)
        stack.pop()
        if tag == "wrap":
            return self._deliver_wrap(frame, produced)
        if tag == "discard":
            return None
        if tag == "meta":
            return _NEED
        if tag == "lit":
            return Lit(frame[1])
        if tag == "cond":
            return self._finish_cond(frame[1], produced)
        raise AssertionError(tag)

    def _finish_cond(self, splicing: bool, produced):
        if not isinstance(produced, Coll) or produced.kind != "list":
            raise ReadError(self.line, "reader conditional body must be a list")
        chosen = _chosen_branch(produced.items)
        if chosen is None:
            return None
        if not splicing:
            return chosen
        spliced = _spliced(chosen)
        if spliced is None:
            raise ReadError(self.line, "splicing reader conditional needs a collection")
        return spliced

    def _open_collection(self, stack: list[tuple], ch: str, line: int):
        self.get()
        end, kind = _COLLECTIONS[ch]
        stack.append(("coll", end, kind, line, [], False))
        return _NEED

    def _open_atom(self, ch: str):
        if ch == '"':
            return self.read_string()
        if ch == "\\":
            return self.read_char()
        return self.read_keyword()

    def _open_unquote(self, stack: list[tuple], line: int):
        name = "unquote"
        if self.peek() == "@":
            self.get()
            name = "unquote-splicing"
        stack.append(("wrap", name, line))
        return _NEED

    def _open_wrapper(self, stack: list[tuple], ch: str, line: int):
        self.get()
        if ch in _WRAPPERS:
            stack.append(("wrap", _WRAPPERS[ch], line))
            return _NEED
        if ch == "~":
            return self._open_unquote(stack, line)
        stack.append(("meta",))
        return _NEED

    def _open_token(self, line: int):
        token = self.read_token()
        if _NUMBER.match(token):
            return Lit(line)
        return Sym(token, line)

    def _open(self, stack: list[tuple]):
        ch = self.peek()
        line = self.line
        if ch in _COLLECTIONS:
            return self._open_collection(stack, ch, line)
        if ch in _ATOMS:
            return self._open_atom(ch)
        if ch in _WRAPPERS or ch in "~^":
            return self._open_wrapper(stack, ch, line)
        if ch == "#":
            return self._dispatch(stack, line)
        return self._open_token(line)

    def _dispatch_discard(self, stack: list[tuple], _line: int):
        self.get()
        stack.append(("discard",))
        return _NEED

    def _dispatch_set(self, stack: list[tuple], _line: int):
        brace = self.line
        self.get()
        stack.append(("coll", "}", "set", brace, [], False))
        return _NEED

    def _dispatch_fn(self, stack: list[tuple], line: int):
        self.get()
        stack.append(("coll", ")", "list", line, [], True))
        return _NEED

    def _dispatch_string(self, _stack: list[tuple], line: int):
        self.read_string()
        return Lit(line)

    def _dispatch_cond(self, stack: list[tuple], _line: int):
        self.get()
        splicing = self.peek() == "@"
        if splicing:
            self.get()
        stack.append(("cond", splicing))
        return _NEED

    def _read_map_qualifier(self) -> None:
        peeked = self.peek()
        if peeked == ":":
            self.get()
            return
        if peeked is None or peeked == "{" or peeked in _WHITESPACE:
            return
        self.read_token()

    def _dispatch_map(self, _stack: list[tuple], _line: int):
        self.get()
        self._read_map_qualifier()
        return _NEED

    def _dispatch_extension(self, stack: list[tuple], line: int):
        self.read_token()
        stack.append(("lit", line))
        return _NEED

    def _dispatch(self, stack: list[tuple], line: int):
        self.get()
        ch = self.peek()
        if ch is None:
            raise ReadError(line, "incomplete dispatch")
        handler = _DISPATCH.get(ch)
        if handler is None:
            return self._dispatch_extension(stack, line)
        return handler(self, stack, line)


_DISPATCH = {
    "_": Reader._dispatch_discard,
    "{": Reader._dispatch_set,
    "(": Reader._dispatch_fn,
    '"': Reader._dispatch_string,
    "?": Reader._dispatch_cond,
    ":": Reader._dispatch_map,
}


def _branch_name(feature):
    if isinstance(feature, Kw):
        return feature.name
    return None


def _chosen_branch(items):
    index = 0
    while index + 1 < len(items):
        expr = items[index + 1]
        if _branch_name(items[index]) in {"clj", "default"}:
            return expr
        index += 2
    return None


def _spliced(chosen):
    if isinstance(chosen, Coll) and chosen.kind in {"list", "vector"}:
        return Splice(list(chosen.items))
    return None


def _strip_bom(text: str) -> str:
    if text.startswith("\ufeff"):
        return text[1:]
    return text


def _skip_shebang(reader: Reader, text: str) -> None:
    if not text.startswith("#!"):
        return
    while reader.peek() not in (None, "\n"):
        reader.get()


def read_source(text: str) -> tuple[list, str | None]:
    """Top-level forms, plus a warning if a later form could not be read."""

    text = _strip_bom(text)
    reader = Reader(text)
    _skip_shebang(reader, text)
    forms: list = []
    while True:
        reader.skip_ws()
        if reader.eof():
            return forms, None
        try:
            form = reader.read_form()
        except ReadError as exc:
            return forms, f"{exc.line}: {exc}"
        if form is None or isinstance(form, Splice):
            continue
        forms.append(form)


def _children(form):
    if isinstance(form, Coll):
        if form.kind == "map":
            for key, value in form.pairs:
                yield key
                yield value
        else:
            yield from form.items


def max_line(form) -> int:
    best = getattr(form, "line", 1) or 1
    stack = [form]
    while stack:
        current = stack.pop()
        if current is None:
            continue
        best = max(best, getattr(current, "line", 1) or 1)
        if isinstance(current, Coll):
            stack.extend(_children(current))
    return best


def _normalize_atom(form, head: bool):
    if isinstance(form, Sym):
        if head:
            return [K("symbol"), form.name]
        return K("symbol")
    if isinstance(form, Kw):
        return K("keyword")
    return K("literal")


def _norm_children(form) -> list[tuple[object, bool]]:
    if form.kind == "list":
        if not form.items:
            return []
        head_form, *args = form.items
        return [(head_form, True), *[(arg, False) for arg in args]]
    if form.kind in {"vector", "set"}:
        return [(item, False) for item in form.items]
    if form.kind == "map":
        children = []
        for key, value in form.pairs:
            children.append((key, False))
            children.append((value, False))
        return children
    return []


def _assemble_form(form, cache):
    if form.kind == "list":
        if not form.items:
            return [K("list"), K("literal")]
        head_form, *args = form.items
        return [
            K("list"),
            cache[(id(head_form), True)],
            *[cache[(id(arg), False)] for arg in args],
        ]
    if form.kind == "vector":
        return [K("vector"), *[cache[(id(item), False)] for item in form.items]]
    if form.kind == "set":
        return [K("set"), *[cache[(id(item), False)] for item in form.items]]
    if form.kind == "map":
        pairs = [
            [cache[(id(key), False)], cache[(id(value), False)]] for key, value in form.pairs
        ]
        return [K("map"), *pairs]
    return [K("literal")]


def normalize(form, head: bool = False):
    """dry4clj's `normalize-form`. Collection heads are normalized in full."""

    if not isinstance(form, Coll):
        return _normalize_atom(form, head)
    cache = {}
    stack: list[tuple] = [(form, head, False)]
    while stack:
        current, is_head, expanded = stack.pop()
        key = (id(current), is_head)
        if not expanded:
            if key in cache:
                continue
            if not isinstance(current, Coll):
                cache[key] = _normalize_atom(current, is_head)
                continue
            stack.append((current, is_head, True))
            for child, child_head in reversed(_norm_children(current)):
                stack.append((child, child_head, False))
            continue
        cache[key] = _assemble_form(current, cache)
    return cache[(id(form), head)]


def is_candidate_form(form) -> bool:
    """A non-empty top-level list whose head is not `ns`."""

    if not isinstance(form, Coll) or form.kind != "list" or not form.items:
        return False
    head = form.items[0]
    return not (isinstance(head, Sym) and head.name == "ns")
