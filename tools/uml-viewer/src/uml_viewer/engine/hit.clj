(ns uml-viewer.engine.hit
  (:require [uml-viewer.domain.geom :as geom]
            [uml-viewer.engine.layout :as layout]))

(defn- port-at [c x y]
  (or (some (fn [p]
              (when (and (:rect p) (geom/inside? (:rect p) x y))
                {:kind :port :id (:id p) :parent (:id c) :dir :in}))
            (:in-ports c))
      (some (fn [p]
              (when (and (:rect p) (geom/inside? (:rect p) x y))
                {:kind :port :id (:id p) :parent (:id c) :dir :out}))
            (:out-ports c))))

(defn deps-of
  "Leaf `from -> to` pairs on an arrow, collapsed or not."
  [e]
  (or (seq (:deps e))
      (when (and (:from e) (:to e))
        [{:from (:from e)
          :to (:to e)
          :violating (boolean (:violating e))}])))

(defn- edge-paths [e]
  (or (seq (:strokes e))
      (when (next (:points e)) [(:points e)])))

(def triangle-side 12.0)
(def triangle-h (* triangle-side 0.8660254037844386))

(defn- class-triangle
  "Incoming apex above the top edge; outgoing apex below the bottom."
  [r dir]
  (let [cx (geom/cx r)
        half (/ triangle-side 2.0)]
    (if (= dir :in)
      [[cx (- (:y r) triangle-h)]
       [(- cx half) (:y r)]
       [(+ cx half) (:y r)]]
      [[(- cx half) (geom/bottom r)]
       [(+ cx half) (geom/bottom r)]
       [cx (+ (geom/bottom r) triangle-h)]])))

(defn- leaf-pairs [e]
  (vec (or (deps-of e) [])))

(defn- deps-for
  "Leaf pairs for `id`: its own endpoints, or leaves riding a collapsed arrow."
  [edges id dir]
  (let [end (if (= dir :in) :to :from)
        as-box (mapcat leaf-pairs (filter #(= id (end %)) edges))
        as-leaf (filterv #(= id (end %)) (mapcat leaf-pairs edges))]
    (vec (if (seq as-leaf) as-leaf as-box))))

(defn dep-indicators
  "Incoming/outgoing triangles for each class that has edges, including
  children whose deps ride a collapsed container arrow."
  [scene]
  (let [edges (or (:edges scene) [])]
    (vec
      (mapcat
        (fn [c]
          (when-let [r (:rect c)]
            (let [id (:id c)
                  incoming (deps-for edges id :in)
                  outgoing (deps-for edges id :out)
                  pack (fn [dir deps]
                         (when (seq deps)
                           {:id id
                            :dir dir
                            :triangle (class-triangle r dir)
                            :violating (boolean (some :violating deps))
                            :deps (vec deps)}))]
              (cond-> []
                (seq incoming) (conj (pack :in incoming))
                (seq outgoing) (conj (pack :out outgoing))))))
        (:classes scene)))))

(defn indicator-at
  ([scene x y] (indicator-at scene x y 5.0))
  ([scene x y pad]
   (some (fn [ind]
           (when (geom/near-triangle? [x y] (:triangle ind) pad)
             {:kind :dep
              :dir (:dir ind)
              :id (:id ind)
              :deps (:deps ind)
              :violating (:violating ind)}))
         (reverse (or (:dep-indicators scene) [])))))

(defn edge-at
  "Arrow under world point [x y], or nil. Classes take priority in `at`."
  ([scene x y] (edge-at scene x y 8.0))
  ([scene x y pad]
   (let [p [x y]]
     (some (fn [e]
             (when (some #(geom/near-polyline? p % pad) (edge-paths e))
               {:kind :edge
                :from (:from e)
                :to (:to e)
                :deps (vec (deps-of e))}))
           (reverse (:edges scene))))))

(defn at
  "Topmost port, class, child row, edge, or package under world point [x y]."
  [scene x y]
  (or (indicator-at scene x y)
      (some (fn [c]
              (or (port-at c x y)
                  (when (geom/inside? (:rect c) x y)
                    (let [line (layout/line-at c x y)]
                      (if (and line (= :child (:kind line)))
                        {:kind :child
                         :id (:id line)
                         :parent (:id c)
                         :drill? (boolean (:drill? line))}
                        {:kind :class
                         :id (:id c)
                         :drill? (boolean (:drill? c))})))))
            (let [cs (:classes scene)
                  visible (vec (remove :dummy? cs))]
              (reverse (if (seq visible) visible cs))))
      (edge-at scene x y)
      (some (fn [p]
              (when (geom/inside? (:rect p) x y)
                {:kind :package :id (:id p)}))
            (reverse (:packages scene)))))

(defn class-by-id [scene id]
  (first (filter #(= id (:id %)) (:classes scene))))

(defn package-by-id [scene id]
  (first (filter #(= id (:id %)) (:packages scene))))

(defn connected-edges [scene class-id]
  (filter (fn [e]
            (or (= class-id (:from e))
                (= class-id (:to e))))
          (:edges scene)))
