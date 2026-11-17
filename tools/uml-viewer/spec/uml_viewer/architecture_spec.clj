(ns uml-viewer.architecture-spec
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [speclj.core :refer :all]))

(def layer-rank
  {:domain 0 :source 0 :graph 0
   :clojure-language 0 :typescript-language 0 :rust-language 0
   :python-language 0 :go-language 0
   :engine 1
   :application 2
   :adapters 3
   :main 4})

(defn- source-files []
  (->> (file-seq (io/file "src"))
       (filter #(re-matches #".*\.clj[cs]?$" (.getName %)))))

(defn- read-ns-form [file]
  (read-string {:read-cond :allow :features #{:clj}} (slurp file)))

(defn- ns-name-of [ns-form] (second ns-form))

(defn- ns-clauses [ns-form]
  (->> ns-form (drop 2)
       (filter seq)
       (filter #(#{:require :import} (first %)))))

(defn- required-lib [spec]
  (cond
    (symbol? spec) spec
    (vector? spec) (first spec)
    :else spec))

(defn- required-libs [ns-form]
  (->> (ns-clauses ns-form)
       (mapcat rest)
       (map required-lib)))

(defn- project-ns? [sym]
  (let [s (str sym)]
    (or (= s "uml-viewer")
        (str/starts-with? s "uml-viewer."))))

(defn- layer-of [ns-name]
  (let [s (str ns-name)
        rest (if (str/starts-with? s "uml-viewer.")
               (subs s (count "uml-viewer."))
               s)
        seg (keyword (first (str/split rest #"\.")))]
    (when (contains? layer-rank seg) seg)))

(defn- quil-lib? [sym]
  (let [s (str sym)]
    (or (= s "quil.core")
        (str/starts-with? s "quil."))))

(defn- violations [from-pred to-pred]
  (for [file (source-files)
        :let [ns-form (read-ns-form file)
              ns-name (ns-name-of ns-form)]
        :when (from-pred ns-name)
        lib (required-libs ns-form)
        :when (to-pred lib)]
    {:ns ns-name :requires lib :file (str file)}))

(describe "architecture"
  (it "places every project namespace in a named layer"
    (let [nses (map ns-name-of (map read-ns-form (source-files)))
          unknown (remove layer-of nses)]
      (should= [] unknown)))

  (it "keeps inner layers free of outer layers"
    (should= []
             (for [file (source-files)
                   :let [ns-form (read-ns-form file)
                         ns-name (ns-name-of ns-form)
                         from (layer-of ns-name)]
                   :when from
                   lib (required-libs ns-form)
                   :when (project-ns? lib)
                   :let [to (layer-of lib)]
                   :when (and to (> (layer-rank to) (layer-rank from)))]
               {:ns ns-name :requires lib :from from :to to})))

  (it "keeps layout, IR, and hit-testing free of Quil"
    (should= [] (violations #(not (contains? #{:adapters :main} (layer-of %)))
                            quil-lib?)))

  (it "confines Processing to draw and sketch"
    (let [owners (set (map (comp str :ns) (violations (constantly true) quil-lib?)))]
      (should= #{"uml-viewer.adapters.draw" "uml-viewer.adapters.sketch"} owners)))

  (it "keeps source lookup free of Swing and Quil"
    (should= [] (violations #(contains? #{:source} (layer-of %))
                            #(or (quil-lib? %)
                                 (#{'javax.swing 'java.awt} %)))))

  (it "keeps the language graph and domain free of Swing and Quil"
    (should= [] (violations #(contains? #{:graph :domain} (layer-of %))
                            #(or (quil-lib? %)
                                 (#{'javax.swing 'java.awt} %)))))

  (it "wires language implementations only from main"
    (should= [] (violations #(not= :main (layer-of %))
                            #(contains? #{'uml-viewer.clojure-language.source-clojure
                                          'uml-viewer.clojure-language.graph-clojure
                                          'uml-viewer.typescript-language.source-typescript
                                          'uml-viewer.typescript-language.graph-typescript
                                          'uml-viewer.rust-language.source-rust
                                          'uml-viewer.rust-language.graph-rust
                                          'uml-viewer.python-language.source-python
                                          'uml-viewer.python-language.graph-python
                                          'uml-viewer.go-language.source-go
                                          'uml-viewer.go-language.graph-go}
                                        %)))))
