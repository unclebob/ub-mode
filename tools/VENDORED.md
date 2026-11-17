# Vendored tools

Copied (no git history) from github.com/unclebob. mutator expects crapper as its sibling directory, so keep this layout.

| Tool | Upstream commit | License |
| --- | --- | --- |
| crapper | c757ec7 | 0BSD |
| dryer | 66ff6d2 | 0BSD |
| mutator | 830ec7e | 0BSD |
| uml-viewer | ad6c36d | see upstream (no LICENSE file) |

Run from the root of the project being measured: `<ub-mode>/tools/crapper/crapper`, `.../dryer/dryer`, `.../mutator/mutator`. First run builds a local `.venv`.
uml-viewer is a Clojure/Quil desktop GUI and needs a display; it does not run in a cloud session.
