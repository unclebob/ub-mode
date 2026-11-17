# mutator

Mutation testing for Clojure, Java, Go, TypeScript, Rust, and Python. One run detects the language of each source file, applies that language's mutation rules, and writes the snapshot [uml-viewer](https://github.com/unclebob/uml-viewer) already reads.

Clojure follows [clj-mutate](https://github.com/unclebob/clj-mutate). Go follows [mutate4go](https://github.com/unclebob/mutate4go). Java follows [mutate4java](https://github.com/unclebob/mutate4java). TypeScript, Rust, and Python use the same decisions as Java, spelled in that language. Function names and namespaces come from [crapper](https://github.com/unclebob/crapper), which is what uml-viewer joins to `.metrics/crap.edn`.

A mutant is killed when the tests fail or time out. It survives when the tests still pass. A site on a line the coverage report does not hit is uncovered and is not run. The score uml-viewer paints is killed / (killed + survived).

## Run

```bash
./mutator
```

The first run creates `.venv` and installs the tool. crapper has to be checked out next to this directory (`../crapper`), because that is where the namespace and function names come from. From a project root the tool walks the tree, skips `test`, `spec`, `vendor`, `node_modules`, and `target`, and writes `.metrics/mutate/<namespace>.edn`.

The first run of a tree executes every covered mutant. Start with the file you are working on.

```bash
./mutator src/demo/core.clj          # one file, or a directory
./mutator --scan src/demo           # list sites, do not run tests
./mutator --changed                 # git additions and edits
./mutator --mutate-all              # rerun killed mutants too
./mutator --no-coverage             # do not skip uncovered lines
./mutator --use-existing-coverage   # read reports already on disk
./mutator --test-command "pytest -q tests/test_demo.py"
./mutator --max-workers 3 src/demo/core.clj
```

Once a snapshot exists, the next run is differential. It reruns survivors and every site in a function whose text changed. Killed mutants in an unchanged function stay killed. `--since-last-run` is that same selection. `--mutate-all` ignores it.

Exit `0` when every executed mutant was killed. Exit `2` when the baseline tests fail. Exit `3` when a mutant survives. A failed baseline does not rewrite the snapshot.

## Workers

Mutants of one file run at the same time. The default is one worker per core. `--max-workers` sets the cap. The run uses the smaller of that cap, the number of cores, and the number of selected sites. Files are still taken one at a time.

Each worker is a directory under `target/mutation-workers`. The file being mutated is a private copy. A module that reaches that file through a relative import is copied too, so Node resolves the import to the private copy. The rest of the tree is linked. The original files stay as they are. The overlay is removed when that file's mutants finish.

The baseline still runs once, in the real tree, before any worker starts. The mutant command then runs inside the worker, in the same directory it would have used in the real tree, so a relative path such as `src` or `./pkg` refers to the overlay. A copy left under `target/mutator-backup/` by an interrupted older run is restored before a non-scan run.

## Snapshot

uml-viewer loads every `*.edn` file under `.metrics/mutate`. Each file is one namespace. A Java file with an inner class, or a Go file with a function and a method, therefore writes more than one file. Two Go files in the same package share one snapshot, and rerunning one of them keeps the other's forms.

```clojure
{:version 1
 :source "src/demo/board.py"
 :namespace "demo.board.Board"
 :outcomes {"[\"src/demo/board.py\",\"demo.board.Board\",\"defn/place\",10,11,\">\",\">=\"]" :survived}
 :forms [{:id "defn/place"
          :kind "defn"
          :file "src/demo/board.py"
          :line 2
          :end-line 4
          :hash "…"
          :killed 1
          :survived 1
          :uncovered 0
          :sites 2}
         {:id "defn-/_hide"
          :kind "defn-"
          :killed 0
          :survived 0
          :uncovered 1
          :sites 1}]}
```

`:namespace` is the class key crapper uses. The form id is what the viewer splits:

| Form id | Operation | Private |
| --- | --- | --- |
| `defn/place` | `place` | no |
| `defn-/hide` | `hide` | yes |

`:sites` is every mutation in the function, including uncovered ones. The viewer prints `---no mutation sites---` when that count is zero.

| Language | `:namespace` | `:name` |
| --- | --- | --- |
| Clojure | the `ns` | the `defn` / `defn-` name |
| Java | `package.Class`, or `package.Outer.Inner` | the method name |
| Go | the package import path, or `import/path.Receiver` | the function or method name |
| TypeScript | the dotted module path, or `module.Class` | the function or method name |
| Rust | `crate::module`, or `crate::module::Type` | the function or method name |
| Python | the dotted module path, or `module.Class` | the function or method name |

`defn-` is Clojure's private form. Elsewhere a private method, an unexported Go function, a Python name that starts with `_`, a Rust function that is not `pub`, or a TypeScript `private` / `#` name uses `defn-/`.

## What each language mutates

Tokens inside strings, comments, and quoted Clojure forms are left alone. A replacement that does not compile fails the test run and counts as killed.

| Language | Mutations |
| --- | --- |
| Clojure | clj-mutate's head symbols: `+`/`-`, `*` to `/`, `inc`/`dec`, comparisons, `=`/`not=`, `if`/`if-not`, `when`/`when-not`, `and`/`or`, and the seq, predicate, and coercion pairs. `true`/`false` and `0`/`1` anywhere. |
| Java | `true`/`false`, `==`/`!=`, `<`/`<=`, `>`/`>=`, `+`/`-`, `*`/`/`, `&&`/`||`, delete `!` and unary `-`, `0`/`1`. Constructors are not entries, matching crapper. |
| Go | mutate4go: `+`/`-`, `*` to `/` only, comparisons, `==`/`!=`, `true`/`false`, `&&`/`||`, `0`/`1`. |
| TypeScript | the Java set, plus `===`/`!==`, `??` to `||`, and `?.` to `.`. A call or index drops `?.` (`a?.()` becomes `a()`). `.js`, `.jsx`, `.mjs`, and `.cjs` use these rules. |
| Rust | the Java set (`&&`/`||`, `!`, unary `-`). |
| Python | the Java set, spelled `and`/`or`, `not`, and `True`/`False`. |

## Tests and coverage

The baseline command has to pass before any mutant runs. A mutant's timeout is ten times that baseline, and at least two seconds. `--timeout-factor` changes the multiple. `--test-command` replaces the default. Defaults:

| Language | Command | Where |
| --- | --- | --- |
| Clojure | `clj -M:spec --tag ~no-mutate` when Speclj is on the classpath, otherwise `clj -M:test` or `bb spec` | nearest `deps.edn` or `bb.edn` |
| Java | `mvn -q test -DexcludeTags=no-mutate` | nearest `pom.xml` |
| Go | `go test -count=1` of the file's package | nearest `go.mod` |
| TypeScript | `npm test` | nearest `package.json` |
| Rust | `cargo test` | nearest `Cargo.toml` |
| Python | the project's `.venv` or `venv` Python running `pytest`, or `unittest discover` | nearest project file |

Coverage is generated with crapper's commands unless `--use-existing-coverage` or `--no-coverage` is set. A file missing from the report is treated as uncovered, and the run says so.

## Development

```bash
python3 -m venv .venv
.venv/bin/pip install -e '.[dev]'
.venv/bin/pytest
```
