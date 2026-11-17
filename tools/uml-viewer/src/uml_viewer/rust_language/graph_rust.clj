(ns uml-viewer.rust-language.graph-rust
  "Rust LanguageGraph: one class per module file, use/mod edges, tauri commands."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [uml-viewer.graph :as graph])
  (:import [java.util.regex Pattern]))

(defn- canonical [file]
  (.getCanonicalFile (io/file file)))

(defn- cargo-file [root]
  (let [dir (canonical root)
        parent (.getParentFile dir)
        here (io/file dir "Cargo.toml")
        up (when parent (io/file parent "Cargo.toml"))]
    (cond
      (.isFile here) here
      (and up (.isFile up)) up
      :else nil)))

(defn lib-crate-name
  "Name of the `[lib]` crate beside `root`, or nil."
  [root]
  (when-let [cargo (cargo-file root)]
    (let [text (slurp cargo)
          body (or (second (re-find #"(?s)\[lib\](.*?)(?:\n\[|\z)" text)) "")]
      (second (re-find #"(?m)^name\s*=\s*\"([^\"]+)\"" body)))))

(defn- rust-string? [c]
  (= c \"))

(defn- plain-char? [source i c nxt]
  (and (= c \')
       nxt
       (not= nxt \\)
       (< (+ i 2) (count source))
       (= (.charAt source (+ i 2)) \')))

(defn- escaped-char? [c nxt]
  (and (= c \') (= nxt \\)))

(defn- scan-escaped-char [source i n]
  (loop [j (+ i 2) esc true]
    (cond
      (>= j n) n
      esc (recur (inc j) false)
      (= (.charAt source j) \') (inc j)
      :else (recur (inc j) false))))

(defn- mask-rust-step [source sb holes i n]
  (let [c (.charAt source i)
        nxt (graph/next-char source i)]
    (cond
      (graph/line-comment? c nxt)
      {:i (graph/mask-line-comment source sb i n) :holes holes}

      (graph/block-comment? c nxt)
      {:i (graph/mask-block-comment source sb i n) :holes holes}

      (rust-string? c)
      (graph/copy-span sb source holes i (graph/scan-quoted source i \" n))

      (plain-char? source i c nxt)
      (graph/copy-span sb source holes i (+ i 3))

      (escaped-char? c nxt)
      (graph/copy-span sb source holes i (scan-escaped-char source i n))

      :else
      (do (.append sb c)
          {:i (inc i) :holes holes}))))

(defn- mask-comments
  "Comments become spaces. String literals are copied and recorded."
  [source]
  (let [n (count source)
        sb (StringBuilder. n)]
    (loop [i 0 holes []]
      (if (>= i n)
        {:text (str sb) :holes holes}
        (let [step (mask-rust-step source sb holes i n)]
          (recur (:i step) (:holes step)))))))

(defn- in-hole? [holes i]
  (boolean (some (fn [[a b]] (and (<= a i) (< i b))) holes)))

(defn- matches [text holes pattern]
  (let [m (.matcher (Pattern/compile pattern) text)]
    (loop [acc []]
      (if (.find m)
        (recur (if (in-hole? holes (.start m))
                 acc
                 (conj acc {:start (.start m) :group (when (pos? (.groupCount m)) (.group m 1))})))
        acc))))

(defn- use-paths [text holes]
  (->> (matches text holes "(?m)^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?use\\s+([^;]+);")
       (map :group)
       (map str/trim)
       vec))

(defn- mod-names [text holes]
  (->> (matches text holes "(?m)^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?mod\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*;")
       (map :group)
       (remove #{"tests"})
       vec))

(defn- path-segs [path]
  (->> (str/split (str/replace path #"\{.*| as .*$" "") #"::")
       (map str/trim)
       (remove str/blank?)
       vec))

(defn- fn-names [text holes pattern]
  (mapv :group (matches text holes pattern)))

(defn- command? [text fn-at]
  (let [prefix (subs text (max 0 (- fn-at 240)) fn-at)]
    (boolean (re-find #"#\[\s*tauri\s*::\s*command\b[^\]]*\]\s*(?:pub(?:\([^)]*\))?\s+)?(?:async\s+)?$"
                      prefix))))

(defn- commands-of [text holes]
  (->> (matches text holes "(?m)\\bfn\\s+([A-Za-z_][A-Za-z0-9_]*)")
       (filter #(command? text (:start %)))
       (map :group)
       distinct
       vec))

(defn- pub-fns [text holes]
  (fn-names text holes "(?m)\\bpub\\s+(?:async\\s+)?fn\\s+([A-Za-z_][A-Za-z0-9_]*)"))

(defn- impl-traits [text holes]
  (->> (matches text holes "(?m)\\bimpl(?:\\s*<[^;{]*>)?\\s+((?:[A-Za-z_][A-Za-z0-9_]*::)*[A-Za-z_][A-Za-z0-9_]*)\\s+for\\s+")
       (map :group)
       vec))

(defn- bindings-of [use-paths]
  (into {}
        (keep (fn [path]
                (let [segs (path-segs path)
                      head (first segs)]
                  (when (and head (#{"crate" "self" "super"} head) (second segs))
                    [(last segs) segs]))))
        use-paths))

(defn read-module
  "Module surface of Rust `source`."
  [source]
  (let [{:keys [text holes]} (mask-comments source)
        uses (use-paths text holes)
        commands (commands-of text holes)
        publics (set (pub-fns text holes))
        command-names (set commands)
        fns (matches text holes "(?m)\\bfn\\s+([A-Za-z_][A-Za-z0-9_]*)")]
    {:uses uses
     :mods (mod-names text holes)
     :impls (impl-traits text holes)
     :bindings (bindings-of uses)
     :commands commands
     :ops (->> fns
               (map :group)
               (filter #(or (publics %) (command-names %)))
               distinct
               vec)}))

(defn- rust-relative [file root]
  (let [rel (str/replace (str (.relativize (.toPath (canonical root))
                                           (.toPath (canonical file))))
                         #"\\" "/")]
    (cond
      (#{"lib.rs" "mod.rs"} rel) ""
      (str/ends-with? rel "/mod.rs")
      (str/replace (subs rel 0 (- (count rel) (count "/mod.rs"))) "/" ".")

      (str/ends-with? rel ".rs")
      (str/replace (subs rel 0 (- (count rel) 3)) "/" ".")

      :else (str/replace rel "/" "."))))

(defn- resolve-mod [file mod-name]
  (let [parent (.getParentFile (canonical file))
        dir (if (#{"lib.rs" "main.rs" "mod.rs"} (.getName (canonical file)))
              parent
              (io/file parent (str/replace (.getName (canonical file)) #"\.rs$" "")))
        file-rs (io/file dir (str mod-name ".rs"))
        mod-rs (io/file dir mod-name "mod.rs")]
    (cond
      (.isFile file-rs) (canonical file-rs)
      (.isFile mod-rs) (canonical mod-rs)
      :else nil)))

(defn- reachable [root]
  (let [lib (io/file root "lib.rs")
        main (io/file root "main.rs")
        seeds (vec (concat (when (.isFile lib) [(canonical lib)])
                           (when (.isFile main) [(canonical main)])))]
    (loop [queue seeds seen #{}]
      (if (empty? queue)
        (sort-by #(.getPath %) seen)
        (let [file (first queue)]
          (if (seen file)
            (recur (rest queue) seen)
            (let [mods (->> (:mods (read-module (slurp file)))
                            (keep #(resolve-mod file %))
                            (remove seen))]
              (recur (into (vec (rest queue)) mods) (conj seen file)))))))))

(defn- class-of [file root prefix ns-prefix]
  (let [relative (rust-relative file root)
        ns-str (graph/ns-join ns-prefix relative)
        id (graph/id-of ns-str prefix)]
    {:id id :ns ns-str :file file :relative relative}))

(defn- child-id [parent-file root prefix ns-prefix mod-name]
  (when-let [child (resolve-mod parent-file mod-name)]
    (:id (class-of child root prefix ns-prefix))))

(defn- module-id-named [relative-suffix classes]
  (some (fn [c]
          (when (= relative-suffix (:relative c))
            (:id c)))
        classes))

(defn- resolve-suffix
  "Class id for a module path `suffix` (`foo` or `foo.bar`). The last
  segment may be an item inside the module, in which case the parent matches."
  [suffix classes]
  (let [suffix (str/replace (or suffix "") #"^\.+|\.+$" "")]
    (when-not (str/blank? suffix)
      (or (module-id-named suffix classes)
          (when (str/includes? suffix ".")
            (module-id-named (str/join "." (butlast (str/split suffix #"\."))) classes))))))

(defn- resolve-path [segs current lib-id classes lib-name]
  (let [head (first segs)
        tail (str/join "." (rest segs))]
    (cond
      (= head "crate") (resolve-suffix tail classes)
      (= head "self")
      (resolve-suffix (str/join "." (remove str/blank? [(:relative current) tail])) classes)
      (= head "super")
      (let [parent (str/join "." (butlast (str/split (or (:relative current) "") #"\.")))]
        (resolve-suffix (str/join "." (remove str/blank? [parent tail])) classes))
      (= head lib-name) (or (resolve-suffix tail classes) lib-id)
      :else nil)))

(defn- foreign-root [path lib-name]
  (let [head (first (path-segs path))]
    (when (and head
               (not (#{"crate" "self" "super"} head))
               (not= head lib-name))
      (keyword head))))

(defn- impl-target [trait bindings current classes lib-id lib-name]
  (let [segs (path-segs trait)
        bound (get bindings trait)]
    (or (when bound (resolve-path bound current lib-id classes lib-name))
        (when (seq segs) (resolve-path segs current lib-id classes lib-name))
        (module-id-named (first segs) classes))))

(defn- op-maps [names]
  (mapv (fn [name] {:name name :text name}) names))

(defn- source-name
  "Every Rust source file is named by its filename."
  [file]
  (.getName (io/file file)))

(defn- mentions-crate? [source lib-name]
  (when lib-name
    (boolean (re-find (re-pattern (str "\\b" (Pattern/quote lib-name) "::"))
                      (:text (mask-comments source))))))

(defn- analyze [file meta classes prefix ns-prefix lib-id lib-name]
  (let [source (slurp file)
        surface (read-module source)
        current (class-of file (:root meta) prefix ns-prefix)
        mod-ids (->> (:mods surface)
                     (keep #(child-id file (:root meta) prefix ns-prefix %))
                     (remove #(= % (:id current)))
                     distinct
                     vec)
        use-targets (->> (:uses surface)
                         (map path-segs)
                         (keep #(resolve-path % current lib-id classes lib-name))
                         (remove #(= % (:id current)))
                         distinct)
        externs (->> (:uses surface)
                     (keep #(foreign-root % lib-name))
                     distinct
                     vec)
        mentioned (when (and lib-id (not= lib-id (:id current)) (mentions-crate? source lib-name))
                    lib-id)
        impls (->> (:impls surface)
                   (keep #(impl-target % (:bindings surface) current classes lib-id lib-name))
                   (remove #(= % (:id current)))
                   distinct
                   vec)
        requires (vec (distinct (concat mod-ids use-targets (when mentioned [mentioned]))))]
    (assoc current
      :name (source-name file)
      :lang :rust
      :file (graph/relative-path file)
      :ops (op-maps (:ops surface))
      :requires requires
      :foreigns externs
      :impls impls
      :commands (:commands surface))))

(defn- public-class [c]
  (cond-> (dissoc c :requires :foreigns :impls :commands :relative)
    (empty? (:ops c)) (dissoc :ops)))

(defrecord RustGraph []
  graph/LanguageGraph
  (scan [_ root opts]
    (let [prefix (or (:prefix opts) "rust")
          ns-prefix (or (:ns-prefix opts) prefix)
          root (canonical root)
          files (reachable root)
          classes (mapv #(class-of % root prefix ns-prefix) files)
          lib-id (some (fn [c] (when (str/blank? (:relative c)) (:id c))) classes)
          lib-name (lib-crate-name root)
          meta {:root root}
          parsed (mapv #(analyze % meta classes prefix ns-prefix lib-id lib-name) files)
          project-ids (set (map :id parsed))
          foreigns (->> parsed
                        (mapcat :foreigns)
                        distinct
                        (remove project-ids)
                        (mapv graph/foreign-class))
          commands (into {} (keep (fn [c]
                                    (when (seq (:commands c))
                                      [(:id c) (:commands c)]))
                                  parsed))]
      {:classes (into (mapv public-class parsed) foreigns)
       :edges (->> (mapcat #(graph/member-edges % [[:requires :dependency]
                                                 [:foreigns :dependency]
                                                 [:impls :implements]])
                            parsed)
                   (remove #(= (:from %) (:to %)))
                   distinct
                   vec)
       :commands commands})))

(def impl (->RustGraph))

(graph/register! :rust impl)
