(ns uml-viewer.typescript-language.source-typescript-spec
  (:require [clojure.java.io :as io]
            [speclj.core :refer :all]
            [uml-viewer.source :as source]
            [uml-viewer.typescript-language.source-typescript]))

(describe "typescript extractor"
  (it "finds an exported function and opens the file named on the ident"
    (let [dir (io/file (System/getProperty "java.io.tmpdir")
                       (str "uml-ts-src-" (System/nanoTime)))
          file (io/file dir "book.ts")]
      (io/make-parents file)
      (spit file "export function loadBook(): string {\n  return \"\";\n}\n")
      (let [found (source/member-source {:lang :typescript
                                         :ns "bookwriter.book"
                                         :file (.getPath file)
                                         :name "loadBook"})]
        (should= :typescript (:lang found))
        (should (re-find #"book\.ts:1$" (:title found)))
        (should= 1 (:line found))
        (should (re-find #"export function loadBook" (:body found))))))

  (it "returns nil when the member is not in the file"
    (let [dir (io/file (System/getProperty "java.io.tmpdir")
                       (str "uml-ts-miss-" (System/nanoTime)))
          file (io/file dir "answer.ts")]
      (io/make-parents file)
      (spit file "export const answer = 1;\n")
      (should-be-nil (source/member-source {:lang :typescript
                                            :file (.getPath file)
                                            :name "missing"}))))

  (it "opens a module at the top when no member is named"
    (let [dir (io/file (System/getProperty "java.io.tmpdir")
                       (str "uml-ts-mod-" (System/nanoTime)))
          file (io/file dir "model.ts")]
      (io/make-parents file)
      (spit file "export function wordCount(): number { return 0; }\n")
      (let [found (source/member-source {:lang :typescript
                                         :file (.getPath file)
                                         :ns "bookwriter.model"})]
        (should= (.getPath file) (:file found))
        (should-be-nil (:line found))))))
