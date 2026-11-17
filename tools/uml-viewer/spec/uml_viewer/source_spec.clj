(ns uml-viewer.source-spec
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [speclj.core :refer :all]
            [uml-viewer.source :as source]
            [uml-viewer.clojure-language.source-clojure :as clj-src]))

(describe "source protocol"
  (it "titles a file-backed member from its path or namespace"
    (let [impl (source/file-source [])]
      (should= "model.py/walk"
               (source/title impl {:file "model.py" :ns "app.model" :name "walk"}))
      (should= "app.model/walk"
               (source/title impl {:ns "app.model" :name "walk"}))
      (should= "model.py"
               (source/title impl {:file "model.py" :ns "app.model"}))))

  (it "returns nil for an unknown language"
    (should-be-nil (source/member-source {:lang :cobol :name "FOO"})))

  (it "defaults the language when the extractor is passed in"
    (let [found (source/member-source (source/lookup :clojure)
                                      {:ns "uml-viewer.domain.geom"
                                       :name "rect"})]
      (should= :clojure (:lang found))
      (should (str/includes? (:body found) "(defn rect"))))

  (it "dispatches clojure by :lang"
    (let [found (source/member-source {:lang :clojure
                                       :ns "uml-viewer.domain.geom"
                                       :name "rect"})]
      (should= :clojure (:lang found))
      (should= "src/uml_viewer/domain/geom.clj" (:file found))
      (should (str/starts-with? (:body found) "(ns uml-viewer.domain.geom"))
      (should (str/includes? (:body found) "(defn rect"))
      (should (pos? (:line found))))))

(describe "clojure extractor"
  (it "maps a namespace to a source file under src/"
    (should= "src/uml_viewer/domain/geom.clj"
             (clj-src/ns->source-path "uml-viewer.domain.geom")))

  (it "extracts a public defn by name"
    (let [body (clj-src/extract-member (slurp "src/uml_viewer/domain/geom.clj") "rect")]
      (should (str/starts-with? body "(defn rect"))
      (should (str/includes? body "[x y w h]"))))

  (it "extracts a private defn-"
    (let [found (source/member-source {:ns "uml-viewer.application.detail" :name "rel-phrase"})]
      (should (re-find #"src/uml_viewer/application/detail.clj:" (:title found)))
      (should (str/includes? (:body found) "(defn- rel-phrase"))
      (should= (clj-src/member-line (slurp "src/uml_viewer/application/detail.clj") "rel-phrase")
               (:line found))))

  (it "extracts names that end with ! or ?"
    (let [src "(ns demo)\n(defn- live? [applet]\n  true)\n(defn pin-card! [on?]\n  on?)\n"
          q (clj-src/extract-member src "live?")
          bang (clj-src/extract-member src "pin-card!")]
      (should (str/starts-with? q "(defn- live?"))
      (should (str/starts-with? bang "(defn pin-card!"))))

  (it "returns nil for an unknown member"
    (should-be-nil (source/member-source {:ns "uml-viewer.domain.geom" :name "no-such-fn"})))

  (it "opens a module at the top of the file when no member name is given"
    (let [found (source/member-source {:ns "uml-viewer.domain.geom"})]
      (should= "src/uml_viewer/domain/geom.clj" (:file found))
      (should= "src/uml_viewer/domain/geom.clj" (:title found))
      (should (str/starts-with? (:body found) "(ns uml-viewer.domain.geom"))
      (should-be-nil (:line found)))))

(describe "member lookup"
  (it "does not search when the source or the name is missing"
    (should-be-nil (source/pattern-start nil "rect" ["(defn %s"]))
    (should-be-nil (source/pattern-start "(defn rect" nil ["(defn %s"])))

  (it "stops a declaration at the following newline"
    (should= "(defn rect [x y]"
             (source/declaration-line "(defn rect [x y]\n  x)" 0)))

  (it "ignores a path that is not a file"
    (should-be-nil (source/existing-file {:file "no/such/member-file.clj"}))
    (should-be-nil (source/existing-file {:file ""}))
    (should-be-nil (source/existing-file {})))

  (it "numbers a found member at line 1 when the pattern has no start"
    (let [root (io/file "target" (str "member-line-" (System/nanoTime)))
          f (io/file root "demo.txt")]
      (.mkdirs root)
      (try
        (spit f "hello\n")
        (let [impl (source/file-source ["nope-%s"] (fn [_text _name] "body"))
              found (source/member-source impl {:file (.getPath f) :name "rect"})]
          (should= (str (.getPath f) ":1") (:title found))
          (should= "hello\n" (:body found))
          (should-be-nil (:line found)))
        (finally
          (doseq [file (reverse (file-seq root))]
            (io/delete-file file true)))))))
