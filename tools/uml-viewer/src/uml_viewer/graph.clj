(ns uml-viewer.graph
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defprotocol LanguageGraph
  (scan [this root opts]
    "Project graph of classes and edges from source under `root`.
     `opts` is a map; `:prefix` is the project namespace prefix.
     Returns `{:classes [{:id :name :ns :stereotype :foreign}]
               :edges [{:from :to :kind}]}`.
     External requires are classes with `:foreign true`.
     A scan may also return `:commands` and `:invokes` for `merge-scans`."))

(defonce ^:private languages (atom {}))

(defn register!
  "Install `impl` as the graph scanner for `lang` (e.g. `:clojure`)."
  [lang impl]
  (swap! languages assoc lang impl)
  lang)

(defn lookup
  [lang]
  (get @languages lang))

(defn scan-project
  "Scan `root` with the registered scanner for `lang` (default `:clojure`)."
  ([root] (scan-project :clojure root {}))
  ([lang-or-root root-or-opts]
   (if (map? root-or-opts)
     (scan-project :clojure lang-or-root root-or-opts)
     (scan-project lang-or-root root-or-opts {})))
  ([lang root opts]
   (if-let [impl (lookup lang)]
     (scan impl root opts)
     (throw (ex-info (str "no LanguageGraph for " lang) {:lang lang})))))

(defn id-of
  "Keyword id of namespace `ns-str` with `prefix` removed.
  `demo.a` with prefix `demo` is `:a`. A name equal to the prefix stays whole."
  [ns-str prefix]
  (let [prefix (str (or prefix ""))
        dotted (str prefix ".")]
    (keyword
      (cond
        (str/blank? prefix) (str ns-str)
        (str/starts-with? (str ns-str) dotted) (subs (str ns-str) (count dotted))
        :else (str ns-str)))))

(defn module-name
  "Last segment of `id`. Hyphen-words are capitalized.
  A segment that already contains a capital keeps the rest of its spelling."
  [id]
  (let [seg (last (str/split (name (keyword id)) #"\."))]
    (->> (str/split seg #"-")
         (remove str/blank?)
         (map (fn [part]
                (if (re-find #"[A-Z]" part)
                  (str (str/upper-case (subs part 0 1)) (subs part 1))
                  (str/capitalize part))))
         (str/join))))

(defn relative-path
  "Path of `file` relative to the working directory, with forward slashes."
  [file]
  (let [path (-> (.getCanonicalPath (io/file file))
                 (str/replace #"\\" "/"))
        root (-> (.getCanonicalPath (io/file (or (System/getProperty "user.dir") ".")))
                 (str/replace #"\\" "/"))
        prefix (str root "/")]
    (if (str/starts-with? path prefix)
      (subs path (count prefix))
      path)))

(defn file-base
  "Last path segment of `file`, or nil."
  [file]
  (when (seq (str file))
    (let [n (peek (str/split (str/replace (str file) #"\\" "/") #"/"))]
      (when-not (str/blank? n) n))))

(defn ns-join
  "Dotted name of `relative` under `ns-prefix`."
  [ns-prefix relative]
  (cond
    (str/blank? relative) (str ns-prefix)
    (str/blank? ns-prefix) relative
    :else (str ns-prefix "." relative)))

(defn foreign-class
  "A class that lives outside the scanned project."
  [id]
  {:id id :name (name id) :ns (name id) :foreign true})

(defn member-edges
  "Edges from `c` for each `[key kind]` pair."
  [c pairs]
  (mapcat (fn [[k kind]]
            (map (fn [to] {:from (:id c) :to to :kind kind}) (get c k)))
          pairs))

(defn next-char
  [source i]
  (let [j (inc i)]
    (when (< j (count source))
      (.charAt ^String source j))))

(defn line-comment?
  [c nxt]
  (and (= c \/) (= nxt \/)))

(defn block-comment?
  [c nxt]
  (and (= c \/) (= nxt \*)))

(defn- blank-span [sb n]
  (dotimes [_ n] (.append ^StringBuilder sb \space)))

(defn mask-line-comment
  "Index just after the line comment that starts at `i`."
  [source sb i n]
  (let [j (or (str/index-of source \newline i) n)]
    (blank-span sb (- j i))
    j))

(defn- comment-mask-char [c]
  (if (= c \newline) \newline \space))

(defn mask-block-comment
  "Index just after the block comment that starts at `i`."
  [source sb i n]
  (let [j (or (str/index-of source "*/" (+ i 2)) (- n 2))
        end (min n (+ j 2))]
    (doseq [k (range i end)]
      (.append ^StringBuilder sb (comment-mask-char (.charAt ^String source k))))
    end))

(defn copy-span
  "Copy `source` from `i` to `end` and record that range as a hole."
  [sb source holes i end]
  (.append ^StringBuilder sb (subs source i end))
  {:i end :holes (conj holes [i end])})

(defn scan-quoted
  "Index just after the quoted literal that opens at `i`."
  [source i quote n]
  (loop [j (inc i) esc false]
    (cond
      (>= j n) n
      esc (recur (inc j) false)
      (= (.charAt ^String source j) \\) (recur (inc j) true)
      (= (.charAt ^String source j) quote) (inc j)
      :else (recur (inc j) false))))

(defn merge-scans
  "Combine language scans into one graph.
  Each scan is `{:classes :edges :commands :invokes}`.
  `:commands` maps a class id to command names. `:invokes` maps a class id
  to names it calls. A call becomes a `:dependency` on the command's class."
  [scans]
  (let [scans (vec scans)
        classes (mapcat :classes scans)
        by-id (group-by :id classes)
        dup (->> by-id
                 (keep (fn [[id cs]]
                         (when (> (count (remove :foreign cs)) 1) id)))
                 first)]
    (when dup
      (throw (ex-info (str "duplicate class id: " dup) {:id dup})))
    (let [classes (mapv (fn [id]
                          (let [cs (get by-id id)]
                            (or (first (remove :foreign cs))
                                (first cs))))
                        (distinct (map :id classes)))
          owners (into {}
                       (for [scan scans
                             [id cmds] (:commands scan)
                             cmd cmds]
                         [cmd id]))
          linked (for [scan scans
                       [id names] (:invokes scan)
                       name names
                       :let [to (get owners name)]
                       :when (and to (not= to id))]
                   {:from id :to to :kind :dependency})
          edges (->> (concat (mapcat :edges scans) linked)
                     distinct
                     vec)]
      {:classes classes :edges edges})))
