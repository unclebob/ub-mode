(ns uml-viewer.application.detail
  (:require [clojure.string :as str]
            [uml-viewer.engine.hit :as hit]
            [uml-viewer.engine.layout :as layout]
            [uml-viewer.application.overlay :as overlay]
            [uml-viewer.graph :as graph]))

(def width 640)
(def height 700)
(def pad 16)
(def col-gap 10)

(def columns
  [{:id :crap :label "Crap" :key :crap-s :w 56 :group :crap}
   {:id :cc :label "CC" :key :cc-s :w 32 :group :crap}
   {:id :cov :label "Cov" :key :cov-s :w 44 :group :crap}
   {:id :killed :label "killed" :key :killed-s :w 52 :group :mutation}
   {:id :survived :label "survived" :key :survived-s :w 60 :group :mutation}
   {:id :uncovered :label "uncovered" :key :uncovered-s :w 78 :group :mutation}])

(def group-labels
  {:crap "--crap--"
   :mutation "--mutation--"})

(def ^:private rel-phrases
  {:inheritance ["extends" "extended by"]
   :implements ["implements" "implemented by"]
   :association ["associates with" "associated from"]
   :dependency ["depends on" "used by"]
   :aggregation ["aggregates" "aggregated by"]
   :composition ["composes" "composed in"]})

(defn- rel-phrase [kind outgoing?]
  (let [[out in] (get rel-phrases kind ["to" "from"])]
    (if outgoing? out in)))

(defn- module-label
  "A Rust source file is named by its filename. Other modules keep
  the namespace. A directory always keeps the namespace."
  [model]
  (let [c (:class model)
        ns-name (:ns model)
        base (graph/file-base (:file model))]
    (if (and base
             (not (:drill? c))
             (str/ends-with? (str/lower-case base) ".rs"))
      base
      ns-name)))

(defn member-ident
  "Source-window identity for a class card. `member-name` may be nil."
  ([model] (member-ident model nil))
  ([model member-name]
   (cond-> {:ns (:ns model)}
     (seq (str member-name)) (assoc :name (str member-name))
     (:lang model) (assoc :lang (:lang model))
     (:file model) (assoc :file (:file model)))))

(defn model
  "Class card for the detail window, or nil if `id` is unknown."
  [scene id]
  (when-let [c (hit/class-by-id scene id)]
    (cond-> {:class c
             :ns (overlay/class-namespace c)
             :package (hit/package-by-id scene (:package c))
             :title (get-in scene [:diagram :title])
             :rels (mapv (fn [e]
                           (let [out? (= id (:from e))
                                 oid (if out? (:to e) (:from e))
                                 other (hit/class-by-id scene oid)]
                             {:id oid
                              :name (or (:name other) (name oid))
                              :kind (:kind e)
                              :label (:label e)
                              :outgoing? out?
                              :phrase (rel-phrase (:kind e) out?)}))
                         (hit/connected-edges scene id))}
      (:lang c) (assoc :lang (:lang c))
      (:file c) (assoc :file (:file c)))))

(defn column-layout
  "Columns from the right edge. Each has :left and :right."
  []
  (loop [cols (reverse columns) x (- width pad) acc ()]
    (if (empty? cols)
      (vec acc)
      (let [c (first cols)
            right x
            left (- x (:w c))]
        (recur (rest cols) (- left col-gap)
               (cons (assoc c :left left :right right) acc))))))

(defn group-layout
  "Spans of `--crap--` / `--mutation--` over their columns."
  []
  (->> (column-layout)
       (partition-by :group)
       (mapv (fn [cs]
               {:id (:group (first cs))
                :label (get group-labels (:group (first cs)))
                :left (:left (first cs))
                :right (:right (last cs))}))))

(defn- crap-mu [crap]
  (cond
    (nil? crap) nil
    (number? crap) (double crap)
    :else (some-> (:mu crap) double)))

