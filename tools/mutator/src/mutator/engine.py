"""Apply each selected mutant, run tests, and record the snapshot."""

from __future__ import annotations

import sys
from pathlib import Path

from mutator.crapper_link import ensure_crapper
from mutator.functions import form_digests, form_spans, sites_in_file
from mutator.metrics import load_history, source_key, write_results
from mutator.model import FormResult, RunResult, Site
from mutator.report import format_scan
from mutator.runner import Command, CommandRunner, display_command, test_plan
from mutator.workers import run_mutants


def restore_backups(root: Path) -> list[Path]:
    """Put back sources left mutated by a killed run."""

    base = root / "target" / "mutator-backup"
    restored: list[Path] = []
    if not base.is_dir():
        return restored
    for backup in sorted(path for path in base.rglob("*") if path.is_file()):
        relative = backup.relative_to(base)
        target = root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(backup.read_bytes())
        backup.unlink()
        restored.append(target)
    return restored


def _drop_bytecode(path: Path) -> None:
    """A same-second .pyc would hide the mutant from Python."""

    cache = path.parent / "__pycache__"
    if not cache.is_dir():
        return
    for item in cache.glob(path.stem + ".*.pyc"):
        item.unlink(missing_ok=True)


def _backup(root: Path, path: Path, original: bytes) -> Path:
    relative = path.resolve().relative_to(root.resolve())
    backup = root / "target" / "mutator-backup" / relative
    backup.parent.mkdir(parents=True, exist_ok=True)
    if not backup.exists():
        backup.write_bytes(original)
    return backup


def _covered(site: Site, lines: set[int] | None, ignore_coverage: bool) -> bool:
    if ignore_coverage:
        return True
    if lines is None:
        return False
    return site.line in lines or site.anchor_line in lines


def _select(
    sites: list[Site],
    covered: dict[str, bool],
    history,
    digests: dict[tuple[str, str], str],
    mutate_all: bool,
    lines: set[int] | None,
) -> list[Site]:
    chosen = []
    for site in sites:
        if not covered[site.mutation_id]:
            continue
        if lines is not None and site.line not in lines:
            continue
        key = (site.namespace, site.form_id)
        prior = history.forms.get(key)
        unchanged = prior is not None and prior.digest == digests.get(key)
        if mutate_all or not unchanged:
            chosen.append(site)
            continue
        if history.outcomes.get(site.mutation_id) == "killed":
            continue
        chosen.append(site)
    return chosen


def _carry_forward(sites: list[Site], covered: dict[str, bool], history, digests) -> dict[str, str]:
    by_id = {site.mutation_id: site for site in sites}
    carried = {}
    for mutation, status in history.outcomes.items():
        site = by_id.get(mutation)
        if site is None or not covered[site.mutation_id]:
            continue
        key = (site.namespace, site.form_id)
        prior = history.forms.get(key)
        if prior is not None and prior.digest == digests.get(key):
            carried[mutation] = status
    return carried


def _forms(
    source: str,
    path: Path,
    root: Path,
    file_key: str,
    sites: list[Site],
    covered: dict[str, bool],
    outcomes: dict[str, str],
    lines: set[int] | None,
) -> list[FormResult]:
    spans = form_spans(source, path, root)
    digests = form_digests(source, path, root)
    grouped: dict[tuple[str, str], list[Site]] = {}
    for site in sites:
        grouped.setdefault((site.namespace, site.form_id), []).append(site)
    forms = []
    for key, (start, end, name, private) in sorted(spans.items(), key=lambda item: item[1][0]):
        namespace, form = key
        owned = grouped.get(key, [])
        killed = survived = uncovered = 0
        for site in owned:
            if not covered[site.mutation_id]:
                uncovered += 1
                continue
            status = outcomes.get(site.mutation_id)
            if status == "killed":
                killed += 1
            elif status == "survived":
                survived += 1
            elif lines is None:
                survived += 1
        forms.append(
            FormResult(
                id=form,
                namespace=namespace,
                name=name,
                private=private,
                file=file_key,
                line=start,
                end_line=end,
                digest=digests[key],
                killed=killed,
                survived=survived,
                uncovered=uncovered,
                sites=len(owned),
            )
        )
    return forms


def _decode(path: Path) -> tuple[bytes, str] | None:
    original = path.read_bytes()
    try:
        return original, original.decode("utf-8")
    except UnicodeError:
        return None


def _coverage_map(
    found: list[Site], covered_lines: set[int] | None, ignore_coverage: bool
) -> dict[str, bool]:
    return {site.mutation_id: _covered(site, covered_lines, ignore_coverage) for site in found}


def _warn_uncovered(file_key: str, found, covered_lines, ignore_coverage: bool) -> None:
    if covered_lines is not None or ignore_coverage or not found:
        return
    print(
        f"No coverage data for {file_key}; its sites are uncovered. "
        "Pass --no-coverage to run them anyway.",
        file=sys.stderr,
    )


def _warn_many(file_key: str, selected, mutation_warning: int) -> None:
    if len(selected) > mutation_warning:
        print(
            f"WARNING: {len(selected)} covered mutations selected in {file_key}.",
            file=sys.stderr,
        )


