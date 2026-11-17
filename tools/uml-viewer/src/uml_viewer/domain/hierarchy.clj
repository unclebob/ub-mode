(ns uml-viewer.domain.hierarchy
  "Namespace-tree views: one level of children, collapsed inter-layer edges."
  (:require [clojure.string :as str]
            [uml-viewer.domain.config :as config]
            [uml-viewer.domain.policy :as policy]
            [uml-viewer.graph :as graph]))

(defn- as-id [x]
  (keyword (name x)))

(defn- segs [id]
  (str/split (name id) #"\."))

(defn- join-id [parts]
  (keyword (str/join "." parts)))

(defn- last-seg [id]
  (keyword (last (segs id))))

(defn- path-names [path]
  (mapv name path))

(defn- under?
  "True when `id` is the node at `path` or a descendant of it."
  [id path]
  (let [s (segs id)
        p (path-names path)]
    (or (and (= (count s) (count p)) (= s p))
        (= p (vec (take (count p) s))))))

(defn- child-id [id path]
  (let [s (segs id)
        n (count path)]
    (when (under? id path)
      (if (= (count s) n)
        id
        (join-id (take (inc n) s))))))

(defn- label-part [part]
  (if (and (seq part) (re-find #"[A-Z]" part))
    (str (str/upper-case (subs part 0 1)) (subs part 1))
    (str/capitalize part)))

(defn- node-label [id]
  (->> (str/split (name (last-seg id)) #"-")
       (remove str/blank?)
       (map label-part)
       (str/join)))

(defn- module-name
  "Box title: last ns segment, or a proposal group's own label."
  [c]
  (if (:proposal-group? c)
    (or (:name c) (node-label (:id c)))
    (node-label (:id c))))

(defn- rust-file?
  "True when `file` is a Rust source file."
  [file]
  (when-let [base (graph/file-base file)]
    (str/ends-with? (str/lower-case base) ".rs")))

(defn- box-name
  "Directory boxes use the namespace segment. Every Rust source file
  is named by its filename."
  [leaf id directory?]
  (let [file (:file leaf)]
    (if (and (not directory?) (rust-file? file))
      (graph/file-base file)
      (node-label id))))

(defn- has-descendants? [id classes]
  (let [pfx (str (name id) ".")]
    (boolean (some #(str/starts-with? (name (:id %)) pfx) classes))))

(defn- index-classes [classes]
  (into {} (map (juxt :id identity) classes)))

(defn- sort-ids [ids order]
  (let [rank (into {} (map-indexed (fn [i id] [(as-id id) i]) order))
        n (count rank)]
    (vec (sort-by (fn [id] [(get rank id n) (name id)]) ids))))

(defn nodes-at
  "Immediate child node ids of `path`, including a self-module when it exists."
  [classes path]
  (->> classes
       (remove :foreign)
       (keep #(child-id (:id %) path))
       distinct
       vec))

(defn- contents-of [classes path node-id]
  (let [next-path (conj (vec path) (last-seg node-id))
        kids (nodes-at classes next-path)
        others (remove #(= % node-id) kids)
        by-id (index-classes classes)]
    (when (seq others)
      (mapv (fn [cid]
              (let [directory? (and (not= cid node-id)
                                    (has-descendants? cid classes))]
                {:id cid
                 :name (box-name (get by-id cid) cid directory?)
                 :drill? directory?}))
            kids))))

(defn- under-id? [c id]
  (let [pfx (str (name id) ".")]
    (or (= id (:id c))
        (str/starts-with? (name (:id c)) pfx))))

(defn- rolled-crap
  "Function-weighted μ, max, and σ for `id` and its descendants.
  A class with no CRAP data counts as red, once per function."
  [classes id]
  (config/pool-crap (filter #(under-id? % id) classes)))

(defn- rolled-mutants
  "Summed mutants for `id` and its descendants.
  A class with no mutant data counts as one failed trial per function."
  [classes id]
  (config/pool-mutants (filter #(under-id? % id) classes)))

(defn- rolled-level
  "Highest (outermost) :level among `id` and its descendants."
  [classes id]
  (let [lvs (keep :level (filter #(under-id? % id) classes))]
    (when (seq lvs)
      (apply max lvs))))

(defn- own-crap
  "CRAP map on this source file, or nil. Files do not inherit."
  [leaf]
  (when (:mu (:crap leaf))
    (:crap leaf)))

(defn- own-mutants
  "Mutation counts on this source file, or nil. Files do not inherit."
  [leaf]
  (let [m (select-keys (or leaf {}) [:killed :survived :uncovered])]
    (when (or (:killed m) (:survived m))
      m)))

(defn- with-leaf-identity [m leaf lv]
  (cond-> m
    (:ns leaf) (assoc :ns (:ns leaf))
    (:lang leaf) (assoc :lang (:lang leaf))
    (:file leaf) (assoc :file (:file leaf))
    (some? lv) (assoc :level lv)
    (:stereotype leaf) (assoc :stereotype (:stereotype leaf))))

(defn- with-leaf-scores [m leaf crap mut]
  (cond-> m
    crap (assoc :crap crap)
    mut (merge mut)
    (:coverage leaf) (assoc :coverage (:coverage leaf))))

(defn- with-leaf-members [m leaf kids hide?]
  (cond-> m
    (:ops leaf) (assoc :ops (:ops leaf))
    (:fields leaf) (assoc :fields (:fields leaf))
    hide? (assoc :hide-members true)
    (seq kids) (assoc :contents kids)))

(defn- view-class [idx classes path id]
  (let [leaf (get idx id)
        kids (contents-of classes path id)
        drill? (boolean (seq kids))
        hide? drill?
        crap (if drill? (rolled-crap classes id) (own-crap leaf))
        mut (if drill? (rolled-mutants classes id) (own-mutants leaf))
        lv (rolled-level classes id)]
    (-> {:id id
         :name (box-name leaf id drill?)
         :drill? drill?}
        (with-leaf-identity leaf lv)
        (with-leaf-scores leaf crap mut)
        (with-leaf-members leaf kids hide?))))

(defn- collapse-end [id path idx]
  (if (:foreign (get idx id))
    id
    (child-id id path)))

(defn- external-end [id path idx]
  (cond
    (:foreign (get idx id))
    {:id id :name (or (:name (get idx id)) (name id)) :foreign true}

    (under? id path)
    nil

    :else
    (let [ext (join-id (take (max 1 (count path)) (segs id)))]
      {:id ext :name (node-label ext) :external true})))

(defn- add-port [m box-id dep]
  (let [cur (get m box-id [])]
    (if (some #(= (:id dep) (:id %)) cur)
      m
      (assoc m box-id (conj cur (select-keys dep [:id :name]))))))

(defn- add-foreign [acc box-id dep e]
  (let [fid (:id dep)
        edge (assoc e :from box-id :to fid)
        seen (some #(= fid (:id %)) (:foreign acc))]
    (cond-> (update acc :foreign-edges conj edge)
      (not seen) (update :foreign conj
                         {:id fid
                          :name (:name dep)
                          :foreign true
                          :shape :oval}))))

(defn- in-view? [id ids]
  (boolean (and id (ids id))))

(defn- edge-ends [e ids path idx]
  (let [from (collapse-end (:from e) path idx)
        to-in (collapse-end (:to e) path idx)]
    {:from from
     :to-in to-in
     :to-ext (when-not (in-view? to-in ids)
               (external-end (:to e) path idx))
     :from-ext (when-not (in-view? from ids)
                 (external-end (:from e) path idx))
     :from-here? (in-view? from ids)
     :to-here? (in-view? to-in ids)}))

(defn- internal-edge? [ends]
  (and (:from-here? ends)
       (:to-here? ends)
       (not= (:from ends) (:to-in ends))))

(defn- foreign-out? [ends]
  (and (:from-here? ends) (:foreign (:to-ext ends))))

(defn- port-out? [ends]
  (and (:from-here? ends) (:to-ext ends)))

(defn- foreign-in? [ends]
  (and (:to-here? ends) (:foreign (:from-ext ends))))

(defn- port-in? [ends]
  (and (:to-here? ends) (:from-ext ends)))

(defn- route-edge [acc e {:keys [from to-in to-ext from-ext] :as ends}]
  (cond
    (internal-edge? ends)
    (update acc :internal conj (assoc e :from from :to to-in))

    (foreign-out? ends)
    (add-foreign acc from to-ext e)

    (port-out? ends)
    (update acc :out add-port from to-ext)

    (foreign-in? ends)
    (add-foreign acc to-in from-ext (assoc e :from (:id from-ext) :to to-in))

    (port-in? ends)
    (update acc :in add-port to-in from-ext)

    :else acc))

(defn- partition-edges
  "In-view edges stay arrows. Foreign libs are ovals. Other off-view deps are ports."
  [edges ids path idx]
  (reduce
    (fn [acc e]
      (route-edge acc e (edge-ends e ids path idx)))
    {:internal [] :in {} :out {} :foreign [] :foreign-edges []}
    edges))

(defn- order-at [doc path]
  (or (get-in doc [:order (str/join "." (map name path))])
      (when (empty? path) (:order doc))
      []))

(defn- visible-edge? [visible e]
  (and (visible (:from e)) (visible (:to e))))

(defn- with-ports [parts c]
  (cond-> c
    (seq (get-in parts [:in (:id c)]))
    (assoc :in-deps (get-in parts [:in (:id c)]))
    (seq (get-in parts [:out (:id c)]))
    (assoc :out-deps (get-in parts [:out (:id c)]))))

(defn- label-at [doc path]
  (if (seq path)
    (str/join "." (map name path))
    (or (:title doc) "UML")))

(defn- assemble-view [label boxes edges foreign]
  (cond-> {:title label
           :direction :tb
           :packages [{:id :view
                       :label label
                       :classes boxes}]
           :edges (vec edges)}
    (seq foreign) (assoc :foreign foreign)))

(defn view-at
  "One diagram: children of `path` as boxes, edges collapsed to that level."
  [doc path]
  (let [path (mapv as-id path)
        omit-ids (or (:omit doc) [])
        classes (vec (remove #(policy/omitted-id? (:id %) omit-ids)
                             (:classes doc)))
        idx (index-classes classes)
        node-ids (sort-ids (nodes-at classes path) (order-at doc path))
        boxes (mapv #(view-class idx classes path %) node-ids)
        ids (set (map :id boxes))
        kinds (or (:edge-kinds doc) {})
        omit (or (:omit-edges doc) [])
        parts (partition-edges (:edges doc) ids path idx)
        foreign (:foreign parts)
        visible (into ids (set (map :id foreign)))
        edges (filterv #(visible-edge? visible %)
                       (policy/apply-edge-kinds
                         (policy/merge-edges
                           (into (:internal parts) (:foreign-edges parts)))
                         kinds omit))
        boxes (mapv #(with-ports parts %) boxes)]
    (assemble-view (label-at doc path) boxes edges foreign)))

(defn proposal-layers
  "Normalized proposal layer maps on `doc`, or []."
  [doc]
  (policy/proposal-layers doc))

(defn named-proposals
  "Named proposals on `doc`, or []."
  [doc]
  (policy/named-proposals doc))

(def declutter-modes
  [:full :arrows :triangles :elements :classes])

(defn next-declutter
  [mode]
  (let [i (.indexOf declutter-modes (or mode :full))]
    (nth declutter-modes (mod (inc (max i 0)) (count declutter-modes)))))

(defn- owner-pkg [view id]
  (some (fn [p]
          (when (some #(= id (:id %)) (:classes p))
            (:id p)))
        (:packages view)))

(defn- leaf-dep [e]
  {:from (or (:orig-from e) (:from e))
   :to (or (:orig-to e) (:to e))
   :violating (boolean (:violating e))})

(defn- merge-direction-edges [edges]
  (->> edges
       (group-by (juxt :from :to))
       vals
       (mapv (fn [es]
               (let [viol (boolean (some :violating es))
                     best (if viol
                            (or (first (filter :violating es))
                                (first es))
                            (apply max-key #(policy/kind-rank (:kind %)) es))
                     via (into #{} (mapcat (fn [e]
                                             [(:from e) (:to e)
                                              (:orig-from e) (:orig-to e)])
                                           es))
                     deps (mapv leaf-dep es)]
                 (cond-> (assoc (dissoc best :violating :orig-from :orig-to)
                           :via-ids (disj via nil)
                           :deps deps)
                   viol (assoc :kind :dependency :violating true)))))))

(defn- pkg-dummy [p]
  {:id (:id p)
   :name (or (:label p) (name (:id p)))
   :dummy? true
   :drill? true
   :hide-members true})

(defn- with-metrics [dummy classes]
  (let [crap (config/pool-crap classes)
        mut (config/pool-mutants classes)
        lv (when (seq (keep :level classes))
             (apply max (keep :level classes)))]
    (cond-> (dissoc dummy :crap :killed :survived :uncovered :mut-gap)
      crap (assoc :crap crap)
      mut (merge mut)
      (some? lv) (assoc :level lv))))

(defn- ensure-pkg-dummies [view]
  (update view :packages
          (fn [pkgs]
            (mapv (fn [p]
                    (if (some #(= (:id p) (:id %)) (:classes p))
                      p
                      (update p :classes
                              #(into [(with-metrics (pkg-dummy p) %)] %))))
                  pkgs))))

(defn collapse-arrows
  "One edge per direction between layers. Several packages collapse to
  package ids; a single wrapping package collapses by box id."
  [view]
  (let [pkgs (:packages view)
        multi? (> (count pkgs) 1)
        view (if multi? (ensure-pkg-dummies view) view)
        remap (fn [id] (if multi? (or (owner-pkg view id) id) id))
        known (set (concat (map :id (mapcat :classes (:packages view)))
                           (map :id (:foreign view))))
        edges (->> (:edges view)
                   (map (fn [e]
                          (assoc e
                            :orig-from (:from e)
                            :orig-to (:to e)
                            :from (remap (:from e))
                            :to (remap (:to e)))))
                   (filter #(and (known (:from %)) (known (:to %))))
                   (remove #(= (:from %) (:to %)))
                   vec)]
    (assoc view :edges (merge-direction-edges edges))))

(defn hide-elements
  "Drop nested names, fields, ops, and ports from class boxes."
  [view]
  (update view :packages
          (fn [pkgs]
            (mapv (fn [p]
                    (update p :classes
                            (fn [cs]
                              (mapv #(assoc (dissoc % :contents :in-deps :out-deps)
                                       :hide-members true)
                                    cs))))
                  pkgs))))

(defn hide-classes
  "Keep layer boxes; drop contained class boxes (dummy endpoints remain).
  A single wrapping package keeps its layer boxes but empties their contents."
  [view]
  (if (> (count (:packages view)) 1)
    (update view :packages
            (fn [pkgs]
              (mapv (fn [p]
                      (let [real (vec (remove :dummy? (:classes p)))
                            dummy (with-metrics
                                    (or (first (filter :dummy? (:classes p)))
                                        (pkg-dummy p))
                                    real)]
                        (assoc p :classes [dummy]
                          :nses (mapv :id real))))
                    pkgs)))
    (update view :packages
            (fn [pkgs]
              (mapv (fn [p]
                      (update p :classes
                              (fn [cs]
                                (mapv #(assoc (dissoc % :contents)
                                         :hide-members true)
                                      (remove :dummy? cs)))))
                    pkgs)))))

(defn apply-declutter
  [view mode]
  (let [mode ({:methods :elements} (or mode :full) (or mode :full))]
    (cond-> view
      (#{:arrows :triangles :elements :classes} mode) collapse-arrows
      (= :triangles mode) (assoc :hide-edges true)
      (#{:elements :classes} mode) hide-elements
      (= :classes mode) hide-classes)))

(defn- id-under?
  "True when `id` is `nse` or a dotted child of `nse`."
  [id nse]
  (let [id (keyword (name id))
        nse (keyword (name nse))]
    (or (= id nse)
        (str/starts-with? (name id) (str (name nse) ".")))))

(defn- claimed-covers?
  [id claimed]
  (some (fn [c]
          (or (id-under? id c) (id-under? c id)))
        claimed))

(defn- covering-visible
  "Map `id` onto a visible box: itself, a nested group owner, then a
  dotted ancestor, else nil."
  [id visible owners]
  (let [id (keyword (name id))
        parts (str/split (name id) #"\.")]
    (or (when (contains? visible id) id)
        (when-let [g (get owners id)]
          (when (contains? visible g) g))
        (some (fn [n]
                (let [k (keyword (str/join "." (take n parts)))]
                  (when (contains? visible k) k)))
              (range (dec (count parts)) 0 -1)))))

(defn- remap-proposal-edges
  "Document leaf edges onto ids that are actually on the canvas."
  [edges visible owners]
  (->> edges
       (keep (fn [e]
               (let [from (covering-visible (:from e) visible owners)
                     to (covering-visible (:to e) visible owners)]
                 (when (and from to (not= from to))
                   (assoc e :from from :to to)))))
       vec))

(defn- group-owners
  "Leaf ns id -> nested group id, for remapping arrows onto the group box."
  [nses]
  (into {}
        (mapcat (fn [x]
                  (when (map? x)
                    (concat (map (fn [id] [id (:id x)])
                                 (policy/nse-ids (:nses x)))
                            (group-owners (:nses x)))))
                nses)))

(defn- nested-group-box
  [group kids]
  (let [id (:id group)
        crap (config/pool-crap kids)
        mut (config/pool-mutants kids)
        lv (when (seq (keep :level kids))
             (apply max (keep :level kids)))]
    (cond-> {:id id
             :name (or (:label group) (node-label id))
             :drill? true
             :proposal-group? true
             :nses (:nses group)
             :members kids
             :hide-members true
             :contents (mapv (fn [c]
                               {:id (:id c)
                                :name (module-name c)
                                :drill? (boolean (:drill? c))})
                             kids)}
      crap (assoc :crap crap)
      mut (merge mut)
      (some? lv) (assoc :level lv))))

(defn- resolve-which [doc which]
  (or (when (and (map? which) (:layers which)) which)
      (when which (policy/proposal-by-id doc which))
      (first (policy/named-proposals doc))))

(defn- chosen-proposal [doc named]
  (or (policy/normalize-proposal named)
      (policy/normalize-proposal (:proposal doc))))

(defn- merged-omit [doc proposal]
  (set (concat (or (:omit doc) []) (or (:omit proposal) []))))

(defn- visible-leaves [doc omit]
  (into [] (remove #(or (:foreign %)
                        (policy/omitted-id? (:id %) omit))
                   (or (:classes doc) []))))

(defn- spare-leaf? [claimed omit c]
  (not (or (claimed-covers? (:id c) claimed)
           (omit (:id c))
           (policy/omitted-id? (:id c) omit))))

(defn- as-module [c]
  (assoc c :name (module-name c)))

(defn- pick-entries [by-id stamped nse]
  (letfn [(pick [nse]
            (if (map? nse)
              (let [kids (into [] (mapcat pick (:nses nse)))]
                (if (seq kids)
                  [(nested-group-box nse kids)]
                  []))
              (if-let [c (get by-id nse)]
                [(as-module c)]
                (mapv as-module
                      (filterv #(id-under? (:id %) nse) stamped)))))]
    (pick nse)))

(defn- layer-package [by-id stamped layer]
  (let [cs (into [] (mapcat #(pick-entries by-id stamped %) (:nses layer)))]
    (when (seq cs)
      {:id (keyword (str "proposal." (name (:id layer))))
       :label (:label layer)
       :classes cs})))

(defn- proposal-packages [by-id stamped layers extras]
  (vec (rseq
         (cond-> (vec (keep #(layer-package by-id stamped %) layers))
           (seq extras)
           (conj {:id :proposal.unassigned
                  :label "Unassigned"
                  :classes (mapv as-module extras)})))))

(defn- proposal-edges [doc ranks by-id pkgs root layers]
  (let [visible (into (set (map :id (mapcat :classes pkgs)))
                      (map :id (:foreign root)))
        owners (into {} (mapcat #(group-owners (:nses %)) layers))
        kinds (or (:edge-kinds doc) {})
        omit-edges (or (:omit-edges doc) [])
        remapped (policy/apply-edge-kinds
                   (policy/merge-edges
                     (remap-proposal-edges (or (:edges doc) []) visible owners))
                   kinds omit-edges)]
    (:edges (policy/restamp-ranks (vals by-id) remapped ranks))))

(defn- attach-proposal [root pkgs edges notice]
  (if (seq pkgs)
    (assoc root
      :title notice
      :proposal true
      :packages pkgs
      :edges edges)
    root))

(defn proposal-view
  "Root view with named proposal packages around real nses.
  A layer `:nses` entry may be a top-level package (`:playfield`), a
  nested class (`:jvm.cli`), or a nested group map
  `{:id :quil-swing :label \"Quil/Swing\" :nses [...]}` drawn as a child
  component inside that layer. Package ids are `proposal.*`.
  `which` is a proposal id, a proposal map, or nil (first proposal)."
  ([doc] (proposal-view doc nil))
  ([doc which]
   (let [root (view-at doc [])
         named (resolve-which doc which)
         proposal (chosen-proposal doc named)
         layers (or (:layers proposal) [])
         ranks (policy/ranks-from-layers layers)
         omit (merged-omit doc proposal)
         leaves (visible-leaves doc omit)
         stamped (:classes (policy/restamp-ranks leaves [] ranks))
         by-id (into {} (map (juxt :id identity) stamped))
         claimed (set (mapcat #(policy/nse-ids (:nses %)) layers))
         extras (filterv #(spare-leaf? claimed omit %) stamped)
         pkgs (proposal-packages by-id stamped layers extras)
         notice (or (:notice named) (:notice proposal) policy/proposal-notice)]
     (attach-proposal root pkgs
                      (proposal-edges doc ranks by-id pkgs root layers)
                      notice))))

(defn- groups-in [nses]
  (mapcat (fn [x]
            (if (map? x)
              (cons x (groups-in (:nses x)))
              []))
          nses))

(defn find-nested-group
  "Nested group map with `id` in proposal `which`, or nil."
  [doc which id]
  (let [id (and id (keyword (name id)))
        named (or (when (and (map? which) (:layers which)) which)
                  (when which (policy/proposal-by-id doc which))
                  (first (policy/named-proposals doc)))
        layers (:layers (or (policy/normalize-proposal named)
                            (policy/normalize-proposal (:proposal doc)))
                        [])]
    (first (filter #(= id (:id %))
                   (mapcat #(groups-in (:nses %)) layers)))))

(defn- class-in-view [view id]
  (first (filter #(= id (:id %))
                 (mapcat :classes (:packages view)))))

(defn- restamp-visible-edges [doc which view classes]
  (let [named (or (when (and (map? which) (:layers which)) which)
                  (when which (policy/proposal-by-id doc which))
                  (first (policy/named-proposals doc)))
        layers (:layers (or (policy/normalize-proposal named)
                            (policy/normalize-proposal (:proposal doc)))
                        [])
        ranks (policy/ranks-from-layers layers)
        visible (into (set (map :id classes))
                      (map :id (:foreign view)))
        remapped (policy/apply-edge-kinds
                   (policy/merge-edges
                     (remap-proposal-edges (or (:edges doc) []) visible {}))
                   (or (:edge-kinds doc) {})
                   (or (:omit-edges doc) []))]
    (:edges (policy/restamp-ranks classes remapped ranks))))

(defn layer-view
  "One proposal package as the diagram, with its classes visible.
  A nested group id expands to that group's member classes."
  [doc which pkg-id]
  (let [root (proposal-view doc which)
        pkg (first (filter #(= pkg-id (:id %)) (:packages root)))
        box (class-in-view root pkg-id)]
    (cond
      pkg
      (assoc root
        :title (or (:label pkg) (name pkg-id))
        :packages [pkg])

      (and box (:proposal-group? box) (seq (:members box)))
      (let [cs (vec (:members box))]
        (assoc root
          :title (or (:name box) (name pkg-id))
          :packages [{:id pkg-id
                      :label (or (:name box) (name pkg-id))
                      :classes cs}]
          :edges (restamp-visible-edges doc which root cs)))

      :else root)))

(defn proposal-package-id?
  [id]
  (and id (str/starts-with? (name id) "proposal.")))