(defn- sum-key [xs k]
  (when (some #(some? (get % k)) xs)
    (long (reduce + 0 (map #(or (get % k) 0) xs)))))

(defn- format-num [n]
  (when n
    (format "%.1f" (double n))))

(defn- site-count [killed survived uncovered]
  (+ (or killed 0) (or survived 0) (or uncovered 0)))

(defn- no-sites? [{:keys [killed survived uncovered sites]}]
  (let [counted (site-count killed survived uncovered)]
    (cond
      (pos? counted) false
      (some? sites) (zero? sites)
      :else true)))

(defn- format-cells [{:keys [crap-mu cc coverage killed survived uncovered sites class-row?]}]
  (let [base {:crap-s (when crap-mu
                        (if class-row?
                          (str (format-num crap-mu) "μ")
                          (format-num crap-mu)))
              :crap-n crap-mu
              :cc-s (when (and cc (not class-row?)) (str (long cc)))
              :cov-s (layout/format-coverage coverage)
              :coverage coverage
              :killed killed
              :survived survived
              :uncovered uncovered
              :sites sites}]
    (if (no-sites? base)
      (assoc base :mut-note "---no mutation sites---")
      (assoc base
        :killed-s (when killed (str (long killed)))
        :survived-s (when survived (str (long survived)))
        :uncovered-s (when uncovered (str (long uncovered)))))))

(defn- class-metrics [c]
  (let [ops (:ops c)]
    {:class-row? true
     :crap-mu (crap-mu (:crap c))
     :coverage (:coverage c)
     :killed (or (:killed c) (sum-key ops :killed))
     :survived (or (:survived c) (sum-key ops :survived))
     :uncovered (or (:uncovered c) (sum-key ops :uncovered))
     :sites (or (:sites c) (sum-key ops :sites))}))

(defn- op-metrics [op]
  {:crap-mu (crap-mu (:crap op))
   :cc (:cc op)
   :coverage (:coverage op)
   :killed (:killed op)
   :survived (:survived op)
   :uncovered (:uncovered op)
   :sites (:sites op)})

(defn- emit [acc kind text extra]
  (let [{:keys [rows y]} acc
        h (or (:h extra) layout/line-h)]
    {:rows (conj rows (merge {:kind kind :text text :y y :h h} extra))
     :y (+ y h)}))

(defn- heading [acc label]
  (-> acc
      (update :y + 12)
      (emit :heading label {})))

(defn- emit-table [acc c]
  (let [acc (update acc :y + 12)
        acc (emit acc :group-header "" {})
        acc (emit acc :col-header "" {})
        acc (emit acc :stats (:name c) (format-cells (class-metrics c)))]
    (reduce (fn [acc op]
              (let [label (str (if (:private op) "- " "+ ") (:text op))]
                (emit acc :stats label
                      (assoc (format-cells (op-metrics op))
                        :private (boolean (:private op))
                        :op-name (:name op)))))
            acc
            (:ops c))))

(defn- package-line [model c]
  (str "package  "
       (or (:label (:package model))
           (some-> (:package c) name))))

(defn- emit-identity [acc model c]
  (let [acc (emit acc :name (:name c) {})
        acc (if (some? (:level c))
              (emit acc :muted (str "Level " (:level c)) {})
              acc)
        acc (if-let [st (:stereotype c)]
              (emit acc :muted (str "«" (name st) "»") {})
              acc)
        acc (if-let [label (module-label model)]
              (emit acc :module label {:module true})
              acc)]
    (emit acc :muted (package-line model c) {})))

(defn- emit-banner [acc model c]
  (let [acc (if-let [t (:title model)]
              (emit acc :muted t {})
              acc)]
    (if-let [s (layout/format-crap (:crap c))]
      (emit acc :crap s {})
      acc)))

(defn- metrics-row? [c]
  (or (seq (:ops c))
      (crap-mu (:crap c))
      (:coverage c)
      (:cc c)
      (:killed c)
      (:survived c)))

(defn- emit-fields [acc c]
  (if (seq (:fields c))
    (reduce (fn [acc f]
              (emit acc :field (:text f) {}))
            (heading acc "Fields")
            (:fields c))
    acc))

(defn- rel-line [r]
  (str (:phrase r) "  " (:name r)
       (when (:label r)
         (str "  «" (:label r) "»"))))

(defn- emit-rels [acc model]
  (if (seq (:rels model))
    (reduce (fn [acc r]
              (emit acc :rel (rel-line r) {:id (:id r)}))
            (heading acc "Relationships")
            (:rels model))
    acc))

(defn rows
  "Laid-out lines for `model`. Y is in content space (scroll separately)."
  [model]
  (when model
    (let [c (:class model)
          acc {:rows [] :y pad}
          acc (emit-identity acc model c)
          acc (emit-banner acc model c)
          acc (if (metrics-row? c)
                (emit-table acc c)
                acc)
          acc (emit-fields acc c)
          acc (emit-rels acc model)]
      (:rows acc))))

(defn content-h [rows]
  (if (seq rows)
    (+ pad (:y (last rows)) (:h (last rows)))
    (* 2 pad)))

(defn rel-at
  "Class id of the relationship row under content-y, or nil."
  [rows y]
  (some (fn [row]
          (when (and (= :rel (:kind row))
                     (<= (:y row) y (+ (:y row) (:h row) -1)))
            (:id row)))
        rows))

(defn member-at
  "Op name of the member row under content-y, or nil."
  [rows y]
  (some (fn [row]
          (when (and (:op-name row)
                     (<= (:y row) y (+ (:y row) (:h row) -1)))
            (:op-name row)))
        rows))

(defn module-at
  "True when content-y is on the module (namespace) row."
  [rows y]
  (boolean
    (some (fn [row]
            (and (:module row)
                 (<= (:y row) y (+ (:y row) (:h row) -1))))
          rows)))
