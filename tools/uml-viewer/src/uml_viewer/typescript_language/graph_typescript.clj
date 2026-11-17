(ns uml-viewer.typescript-language.graph-typescript
  "TypeScript LanguageGraph: one class per module, import edges, invoke names."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [uml-viewer.graph :as graph])
  (:import [java.util.regex Pattern]))

(def ^:private exts [".ts" ".tsx" ".js" ".jsx" ".mjs" ".cjs"])

(defn- excluded-name? [name]
  (or (str/ends-with? name ".d.ts")
      (re-find #"\.(?:test|spec)\.[cm]?[jt]sx?$" name)))

(defn- source-files [root]
  (let [root (.getCanonicalFile (io/file root))]
    (->> (file-seq root)
         (remove (fn [f]
                   (re-find #"/node_modules/|/dist/|/target/"
                            (str/replace (str f) #"\\" "/"))))
         (filter #(.isFile %))
         (filter #(re-find #"\.[cm]?[jt]sx?$" (.getName %)))
         (remove #(excluded-name? (.getName %)))
         (sort-by #(.getPath %)))))

(def ^:private regex-keywords
  #{"return" "throw" "case" "void" "typeof" "delete"
    "await" "yield" "else" "do" "in" "of"})

(defn- skip-ws-left [source j]
  (loop [j j]
    (if (and (>= j 0) (Character/isWhitespace (.charAt ^String source j)))
      (recur (dec j))
      j)))

(defn- regex-ident? [c]
  (or (Character/isLetterOrDigit ^char c) (#{\_ \$} c)))

(defn- regex-operand? [c]
  (or (Character/isLetterOrDigit ^char c) (#{\_ \$ \) \]} c)))

(defn- keyword-start [source j]
  (loop [k j]
    (if (and (>= k 0) (regex-ident? (.charAt ^String source k)))
      (recur (dec k))
      (inc k))))

(defn- regex-keyword? [source j]
  (regex-keywords (subs source (keyword-start source j) (inc j))))

(defn- regex-before?
  "True when `/` at `i` can open a regex literal rather than division."
  [source i]
  (let [j (skip-ws-left source (dec i))]
    (or (neg? j)
        (if (regex-operand? (.charAt ^String source j))
          (regex-keyword? source j)
          true))))

(defn- class-open? [c class?]
  (and (= c \[) (not class?)))

(defn- class-close? [c class?]
  (and (= c \]) class?))

(defn- in-class? [c class?]
  (cond
    (class-open? c class?) true
    (class-close? c class?) false
    :else class?))

(defn- regex-closed? [c class?]
  (and (= c \/) (not class?)))

(defn- regex-flag-end [source j n]
  (loop [k (inc j)]
    (if (and (< k n) (Character/isLetter (.charAt ^String source k)))
      (recur (inc k))
      k)))

(defn- regex-end
  "Index just after the flags of a regex that opens at `i`, or nil."
  [source i]
  (let [n (count source)]
    (loop [j (inc i) class? false esc false]
      (when (< j n)
        (let [c (.charAt ^String source j)]
          (cond
            esc (recur (inc j) class? false)
            (= c \\) (recur (inc j) class? true)
            (= c \newline) nil
            (regex-closed? c class?) (regex-flag-end source j n)
            :else (recur (inc j) (in-class? c class?) false)))))))

(defn- slash-regex? [source i c nxt]
  (and (= c \/) nxt (not= nxt \*) (regex-before? source i)))

(defn- string-quote? [c]
  (#{\' \" \`} c))

(defn- mask-regex [source sb holes i c]
  (if-let [end (regex-end source i)]
    (graph/copy-span sb source holes i end)
    (do (.append sb c)
        {:i (inc i) :holes holes})))

(defn- mask-ts-step [source sb holes i n]
  (let [c (.charAt source i)
        nxt (graph/next-char source i)]
    (cond
      (graph/line-comment? c nxt)
      {:i (graph/mask-line-comment source sb i n) :holes holes}

      (graph/block-comment? c nxt)
      {:i (graph/mask-block-comment source sb i n) :holes holes}

      (slash-regex? source i c nxt)
      (mask-regex source sb holes i c)

      (string-quote? c)
      (graph/copy-span sb source holes i (graph/scan-quoted source i c n))

      :else
      (do (.append sb c)
          {:i (inc i) :holes holes}))))

(defn- mask-comments
  "Comments become spaces. String and template literals are copied and
  recorded as half-open ranges so a match inside them can be ignored.
  A regex literal is a hole too, so a quote inside `/\"/g` is not a string."
  [source]
  (let [n (count source)
        sb (StringBuilder. n)]
    (loop [i 0 holes []]
      (if (>= i n)
        {:text (str sb) :holes holes}
        (let [step (mask-ts-step source sb holes i n)]
          (recur (:i step) (:holes step)))))))

(defn- in-hole? [holes i]
  (boolean (some (fn [[a b]] (and (<= a i) (< i b))) holes)))

(defn- header-of
  "Text of a declaration up to its body, assignment, or semicolon."
  [s]
  (loop [i 0 paren 0 bracket 0 brace 0]
    (if (>= i (count s))
      s
      (let [c (.charAt s i)]
        (cond
          (= c \() (recur (inc i) (inc paren) bracket brace)
          (= c \)) (recur (inc i) (max 0 (dec paren)) bracket brace)
          (= c \[) (recur (inc i) paren (inc bracket) brace)
          (= c \]) (recur (inc i) paren (max 0 (dec bracket)) brace)
          (= c \{) (if (and (zero? paren) (zero? bracket) (zero? brace))
                     (subs s 0 i)
                     (recur (inc i) paren bracket (inc brace)))
          (and (#{\= \;} c) (zero? paren) (zero? bracket) (zero? brace))
          (subs s 0 i)
          :else (recur (inc i) paren bracket brace))))))

(defn- match-paren [s open]
  (loop [i (inc open) depth 1]
    (when (< i (count s))
      (case (.charAt s i)
        \( (recur (inc i) (inc depth))
        \) (if (= depth 1) i (recur (inc i) (dec depth)))
        (recur (inc i) depth)))))

(defn- return-type [header]
  (when-let [open (str/index-of header "(")]
    (when-let [close (match-paren header open)]
      (second (re-find #"^\s*:\s*([A-Za-z_$][\w$]*)\s*$"
                       (subs header (inc close)))))))

(defn- const-type [header]
  (second (re-find #"\b(?:const|let|var)\s+[A-Za-z_$][\w$]*\s*:\s*([A-Za-z_$][\w$]*)\s*$"
                   header)))

(defn- statement-end [s start]
  (loop [i start paren 0 brace 0 bracket 0]
    (if (>= i (count s))
      (count s)
      (let [c (.charAt s i)]
        (cond
          (= c \() (recur (inc i) (inc paren) brace bracket)
          (= c \)) (recur (inc i) (max 0 (dec paren)) brace bracket)
          (= c \{) (recur (inc i) paren (inc brace) bracket)
          (= c \}) (recur (inc i) paren (max 0 (dec brace)) bracket)
          (= c \[) (recur (inc i) paren brace (inc bracket))
          (= c \]) (recur (inc i) paren brace (max 0 (dec bracket)))
          (and (= c \;) (zero? paren) (zero? brace) (zero? bracket)) (inc i)
          :else (recur (inc i) paren brace bracket))))))

(defn- from-spec [stmt]
  (second (re-find #"from\s*[\"']([^\"']+)[\"']" stmt)))

(defn- side-spec [stmt]
  (second (re-find #"^import\s*[\"']([^\"']+)[\"']" stmt)))

(defn- brace-body [stmt]
  (second (re-find #"\{([^}]*)\}" stmt)))

(defn- brace-names [stmt]
  (when-let [body (brace-body stmt)]
    (->> (str/split body #",")
         (map str/trim)
         (remove str/blank?)
         (keep (fn [part]
                 (let [part (str/replace part #"^type\s+" "")]
                   (or (second (re-find #"\bas\s+([A-Za-z_$][\w$]*)\s*$" part))
                       (second (re-find #"^([A-Za-z_$][\w$]*)" part))))))
         vec)))

(defn- default-binding [stmt]
  (second (re-find #"^import\s+(?:type\s+)?(?!type\b)([A-Za-z_$][\w$]*)\s*(?:,|\s+from\b)"
                   stmt)))

(defn- star-binding [stmt]
  (second (re-find #"\*\s+as\s+([A-Za-z_$][\w$]*)" stmt)))

(defn- word-at? [text i word]
  (and (.startsWith ^String text ^String word (int i))
       (let [before (when (pos? i) (.charAt text (dec i)))
             after-i (+ i (count word))
             after (when (< after-i (count text)) (.charAt text after-i))
             ident? #(and % (or (Character/isLetterOrDigit ^char %) (#{\_ \$} %)))]
         (and (not (ident? before)) (not (ident? after))))))

(defn- angle-depth [c depth]
  (cond
    (= c \<) (inc depth)
    (= c \>) (dec depth)
    :else depth))

(defn- open-paren? [c]
  (= c \())

(defn- arg-quote? [c]
  (or (= c \') (= c \")))

(defn- quoted-end [text j quote]
  (let [n (count text)]
    (loop [k (inc j) esc false]
      (cond
        (>= k n) n
        esc (recur (inc k) false)
        (= (.charAt text k) \\) (recur (inc k) true)
        (= (.charAt text k) quote) k
        :else (recur (inc k) false)))))

(defn- quoted-arg [text j quote]
  (let [end (quoted-end text j quote)]
    (when (> end j)
      (subs text (inc j) end))))

(defn- invoke-before [c]
  (cond
    (Character/isWhitespace c) {:state :before}
    (= c \<) {:state :angle :angle 1}
    (open-paren? c) {:state :paren :paren 1}
    :else nil))

(defn- invoke-angle [c depth]
  (let [depth (angle-depth c depth)]
    {:state (if (pos? depth) :angle :after)
     :angle depth}))

(defn- invoke-after [c]
  (when (open-paren? c)
    {:state :paren :paren 1}))

(defn- invoke-paren [text j c depth]
  (cond
    (arg-quote? c) (quoted-arg text j c)
    (Character/isWhitespace c) {:state :paren :paren depth}
    :else nil))

(defn- invoke-state [state text j c angle paren]
  (case state
    :before (invoke-before c)
    :angle (invoke-angle c angle)
    :after (invoke-after c)
    :paren (invoke-paren text j c paren)))

(defn- invoke-name [text i]
  (loop [j i state :before angle 0 paren 0]
    (when (< j (count text))
      (let [c (.charAt text j)
            step (invoke-state state text j c angle paren)]
        (if (string? step)
          step
          (when step
            (recur (inc j) (:state step) (:angle step 0) (:paren step 0))))))))

(defn- invoke-at [text holes j]
  (when (and (word-at? text j "invoke")
             (not (in-hole? holes j)))
    (invoke-name text (+ j (count "invoke")))))

(defn- conj-name [acc name]
  (if name (conj acc name) acc))

(defn- invokes-of [text holes]
  (loop [i 0 acc []]
    (if-let [j (str/index-of text "invoke" i)]
      (recur (inc j) (conj-name acc (invoke-at text holes j)))
      acc)))

(defn- line-starts [text holes keyword]
  (let [p (Pattern/compile (str "(?m)^\\s*" keyword "\\b"))
        m (.matcher p text)]
    (loop [acc []]
      (if (.find m)
        (let [i (.start m)]
          (recur (if (in-hole? holes i) acc (conj acc i))))
        acc))))

(defn- at-keyword [text at keyword]
  (or (str/index-of text keyword at) at))

(defn- export-spec [stmt]
  {:spec (from-spec stmt)})

(defn- export-brace-ops [stmt]
  (when-not (re-find #"^export\s+type\b" stmt)
    (brace-names stmt)))

(defn- export-brace [stmt]
  {:spec (from-spec stmt)
   :ops (export-brace-ops stmt)})

(defn- export-function [stmt header]
  (when-let [name (second (re-find #"function\s+\*?\s*([A-Za-z_$][\w$]*)" stmt))]
    {:ops [name] :type (return-type header) :value true}))

(defn- export-named [stmt pattern]
  (when-let [name (second (re-find pattern stmt))]
    {:ops [name] :value true}))

(defn- export-binding [stmt header]
  (when-let [name (second (re-find #"(?:const|let|var)\s+([A-Za-z_$][\w$]*)" stmt))]
    {:ops [name] :type (const-type header) :value true}))

(defn- export-from [stmt]
  (cond
    (re-find #"^export\s+type\s*\{" stmt) (export-spec stmt)
    (re-find #"^export\s+\*" stmt) (export-spec stmt)
    (re-find #"^export\s+\{" stmt) (export-brace stmt)
    :else nil))

(defn- export-value [stmt header]
  (cond
    (re-find #"^export\s+(?:default\s+)?(?:async\s+)?function\b" stmt)
    (export-function stmt header)

    (re-find #"^export\s+(?:default\s+)?class\b" stmt)
    (export-named stmt #"class\s+([A-Za-z_$][\w$]*)")

    (re-find #"^export\s+(?:const|let|var)\b" stmt)
    (export-binding stmt header)

    (re-find #"^export\s+(?:default\s+)?interface\b" stmt)
    {:interface true}

    (re-find #"^export\s+(?:default\s+)?enum\b" stmt)
    (export-named stmt #"enum\s+([A-Za-z_$][\w$]*)")

    :else nil))

(defn- export-fact [text at]
  (let [at (at-keyword text at "export")
        stmt (str/triml (subs text at (statement-end text at)))
        header (header-of (subs text at))]
    (or (export-from stmt)
        (export-value stmt header))))

(defn- import-fact [text at]
  (let [at (at-keyword text at "import")
        stmt (str/triml (subs text at (statement-end text at)))
        spec (or (from-spec stmt) (side-spec stmt))]
    (when spec
      {:spec spec
       :names (vec (concat (brace-names stmt)
                           (keep identity [(default-binding stmt) (star-binding stmt)])))})))

(defn read-module
  "Module surface of TypeScript `source`: import specs, exported names,
  type annotations on those exports, and invoke(\"name\") calls."
  [source]
  (let [{:keys [text holes]} (mask-comments source)
        imports (keep #(import-fact text %) (line-starts text holes "import"))
        exports (keep #(export-fact text %) (line-starts text holes "export"))]
    {:imports (vec imports)
     :exports (vec exports)
     :invokes (vec (distinct (invokes-of text holes)))}))

(defn- module-relative [file root]
  (let [root (.getCanonicalFile (io/file root))
        file (.getCanonicalFile (io/file file))
        rel (str/replace (str (.relativize (.toPath root) (.toPath file))) #"\\" "/")
        no-ext (str/replace rel #"\.(?:[cm]?[jt]sx?)$" "")]
    (-> no-ext
        (str/replace #"(^|/)index$" "$1")
        (str/replace #"/\." ".")
        (str/replace #"/$" "")
        (str/replace "/" "."))))

(defn- asset? [spec]
  (boolean (re-find #"\.(?:css|scss|sass|less|svg|png|jpe?g|gif|webp|html|md|json)$" spec)))

(defn- resolve-project [file spec path->id]
  (when (and spec (str/starts-with? spec "."))
    (let [base (io/file (.getParentFile (.getCanonicalFile (io/file file))) spec)
          candidates (concat [base]
                             (map #(io/file (str (.getPath base) %)) exts)
                             (map #(io/file base (str "index" %)) exts))]
      (some (fn [c]
              (when (.isFile c)
                (get path->id (.getCanonicalPath c))))
            candidates))))

(defn- foreign-id [spec]
  (-> spec
      (str/replace #"^@" "at.")
      (str/replace #":" ".")
      (str/replace #"/" ".")
      keyword))

(defn- op-maps [names]
  (mapv (fn [name] {:name name :text name}) (distinct names)))

(defn- parse-file [file root path->id prefix ns-prefix]
  (let [surface (read-module (slurp file))
        relative (module-relative file root)
        ns-str (graph/ns-join ns-prefix relative)
        id (graph/id-of ns-str prefix)
        project-bindings (into {}
                               (for [imp (:imports surface)
                                     name (:names imp)
                                     :let [to (resolve-project file (:spec imp) path->id)]
                                     :when to]
                                 [name to]))
        specs (->> (:imports surface)
                   (map :spec)
                   (concat (keep :spec (:exports surface)))
                   (remove str/blank?)
                   (remove asset?)
                   distinct)
        requires (vec (distinct (keep #(resolve-project file % path->id) specs)))
        foreigns (->> specs
                      (remove #(str/starts-with? % "."))
                      (map foreign-id)
                      distinct
                      vec)
        impls (->> (:exports surface)
                   (keep :type)
                   (keep project-bindings)
                   distinct
                   vec)
        ops (op-maps (mapcat :ops (:exports surface)))
        interface? (and (some :interface (:exports surface))
                        (not (some :value (:exports surface))))]
    {:id id
     :name (graph/module-name id)
     :ns ns-str
     :lang :typescript
     :file (graph/relative-path file)
     :ops ops
     :stereotype (when interface? :interface)
     :requires (vec (remove #(= % id) requires))
     :foreigns foreigns
     :impls (vec (remove #(= % id) impls))
     :invokes (:invokes surface)}))

(defn- public-class [c]
  (cond-> (dissoc c :requires :foreigns :impls :invokes)
    (empty? (:ops c)) (dissoc :ops)
    (nil? (:stereotype c)) (dissoc :stereotype)))

(defrecord TypeScriptGraph []
  graph/LanguageGraph
  (scan [_ root opts]
    (let [prefix (or (:prefix opts) "app")
          ns-prefix (or (:ns-prefix opts) prefix)
          files (source-files root)
          path->id (into {}
                         (map (fn [file]
                                (let [ns-str (graph/ns-join ns-prefix (module-relative file root))]
                                  [(.getCanonicalPath file) (graph/id-of ns-str prefix)]))
                              files))
          parsed (mapv #(parse-file % root path->id prefix ns-prefix) files)
          project-ids (set (map :id parsed))
          foreigns (->> parsed
                        (mapcat :foreigns)
                        distinct
                        (remove project-ids)
                        (mapv graph/foreign-class))
          classes (into (mapv public-class parsed) foreigns)
          edges (->> (mapcat #(graph/member-edges % [[:requires :dependency]
                                                     [:foreigns :dependency]
                                                     [:impls :implements]])
                                parsed)
                     (remove #(= (:from %) (:to %)))
                     distinct
                     vec)
          invokes (into {} (keep (fn [c]
                                   (when (seq (:invokes c))
                                     [(:id c) (:invokes c)]))
                                 parsed))]
      {:classes classes
       :edges edges
       :invokes invokes})))

(def impl (->TypeScriptGraph))

(graph/register! :typescript impl)
