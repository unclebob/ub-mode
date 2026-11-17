(ns uml-viewer.engine.layout
  (:require [uml-viewer.domain.config :as config]
            [uml-viewer.domain.geom :as geom]))

(def char-w 8)
(def line-h 18)
(def pad 12)
(def banner-h 32)
(def class-gap 40)
(def pack-gap 40)
(def rank-gap 80)
(def class-rank-gap 140)
(def margin 40)
(def head-size 16)
(def lane-gap 10)
(def sidebar-w 280)

(defn regen-button
  "Screen-space rect of the inspector Regen control."
  [window-w window-h]
  (let [x (+ (- window-w sidebar-w) 16)]
    {:x x :y (- window-h 56) :w (- sidebar-w 32) :h 32}))

(def inspector-pad 16)
(def inspector-row-h 22)
(def inspector-btn-h 28)
(def inspector-btn-gap 8)

(defn inspector-x [window-w]
  (+ (- window-w sidebar-w) inspector-pad))

(defn inspector-inner-w []
  (- sidebar-w (* 2 inspector-pad)))

(def real-diagram-y 40)
(def proposals-label-y 66)
(def proposal-rows-y 82)

(defn real-diagram-rect
  "Clickable real (namespace-tree) diagram, just above Proposals."
  [window-w]
  {:x (inspector-x window-w)
   :y real-diagram-y
   :w (inspector-inner-w)
   :h inspector-row-h})

(defn proposal-row-rect
  [window-w i]
  {:x (inspector-x window-w)
   :y (+ proposal-rows-y (* i inspector-row-h))
   :w (inspector-inner-w)
   :h inspector-row-h})

(defn new-proposal-rect
  [window-w n]
  {:x (inspector-x window-w)
   :y (+ proposal-rows-y (* n inspector-row-h) inspector-btn-gap)
   :w (inspector-inner-w)
   :h inspector-btn-h})

(defn declutter-rect
  [window-w n]
  (let [nr (new-proposal-rect window-w n)]
    (assoc nr :y (+ (:y nr) inspector-btn-h inspector-btn-gap))))

(defn inspector-body-y
  [n]
  (let [d (declutter-rect 1500 (or n 0))]
    (+ (:y d) inspector-btn-h 16)))

(defn in-rect? [r x y]
  (and r
       (>= x (:x r)) (< x (+ (:x r) (:w r)))
       (>= y (:y r)) (< y (+ (:y r) (:h r)))))

(def under-gap 5)

(defn text-w [s]
  (* char-w (count (or s ""))))

(defn format-crap [crap]
  (when (:mu crap)
    (format "Crap μ %.1f   max %.1f   σ %.1f"
            (double (:mu crap))
            (double (or (:max crap) (:mu crap)))
            (double (or (:sigma crap) 0)))))

(defn format-coverage [p]
  (when p
    (format "%.0f%%" (* 100.0 p))))

(defn format-mutants [killed survived]
  (when (or killed survived)
    (format "%d killed / %d survived"
            (long (or killed 0))
            (long (or survived 0)))))

(defn- stereotype-line [c]
  (when-let [st (:stereotype c)]
    (str "«" (name st) "»")))

(defn- member-gate [c]
  (and (not (:hide-members c)) (empty? (:contents c))))

(defn- shown-kids [c]
  (when-not (:hide-members c)
    (:contents c)))

(defn- field-texts [c]
  (when (member-gate c)
    (mapv :text (or (:fields c) []))))

(defn- op-texts [c]
  (when (member-gate c)
    (mapv :text (remove :private (or (:ops c) [])))))

(defn- child-line [ch]
  {:kind :child
   :text (:name ch)
   :id (:id ch)
   :drill? (boolean (:drill? ch))})

(defn- text-line [kind]
  (fn [text] {:kind kind :text text}))

(defn- ruled-lines [items line]
  (when (seq items)
    (into [{:kind :rule :text nil}] (map line items))))

(defn class-lines [c]
  (let [stereo (stereotype-line c)]
    (vec (concat (when stereo [{:kind :stereo :text stereo}])
                 [{:kind :name :text (:name c)}]
                 (ruled-lines (shown-kids c) child-line)
                 (ruled-lines (field-texts c) (text-line :field))
                 (ruled-lines (op-texts c) (text-line :op))))))

