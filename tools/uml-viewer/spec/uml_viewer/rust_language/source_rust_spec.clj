(ns uml-viewer.rust-language.source-rust-spec
  (:require [clojure.java.io :as io]
            [speclj.core :refer :all]
            [uml-viewer.rust-language.source-rust :as rust]
            [uml-viewer.source :as source]))

(describe "rust extractor"
  (it "finds a tauri command and a public fn"
    (let [src (str "#[tauri::command]\n"
                   "fn read_text(path: String) -> String { path }\n"
                   "pub fn run() {}\n")]
      (should= 1 (rust/member-line src "read_text"))
      (should= 3 (rust/member-line src "run"))
      (should (re-find #"fn read_text" (rust/extract-member src "read_text")))))

  (it "opens the file named on the ident"
    (let [dir (io/file (System/getProperty "java.io.tmpdir")
                       (str "uml-rs-src-" (System/nanoTime)))
          file (io/file dir "lib.rs")]
      (io/make-parents file)
      (spit file "pub fn run() {}\n")
      (let [found (source/member-source {:lang :rust
                                         :ns "bookwriter.rust"
                                         :file (.getPath file)
                                         :name "run"})]
        (should= :rust (:lang found))
        (should= 1 (:line found))
        (should (re-find #"lib\.rs:1$" (:title found)))))))
