(ns uml-viewer.go-language.graph-go
  "Go LanguageGraph: one class per package, one per package-level type.
  Imports are dependencies, method sets are implements."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [uml-viewer.go-language.read-go :as read-go]
            [uml-viewer.graph :as graph]))

;; ---------------------------------------------------------------- files and packages

(defn- skipped-dir? [seg]
  (or (#{"vendor" "testdata" "node_modules"} seg)
      (str/starts-with? seg ".")
      (str/starts-with? seg "_")))

(defn- canonical [file]
  (.getCanonicalFile (io/file file)))

(defn- relative [root file]
  (str/replace (str (.relativize (.toPath root) (.toPath file))) #"\\" "/"))

(defn- go-source? [root file]
  (let [name (.getName file)
        dirs (butlast (str/split (relative root file) #"/"))]
    (and (.isFile file)
         (str/ends-with? name ".go")
         (not (str/ends-with? name "_test.go"))
         (not-any? skipped-dir? dirs))))

(defn- build-ignored? [source]
  (boolean (re-find #"(?m)^//\s*(?:go:build|\+build)\s+ignore\b" source)))

(defn- go-files [root]
  (->> (file-seq root)
       (filter #(go-source? root %))
       (sort-by #(.getPath %))))

(defn- module-of
  "`[module-path module-dir]` of the nearest go.mod at or above `dir`."
  [dir]
  (loop [d dir]
    (when d
      (let [mod (io/file d "go.mod")]
        (if-let [path (and (.isFile mod)
                           (second (re-find #"(?m)^module\s+(\S+)" (slurp mod))))]
          [path d]
          (recur (.getParentFile d)))))))

(defn import-path
  "Import path of the package in `dir`, or `clause` when no go.mod is found."
  [dir clause]
  (if-let [[module module-dir] (module-of dir)]
    (let [rel (relative module-dir dir)]
      (if (str/blank? rel) module (str module "/" rel)))
    clause))

(defn- majority [xs]
  (key (apply max-key val (frequencies xs))))

(defn- home-file
  "The file that names the package: `<dir>.go`, `<package>.go`, `doc.go`, or the first."
  [dir clause files]
  (let [names (set (map #(.getName %) files))
        wanted (some names [(str (.getName dir) ".go") (str clause ".go") "doc.go"])]
    (or (some #(when (= wanted (.getName %)) %) files)
        (first files))))

(defn- read-package [root ns-prefix prefix [dir parsed]]
  (let [clause (majority (keep :package parsed))
        parsed (filterv #(= clause (:package %)) parsed)
        rel (str/replace (relative root dir) "/" ".")]
    {:id (graph/id-of (graph/ns-join ns-prefix rel) prefix)
     :name clause
     :path (import-path dir clause)
     :file (home-file dir clause (map :file parsed))
     :files parsed}))

(defn- parse-file [file]
  (let [source (slurp file)]
    (when-not (build-ignored? source)
      (assoc (read-go/read-file source) :file file))))

(defn packages
  "Go packages under `root`, one per directory."
  [root ns-prefix prefix]
  (->> (go-files root)
       (keep parse-file)
       (filter :package)
       (group-by #(.getParentFile (:file %)))
       (sort-by #(.getPath (key %)))
       (mapv #(read-package root ns-prefix prefix %))))

;; ---------------------------------------------------------------- project model

(defn- type-id [pkg type-name]
  (keyword (str (name (:id pkg)) "." type-name)))

(defn- package-types
  "Types of `pkg` with their methods and the file each is declared in."
  [pkg]
  (let [methods (group-by :receiver (mapcat #(map (fn [m] (assoc m :file (:file %))) (:methods %))
                                            (:files pkg)))]
    (vec (for [f (:files pkg)
               t (:types f)]
           (assoc t
             :id (type-id pkg (:name t))
             :ns (str (:path pkg) "." (:name t))
             :pkg (:id pkg)
             :file (:file f)
             :methods (vec (get methods (:name t))))))))

(defn- default-alias [path]
  (last (str/split path #"/")))

(defn- alias-map
  "Local name to project package for the imports of one file."
  [file by-path]
  (into {}
        (keep (fn [{:keys [alias path]}]
                (when-let [pkg (by-path path)]
                  (when-not (#{"_" "."} alias)
                    [(or alias (:name pkg) (default-alias path)) pkg]))))
        (:imports file)))

(defn- index-project [pkgs]
  (let [pkgs (mapv #(assoc % :types (package-types %)) pkgs)]
    {:pkgs pkgs
     :by-path (into {} (map (juxt :path identity) pkgs))
     :by-id (into {} (map (juxt :id identity) pkgs))
     :type-of (into {} (for [p pkgs t (:types p)] [[(:id p) (:name t)] t]))}))

(defn- resolve-name
  "Project type or package for `name` (bare or `alias.Name`) seen in `file` of `pkg`."
  [project pkg aliases name]
  (let [[a b] (str/split name #"\." 2)]
    (if b
      (when-let [target (get aliases a)]
        (or (get-in project [:type-of [(:id target) b]]) target))
      (get-in project [:type-of [(:id pkg) a]]))))

;; ---------------------------------------------------------------- edges

(def ^:private qualified-re
  (re-pattern (str "(?<![\\w.])(" read-go/ident "\\." read-go/ident ")")))

(def ^:private bare-re
  (re-pattern (str "(?<![\\w.])(" read-go/ident ")\\b(?!\\s*(?::=|=[^=]|\\.))")))

(defn- refs-in [text]
  (concat (map second (re-seq qualified-re text))
          (map second (re-seq bare-re text))))

(defn- file-of [pkg file]
  (some #(when (= file (:file %)) %) (:files pkg)))

(defn- type-refs
  "Project ids `t` names in its declaration and its methods."
  [project pkg t]
  (->> (cons {:text (:text t) :file (:file t)} (:methods t))
       (mapcat (fn [{:keys [text file]}]
                 (let [aliases (alias-map (file-of pkg file) (:by-path project))]
                   (keep #(:id (resolve-name project pkg aliases %)) (refs-in text)))))
       (remove #{(:id t) (:id pkg)})
       distinct))

(defn- package-imports [pkg]
  (->> (:files pkg) (mapcat :imports) (map :path) (remove #{"C"}) distinct))

(defn- foreign-id [path]
  (keyword (str/replace path "/" ".")))

(defn- import-edges [project pkg]
  (for [path (package-imports pkg)
        :let [target (get-in project [:by-path path])]
        :when (not= (:id target) (:id pkg))]
    {:from (:id pkg) :to (if target (:id target) (foreign-id path)) :kind :dependency}))

(defn- resolve-embed [project pkg t embed]
  (let [aliases (alias-map (file-of pkg (:file t)) (:by-path project))
        found (resolve-name project pkg aliases embed)]
    (when (:kind found) found)))

(defn- owner [project t]
  (get-in project [:by-id (:pkg t)]))

(defn- method-shapes
  "`[name params results]` of `t` and of the types it embeds."
  ([project t] (method-shapes project t #{}))
  ([project t seen]
   (if (seen (:id t))
     #{}
     (into (set (map (juxt :name :params :results) (:methods t)))
           (mapcat #(when-let [e (resolve-embed project (owner project t) t %)]
                      (method-shapes project e (conj seen (:id t))))
                   (:embeds t))))))

(defn- interface-shapes
  "Method shapes `i` requires, or nil when part of it cannot be seen."
  ([project i] (interface-shapes project i #{}))
  ([project i seen]
   (when (and (= :interface (:kind i)) (not (:constraint i)) (not (seen (:id i))))
     (let [embeds (map #(resolve-embed project (owner project i) i %) (:embeds i))]
       (when (every? some? embeds)
         (let [inner (map #(interface-shapes project % (conj seen (:id i))) embeds)]
           (when (every? some? inner)
             (into (set (map (juxt :name :params :results) (:specs i)))
                   (apply concat inner)))))))))

(defn- implements-edges [project types]
  (let [ifaces (keep (fn [i]
                       (let [shapes (interface-shapes project i)]
                         (when (seq shapes) [i shapes])))
                     types)]
    (for [t types
          :when (not= :interface (:kind t))
          :let [have (method-shapes project t)]
          [i need] ifaces
          :when (every? have need)]
      {:from (:id t) :to (:id i) :kind :implements})))

(defn- inheritance-edges [project types]
  (for [i types
        :when (= :interface (:kind i))
        e (:embeds i)
        :let [j (resolve-embed project (owner project i) i e)]
        :when (= :interface (:kind j))]
    {:from (:id i) :to (:id j) :kind :inheritance}))

(defn- dependency-edges [project pkg]
  (for [t (:types pkg)
        to (type-refs project pkg t)]
    {:from (:id t) :to to :kind :dependency}))

;; ---------------------------------------------------------------- classes

(defn- exported? [name]
  (Character/isUpperCase (.charAt ^String name 0)))

(defn- op-maps [names]
  (->> names (filter exported?) distinct (mapv (fn [n] {:name n :text n}))))

(defn- type-ops [t]
  (op-maps (map :name (if (= :interface (:kind t)) (:specs t) (:methods t)))))

(defn- class-map [m]
  (cond-> (assoc m :lang :go :file (graph/relative-path (:file m)))
    (empty? (:ops m)) (dissoc :ops)))

(defn- package-class [pkg]
  (class-map {:id (:id pkg) :name (:name pkg) :ns (:path pkg) :file (:file pkg)
              :ops (op-maps (mapcat #(map :name (:funcs %)) (:files pkg)))}))

(defn- type-class [t]
  (class-map (cond-> {:id (:id t) :name (:name t) :ns (:ns t) :file (:file t)
                      :ops (type-ops t)}
               (= :interface (:kind t)) (assoc :stereotype :interface))))

(defn scan-go
  "Classes and edges for the Go packages under `root`."
  [root opts]
  (let [prefix (or (:prefix opts) "go")
        project (index-project (packages (canonical root) (or (:ns-prefix opts) prefix) prefix))
        pkgs (:pkgs project)
        types (vec (mapcat :types pkgs))
        edges (->> (concat (mapcat #(import-edges project %) pkgs)
                           (mapcat #(dependency-edges project %) pkgs)
                           (implements-edges project types)
                           (inheritance-edges project types))
                   distinct
                   vec)
        project-ids (set (concat (map :id pkgs) (map :id types)))]
    {:classes (-> (into (mapv package-class pkgs) (map type-class types))
                  (into (->> (map :to edges) (remove project-ids) distinct
                             (map graph/foreign-class))))
     :edges edges}))

(defrecord GoGraph []
  graph/LanguageGraph
  (scan [_ root opts] (scan-go root opts)))

(def impl (->GoGraph))

(graph/register! :go impl)
