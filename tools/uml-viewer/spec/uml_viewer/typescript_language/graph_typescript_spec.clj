(ns uml-viewer.typescript-language.graph-typescript-spec
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [speclj.core :refer :all]
            [uml-viewer.graph :as graph]
            [uml-viewer.typescript-language.graph-typescript :as ts]))

(defn- spit-file [dir rel content]
  (let [f (io/file dir rel)]
    (io/make-parents f)
    (spit f content)
    f))

(defn- temp-root []
  (io/file (System/getProperty "java.io.tmpdir")
           (str "uml-ts-" (System/nanoTime))))

(describe "typescript module surface"
  (it "reads imports, exports, annotations, and invoke names"
    (let [surface (ts/read-module
                    (str "import type { DirEntry, Fs } from \"./book\";\n"
                         "import { invoke } from \"@tauri-apps/api/core\";\n"
                         "import \"./styles.css\";\n"
                         "export const tauriFs: Fs = {\n"
                         "  readText: (path) => invoke(\"read_text\", { path }),\n"
                         "  readDir: (path) => invoke<DirEntry[]>(\"read_dir\", { path }),\n"
                         "};\n"
                         "export function allowBook(root: string): Promise<void> {\n"
                         "  return invoke(\"allow_book\", { root });\n"
                         "}\n"
                         "// invoke(\"not_a_call\")\n"
                         "const note = \"invoke(\\\"hidden\\\")\";\n"))]
      (should= ["./book" "@tauri-apps/api/core" "./styles.css"]
               (map :spec (:imports surface)))
      (should= ["DirEntry" "Fs"] (:names (first (:imports surface))))
      (should= ["tauriFs" "allowBook"] (mapcat :ops (:exports surface)))
      (should= ["Fs"] (keep :type (:exports surface)))
      (should= ["read_text" "read_dir" "allow_book"] (:invokes surface))))

  (it "ignores an import mentioned only inside a comment or a string"
    (let [surface (ts/read-module
                    (str "const s = \"import { a } from \\\"./nope\\\";\";\n"
                         "/* import { b } from \"./nope\"; */\n"
                         "export function go(): number { return 1; }\n"))]
      (should= [] (:imports surface))
      (should= ["go"] (mapcat :ops (:exports surface)))))

  (it "keeps a quote inside a regex from swallowing the rest of the file"
    (let [surface (ts/read-module
                    (str "const q = /\"/g;\n"
                         "export function render(): string { return \"\"; }\n"))]
      (should= ["render"] (mapcat :ops (:exports surface)))))

  (it "reads export lists, classes, and enums, and skips export type names"
    (let [surface (ts/read-module
                    (str "export type { Secret } from \"./book\";\n"
                         "export * from \"./book\";\n"
                         "export { allowBook as allow };\n"
                         "export class Page {}\n"
                         "export enum Kind { A }\n"
                         "export interface Fs { read(): void; }\n"))]
      (should= ["allow" "Page" "Kind"] (mapcat :ops (:exports surface)))
      (should (some :value (:exports surface)))
      (should-not (some #(= ["Secret"] (:ops %)) (:exports surface))))))

(describe "typescript graph"
  (it "scans modules, skips tests, and marks an implemented interface"
    (let [dir (temp-root)]
      (spit-file dir "src/model.ts" "export function wordCount(body: string): number { return 0; }\n")
      (spit-file dir "src/book.ts"
                 (str "import { wordCount } from \"./model\";\n"
                      "export interface Fs { readText(path: string): Promise<string>; }\n"
                      "export async function loadBook(fs: Fs): Promise<string> {\n"
                      "  return wordCount(\"\");\n"
                      "}\n"))
      (spit-file dir "src/nodeFs.ts"
                 (str "import type { Fs } from \"./book\";\n"
                      "export function nodeFs(): Fs { return null as unknown as Fs; }\n"))
      (spit-file dir "src/book.test.ts" "import { loadBook } from \"./book\";\n")
      (spit-file dir "src/types.d.ts" "export interface Hidden {}\n")
      (let [g (graph/scan (graph/lookup :typescript) (io/file dir "src")
                          {:prefix "bookwriter"})
            by-id (into {} (map (juxt :id identity) (:classes g)))
            edges (set (map (juxt :from :to :kind) (:edges g)))]
        (should= #{:model :book :nodeFs} (set (remove #(get-in by-id [% :foreign]) (keys by-id))))
        (should= "bookwriter.model" (:ns (by-id :model)))
        (should= :typescript (:lang (by-id :nodeFs)))
        (should (str/ends-with? (:file (by-id :nodeFs)) "src/nodeFs.ts"))
        (should= "NodeFs" (:name (by-id :nodeFs)))
        (should= ["wordCount"] (map :name (:ops (by-id :model))))
        (should (contains? edges [:book :model :dependency]))
        (should (contains? edges [:nodeFs :book :dependency]))
        (should (contains? edges [:nodeFs :book :implements]))
        (should-not (contains? (set (keys by-id)) :book.test)))))

  (it "records a bare import as a foreign class"
    (let [dir (temp-root)]
      (spit-file dir "src/preview.ts"
                 "import MarkdownIt from \"markdown-it\";\nexport function render(): string { return \"\"; }\n")
      (let [g (graph/scan (graph/lookup :typescript) (io/file dir "src") {:prefix "bookwriter"})
            by-id (into {} (map (juxt :id identity) (:classes g)))
            edges (set (map (juxt :from :to :kind) (:edges g)))]
        (should (:foreign (by-id :markdown-it)))
        (should (contains? edges [:preview :markdown-it :dependency])))))

  (it "nests a file under its directory"
    (let [dir (temp-root)]
      (spit-file dir "src/editor/view.ts" "export function show(): void {}\n")
      (let [g (graph/scan (graph/lookup :typescript) (io/file dir "src") {:prefix "bookwriter"})
            c (first (:classes g))]
        (should= :editor.view (:id c))
        (should= "View" (:name c))
        (should= "bookwriter.editor.view" (:ns c))))))
