(ns uml-viewer.python-language.graph-python
  "Python LanguageGraph: one class per module, import edges, class bases."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [uml-viewer.graph :as graph]))

(def ^:private skip-dir-names
  #{"__pycache__" "venv" "site-packages" "dist" "build"
    "node_modules" "tests" "__pypackages__"})

(defn- skip-dir? [name]
  (or (str/starts-with? name ".")
      (contains? skip-dir-names name)))

(defn- excluded-name? [name]
  (or (= name "conftest.py")
      (boolean (re-find #"^(?:test_.+|.+_tests?)\.py$" name))))

(defn- source-files [root]
  (let [root (.getCanonicalFile (io/file root))]
    (->> (tree-seq (fn [f]
                     (and (.isDirectory f)
                          (or (= f root) (not (skip-dir? (.getName f))))))
                   (fn [dir] (vec (.listFiles dir)))
                   root)
         (filter #(.isFile %))
         (filter #(str/ends-with? (.getName %) ".py"))
         (remove #(excluded-name? (.getName %)))
         (sort-by #(.getPath %)))))

(declare skip-string)

(defn- closer-at? [source j q len]
  (let [n (count source)]
    (and (<= (+ j len) n)
         (every? #(= q (.charAt ^String source %)) (range j (+ j len))))))

(defn- escaped-string-char? [source j c n raw? q]
  (and (= c \\)
       (< (inc j) n)
       (or (not raw?) (= q (.charAt ^String source (inc j))))))

(defn- doubled-brace? [source j c n brace]
  (and (< (inc j) n)
       (= c brace)
       (= brace (.charAt ^String source (inc j)))))

(defn- f-escape? [source j c n f? brace]
  (and f? (doubled-brace? source j c n brace)))

(def ^:private open-brace \{)
(def ^:private close-brace \})

(defn- f-open? [c f?]
  (and f? (= c open-brace)))

(defn- f-close? [c f? depth]
  (and f? (pos? depth) (= c close-brace)))

(defn- f-string-step [source j c n f? depth]
  (cond
    (f-escape? source j c n f? open-brace) {:j (+ j 2) :depth depth}
    (f-escape? source j c n f? close-brace) {:j (+ j 2) :depth depth}
    (f-open? c f?) {:j (inc j) :depth (inc depth)}
    (f-close? c f? depth) {:j (inc j) :depth (dec depth)}
    :else nil))

(defn- py-quote? [c]
  (or (= c \") (= c \')))

(defn- nested-quote? [c depth]
  (and (pos? depth) (py-quote? c)))

(defn- at-closer? [source j depth q len]
  (and (zero? depth) (closer-at? source j q len)))

(defn- skip-nested [source j]
  (or (skip-string source j) (inc j)))

(defn- escape-advance [source j c n raw? q depth]
  (when (escaped-string-char? source j c n raw? q)
    {:j (+ j 2) :depth depth}))

(defn- brace-advance [brace]
  (when brace
    {:j (:j brace) :depth (:depth brace)}))

(defn- nested-advance [source j c depth]
  (when (nested-quote? c depth)
    {:j (skip-nested source j) :depth depth}))

(defn- closer-advance [source j depth q len]
  (when (at-closer? source j depth q len)
    {:end (+ j len)}))

(defn- string-advance [source j depth q len raw? f? n]
  (let [c (.charAt ^String source j)
        brace (f-string-step source j c n f? depth)]
    (or (escape-advance source j c n raw? q depth)
        (brace-advance brace)
        (nested-advance source j c depth)
        (closer-advance source j depth q len)
        {:j (inc j) :depth depth})))

(defn- scan-string
  "Index just after the string body that starts at `j`."
  [source j q len raw? f?]
  (let [n (count source)]
    (loop [j j depth 0]
      (if (>= j n)
        n
        (let [step (string-advance source j depth q len raw? f? n)]
          (if (:end step)
            (:end step)
            (recur (:j step) (:depth step))))))))

(defn- string-open
  "Prefix flags when a string starts at quote `i`, else nil."
  [source i]
  (let [q (.charAt ^String source i)]
    (when (or (= q \") (= q \'))
      (loop [j (dec i) raw? false f? false]
        (if (and (>= j 0)
                 (#{\r \R \u \U \b \B \f \F} (.charAt ^String source j)))
          (let [c (.charAt ^String source j)]
            (recur (dec j) (or raw? (or (= c \r) (= c \R)))
                   (or f? (or (= c \f) (= c \F)))))
          (when (or (neg? j)
                    (let [c (.charAt ^String source j)]
                      (and (not (Character/isLetterOrDigit c))
                           (not= c \_))))
            {:raw raw? :f f?}))))))

(defn- skip-string
  "Index just after the string whose opening quote is at `i`, or nil."
  [source i]
  (when (and (< i (count source)) (string-open source i))
    (let [q (.charAt ^String source i)
          n (count source)
          flags (string-open source i)
          triple? (and (< (+ i 2) n)
                       (= q (.charAt ^String source (inc i)))
                       (= q (.charAt ^String source (+ i 2))))
          len (if triple? 3 1)]
      (scan-string source (+ i len) q len (:raw flags) (:f flags)))))

(defn- mask-hash-comment [source sb i n]
  (let [j (or (str/index-of source \newline i) n)]
    (dotimes [_ (- j i)] (.append sb \space))
    j))

(defn- mask-python-string [source sb holes i]
  (let [end (skip-string source i)]
    (.append sb (subs source i end))
    {:i end :holes (conj holes [i end])}))

(defn- mask-comments
  "Comments become spaces. String literals are copied and recorded as
  half-open ranges so a match inside them can be ignored. An f-string
  keeps quotes inside `{...}` in the hole, so they do not end the literal."
  [source]
  (let [n (count source)
        sb (StringBuilder. n)]
    (loop [i 0 holes []]
      (if (>= i n)
        {:text (str sb) :holes holes}
        (let [c (.charAt ^String source i)]
          (cond
            (= c \#)
            (recur (mask-hash-comment source sb i n) holes)

            (string-open source i)
            (let [step (mask-python-string source sb holes i)]
              (recur (:i step) (:holes step)))

            :else
            (do (.append sb c)
                (recur (inc i) holes))))))))

(defn- in-hole? [holes i]
  (boolean (some (fn [[a b]] (and (<= a i) (< i b))) holes)))

(defn- ident-char? [c]
  (or (Character/isLetterOrDigit ^char c) (= c \_)))

(defn- keyword-at? [text i word]
  (and (.startsWith ^String text ^String word (int i))
       (or (zero? i) (not (ident-char? (.charAt ^String text (dec i)))))
       (let [j (+ i (count word))]
         (or (>= j (count text))
             (not (ident-char? (.charAt ^String text j)))))))

(defn- stmt-start? [text i]
  (loop [j (dec i)]
    (cond
      (neg? j) true
      (#{ \newline \; } (.charAt ^String text j)) true
      (Character/isWhitespace (.charAt ^String text j)) (recur (dec j))
      :else false)))

(defn- continued-line? [text j c n]
  (and (= c \\) (< (inc j) n) (= \newline (.charAt ^String text (inc j)))))

(defn- bump [c open close depth]
  (cond
    (= c open) (inc depth)
    (= c close) (max 0 (dec depth))
    :else depth))

(defn- next-depths [c paren brack brace]
  [(bump c \( \) paren)
   (bump c \[ \] brack)
   (bump c \{ \} brace)])

(defn- statement-break? [c paren brack brace]
  (and (= c \newline) (zero? paren) (zero? brack) (zero? brace)))

(defn- statement-end [text i]
  (let [n (count text)]
    (loop [j i paren 0 brack 0 brace 0]
      (if (>= j n)
        n
        (let [c (.charAt ^String text j)]
          (cond
            (continued-line? text j c n)
            (recur (+ j 2) paren brack brace)

            (statement-break? c paren brack brace)
            j

            :else
            (let [[paren brack brace] (next-depths c paren brack brace)]
              (recur (inc j) paren brack brace))))))))

(defn- flatten-stmt [s]
  (-> s
      (str/replace #"\\\n" " ")
      (str/replace #"\s+" " ")
      str/trim))

(defn- binding-part [part]
  (when-let [[_ name alias] (re-matches #"([A-Za-z_][\w.]*)(?:\s+as\s+([A-Za-z_]\w*))?"
                                        (str/trim part))]
    {:name name
     :bind (or alias (first (str/split name #"\.")))}))

(defn- import-parts [s]
  (->> (str/split (str/replace s #"[()]" " ") #",")
       (map str/trim)
       (remove str/blank?)
       (keep binding-part)
       vec))

(defn- parse-import [stmt]
  (let [s (flatten-stmt stmt)]
    (cond
      (str/starts-with? s "from ")
      (when-let [[_ dots module names] (re-matches #"from\s+(\.*)([A-Za-z_][\w.]*)?\s+import\s+(.+)" s)]
        {:kind :from
         :dots (count dots)
         :module module
         :names (import-parts names)})

      (str/starts-with? s "import ")
      (when-let [[_ modules] (re-matches #"import\s+(.+)" s)]
        {:kind :import
         :modules (import-parts modules)}))))

(defn- import-at? [text holes i]
  (and (not (in-hole? holes i))
       (stmt-start? text i)
       (or (keyword-at? text i "from")
           (keyword-at? text i "import"))))

(defn- conj-import [acc parsed]
  (if parsed (conj acc parsed) acc))

(defn- imports-of [text holes]
  (let [n (count text)]
    (loop [i 0 acc []]
      (if (>= i n)
        acc
        (if (import-at? text holes i)
          (let [end (statement-end text i)]
            (recur end (conj-import acc (parse-import (subs text i end)))))
          (recur (inc i) acc))))))

(defn- bases-of [header]
  (let [flat (str/replace header #"\s+" " ")]
    (when-let [m (re-find #"\(([^)]*)\)\s*:" flat)]
      (->> (str/split (second m) #",")
           (map str/trim)
           (keep (fn [base]
                   (when-let [[_ name] (re-matches #"([A-Za-z_][\w.]*)" base)]
                     name)))
           vec))))

(defn- return-of [header]
  (let [flat (str/replace header #"\s+" " ")]
    (second (re-find #"\)\s*->\s*([A-Za-z_][A-Za-z0-9_]*)\s*:" flat))))

(defn- defs-of [text holes]
  (let [m (re-matcher #"(?m)^(?:async[ \t]+def|def|class)[ \t]+([A-Za-z_][A-Za-z0-9_]*)" text)]
    (loop [acc []]
      (if (.find m)
        (let [i (.start m)
              name (.group m 1)]
          (recur (if (or (in-hole? holes i) (str/starts-with? name "_"))
                   acc
                   (let [header (subs text i (statement-end text i))]
                     (conj acc (if (str/starts-with? (str/triml header) "class")
                                 {:name name :kind :class :bases (bases-of header)}
                                 {:name name :kind :function :return (return-of header)}))))))
        acc))))

(defn read-module
  "Module surface of Python `source`: imports, and public module-level
  functions and classes."
  [source]
  (let [{:keys [text holes]} (mask-comments source)]
    {:imports (imports-of text holes)
     :defs (defs-of text holes)}))

(defn- module-relative [file root]
  (let [root (.getCanonicalFile (io/file root))
        file (.getCanonicalFile (io/file file))
        rel (str/replace (str (.relativize (.toPath root) (.toPath file))) #"\\" "/")
        no-ext (str/replace rel #"\.py$" "")]
    (-> no-ext
        (str/replace #"(^|/)__init__$" "$1")
        (str/replace #"/$" "")
        (str/replace "/" "."))))

(defn- ns-join [ns-prefix relative]
  (cond
    (str/blank? relative) (str ns-prefix)
    (str/blank? ns-prefix) relative
    (or (= relative ns-prefix)
        (str/starts-with? relative (str ns-prefix "."))) relative
    :else (str ns-prefix "." relative)))

(defn- parent-mod [dotted]
  (when (and (seq dotted) (str/includes? dotted "."))
    (subs dotted 0 (str/last-index-of dotted "."))))

(defn- package-of [ns-str init?]
  (if init? ns-str (or (parent-mod ns-str) "")))

(defn- climb
  "Package reached by `dots` leading dots. One dot stays at `package`."
  [package dots]
  (loop [p package left (dec dots)]
    (cond
      (neg? left) nil
      (zero? left) p
      (str/blank? p) nil
      :else (recur (or (parent-mod p) "") (dec left)))))

(defn- join-mod [base name]
  (cond
    (str/blank? name) base
    (str/blank? base) name
    :else (str base "." name)))

(defn- exact-id [dotted by-ns by-rel]
  (when-not (str/blank? (str dotted))
    (or (get by-ns dotted) (get by-rel dotted))))

(defn- marker? [base]
  (contains? #{"Protocol" "ABC" "ABCMeta"}
             (last (str/split base #"\."))))

(defn- from-root [package {:keys [dots module]}]
  (if (pos? dots)
    (when-let [base (climb package dots)]
      (if (str/blank? module) base (join-mod base module)))
    module))

(defn- relative-only? [imp]
  (and (pos? (:dots imp)) (str/blank? (:module imp))))

(defn- bind-child [root name bind by-ns by-rel]
  (when-let [id (exact-id (join-mod root name) by-ns by-rel)]
    {:id id :bind bind}))

(defn- bind-name [root parent name bind by-ns by-rel]
  (when-let [id (or (exact-id (join-mod root name) by-ns by-rel)
                    parent)]
    {:id id :bind bind}))

(defn- from-fallback [root parent]
  (cond
    parent [{:id parent}]
    (not (str/blank? (str root))) [{:foreign root}]))

(defn- resolve-members [root parent imp by-ns by-rel]
  (let [hits (keep (fn [{:keys [name bind]}]
                     (bind-name root parent name bind by-ns by-rel))
                   (:names imp))]
    (if (seq hits)
      hits
      (from-fallback root parent))))

(defn- resolve-from [package imp by-ns by-rel]
  (let [root (from-root package imp)]
    (cond
      (nil? root) nil
      (relative-only? imp)
      (keep (fn [{:keys [name bind]}]
              (bind-child root name bind by-ns by-rel))
            (:names imp))
      :else
      (resolve-members root (exact-id root by-ns by-rel) imp by-ns by-rel))))

(defn- resolve-import [imp by-ns by-rel]
  (keep (fn [{:keys [name bind]}]
          (if-let [id (exact-id name by-ns by-rel)]
            {:id id :bind bind :path name}
            {:foreign name}))
        (:modules imp)))

(defn- resolved-imports [imports package by-ns by-rel]
  (mapcat (fn [imp]
            (if (= :from (:kind imp))
              (resolve-from package imp by-ns by-rel)
              (resolve-import imp by-ns by-rel)))
          imports))

(defn- target-of [expr bindings paths]
  (when (and expr (re-matches #"[A-Za-z_][\w.]*" expr))
    (or (get bindings expr)
        (get paths expr)
        (let [segs (str/split expr #"\.")]
          (some (fn [n]
                  (let [p (str/join "." (take n segs))]
                    (or (get paths p) (get bindings p))))
                (range (dec (count segs)) 0 -1))))))

(defn- interface-module? [defs]
  (let [classes (filter #(= :class (:kind %)) defs)
        functions (filter #(= :function (:kind %)) defs)]
    (and (seq classes)
         (empty? functions)
         (every? (fn [c]
                   (and (seq (:bases c))
                        (every? marker? (:bases c))))
                 classes))))

(defn- op-maps [defs]
  (mapv (fn [d] {:name (:name d) :text (:name d)}) defs))

(defn- analyze [surface ns-str init? by-ns by-rel]
  (let [package (package-of ns-str init?)
        hits (resolved-imports (:imports surface) package by-ns by-rel)
        bindings (into {} (keep (fn [hit]
                                  (when (:bind hit) [(:bind hit) (:id hit)]))
                                hits))
        paths (into {} (keep (fn [hit]
                               (when (:path hit) [(:path hit) (:id hit)]))
                             hits))
        requires (->> hits (keep :id) distinct (remove nil?) vec)
        foreigns (->> hits (keep :foreign) distinct (map keyword) vec)
        inherits (->> (:defs surface)
                      (mapcat :bases)
                      (keep (fn [base]
                              (when-not (marker? base)
                                (target-of base bindings paths))))
                      distinct vec)
        impls (->> (concat
                     (->> (:defs surface)
                          (mapcat :bases)
                          (keep (fn [base]
                                  (when (marker? base)
                                    (target-of base bindings paths)))))
                     (->> (:defs surface)
                          (keep :return)
                          (keep bindings)))
                   distinct vec)]
    {:requires requires
     :foreigns foreigns
     :inherits inherits
     :impls impls
     :ops (op-maps (:defs surface))
     :stereotype (when (interface-module? (:defs surface)) :interface)}))

(defn- file-meta [file root prefix ns-prefix]
  (let [relative (module-relative file root)
        ns-str (ns-join ns-prefix relative)]
    {:file file
     :init? (= "__init__.py" (.getName ^java.io.File file))
     :relative relative
     :ns ns-str
     :id (graph/id-of ns-str prefix)}))

(defn- as-edges [c]
  (concat
    (map (fn [to] {:from (:id c) :to to :kind :implements}) (:impls c))
    (map (fn [to] {:from (:id c) :to to :kind :inheritance}) (:inherits c))
    (map (fn [to] {:from (:id c) :to to :kind :dependency}) (:requires c))
    (map (fn [to] {:from (:id c) :to to :kind :dependency}) (:foreigns c))))

(defn- public-class [c]
  (cond-> (dissoc c :requires :foreigns :inherits :impls :init? :relative)
    (empty? (:ops c)) (dissoc :ops)
    (nil? (:stereotype c)) (dissoc :stereotype)))

(defrecord PythonGraph []
  graph/LanguageGraph
  (scan [_ root opts]
    (let [prefix (or (:prefix opts) "app")
          ns-prefix (or (:ns-prefix opts) prefix)
          rootf (.getCanonicalFile (io/file root))
          files (source-files rootf)
          metas (mapv #(file-meta % rootf prefix ns-prefix) files)
          by-ns (into {} (map (juxt :ns :id) metas))
          by-rel (into {} (keep (fn [m]
                                  (when-not (str/blank? (:relative m))
                                    [(:relative m) (:id m)]))
                                metas))
          parsed (mapv (fn [m]
                         (let [surface (read-module (slurp (:file m)))
                               facts (analyze surface (:ns m) (:init? m) by-ns by-rel)]
                           (merge m facts
                                  {:name (graph/module-name (:id m))
                                   :lang :python
                                   :file (graph/relative-path (:file m))})))
                       metas)
          project-ids (set (map :id parsed))
          foreigns (->> parsed
                        (mapcat :foreigns)
                        distinct
                        (remove project-ids)
                        (mapv graph/foreign-class))]
      {:classes (into (mapv public-class parsed) foreigns)
       :edges (->> (mapcat as-edges parsed)
                   (remove #(= (:from %) (:to %)))
                   distinct
                   vec)})))

(def impl (->PythonGraph))

(graph/register! :python impl)
