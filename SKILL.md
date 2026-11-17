---
name: ub-mode
description: >
  Follow the reduced principles when writing or changing code. Every change
  follows First Make it Work, then Then Make it Right, then Follow Practice.
  Use when the user runs /ub-mode or asks for the reduced principles.
---

# ub-mode

Every time you are asked to make a change follow:
* First Make it Work.
* Then Make it Right.
* Follow Practice

# First Make it Work

## Laziness Protocol

* One decision lives in one place.
* Ship the smallest edit that solves the problem.

## Foundational Thinking

* Get the data shape right before you write the logic.
* Do first whatever helps every later phase.
* If a concurrent writer would matter, do not share state.

## TDD

* Write the failing test before you change the code.
* See it fail for the reason you expect.
* Make the smallest change that makes it pass.
* If an honest test is impractical, do not write a bad one.

## Redesign From First Principles

* Do not bolt a new requirement onto the old design.
* Ask what you would have built if it had been there from the start, think that through, and deliver it in pieces.

## Attack the Premise

* When two fixes that share one premise fail the same gate, write that premise in one sentence and count which actors hold the imbalance.
* Remove the assignment.
* Do not start another fix until you have done that.

## Subtract Before You Add

* Take complexity out before you add anything.

## Outcome-Oriented Execution

* Aim at the end state you can check.
* A break in the middle is allowed when you planned it and can undo it.

## Build the Lever

* If the work is not obvious at a glance, build the tool that does it.
* Do one unit by hand, then make the tool match that unit.

## Architecture

### Model the Domain

* Put the domain in a structure that makes the illegal states impossible.
* Maintain a glossary of domain terms.
* If the code is already clear and local, leave it.

### Boundary Discipline

* Check the data where it enters.
* Inside, trust it.
* Keep the business logic in a pure function.

### Type System Discipline

* Make the illegal value impossible to write.
* Parse outside data once, where it comes in.
* Do not silence the compiler.

### Make Operations Idempotent

* Running it twice, or running it after a crash halfway through, ends in the same state.

### Separate Before Serializing Shared State

* If they do not need the same mutable thing, give each its own.
* If they do, allow one writer, and enforce that in the structure.

# Then Make it Right

## Fix Root Causes

* Reproduce it.
* Fix the cause.
* A guard that only hides the crash is not a fix.

## Sequence work into verifiable units

* Finish one small unit and check it.
* Then start the next.

## Test Behavior, Not Implementation

* The test calls the code the way its user does and checks the result that user would see.
* If the test would still pass when every function returned nothing, throw it out.

## Crapper

* Use the crapper tool
* Reduce each function under  CRAP 10 by splitting it, by testing it, or both.
* Consider whether case statements are worth reducing.

## Dryer

* Use the dryer tool to eliminate duplication that rank 0.8 or above.

## Mutator

* Use the mutator tool
* Cover the uncovered.
* Kill the survivors.
* Delete dead code.

## Refactoring

* Pin what the code does.
* Change the structure.
* Prove the behavior did not change.

# Running the tools

The gate tools are vendored in this repo under `tools/` (clone `github.com/AlexanderStone/ub-mode`). Run each from the root of the project you are measuring; the first run builds a local `.venv`. Set `UB=<path to ub-mode checkout>`.

* `$UB/tools/crapper/crapper [--no-coverage] [paths]` writes `.metrics/crap.edn`. Handles Clojure, Java, Go, TypeScript, JavaScript (`.js .mjs .cjs`), Rust, Python. Use `--no-coverage` when the project has no test runner: complexity only, CRAP is N/A.
* `$UB/tools/dryer/dryer [paths]` writes `.metrics/dry.edn`. It does not read `.js/.mjs/.cjs`, only the other crapper languages.
* `$UB/tools/mutator/mutator [--scan] [paths]` needs a working test command; `--scan` lists sites without running tests.
* `$UB/tools/uml-viewer` is a desktop GUI (Clojure/Quil). It cannot run in a cloud session; read `.metrics/*.edn` instead.

Delete `.metrics/` and `target/` before committing unless the project tracks them.

# Practice

## Keep a Log

* Keep a log of every action you take and the reason you took it.
* Report that log upon completion.

## Guard the Context Window

* Keep the main thread small.
* Send the bulk elsewhere, and keep the summary.

## Worktree Cleanup

* Find what is safe to remove, confirm nothing still in use needs it, then reclaim the disk.
* Uncommitted work waits for a decision.

## Commit and Push

* Commit often, in small ordered commits.
* Each commit says why, what changed, and how you proved it.
* Push to the main line at the end.
