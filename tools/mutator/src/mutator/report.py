"""Text reports for a mutation run and for --scan."""

from __future__ import annotations

from mutator.model import FormResult, Site


def _score(form: FormResult) -> str:
    if form.score is None:
        return "   n/a"
    return f"{form.score * 100:5.1f}%"


def format_results(forms: list[FormResult]) -> str:
    if not forms:
        return "No functions to mutate.\n"
    name_width = max(8, min(48, max(len(form.name) for form in forms)))
    namespace_width = max(9, min(72, max(len(form.namespace) for form in forms)))
    header = (
        f"{'Function':<{name_width}} {'Namespace':<{namespace_width}} "
        f"{'Killed':>7} {'Survived':>9} {'Uncovered':>10} {'Sites':>6} {'Score':>7}"
    )
    ordered = sorted(
        forms,
        key=lambda form: (form.score is not None, form.score or 0, -form.survived, form.name),
    )
    lines = [header, "-" * len(header)]
    killed = survived = uncovered = sites = 0
    for form in ordered:
        lines.append(
            f"{form.name:<{name_width}} {form.namespace:<{namespace_width}} "
            f"{form.killed:7d} {form.survived:9d} {form.uncovered:10d} {form.sites:6d} {_score(form)}"
        )
        killed += form.killed
        survived += form.survived
        uncovered += form.uncovered
        sites += form.sites
    executed = killed + survived
    score = "n/a" if executed == 0 else f"{(killed / executed) * 100:.1f}%"
    lines.append("-" * len(header))
    lines.append(
        f"{'Total':<{name_width}} {'':<{namespace_width}} "
        f"{killed:7d} {survived:9d} {uncovered:10d} {sites:6d} {score:>7}"
    )
    lines.append("")
    return "\n".join(lines)


def format_site_log(sites: list[Site], status: dict[str, str]) -> str:
    lines = []
    for site in sites:
        label = status.get(site.mutation_id)
        if label is None:
            continue
        lines.append(f"{label.upper():9} {site.file}:{site.line} {site.description}")
    if not lines:
        return ""
    return "\n".join(lines) + "\n"


def format_scan(
    path: str, sites: list[Site], changed: set[tuple[str, str]], covered: dict[str, bool] | None
) -> str:
    lines = [f"Scan: {len(sites)} mutation sites in {path}"]
    for site in sites:
        mark = "*" if (site.namespace, site.form_id) in changed else " "
        coverage = ""
        if covered is not None and not covered.get(site.mutation_id, True):
            coverage = " uncovered"
        lines.append(f"{mark} {path}:{site.line} {site.description}{coverage}  [{site.form_id}]")
    if changed:
        lines.append("* marks a function whose text changed since the last snapshot.")
    lines.append("")
    return "\n".join(lines)
