(ns uml-viewer.engine.compose
  (:require [uml-viewer.engine.curve :as curve]
            [uml-viewer.domain.geom :as geom]
            [uml-viewer.engine.hit :as hit]
            [uml-viewer.engine.layout :as layout]
            [uml-viewer.engine.route :as route]))

(defn- qid [idx id]
  (keyword (str "d" idx "-" (name id))))

(defn- move-rect [r dx dy]
  (geom/rect (+ (:x r) dx) (+ (:y r) dy) (:w r) (:h r)))

(defn- qualify [idx scene]
  (let [q #(qid idx %)]
    (-> scene
        (update :packages
                (fn [ps]
                  (mapv #(assoc % :id (q (:id %))) ps)))
        (update :classes
                (fn [cs]
                  (mapv #(assoc %
                           :id (q (:id %))
                           :package (when-let [p (:package %)] (q p)))
                        cs)))
        (update :edges
                (fn [es]
                  (mapv #(assoc % :from (q (:from %)) :to (q (:to %))) es))))))

(defn- translate [scene dx dy]
  (-> scene
      (update :packages (fn [ps] (mapv #(update % :rect move-rect dx dy) ps)))
      (update :classes (fn [cs] (mapv #(update % :rect move-rect dx dy) cs)))
      (update :edges
              (fn [es]
                (mapv (fn [e]
                        (update e :points
                                (fn [pts]
                                  (mapv (fn [[x y]] [(+ x dx) (+ y dy)]) pts))))
                      es)))))

(defn- drawn-path [scene e]
  (let [pts (vec (:points e))]
    (when (next pts)
      (let [from (first (filter #(= (:from e) (:id %)) (:classes scene)))
            to (first (filter #(= (:to e) (:id %)) (:classes scene)))]
        (curve/constrain-ends (curve/basis-path pts)
                              (:rect from) (:rect to))))))

(defn- edge-bounds [scene e]
  (when-let [path (drawn-path scene e)]
    (curve/path-bounds path)))

(defn- content-rects [scene]
  (concat (keep :rect (:packages scene))
          (keep :rect (:classes scene))
          (keep #(edge-bounds scene %) (:edges scene))))

(defn- inflate [r pad]
  (geom/rect (- (:x r) pad)
             (- (:y r) pad)
             (+ (:w r) (* 2 pad))
             (+ (:h r) (* 2 pad))))

(defn- bounds-of [scene]
  (or (some-> (geom/union (content-rects scene))
              (inflate layout/head-size))
      (geom/rect 0 0 400 300)))

(defn- with-size [scene]
  (let [b (bounds-of scene)]
    (assoc scene :size {:w (+ (geom/right b) layout/margin)
                        :h (+ (geom/bottom b) layout/margin)
                        :min-x (min 0.0 (- (:x b) layout/margin))
                        :min-y (min 0.0 (- (:y b) layout/margin))})))

(defn- fit-scene
  "Shift and size the scene so routed edges that swing past the boxes stay on canvas."
  [scene]
  (let [b (bounds-of scene)
        dx (max 0 (- layout/margin (:x b)))
        dy (max 0 (- layout/margin (:y b)))
        scene (if (and (zero? dx) (zero? dy))
                scene
                (translate scene dx dy))]
    (with-size scene)))

(defn- nearby-rects [classes e bb]
  (let [ends #{(:from e) (:to e)}
        probe (when bb (geom/inflate bb (+ layout/under-gap 8)))]
    (into []
          (keep (fn [c]
                  (when (and (not (:dummy? c))
                             (not (contains? ends (:id c)))
                             (or (nil? probe)
                                 (geom/overlaps? probe (:rect c))))
                    (:rect c))))
          classes)))

(defn- prepare-edge [classes e]
  (let [pts (vec (:points e))]
    (if-not (next pts)
      e
      (let [idx (into {} (map (juxt :id identity) classes))
            from (idx (:from e))
            to (idx (:to e))
            path (-> (curve/basis-path pts)
                     (curve/constrain-ends (:rect from) (:rect to)))
            [behind tip] (curve/end-tangent path)
            samples (curve/flatten-path path)
            bb (curve/path-bounds path)
            strokes (geom/gap-polyline samples
                                       (nearby-rects classes e bb)
                                       layout/under-gap)]
        (assoc e
          :strokes strokes
          :tip tip
          :behind behind
          :draw-bounds bb)))))

(defn- prepare-scene [scene]
  (let [cs (:classes scene)]
    (assoc scene
      :class-by-id (into {} (map (juxt :id identity) cs))
      :package-by-id (into {} (map (juxt :id identity) (:packages scene)))
      :edges (mapv #(prepare-edge cs %) (:edges scene)))))

(defn compile-diagram [diagram]
  (let [scene (prepare-scene (fit-scene (route/route (layout/layout diagram))))]
    (if (:hide-edges diagram)
      (assoc scene :dep-indicators (hit/dep-indicators scene))
      scene)))

(defn compile-document
  "Layout and route each diagram, then stack them top to bottom."
  [doc]
  (let [gap 64
        title-h 40
        raw (map-indexed
              (fn [i d]
                (-> (fit-scene (route/route (layout/layout d)))
                    (assoc :title (:title d) :crap (:crap d))
                    (#(qualify i %))))
              (:diagrams doc))
        [total-h sections]
        (reduce
          (fn [[y acc] s]
            (let [s' (translate s layout/margin (+ y title-h))]
              [(+ y title-h (get-in s [:size :h]) gap)
               (conj acc (assoc s' :title-y y))]))
          [24 []]
          raw)
        stacked {:packages (vec (mapcat :packages sections))
                 :classes (vec (mapcat :classes sections))
                 :edges (vec (mapcat :edges sections))}
        b (bounds-of stacked)
        dx (max 0 (- layout/margin (:x b)))
        sections (if (zero? dx)
                   sections
                   (mapv #(translate % dx 0) sections))
        fitted (with-size (if (zero? dx)
                            stacked
                            (translate stacked dx 0)))
        prepared (prepare-scene fitted)]
    {:title (:title doc)
     :sections sections
     :classes (:classes prepared)
     :packages (:packages prepared)
     :edges (:edges prepared)
     :class-by-id (:class-by-id prepared)
     :package-by-id (:package-by-id prepared)
     :diagram {:title (:title doc)}
     :size (assoc (:size prepared) :h total-h)}))
