(ns uml-viewer.engine.curve
  (:require [uml-viewer.domain.geom :as geom]))

(def corner-radius 24.0)

(defn- vsub [[x1 y1] [x2 y2]] [(- x1 x2) (- y1 y2)])
(defn- vadd [[x1 y1] [x2 y2]] [(+ x1 x2) (+ y1 y2)])
(defn- vscale [[x y] s] [(* x s) (* y s)])
(defn- vlen [[x y]] (Math/hypot x y))
(defn- vnorm [v]
  (let [l (vlen v)]
    (if (< l 1.0e-9) [0.0 0.0] [(/ (first v) l) (/ (second v) l)])))

(defn round-corners
  "Replace each interior elbow with a pair of points inset along the legs
   so the spline cannot make a tight 90° (or sharper) turn."
  ([pts] (round-corners pts corner-radius))
  ([pts radius]
   (let [pts (vec pts)
         n (count pts)]
     (if (< n 3)
       pts
       (loop [i 1 out [(first pts)]]
         (if (>= i (dec n))
           (conj (vec out) (last pts))
           (let [prev (nth pts (dec i))
                 cur (nth pts i)
                 nxt (nth pts (inc i))
                 a (vsub cur prev)
                 b (vsub nxt cur)
                 la (vlen a)
                 lb (vlen b)
                 r (min (double radius) (* 0.45 la) (* 0.45 lb))
                 na (vnorm a)
                 nb (vnorm b)
                 turn (+ (* (first na) (first nb))
                         (* (second na) (second nb)))]
             (if (or (< r 4.0) (> turn 0.92))
               (recur (inc i) (conj out cur))
               (recur (inc i)
                      (conj out
                            (vadd cur (vscale na (- r)))
                            (vadd cur (vscale nb r))))))))))))

(defn- cubic [x0 y0 x1 y1 x y]
  {:op :cubic
   :c1 [(/ (+ (* 2 x0) x1) 3.0) (/ (+ (* 2 y0) y1) 3.0)]
   :c2 [(/ (+ x0 (* 2 x1)) 3.0) (/ (+ y0 (* 2 y1)) 3.0)]
   :p  [(/ (+ x0 (* 4 x1) x) 6.0) (/ (+ y0 (* 4 y1) y) 6.0)]})

(defn- step
  [{:keys [point x0 y0 x1 y1 ops] :as state} pt]
  (let [x (double (first pt))
        y (double (second pt))]
    (case (int point)
      0 (assoc state :point 1 :x0 x :y0 y :start pt)
      1 (assoc state :point 2 :x1 x :y1 y)
      2 (let [mx (/ (+ (* 5 x0) x1) 6.0)
              my (/ (+ (* 5 y0) y1) 6.0)]
          (assoc state
            :point 3 :x0 x1 :y0 y1 :x1 x :y1 y
            :ops (conj ops {:op :line :p [mx my]} (cubic x0 y0 x1 y1 x y))))
      (assoc state
        :x0 x1 :y0 y1 :x1 x :y1 y
        :ops (conj ops (cubic x0 y0 x1 y1 x y))))))

(defn- finish
  "D3 curveBasis would add a degenerate cubic along the last chord
   and a lineTo the last point. That forces the end tangent onto the
   last polyline segment (orthogonal when the router used a face
   port). Mermaid's marker is orient=auto on the spline, so keep the
   last real cubic's handles and only pin its end to the last point."
  [{:keys [point x1 y1 start ops]}]
  (let [ops (cond
              (>= point 3)
              (if (seq ops)
                (conj (pop ops) (assoc (last ops) :p [x1 y1]))
                ops)
              (= point 2)
              (conj ops {:op :line :p [x1 y1]})
              :else ops)]
    {:start start :ops ops}))

(defn basis-path
  "D3 curveBasis through `pts`. Returns {:start [x y] :ops [...]}."
  [pts]
  (let [pts (vec (round-corners pts))
        n (count pts)]
    (cond
      (zero? n) {:start [0 0] :ops []}
      (= n 1) {:start (first pts) :ops []}
      :else (finish (reduce step
                            {:point 0 :x0 0.0 :y0 0.0 :x1 0.0 :y1 0.0
                             :start (first pts) :ops []}
                            pts)))))

(defn end-tangent
  "Point just behind the path end, along the last stroke (curve or line)."
  [{:keys [start ops]}]
  (loop [cur start ops ops from start]
    (if (empty? ops)
      [from cur]
      (let [op (first ops)
            nxt (:p op)
            from (if (= :cubic (:op op)) (:c2 op) cur)]
        (recur nxt (rest ops) from)))))

(defn face-of
  "Nearest side of `r` to point `p`."
  [r [x y]]
  (let [dl (abs (- x (:x r)))
        dr (abs (- x (geom/right r)))
        dt (abs (- y (:y r)))
        db (abs (- y (geom/bottom r)))
        m (min dl dr dt db)]
    (cond
      (= m dl) :left
      (= m dr) :right
      (= m dt) :top
      :else :bottom)))

