(ns uml-viewer.clojure-language.graph-clojure
  "Clojure LanguageGraph: ns requires, requiring-resolve, defprotocol, defrecord/deftype."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [uml-viewer.graph :as graph])
  (:import [java.io PushbackReader]))

(defn- source-files [root]
  (->> (file-seq (io/file root))
       (filter #(.isFile %))
       (filter #(re-find #"\.clj[cs]?$" (.getName %)))
       (sort-by #(.getPath %))))

(defn- read-forms [file]
  (with-open [r (PushbackReader. (io/reader file))]
    (binding [*read-eval* false]
      (loop [forms []]
        (let [form (read {:eof ::eof :read-cond :allow :features #{:clj}} r)]
          (if (= form ::eof)
            forms
            (recur (conj forms form))))))))

(defn- ns-form [forms]
  (first (filter #(and (seq? %) (= 'ns (first %))) forms)))

(defn- prefix-list? [spec]
  (and (vector? spec)
       (symbol? (first spec))
       (seq (rest spec))
       (let [x (second spec)]
         (or (symbol? x) (vector? x)))))

(defn- expand-libspec [spec]
  (cond
    (symbol? spec) [{:lib spec}]
    (prefix-list? spec)
    (mapcat (fn [item]
              (let [sub (if (symbol? item) [item] (vec item))
                    lib (symbol (str (first spec) "." (first sub)))]
                (expand-libspec (assoc sub 0 lib))))
            (rest spec))
    (vector? spec)
    (let [opts (apply hash-map (rest spec))]
      [{:lib (first spec)
        :alias (or (:as opts) (:as-alias opts))}])
    :else []))

(defn- ns-clauses [ns-form]
  (->> ns-form
       (drop 2)
       (filter list?)
       (filter #(keyword? (first %)))))

(defn- require-specs [ns-form]
  (->> (ns-clauses ns-form)
       (filter #(#{:require :use} (first %)))
       (mapcat rest)
       (mapcat expand-libspec)))

(defn- import-packages [ns-form]
  (->> (ns-clauses ns-form)
       (filter #(= :import (first %)))
       (mapcat rest)
       (keep (fn [spec]
               (cond
                 (symbol? spec)
                 (let [s (str spec)
                       i (str/last-index-of s ".")]
                   (when i (subs s 0 i)))
                 (and (vector? spec) (symbol? (first spec)))
                 (str (first spec))
                 :else nil)))))

(defn- project-ns? [sym prefix]
  (let [s (str sym)
        p (str prefix)]
    (or (= s p) (str/starts-with? s (str p ".")))))

(defn- class-id [ns-str prefix]
  (let [dot (str prefix ".")
        tail (if (str/starts-with? ns-str dot)
               (subs ns-str (count dot))
               ns-str)]
    (keyword tail)))

(defn- class-name [id]
  "Last ns segment only — do not prefix the module with its component."
  (->> (str/split (name (last (str/split (name id) #"\."))) #"\-")
       (remove str/blank?)
       (map str/capitalize)
       (str/join)))

(defn- aliases-of [specs]
  (into {}
        (keep (fn [{:keys [lib alias]}]
                (when alias [alias lib]))
              specs)))

(defn- resolve-lib [sym aliases]
  (when-let [n (namespace sym)]
    (str (get aliases (symbol n) (symbol n)))))

(defn- protocol-nses [forms aliases current-ns]
  (into []
        (comp (filter #(and (seq? %) (#{'defrecord 'deftype} (first %))))
              (mapcat #(drop 3 %))
              (filter symbol?)
              (keep #(resolve-lib % aliases))
              (remove #(= % current-ns)))
        forms))

(defn- walk-forms [x]
  (tree-seq sequential? seq x))

(defn- requiring-resolve-op? [op]
  (and (symbol? op)
       (= "requiring-resolve" (name op))))

(defn- quoted-symbol [x]
  (cond
    (qualified-symbol? x) x
    (and (seq? x) (= 'quote (first x)) (symbol? (second x))) (second x)
    :else nil))

(defn- requiring-resolve-libs [forms aliases]
  (into []
        (comp (filter seq?)
              (filter #(requiring-resolve-op? (first %)))
              (keep #(quoted-symbol (second %)))
              (keep #(resolve-lib % aliases)))
        (walk-forms forms)))

(defn- interface? [forms]
  (boolean (some #(and (seq? %) (= 'defprotocol (first %))) forms)))

(defn- parse-file [file prefix]
  (let [forms (read-forms file)
        ns-form (ns-form forms)]
    (when ns-form
      (let [ns-str (str (second ns-form))
            specs (require-specs ns-form)
            aliases (aliases-of specs)
            id (class-id ns-str prefix)
            libs (concat (map (comp str :lib) specs)
                         (requiring-resolve-libs forms aliases))]
        {:id id
         :name (class-name id)
         :ns ns-str
         :lang :clojure
         :file (graph/relative-path file)
         :stereotype (when (interface? forms) :interface)
         :requires (->> libs
                        (map symbol)
                        (filter #(project-ns? % prefix))
                        (map #(class-id (str %) prefix))
                        (remove #(= % id))
                        distinct
                        vec)
         :foreign-requires (->> (concat libs (import-packages ns-form))
                                (remove #(project-ns? (symbol %) prefix))
                                distinct
                                vec)
         :implements (->> (protocol-nses forms aliases ns-str)
                          (filter #(project-ns? (symbol %) prefix))
                          (map #(class-id % prefix))
                          (remove #(= % id))
                          vec)}))))

(defn- as-edges [c]
  (concat
    (map (fn [to] {:from (:id c) :to to :kind :dependency}) (:requires c))
    (map (fn [to] {:from (:id c) :to (keyword to) :kind :dependency})
         (:foreign-requires c))
    (map (fn [to] {:from (:id c) :to to :kind :implements}) (:implements c))))

(defn- foreign-class [ns-str]
  {:id (keyword ns-str)
   :name ns-str
   :ns ns-str
   :foreign true})

(defrecord ClojureGraph []
  graph/LanguageGraph
  (scan [_ root opts]
    (let [prefix (or (:prefix opts) "uml-viewer")
          parsed (keep #(parse-file % prefix) (source-files root))
          project (mapv #(dissoc % :requires :implements :foreign-requires) parsed)
          foreigns (->> parsed
                        (mapcat :foreign-requires)
                        distinct
                        (mapv foreign-class))
          classes (into project foreigns)
          edges (vec (mapcat as-edges parsed))]
      {:classes classes :edges edges})))

(def impl (->ClojureGraph))

(graph/register! :clojure impl)