(def port-h 18)
(def port-gap 6)
(def port-link 22)
(def port-link-out 40)
(def port-stair-x 16)
(def port-stair-y 28)

(defn port-w [dep]
  (max 44 (+ 10 (text-w (or (:name dep) (name (:id dep)))))))

(defn- port-row-w [deps]
  (if (seq deps)
    (+ (reduce + 0 (map port-w deps))
       (* port-gap (max 0 (dec (count deps)))))
    0))

(defn- out-stack-w [deps]
  (if (seq deps)
    (let [widest (apply max (map port-w deps))]
      (if (>= (count deps) 2)
        (+ widest port-stair-x)
        widest))
    0))

(defn- out-stack-h [n]
  (if (pos? n)
    (+ port-link-out port-h (* (dec n) port-stair-y))
    0))

(defn line-at
  "Class line under local point [x y], matching draw-class-line y steps."
  [c x y]
  (when (and (:rect c) (seq (:lines c)))
    (loop [lines (:lines c)
           ly (+ (:y (:rect c)) pad 4)]
      (when-let [line (first lines)]
        (if (= :rule (:kind line))
          (recur (rest lines) (+ ly pad))
          (if (<= ly y (+ ly line-h -1))
            line
            (recur (rest lines) (+ ly line-h))))))))