(defn- face-out
  "Unit normal pointing out of `face`."
  [face]
  (case face
    :left [-1.0 0.0]
    :right [1.0 0.0]
    :top [0.0 -1.0]
    :bottom [0.0 1.0]))

(defn- travel-normal
  "Outward normal, flipped when the stroke is arriving into the box."
  [face arriving?]
  (let [[x y] (face-out face)]
    (if arriving?
      [(- x) (- y)]
      [x y])))

(defn- in-cone? [along plen]
  (and (pos? along) (<= plen along)))

(defn- cone-unit [px py plen nx ny]
  (if (< plen 1.0e-9)
    [(- ny) nx]
    [(/ px plen) (/ py plen)]))

(defn- on-cone [len nx ny sx sy]
  (let [s (/ 1.0 (Math/sqrt 2.0))]
    [(* len (+ (* s nx) (* s sx)))
     (* len (+ (* s ny) (* s sy)))]))

(defn- clamp-to-face-cone
  "Rotate `(dx,dy)` so its angle with the class edge is at least 45°.
   `arriving?` true: path is heading into the box; false: leaving it."
  [dx dy face arriving?]
  (let [[nx ny] (travel-normal face arriving?)
        len (Math/hypot dx dy)]
    (if (< len 1.0e-6)
      [(* 1.0 nx) (* 1.0 ny)]
      (let [ux (/ dx len)
            uy (/ dy len)
            along (+ (* ux nx) (* uy ny))
            px (- ux (* along nx))
            py (- uy (* along ny))
            plen (Math/hypot px py)]
        (if (in-cone? along plen)
          [dx dy]
          (let [[sx sy] (cone-unit px py plen nx ny)]
            (on-cone len nx ny sx sy)))))))

(defn- constrain-start [path r]
  (let [ops (vec (:ops path))]
    (if (or (nil? r) (empty? ops) (= 1 (count ops)))
      path
      (let [face (face-of r (:start path))
            op (first ops)
            s (:start path)]
        (if (= :line (:op op))
          (let [p (:p op)
                [dx' dy'] (clamp-to-face-cone (- (first p) (first s))
                                              (- (second p) (second s))
                                              face
                                              false)]
            (assoc path :ops (assoc ops 0 (assoc op :p [(+ (first s) dx')
                                                       (+ (second s) dy')]))))
          (if (= :cubic (:op op))
            (let [c1 (:c1 op)
                  [dx' dy'] (clamp-to-face-cone (- (first c1) (first s))
                                                (- (second c1) (second s))
                                                face
                                                false)]
              (assoc path :ops (assoc ops 0 (assoc op :c1 [(+ (first s) dx')
                                                          (+ (second s) dy')]))))
            path))))))

(defn- constrain-end [path r]
  (let [ops (vec (:ops path))]
    (if (or (nil? r) (empty? ops))
      path
      (let [op (last ops)
            face (face-of r (:p op))]
        (if (= :cubic (:op op))
          (let [p (:p op)
                c2 (:c2 op)
                [dx' dy'] (clamp-to-face-cone (- (first p) (first c2))
                                              (- (second p) (second c2))
                                              face
                                              true)
                c2' [(- (first p) dx') (- (second p) dy')]]
            (assoc path :ops (conj (pop ops) (assoc op :c2 c2'))))
          path)))))

(defn constrain-ends
  "Keep the stroke and its end tangent at least 45° to the class edges."
  [path start-r end-r]
  (-> path
      (constrain-start start-r)
      (constrain-end end-r)))

(defn- cubic-at [[x0 y0] [x1 y1] [x2 y2] [x3 y3] t]
  (let [u (- 1.0 t)
        a (* u u u)
        b (* 3.0 u u t)
        c (* 3.0 u t t)
        d (* t t t)]
    [(+ (* a x0) (* b x1) (* c x2) (* d x3))
     (+ (* a y0) (* b y1) (* c y2) (* d y3))]))

(defn path-bounds
  "Axis-aligned box containing the spline (convex hull of Bezier controls)."
  [{:keys [start ops]}]
  (let [pts (into [start]
                  (mapcat (fn [op]
                            (if (= :cubic (:op op))
                              [(:c1 op) (:c2 op) (:p op)]
                              (when-let [p (:p op)] [p])))
                          ops))]
    (when (seq pts)
      (let [xs (map first pts)
            ys (map second pts)
            x0 (apply min xs)
            y0 (apply min ys)]
        (geom/rect x0 y0
                   (- (apply max xs) x0)
                   (- (apply max ys) y0))))))

(defn flatten-path
  "Sample `path` into a polyline. `step` is the cubic parameter increment."
  ([path] (flatten-path path 0.0625))
  ([{:keys [start ops]} step]
   (loop [cur start ops ops out [start]]
     (if (empty? ops)
       out
       (let [op (first ops)
             nxt (:p op)]
         (if (= :cubic (:op op))
           (recur nxt (rest ops)
                  (into out (map #(cubic-at cur (:c1 op) (:c2 op) nxt %)
                                 (rest (range 0.0 1.0000001 step)))))
           (recur nxt (rest ops) (conj out nxt))))))))
