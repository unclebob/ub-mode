(ns uml-viewer.go-language.read-go
  "Declarations of one Go source file: package, imports, funcs, methods, types."
  (:require [clojure.string :as str]
            [uml-viewer.graph :as graph]))

;; ---------------------------------------------------------------- masking

(defn- blank-char [c]
  (if (= c \newline) \newline \space))

(defn- literal-end
  "Index just after the string, rune, or raw literal that opens at `i`."
  [source i c n]
  (if (= c \`)
    (inc (or (str/index-of source "`" (inc i)) (dec n)))
    (graph/scan-quoted source i c n)))

(defn- mask-literal
  "Copy the delimiters of the literal at `i`, blank its contents."
  [source sb i end]
  (.append ^StringBuilder sb (.charAt ^String source i))
  (doseq [k (range (inc i) (dec end))]
    (.append ^StringBuilder sb (blank-char (.charAt ^String source k))))
  (when (> (dec end) i)
    (.append ^StringBuilder sb (.charAt ^String source (dec end))))
  end)

(defn- mask-step [source sb i n]
  (let [c (.charAt ^String source i)
        nxt (graph/next-char source i)]
    (cond
      (graph/line-comment? c nxt) [(graph/mask-line-comment source sb i n) nil]
      (graph/block-comment? c nxt) [(graph/mask-block-comment source sb i n) nil]
      (#{\" \' \`} c) (let [end (literal-end source i c n)]
                        [(mask-literal source sb i end) [i end]])
      :else (do (.append ^StringBuilder sb c) [(inc i) nil]))))

(defn mask
  "Comments and literal contents become blanks, at the same offsets.
  `:strings` lists each literal as `[start end]`."
  [source]
  (let [n (count source)
        sb (StringBuilder. n)]
    (loop [i 0 strings []]
      (if (>= i n)
        {:code (str sb) :strings strings}
        (let [[j span] (mask-step source sb i n)]
          (recur j (cond-> strings span (conj span))))))))

;; ---------------------------------------------------------------- brackets

(def ^:private openers {\( \) \[ \] \{ \}})
(def ^:private closers #{\) \] \}})

(defn- depth-step [depth c]
  (cond (openers c) (inc depth)
        (closers c) (dec depth)
        :else depth))

(defn matching
  "Index of the bracket that closes the one at `i` in `code`, or nil."
  [^String code i]
  (loop [j (inc i) depth 1]
    (when (< j (count code))
      (let [depth (depth-step depth (.charAt code j))]
        (if (zero? depth) j (recur (inc j) depth))))))

(defn- inside
  "Text between the bracket at `i` and its partner."
  [code i]
  (subs code (inc i) (or (matching code i) (count code))))

(defn split-top
  "Pieces of `text` cut at each top-level character in `seps`."
  [text seps]
  (loop [i 0 depth 0 start 0 acc []]
    (if (>= i (count text))
      (conj acc (subs text start))
      (let [c (.charAt ^String text i)]
        (if (and (zero? depth) (seps c))
          (recur (inc i) depth (inc i) (conj acc (subs text start i)))
          (recur (inc i) (depth-step depth c) start acc))))))

(defn- members
  "Non-blank lines or `;` statements of a block body."
  [body]
  (->> (split-top body #{\newline \;})
       (map str/trim)
       (remove str/blank?)
       vec))

(defn- item-count
  "Number of comma-separated items in a parameter or result list."
  [text]
  (count (remove str/blank? (split-top text #{\,}))))

;; ---------------------------------------------------------------- declarations

(def ident "[A-Za-z_][A-Za-z0-9_]*")

(defn- skip-type-params
  "Index past `[...]` when it opens at `i`, else `i`."
  [code i]
  (if (and (< i (count code)) (= \[ (.charAt ^String code i)))
    (inc (or (matching code i) (dec (count code))))
    i))

(defn- body-open
  "Index of the `{` that opens a function body in `code` from `i`, or nil.
  A brace right after `struct` or `interface` is part of a type."
  [code i]
  (let [m (re-matcher #"\{" code)]
    (loop [from i]
      (when (.find m from)
        (let [at (.start m)
              before (str/trimr (subs code i at))]
          (if (re-find #"(?:struct|interface)$" before)
            (recur (inc (or (matching code at) at)))
            at))))))

(defn- result-count
  "`(a, b error)` has two results; a bare `error` has one; none has zero."
  [results]
  (let [r (str/trim results)]
    (item-count (if (str/starts-with? r "(") (inside r 0) r))))

(defn- signature
  "Name, parameter count, and result count of `name(params) results` at `at`."
  [code at name]
  (let [open (str/index-of code "(" at)
        close (or (matching code open) (count code))
        stop (or (body-open code (inc close)) (count code))]
    {:name name
     :params (item-count (subs code (inc open) close))
     :results (result-count (subs code (inc close) stop))}))

(defn- receiver-type [receiver]
  (second (re-find (re-pattern (str "(" ident ")\\s*(?:\\[[^\\]]*\\])?\\s*$")) receiver)))

(defn- read-method [decl]
  (let [open (str/index-of decl "(")
        close (or (matching decl open) (count decl))
        m (re-matcher (re-pattern (str "\\G\\s*(" ident ")\\s*\\(")) decl)]
    (when (.find m (inc close))
      (assoc (signature decl (dec (.end m)) (.group m 1))
        :receiver (receiver-type (subs decl (inc open) close))
        :text decl))))

(defn- read-func [decl]
  (let [m (re-matcher (re-pattern (str "^func\\s+(" ident ")")) decl)]
    (when (.find m)
      (let [at (skip-type-params decl (.end m))]
        (when (re-find #"^\s*\(" (subs decl at))
          (assoc (signature decl at (.group m 1)) :text decl))))))

(defn- method? [decl]
  (boolean (re-find #"^func\s*\(" decl)))

(def ^:private embed-re
  (re-pattern (str "^\\*?(" ident "(?:\\." ident ")?)(?:\\[.*\\])?$")))

(defn- struct-embeds [body]
  (->> (members body)
       (map #(str/trim (str/replace % #"[`\"]" "")))
       (keep #(second (re-find embed-re %)))
       vec))

(defn- interface-member [line]
  (if-let [[_ name] (re-find (re-pattern (str "^(" ident ")\\s*\\(")) line)]
    {:spec (signature line 0 name)}
    (if-let [[_ embed] (re-find embed-re line)]
      {:embed embed}
      {:constraint true})))

(defn- read-interface [body]
  (let [parts (map interface-member (members body))]
    {:specs (vec (keep :spec parts))
     :embeds (vec (keep :embed parts))
     :constraint (boolean (some :constraint parts))}))

(defn- type-kind [rest]
  (cond (re-find #"^struct\b" rest) :struct
        (re-find #"^interface\b" rest) :interface
        :else :other))

(defn- type-body [rest]
  (if-let [open (str/index-of rest "{")]
    (inside rest open)
    ""))

(defn- read-type-spec [spec]
  (when-let [[whole name] (re-find (re-pattern (str "^\\s*(" ident ")")) spec)]
    (let [rest (-> (subs spec (skip-type-params spec (count whole)))
                   str/trim
                   (str/replace #"^=\s*" ""))
          kind (type-kind rest)
          body (type-body rest)]
      (merge {:name name :kind kind :text spec :embeds []}
             (case kind
               :struct {:embeds (struct-embeds body)}
               :interface (read-interface body)
               {})))))

(defn- type-specs [decl]
  (let [rest (str/trim (subs decl (count "type")))]
    (if (str/starts-with? rest "(")
      (members (inside rest 0))
      [rest])))

(defn- import-alias [code start hole]
  (let [line-start (inc (max (or (str/last-index-of code "\n" hole) -1)
                             (or (str/last-index-of code "(" hole) -1)
                             (or (str/last-index-of code ";" hole) -1)))
        prefix (-> (subs code (max start line-start) hole)
                   (str/replace #"^\s*import\b" "")
                   str/trim)]
    (when-not (str/blank? prefix) prefix)))

(defn- read-imports [source code strings start end]
  (->> strings
       (filter (fn [[s _]] (and (<= start s) (< s end))))
       (mapv (fn [[s e]]
               {:alias (import-alias code start s)
                :path (subs source (inc s) (dec e))}))))

(defn- decl-starts [code]
  (let [m (re-matcher #"(?m)^(?:func|type|import|var|const)\b" code)]
    (loop [acc []]
      (if (.find m) (recur (conj acc (.start m))) acc))))

(defn- declarations [code]
  (let [starts (decl-starts code)]
    (map (fn [s e] {:start s :end e :text (subs code s e)})
         starts
         (concat (rest starts) [(count code)]))))

(defn- add-decl [surface source code strings {:keys [start end text]}]
  (cond
    (str/starts-with? text "import")
    (update surface :imports into (read-imports source code strings start end))

    (str/starts-with? text "type")
    (update surface :types into (keep read-type-spec (type-specs text)))

    (method? text)
    (cond-> surface (read-method text) (update :methods conj (read-method text)))

    (str/starts-with? text "func")
    (cond-> surface (read-func text) (update :funcs conj (read-func text)))

    :else surface))

(defn read-file
  "Declarations of one Go source file."
  [source]
  (let [{:keys [code strings]} (mask source)]
    (reduce #(add-decl %1 source code strings %2)
            {:package (second (re-find (re-pattern (str "(?m)^package\\s+(" ident ")")) code))
             :imports [] :funcs [] :methods [] :types []}
            (declarations code))))
