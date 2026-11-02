# ub-mode

`ub-mode` is an agent skill that enforces disciplined software engineering principles when writing or changing code. It is loosely based on portions of [@poteto](https://github.com/poteto)'s rules (`poteto-mode` / `pstack`), reduced and paired with deterministic quality-gate tools:

1. **First Make it Work** — Laziness Protocol, Foundational Thinking, TDD, Redesign From First Principles, Attack the Premise, Subtract Before You Add, Outcome-Oriented Execution, Build the Lever, and Architecture (Model the Domain, Boundary Discipline, Type System Discipline, Make Operations Idempotent, Separate Before Serializing Shared State).
2. **Then Make it Right** — Fix Root Causes, Sequence Work into Verifiable Units, Test Behavior (Not Implementation), [Crapper](https://github.com/unclebob/crapper) (`CRAP < 10`), [Dryer](https://github.com/unclebob/dryer) (duplication `< 0.8`), [Mutator](https://github.com/unclebob/mutator) (cover the uncovered, kill the survivors, delete dead code), and Refactoring.
3. **Practice** — Keep a Log, Guard the Context Window, Worktree Cleanup, and Commit and Push in small ordered commits.

## Companion Tool Repositories

`ub-mode` relies on deterministic quality-gate tools to enforce structure, complexity, duplication, and test thoroughness:

- [unclebob/crapper](https://github.com/unclebob/crapper) — Computes cyclomatic complexity, test coverage, and CRAP score per function so every function stays under the CRAP threshold (`< 10`).
- [unclebob/dryer](https://github.com/unclebob/dryer) — Detects structural code duplication so duplicate blocks (`>= 0.8` similarity) are eliminated.
- [unclebob/mutator](https://github.com/unclebob/mutator) — Runs mutation testing to expose uncovered code and surviving mutants that tests fail to catch.
- [unclebob/uml-viewer](https://github.com/unclebob/uml-viewer) — Visualizes components, coverage, CRAP, mutation scores, and dependency directions.

## Installation

You can install the skill with the [Skills CLI](https://skills.sh), which installs it into the current project:

```bash
npx skills add https://github.com/unclebob/ub-mode --skill ub-mode
```

Add `--global` to install it for your user, so every project can use it:

```bash
npx skills add https://github.com/unclebob/ub-mode --skill ub-mode --global
```

The CLI asks which agents to install the skill for. Run `npx skills --help` to see how to pick agents and how to skip the prompts.

You can also install the skill by hand. Copy `skills/ub-mode/SKILL.md` into your agent's skills directory as `ub-mode/SKILL.md`, e.g., `~/.claude/skills/ub-mode/SKILL.md`, `~/.cursor/skills/ub-mode/SKILL.md`, or `~/.grok/skills/ub-mode/SKILL.md`.

After you install the skill, invoke it with `/ub-mode`.