(defn class-box-size [c]
  (let [lines (class-lines c)
        texts (keep :text lines)
        content-w (apply max 0 (map text-w texts))
        w (max 120 (+ (* 2 pad) content-w))
        text-lines (count (remove #(= :rule (:kind %)) lines))
        rules (count (filter #(= :rule (:kind %)) lines))
        h (+ (* 2 pad)
             (* line-h text-lines)
             (* pad rules))]
    [w h lines]))

(defn- inherit-edge? [e]
  (contains? #{:inheritance :implements} (:kind e)))

(defn- bfs-ranks
  "Shortest-path ranks from roots so a hub (Game) keeps all targets on the next rank."
  [ids edges]
  (let [idset (set ids)
        out (reduce (fn [m e]
                      (if-not (and (idset (:from e)) (idset (:to e)))
                        m
                        (if (inherit-edge? e)
                          (update m (:to e) (fnil conj []) (:from e))
                          (update m (:from e) (fnil conj []) (:to e)))))
                    {}
                    edges)
        high (set (mapcat val out))
        roots (let [r (filterv #(not (high %)) ids)]
                (if (seq r) r ids))]
    (loop [q (into clojure.lang.PersistentQueue/EMPTY (map #(vector % 0) roots))
           rank (zipmap roots (repeat 0))]
      (if (empty? q)
        (merge (zipmap ids (repeat 0)) rank)
        (let [[n r] (peek q)
              kids (get out n [])
              fresh (remove #(contains? rank %) kids)]
          (recur (into (pop q) (map #(vector % (inc r)) fresh))
                 (reduce (fn [rk k] (assoc rk k (inc r))) rank fresh)))))))

(defn- neighbors [ids edges]
  (let [idset (set ids)]
    (reduce (fn [m e]
              (if (and (idset (:from e)) (idset (:to e)))
                (-> m
                    (update (:from e) (fnil conj #{}) (:to e))
                    (update (:to e) (fnil conj #{}) (:from e)))
                m))
            {}
            edges)))

(defn- barycenter-order [rank-ids pos nbr]
  (vec
    (sort-by (fn [id]
               (let [xs (keep pos (nbr id))]
                 (if (seq xs)
                   (/ (double (reduce + xs)) (count xs))
                   (double (pos id 0)))))
             rank-ids)))

(defn- place-port-row [c deps y]
  (when (seq deps)
    (let [r (:rect c)
          widths (mapv port-w deps)
          total (+ (reduce + widths) (* port-gap (max 0 (dec (count deps)))))
          x0 (- (geom/cx r) (/ total 2.0))]
      (second
        (reduce (fn [[x acc] [d w]]
                  [(+ x w port-gap)
                   (conj acc (assoc d :rect (geom/rect x y w port-h)))])
                [x0 []]
                (map vector deps widths))))))

(defn- place-out-ports [c deps]
  (when (seq deps)
    (let [r (:rect c)
          widths (mapv port-w deps)
          widest (apply max widths)
          stack-w (if (>= (count deps) 2)
                    (+ widest port-stair-x)
                    widest)
          x0 (- (geom/cx r) (/ stack-w 2.0))
          y0 (+ (geom/bottom r) port-link-out)]
      (mapv (fn [i d w]
              (let [dx (if (odd? i) port-stair-x 0)
                    y (+ y0 (* i port-stair-y))]
                (assoc d :rect (geom/rect (+ x0 dx) y w port-h))))
            (range)
            deps
            widths))))

(defn- layout-ports [c]
  (let [r (:rect c)]
    (assoc c
      :in-ports (or (place-port-row c (:in-deps c)
                                    (- (:y r) port-link port-h))
                    [])
      :out-ports (or (place-out-ports c (:out-deps c)) []))))

(defn- pack-class [c]
  (let [[cw ch lines] (class-box-size c)
        in-space (if (seq (:in-deps c)) (+ port-h port-link) 0)
        out-space (out-stack-h (count (:out-deps c)))
        pw (max cw (port-row-w (:in-deps c)) (out-stack-w (:out-deps c)))]
    (assoc c
      :lines lines
      :content-w cw
      :content-h ch
      :in-space in-space
      :out-space out-space
      :w pw
      :h (+ in-space ch out-space))))

(defn- content-rect [c x y]
  (geom/rect (+ x (/ (- (:w c) (:content-w c)) 2.0))
             (+ y (:in-space c 0))
             (:content-w c)
             (:content-h c)))

(defn- place-classes [classes edges direction]
  (let [sized (mapv pack-class classes)
        by-id (into {} (map (juxt :id identity) sized))
        ids (mapv :id sized)
        ranks (bfs-ranks ids edges)
        nbr (neighbors ids edges)
        lr? (contains? #{:lr :rl} direction)
        use-level? (and (not lr?) (some #(some? (:level %)) sized))
        grouped (if use-level?
                  (group-by #(or (:level (by-id %)) 0) ids)
                  (group-by ranks ids))
        rank-keys (if use-level?
                    (sort-by - (keys grouped))
                    (sort (keys grouped)))
        pos0 (into {} (map-indexed (fn [i id] [id (* i 80)]) ids))
        pos (loop [p pos0 k 0]
              (if (> k 4)
                p
                (recur
                  (reduce (fn [p r]
                            (let [ordered (barycenter-order (grouped r) p nbr)]
                              (into p (map-indexed (fn [i id] [id (* i 80)]) ordered))))
                          p
                          rank-keys)
                  (inc k))))
        groups (mapv (fn [r]
                       (let [ordered (barycenter-order (grouped r) pos nbr)
                             items (mapv by-id ordered)]
                         {:rank r
                          :items items
                          :w (apply max 0 (map :w items))
                          :h (apply max 0 (map :h items))}))
                     rank-keys)]
    (if lr?
      (let [cols (mapv (fn [g]
                         (let [h (+ (apply + (map :h (:items g)))
                                    (* class-gap (max 0 (dec (count (:items g))))))]
                           (assoc g :col-h h)))
                       groups)
            total-h (apply max 0 (map :col-h cols))]
        (second
          (reduce
            (fn [[x acc] col]
              (let [y0 (/ (max 0 (- total-h (:col-h col))) 2.0)
                    placed (second
                             (reduce
                               (fn [[y out] c]
                                 [(+ y (:h c) class-gap)
                                  (conj out (assoc (dissoc c :w :h)
                                              :rank (:rank col)
                                              :rect (content-rect c x y)))])
                               [y0 []]
                               (:items col)))]
                [(+ x (:w col) class-rank-gap)
                 (into acc placed)]))
            [0 []]
            cols)))
      (second
        (reduce
          (fn [[y acc] row]
            (let [row-placed (second
                               (reduce
                                 (fn [[x out] c]
                                   [(+ x (:w c) class-gap)
                                    (conj out (assoc (dissoc c :w :h)
                                                :rank (:rank row)
                                                :rect (content-rect c x y)))])
                                 [0 []]
                                 (:items row)))]
              [(+ y (:h row) class-rank-gap)
               (into acc row-placed)]))
          [0 []]
          groups)))))

(defn- shift-class [c pkg origin-x origin-y rank-base]
  (layout-ports
    (assoc c
      :package (:id pkg)
      :rank (+ rank-base (:rank c 0))
      :rect (geom/rect (+ origin-x pad (get-in c [:rect :x]))
                       (+ origin-y banner-h pad (get-in c [:rect :y]))
                       (get-in c [:rect :w])
                       (get-in c [:rect :h])))))

(defn- rolled-crap
  "Function-weighted CRAP. Each class summary already covers that class."
  [classes]
  (config/pool-crap classes true))

(defn- rolled-mutants [classes]
  (config/pool-mutants classes))

(defn- package-body [real origin-x origin-y]
  (or (geom/union
        (mapcat (fn [c]
                  (concat [(:rect c)]
                          (map :rect (:in-ports c))
                          (map :rect (:out-ports c))))
                real))
      (geom/rect (+ origin-x pad)
                 (+ origin-y banner-h pad)
                 160 40)))

(defn- seat-class [c pack-rect pkg-id]
  (if (:dummy? c)
    (layout-ports (assoc c :rect pack-rect :package pkg-id))
    c))

(defn- with-rollup [pkg crap mut]
  (cond-> pkg
    crap (assoc :crap crap)
    mut (merge mut)))

(defn- layout-package [pkg origin-x origin-y edges direction rank-base]
  (let [inner (mapv #(shift-class % pkg origin-x origin-y rank-base)
                    (place-classes (:classes pkg) edges direction))
        real (vec (remove :dummy? inner))
        src (if (seq real) real inner)
        title (:label pkg)
        body (package-body real origin-x origin-y)
        pack-w (max (- (+ (geom/right body) pad) origin-x)
                    (+ (* 2 pad) (text-w title))
                    180)
        pack-h (- (+ (geom/bottom body) pad) origin-y)
        pack-rect (geom/rect origin-x origin-y pack-w pack-h)
        inner (mapv #(seat-class % pack-rect (:id pkg)) inner)]
    (with-rollup {:id (:id pkg)
                  :label (:label pkg)
                  :title title
                  :rect pack-rect
                  :classes inner}
                 (rolled-crap src)
                 (rolled-mutants src))))

(defn- oval-size [c]
  (let [w (max 80 (+ (* 2 pad) (text-w (:name c))))
        h (max 44 (long (* 0.55 w)))]
    [w h]))

(defn- layout-foreigns [foreigns origin-x origin-y]
  (second
    (reduce
      (fn [[y acc] c]
        (let [[w h] (oval-size c)]
          [(+ y h class-gap)
           (conj acc (assoc c
                       :shape :oval
                       :lines [{:kind :name :text (:name c)}]
                       :rect (geom/rect origin-x y w h)))]))
      [origin-y []]
      foreigns)))

(defn layout
  "Content-size classes, Sugiyama-place them inside packages, stack packages
   in document order. Foreign ovals sit to the right of the layer stack."
  [diagram]
  (let [edges (:edges diagram)
        direction (:direction diagram :tb)
        pkgs (:packages diagram)
        stride (inc (apply max 1 (map #(count (:classes %)) pkgs)))
        laid (second
               (reduce
                 (fn [[y packs] [i pkg]]
                   (let [lp (layout-package pkg margin y edges direction (* i stride))]
                     [(+ y (get-in lp [:rect :h]) rank-gap)
                      (conj packs lp)]))
                 [margin []]
                 (map-indexed vector pkgs)))
        pack-classes (mapcat :classes laid)
        pack-bounds (or (geom/union (map :rect laid))
                        (geom/rect margin margin 400 300))
        fx (+ (geom/right pack-bounds) pack-gap)
        fy (:y pack-bounds)
        ovals (layout-foreigns (:foreign diagram) fx fy)
        oval-h (if (seq ovals)
                 (- (geom/bottom (:rect (last ovals))) fy)
                 0)
        dy (if (and (seq ovals) (> (:h pack-bounds) oval-h))
             (/ (- (:h pack-bounds) oval-h) 2.0)
             0)
        ovals (mapv #(update % :rect (fn [r] (geom/rect (:x r) (+ (:y r) dy) (:w r) (:h r))))
                    ovals)
        classes (into (vec pack-classes) ovals)
        bounds (or (geom/union (concat (map :rect laid) (map :rect ovals)))
                   pack-bounds)]
    {:diagram diagram
     :packages (mapv #(dissoc % :classes) laid)
     :classes classes
     :size {:w (+ (geom/right bounds) margin)
            :h (+ (geom/bottom bounds) margin)}}))