def _command_key(command: Command) -> tuple[str, ...]:
    """A hashable identity. A list cannot be a dict key."""

    if isinstance(command, str):
        return ("sh", command)
    return ("argv", *command)


def _remember_baseline(baselines, cache_key, runner, command: Command, cwd: Path) -> None:
    if cache_key in baselines:
        return
    baseline = runner.run(command, cwd, None)
    if baseline.code != 0:
        tail = "\n".join(baseline.output.splitlines()[-20:])
        baselines[cache_key] = (False, baseline.seconds, tail)
        return
    baselines[cache_key] = (True, baseline.seconds, "")


def _baseline_failure(file_key: str, command: Command, tail: str) -> RunResult:
    message = f"Baseline failed for {file_key}: {display_command(command)}"
    if tail:
        message = f"{message}\n{tail}"
    return RunResult(path=file_key, forms=[], written=[], baseline_failed=True, baseline_message=message)


def _apply_selected(
    path,
    root,
    original,
    selected,
    runner,
    language,
    test_command,
    timeout_factor,
    file_key,
    baselines,
    outcomes,
    max_workers,
) -> RunResult | None:
    if not selected:
        return None
    command, cwd = test_plan(root, path, language, test_command)
    cache_key = (_command_key(command), str(cwd))
    _remember_baseline(baselines, cache_key, runner, command, cwd)
    ok, seconds, tail = baselines[cache_key]
    if not ok:
        return _baseline_failure(file_key, command, tail)
    timeout = max(2.0, seconds * timeout_factor)
    run_mutants(
        root,
        path,
        original,
        selected,
        max_workers,
        runner,
        command,
        cwd,
        timeout,
        file_key,
        outcomes,
    )
    return None


def _statuses(found: list[Site], covered: dict[str, bool], outcomes: dict[str, str]) -> dict[str, str]:
    statuses = {}
    for site in found:
        if not covered[site.mutation_id]:
            statuses[site.mutation_id] = "uncovered"
        elif site.mutation_id in outcomes:
            statuses[site.mutation_id] = outcomes[site.mutation_id]
    return statuses


def _restore_if_dirty(path: Path, original: bytes) -> None:
    if path.read_bytes() != original:
        path.write_bytes(original)


def mutate_file(
    path: Path,
    root: Path,
    *,
    runner: CommandRunner,
    covered_lines: set[int] | None,
    ignore_coverage: bool,
    mutate_all: bool,
    lines: set[int] | None,
    test_command: str | None,
    timeout_factor: float,
    mutation_warning: int,
    baselines: dict[tuple[tuple[str, ...], str], tuple[bool, float, str]],
    max_workers: int | None = None,
) -> RunResult:
    """Mutate one file and write its namespaces into `.metrics/mutate`."""

    root = root.resolve()
    path = path.resolve()
    crapper = ensure_crapper()
    language = crapper.discover.language_of(path)
    file_key = source_key(path, root)
    if language is None:
        return RunResult(path=file_key, forms=[], written=[], skipped="unsupported file")
    decoded = _decode(path)
    if decoded is None:
        return RunResult(path=file_key, forms=[], written=[], skipped="file is not UTF-8")
    original, source = decoded
    found = sites_in_file(source, path, root, file_key)
    covered = _coverage_map(found, covered_lines, ignore_coverage)
    _warn_uncovered(file_key, found, covered_lines, ignore_coverage)
    history = load_history(root, file_key)
    digests = form_digests(source, path, root)
    selected = _select(found, covered, history, digests, mutate_all, lines)
    outcomes = _carry_forward(found, covered, history, digests)
    _warn_many(file_key, selected, mutation_warning)
    failure = _apply_selected(
        path,
        root,
        original,
        selected,
        runner,
        language,
        test_command,
        timeout_factor,
        file_key,
        baselines,
        outcomes,
        max_workers,
    )
    if failure is not None:
        return failure
    forms = _forms(source, path, root, file_key, found, covered, outcomes, lines)
    written = write_results(root, file_key, forms, outcomes)
    _restore_if_dirty(path, original)
    return RunResult(
        path=file_key,
        forms=forms,
        written=[item.as_posix() for item in written],
        sites=found,
        statuses=_statuses(found, covered, outcomes),
    )


def scan_file(
    path: Path,
    root: Path,
    *,
    covered_lines: set[int] | None,
    ignore_coverage: bool,
    lines: set[int] | None,
) -> str:
    """Inventory mutation sites without running tests or writing metrics."""

    root = root.resolve()
    path = path.resolve()
    file_key = source_key(path, root)
    source = path.read_text(encoding="utf-8")
    found = sites_in_file(source, path, root, file_key)
    if lines is not None:
        found = [site for site in found if site.line in lines]
    covered = None
    if not ignore_coverage and covered_lines is not None:
        covered = _coverage_map(found, covered_lines, ignore_coverage)
    history = load_history(root, file_key)
    digests = form_digests(source, path, root)
    changed = set()
    if history.forms:
        changed = {
            key
            for key, digest in digests.items()
            if key not in history.forms or history.forms[key].digest != digest
        }
    return format_scan(file_key, found, changed, covered)
