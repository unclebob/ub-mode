(ns uml-viewer.clojure-language.source-clojure
  "Clojure LanguageSource: src/ ns path + top-level defn/defn- slice."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [uml-viewer.source :as source])
  (:import [java.util.regex Pattern]))

(defn ns->source-path
  [ns-name]
  (when ns-name
    (let [rel (-> (str ns-name)
                  (str/replace "-" "_")
                  (str/replace "." "/"))
          candidates [(str "src/" rel ".clj")
                      (str "src/" rel ".cljc")
                      (str "src/" rel ".cljs")]]
      (first (filter #(.exists (io/file %)) candidates)))))

(defn- form-end
  "Index after the top-level form that starts at `start` (a '(')."
  [source start]
  (loop [i start depth 0 in-str false esc false]
    (if (>= i (count source))
      (count source)
      (let [ch (.charAt source i)]
        (cond
          esc (recur (inc i) depth in-str false)
          in-str (cond
                   (= ch \\) (recur (inc i) depth true true)
                   (= ch \") (recur (inc i) depth false false)
                   :else (recur (inc i) depth true false))
          (= ch \") (recur (inc i) depth true false)
          (= ch \() (recur (inc i) (inc depth) false false)
          (= ch \)) (let [d (dec depth)]
                      (if (zero? d)
                        (inc i)
                        (recur (inc i) d false false)))
          :else (recur (inc i) depth false false))))))

(defn- member-start
  [source member-name]
  (when (and source member-name)
    (let [p (Pattern/compile
              (str "(?m)^\\((?:defn-|defn)\\s+"
                   (Pattern/quote (str member-name))
                   "(?=[\\s\\[\\{\\\"]|$)"))
          m (.matcher p source)]
      (when (.find m)
        (.start m)))))

(defn extract-member
  "Source text of `defn` / `defn-` named `member-name`, or nil."
  [source member-name]
  (when-let [start (member-start source member-name)]
    (str/trimr (subs source start (form-end source start)))))

(defn member-line
  "1-based line of `member-name` in `source`, or nil."
  [source member-name]
  (when-let [start (member-start source member-name)]
    (source/line-number source start)))

(defrecord ClojureSource []
  source/LanguageSource
  (locate [_ ident]
    (ns->source-path (:ns ident)))
  (extract [_ source ident]
    (extract-member source (:name ident)))
  (start-line [_ source ident]
    (member-line source (:name ident)))
  (title [_ ident]
    (str (:ns ident) "/" (:name ident))))

(def impl (->ClojureSource))

(source/register! :clojure impl)
