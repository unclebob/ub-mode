(ns uml-viewer.graph-spec
  (:require [clojure.java.io :as io]
            [speclj.core :refer :all]
            [uml-viewer.graph :as graph]
            [uml-viewer.clojure-language.graph-clojure]))

(defn- spit-ns [dir rel content]
  (let [f (io/file dir rel)]
    (io/make-parents f)
    (spit f content)
    f))

(describe "LanguageGraph"
  (it "throws when no scanner is registered"
    (should-throw (graph/scan-project :cobol "src" {})))

  (it "dispatches clojure by :lang"
    (let [g (graph/scan-project :clojure "src" {:prefix "uml-viewer"})]
      (should (some #(= :source (:id %)) (:classes g)))
      (should (some #(= :interface (:stereotype %))
                    (filter #(= :source (:id %)) (:classes g)))))))

(describe "clojure graph"
  (it "reads requires, protocols, and record implementations from a tree"
    (let [dir (io/file (System/getProperty "java.io.tmpdir")
                       (str "uml-graph-" (System/nanoTime)))]
      (spit-ns dir "demo/a.clj"
               "(ns demo.a
                  (:require [demo.b :as b]
                            [clojure.string :as str]
                            [demo [c :as c]]))
                (defrecord R []
                  b/Q)")
      (spit-ns dir "demo/b.clj"
               "(ns demo.b)
                (defprotocol Q)")
      (spit-ns dir "demo/c.clj"
               "(ns demo.c)")
      (let [g (graph/scan (graph/lookup :clojure) dir {:prefix "demo"})
            by-id (into {} (map (juxt :id identity) (:classes g)))
            edges (set (map (juxt :from :to :kind) (:edges g)))]
        (should= #{:a :b :c :clojure.string} (set (keys by-id)))
        (should= "A" (get-in by-id [:a :name]))
        (should= :interface (:stereotype (by-id :b)))
        (should-be-nil (:stereotype (by-id :a)))
        (should (contains? edges [:a :b :dependency]))
        (should (contains? edges [:a :c :dependency]))
        (should (contains? edges [:a :b :implements]))
        (should (:foreign (by-id :clojure.string)))
        (should (contains? edges [:a :clojure.string :dependency])))))

  (it "treats requiring-resolve as a dependency"
    (let [dir (io/file (System/getProperty "java.io.tmpdir")
                       (str "uml-graph-rr-" (System/nanoTime)))]
      (spit-ns dir "demo/a.clj"
               "(ns demo.a)
                (defn go []
                  ((requiring-resolve 'demo.b/run))
                  @(clojure.core/requiring-resolve 'quil.core/width))")
      (spit-ns dir "demo/b.clj" "(ns demo.b)")
      (let [g (graph/scan (graph/lookup :clojure) dir {:prefix "demo"})
            edges (set (map (juxt :from :to :kind) (:edges g)))
            by-id (into {} (map (juxt :id identity) (:classes g)))]
        (should (contains? edges [:a :b :dependency]))
        (should (contains? edges [:a :quil.core :dependency]))
        (should (:foreign (by-id :quil.core))))))

  (it "treats Java imports as foreign packages"
    (let [dir (io/file (System/getProperty "java.io.tmpdir")
                       (str "uml-graph-imp-" (System/nanoTime)))]
      (spit-ns dir "demo/a.clj"
               "(ns demo.a
                  (:import [javax.swing JFrame]
                           java.io.File))")
      (let [g (graph/scan (graph/lookup :clojure) dir {:prefix "demo"})
            ids (set (map :id (:classes g)))
            edges (set (map (juxt :from :to) (:edges g)))]
        (should (contains? ids :javax.swing))
        (should (contains? ids :java.io))
        (should (contains? edges [:a :javax.swing]))
        (should (contains? edges [:a :java.io])))))

  (it "scans cljs namespaces alongside clj and cljc"
    (let [dir (io/file (System/getProperty "java.io.tmpdir")
                       (str "uml-graph-cljs-" (System/nanoTime)))]
      (spit-ns dir "demo/web.cljs"
               "(ns demo.web (:require [demo.a :as a] [quil.core :as q]))")
      (spit-ns dir "demo/a.cljc" "(ns demo.a)")
      (let [g (graph/scan (graph/lookup :clojure) dir {:prefix "demo"})
            by-id (into {} (map (juxt :id identity) (:classes g)))
            edges (set (map (juxt :from :to) (:edges g)))]
        (should= "demo.web" (:ns (by-id :web)))
        (should (contains? edges [:web :a]))
        (should (contains? edges [:web :quil.core])))))

  (it "names nested namespaces like source.clojure"
    (let [dir (io/file (System/getProperty "java.io.tmpdir")
                       (str "uml-graph-nest-" (System/nanoTime)))]
      (spit-ns dir "demo/source/clojure.clj"
               "(ns demo.source.clojure)")
      (let [g (graph/scan (graph/lookup :clojure) dir {:prefix "demo"})
            c (first (:classes g))]
        (should= :source.clojure (:id c))
        (should= "Clojure" (:name c)))))

  (it "scans this project for Source and its Clojure impl"
    (let [g (graph/scan-project "src" {:prefix "uml-viewer"})
          by-id (into {} (map (juxt :id identity) (:classes g)))
          edges (set (map (juxt :from :to :kind) (:edges g)))]
      (should= :interface (:stereotype (by-id :source)))
      (should= :clojure (:lang (by-id :source)))
      (should= "src/uml_viewer/source.clj" (:file (by-id :source)))
      (should= "SourceClojure"
               (:name (by-id :clojure-language.source-clojure)))
      (should (contains? edges [:clojure-language.source-clojure :source :implements]))
      (should (contains? edges [:application.document :engine.compose :dependency]))
      (should (:foreign (by-id :quil.core)))
      (should (contains? edges [:adapters.draw :quil.core :dependency])))))
