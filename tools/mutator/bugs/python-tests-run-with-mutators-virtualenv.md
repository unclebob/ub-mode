# Python tests run with mutator's virtualenv

Mutator runs a Python project's tests with mutator's own interpreter. That interpreter does not have the project installed, so the baseline fails and no mutants run.

## What happened

From `/Users/unclebob/projects/crapper`:

```bash
/Users/unclebob/projects/mutator/mutator --use-existing-coverage
```

Every file that still had mutants to run failed the baseline:

```text
Baseline failed for src/crapper/cli.py: /Users/unclebob/projects/mutator/.venv/bin/python -m pytest
E   ModuleNotFoundError: No module named 'crapper'
```

Mutator exited 2. The same suite passes under crapper's virtualenv. `./crapper` collected 120 tests and they passed.

This command gets past the baseline, because it names crapper's interpreter:

```bash
/Users/unclebob/projects/mutator/mutator --use-existing-coverage \
  --test-command "/Users/unclebob/projects/crapper/.venv/bin/python -m pytest"
```

## Cause

`./mutator` starts `mutator/.venv/bin/python`. `_python_command` in `src/mutator/runner.py` builds the test command from that process:

```python
return f"{sys.executable} -m pytest"
```

`sys.executable` is mutator's Python. Crapper is not installed in `mutator/.venv`.

Mutator can still import crapper inside its own process. `ensure_crapper()` in `src/mutator/crapper_link.py` inserts `../crapper/src` onto `sys.path`. Pytest is a new process and does not see that insertion.

The baseline also runs in the real tree, before any worker exists. `_prefer_worker_sources` adds `PYTHONPATH` only when the working directory is inside `target/mutation-workers`, so the baseline has no path to the project sources either.

## Fix

When the project under test has `.venv/bin/python` or `venv/bin/python`, use that interpreter in the test command. The path has to be absolute. Workers do not link `.venv` (`SKIP_LINK` in `src/mutator/workers.py`), so a relative `.venv/bin/python` is missing inside a worker.

If the project has no virtualenv, keep using mutator's Python.
