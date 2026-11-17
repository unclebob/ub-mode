(ns uml-viewer.adapters.draw
  (:require [clojure.string :as str]
            [quil.core :as q]
            [uml-viewer.domain.config :as config]
            [uml-viewer.domain.geom :as geom]
            [uml-viewer.engine.curve :as curve]
            [uml-viewer.application.detail :as detail]
            [uml-viewer.application.document :as document]
            [uml-viewer.engine.hit :as hit]
            [uml-viewer.engine.layout :as layout]
            [uml-viewer.domain.hierarchy :as hierarchy])
  (:import [java.awt Font]
           [processing.core PFont]))

(def bg [22 28 32])
(def panel [26 36 40])
(def ink [236 236 228])
(def white [255 255 255])
(def muted [157 184 168])
(def gold [232 196 72])
(def line [42 61 54])
(def good [95 181 138])
(def violation [196 42 36])
(def violation-hot [255 64 48])

(defn- mix [a b t]
  (int (+ a (* t (- b a)) 0.5)))

(defn- mix-rgb [c1 c2 t]
  [(mix (nth c1 0) (nth c2 0) t)
   (mix (nth c1 1) (nth c2 1) t)
   (mix (nth c1 2) (nth c2 2) t)])

(defn- grade-ramp [g none red mid green]
  (cond
    (nil? g) none
    (<= g 5.0) (mix-rgb red mid (/ (double g) 5.0))
    :else (mix-rgb mid green (min 1.0 (/ (- (double g) 5.0) 5.0)))))

(defn fill-for
  "RGB fill for a 0–10 red–green grade."
  [grade]
  (grade-ramp grade [36 52 48] [74 40 24] [61 58 24] [30 74 56]))

(defn stroke-for
  "RGB stroke for a 0–10 red–green grade."
  [grade]
  (grade-ramp grade [90 110 100] [224 122 74] [212 192 90] [95 181 138]))

(defn crap-grade-of [m]
  (config/crap-grade (config/crap-risk (:crap m))))

(defn mutation-grade-of [m]
  (config/mutation-grade (config/mutation-ratio m)))

(defn grade-of
  "Average of CRAP and mutation 1–10 grades on `m`."
  [m]
  (config/combined-grade (crap-grade-of m) (mutation-grade-of m)))

(defn coverage-ink [p]
  (cond
    (nil? p) muted
    (>= p 0.8) [95 181 138]
    (>= p 0.5) gold
    :else [224 122 74]))

(defn- rgb
  ([c] (apply q/fill c))
  ([c a] (apply q/fill (conj (vec c) a))))

(defn- stroke-rgb
  ([c] (apply q/stroke c))
  ([c w]
   (apply q/stroke c)
   (q/stroke-weight w)))

(defn- arrowhead [kind [x y] [px py]]
  ;; Mermaid class markers: long shallow chevron (half-angle ~19°), orient=auto.
  (let [ang (Math/atan2 (- y py) (- x px))
        size layout/head-size
        half (/ Math/PI 9)
        left (+ ang half)
        right (- ang half)
        x1 (- x (* size (Math/cos left)))
        y1 (- y (* size (Math/sin left)))
        x2 (- x (* size (Math/cos right)))
        y2 (- y (* size (Math/sin right)))]
    (case kind
      :triangle (do
                  (rgb bg)
                  (q/stroke-weight 1.5)
                  (q/triangle x y x1 y1 x2 y2))
      :diamond (let [back (- ang Math/PI)
                     bx (+ x (* size (Math/cos back)))
                     by (+ y (* size (Math/sin back)))]
                 (rgb bg)
                 (q/quad x y x1 y1 bx by x2 y2))
      :diamond-fill (let [back (- ang Math/PI)
                          bx (+ x (* size (Math/cos back)))
                          by (+ y (* size (Math/sin back)))]
                      (rgb ink)
                      (q/quad x y x1 y1 bx by x2 y2))
      (do
        (q/line x y x1 y1)
        (q/line x y x2 y2)))))

(defn- draw-polyline [pts]
  (doseq [[[x1 y1] [x2 y2]] (partition 2 1 pts)]
    (q/line x1 y1 x2 y2)))

(defn- obstacle-rects [scene e]
  (let [ends #{(:from e) (:to e)}]
    (mapv :rect
          (remove (fn [c]
                    (or (:dummy? c) (contains? ends (:id c))))
                  (:classes scene)))))

(defn- live-edge [e scene]
  (let [pts (vec (:points e))]
    (when (next pts)
      (let [from (hit/class-by-id scene (:from e))
            to (hit/class-by-id scene (:to e))
            path (-> (curve/basis-path pts)
                     (curve/constrain-ends (:rect from) (:rect to)))
            [behind tip] (curve/end-tangent path)
            samples (curve/flatten-path path)
            obstacles (obstacle-rects scene e)]
        {:strokes (geom/gap-polyline samples obstacles layout/under-gap)
         :tip tip
         :behind behind}))))

(defn- edge-ink [e selected?]
  (cond
    (and (:violating e) selected?) [violation-hot 3.2]
    (:violating e) [violation 2.0]
    selected? [gold 2.2]
    :else [muted 1.4]))

(defn- draw-edge [e selected? scene]
  (when-let [drawn (if (:strokes e)
                     e
                     (live-edge e scene))]
    (let [[c w] (edge-ink e selected?)]
      (stroke-rgb c w))
    (q/no-fill)
    (q/stroke-cap :round)
    (doseq [sub (:strokes drawn)]
      (draw-polyline sub))
    (when (and (:head e) (:tip drawn))
      (arrowhead (:head e) (:tip drawn) (:behind drawn)))))

(defn- pfont [italic? size]
  (try
    (PFont. (Font. "SansSerif"
                   (if italic? Font/ITALIC Font/PLAIN)
                   (int size))
            true)
    (catch Exception _ nil)))

(defn- name-font! [italic?]
  (when-let [f (pfont italic? 14)]
    (q/text-font f)))

(defn italic-name?
  "True for component/layer boxes and non-class classifiers (interface, enum, …)."
  [c]
  (boolean
    (or (seq (:contents c))
        (:drill? c)
        (let [st (some-> (:stereotype c) name)]
          (and st (not= "class" st))))))

(def ^:private dot-size 12)
(def ^:private dot-inset 10)
(def ^:private dot-gap 14)

(defn- dot-centers [r]
  (let [y (+ (:y r) dot-inset)
        mx (- (geom/right r) dot-inset)]
    {:c [(- mx dot-gap) y]
     :m [mx y]}))

(defn- draw-metric-dot [[x y] ch grade]
  (rgb (fill-for grade))
  (stroke-rgb (stroke-for grade) 1)
  (q/ellipse x y dot-size dot-size)
  (q/text-align :center :center)
  (q/text-size 9)
  (rgb [255 255 255])
  (q/text ch x y))

(defn- draw-metric-dots [m r]
  (let [dots (dot-centers r)]
    (draw-metric-dot (:c dots) "C" (crap-grade-of m))
    (draw-metric-dot (:m dots) "M" (mutation-grade-of m))))

(defn- draw-package-body [p selected?]
  (let [r (:rect p)
        g (grade-of p)]
    (rgb (fill-for g) 80)
    (stroke-rgb (if selected? gold (stroke-for g))
                (if selected? 2.5 1.4))
    (q/rect (:x r) (:y r) (:w r) (:h r) 8)))

(defn- draw-package-title [p]
  (let [r (:rect p)]
    (rgb gold)
    (q/text-align :left :center)
    (q/text-size 14)
    (name-font! true)
    (q/text (:title p) (+ (:x r) layout/pad) (+ (:y r) (/ layout/banner-h 2)))
    (name-font! false)
    (draw-metric-dots p r)))

(defn- draw-package [p selected?]
  (draw-package-body p selected?)
  (draw-package-title p))

(defn- class-line-ink [kind]
  (case kind
    :crap gold
    :stereo muted
    :child ink
    ink))

(defn- draw-rule [r grade y]
  (stroke-rgb (stroke-for grade) 1)
  (q/line (+ (:x r) 6) (+ y 4)
          (- (geom/right r) 6) (+ y 4))
  (+ y layout/pad))

(defn- draw-text-line
  ([r line y] (draw-text-line r line y false))
  ([r line y italic-name?]
   (q/text-align :center :top)
   (q/text-size (if (= :name (:kind line)) 14 12))
   (when (and italic-name? (= :name (:kind line)))
     (name-font! true))
   (rgb (class-line-ink (:kind line)))
   (q/text (:text line) (geom/cx r) y)
   (when (and italic-name? (= :name (:kind line)))
     (name-font! false))
   (+ y layout/line-h)))

(defn- draw-child-wash [r y]
  (q/no-stroke)
  (rgb gold 55)
  (q/rect (+ (:x r) 5) (- y 1) (- (:w r) 10) layout/line-h 2))

(defn- highlight-child? [line mark]
  (and (= :child (:kind line))
       (= :child (:kind mark))
       (= (:id line) (:id mark))))

(defn- draw-class-line
  ([r grade line y] (draw-class-line r grade line y nil nil false))
  ([r grade line y hover selected] (draw-class-line r grade line y hover selected false))
  ([r grade line y hover selected italic-name?]
   (when (or (highlight-child? line hover) (highlight-child? line selected))
     (draw-child-wash r y))
   (if (= :rule (:kind line))
     (draw-rule r grade y)
     (draw-text-line r line y italic-name?))))

(defn- port-marked? [p mark]
  (and (= :port (:kind mark))
       (= (:id p) (:id mark))))

(defn- draw-port [p hover selected]
  (let [r (:rect p)
        on? (or (port-marked? p hover) (port-marked? p selected))]
    (rgb (if on? [74 96 78] [42 56 52]))
    (stroke-rgb (if on? gold muted) (if on? 2.2 1))
    (q/rect (:x r) (:y r) (:w r) (:h r) 3)
    (q/text-align :center :center)
    (q/text-size 10)
    (rgb (if on? ink muted))
    (q/text (or (:name p) "") (geom/cx r) (geom/cy r))))

(defn- clamp-x [r x]
  (max (+ (:x r) 6) (min (- (geom/right r) 6) x)))

(defn- draw-port-link [[x1 y1] [x2 y2]]
  (stroke-rgb muted 1.3)
  (q/line x1 y1 x2 y2)
  (arrowhead :open [x2 y2] [x1 y1]))

(defn- draw-class-ports [c hover selected]
  (let [r (:rect c)]
    (doseq [p (:in-ports c)]
      (draw-port p hover selected)
      (let [pr (:rect p)
            x (geom/cx pr)]
        (draw-port-link [x (geom/bottom pr)] [(clamp-x r x) (:y r)])))
    (doseq [p (:out-ports c)]
      (draw-port p hover selected)
      (let [pr (:rect p)
            x (geom/cx pr)]
        (draw-port-link [(clamp-x r x) (geom/bottom r)] [x (:y pr)])))))

(defn- corner-mark [c]
  (case (some-> (:stereotype c) name)
    "abstract" "α"
    "interface" "I"
    nil))

(defn- draw-corner-mark [r ch]
  (let [cx (first (:c (dot-centers r)))]
    (q/text-align :right :top)
    (q/text-size 12)
    (rgb [255 255 255])
    (q/text ch (- cx 10) (+ (:y r) 4))))

(defn- draw-oval [c selected? hovered?]
  (let [r (:rect c)]
    (rgb (fill-for nil))
    (stroke-rgb (cond
                  selected? gold
                  hovered? ink
                  :else (stroke-for nil))
                (if selected? 2.6 1.3))
    (q/ellipse (geom/cx r) (geom/cy r) (:w r) (:h r))
    (q/text-align :center :center)
    (q/text-size 14)
    (rgb ink)
    (q/text (:name c) (geom/cx r) (geom/cy r))))

(defn- draw-class
  ([c selected? hovered?] (draw-class c selected? hovered? nil nil))
  ([c selected? hovered? hover selected]
   (if (= :oval (:shape c))
     (draw-oval c selected? hovered?)
     (let [r (:rect c)
           g (grade-of c)]
       (rgb (fill-for g))
       (stroke-rgb (cond
                     selected? gold
                     hovered? ink
                     :else (stroke-for g))
                   (if selected? 2.6 1.3))
       (q/rect (:x r) (:y r) (:w r) (:h r) 4)
       (when (some? (:level c))
         (q/text-align :left :top)
         (q/text-size 11)
         (rgb muted)
         (q/text (str (:level c)) (+ (:x r) 5) (+ (:y r) 3)))
       (draw-class-ports c hover selected)
       (reduce (fn [y line] (draw-class-line r g line y hover selected
                                            (italic-name? c)))
               (+ (:y r) layout/pad 4)
               (:lines c))
       (draw-metric-dots c r)
       (when-let [ch (corner-mark c)]
         (draw-corner-mark r ch))))))

(def ^:private declutter-label
  {:full "Declutter none"
   :arrows "Declutter arrows"
   :triangles "Remove arrows"
   :elements "Declutter elements"
   :classes "Declutter classes"})

(defn- dep-label [d]
  (str (name (:from d)) " -> " (name (:to d))))

(defn- draw-dep-triangle [ind]
  (let [[[x1 y1] [x2 y2] [x3 y3]] (:triangle ind)]
    (q/no-stroke)
    (rgb (if (:violating ind) violation muted))
    (q/triangle x1 y1 x2 y2 x3 y3)))

(defn- draw-edge-popup [hover pointer]
  (when (and (#{:edge :dep} (:kind hover)) (seq (:deps hover)) (seq pointer))
    (let [deps (vec (sort-by (juxt (complement :violating)
                                   #(name (:from %))
                                   #(name (:to %)))
                             (:deps hover)))
          labels (mapv dep-label deps)
          line-h 16
          pad 8
          tw (apply max 40 (map layout/text-w labels))
          box-w (+ (* 2 pad) tw)
          box-h (+ (* 2 pad) (* line-h (count labels)))
          [mx my] pointer
          x (max 8 (min (double mx) (max 8.0 (- (q/width) box-w 8))))
          y (let [below (+ (double my) 16)]
              (if (< (+ below box-h) (q/height))
                below
                (max 8.0 (- (double my) box-h 8))))]
      (rgb [18 22 24] 240)
      (q/no-stroke)
      (q/rect x y box-w box-h 4)
      (q/stroke-weight 1)
      (stroke-rgb line 1)
      (q/no-fill)
      (q/rect x y box-w box-h 4)
      (q/text-align :left :top)
      (q/text-size 12)
      (doseq [[i d] (map-indexed vector deps)]
        (rgb (if (:violating d) violation ink))
        (q/text (dep-label d) (+ x pad) (+ y pad (* i line-h)))))))

(defn- draw-btn [r label]
  (rgb [42 61 54])
  (q/no-stroke)
  (q/rect (:x r) (:y r) (:w r) (:h r) 4)
  (rgb gold)
  (q/text-align :center :center)
  (q/text-size 13)
  (q/text label (geom/cx r) (geom/cy r)))

(defn- draw-real-diagram-row [state w selected]
  (let [real (layout/real-diagram-rect w)
        real-on? (nil? selected)
        real-label (or (get-in state [:doc :title]) "Real diagram")]
    (when real-on?
      (rgb gold 40)
      (q/no-stroke)
      (q/rect (:x real) (:y real) (:w real) (:h real) 3))
    (q/text-align :left :center)
    (q/text-size 12)
    (rgb (if real-on? gold ink))
    (q/text real-label (+ (:x real) 6) (geom/cy real))))

(defn- draw-proposal-row [w selected i p]
  (let [r (layout/proposal-row-rect w i)]
    (when (= selected (:id p))
      (rgb gold 40)
      (q/no-stroke)
      (q/rect (:x r) (:y r) (:w r) (:h r) 3))
    (q/text-align :left :center)
    (q/text-size 12)
    (rgb (if (= selected (:id p)) gold ink))
    (q/text (or (:name p) (name (:id p)))
            (+ (:x r) 6) (geom/cy r))))

(defn- declutter-button-label [state]
  (get declutter-label (or (:declutter state) :full) "Declutter none"))

(defn- draw-sidebar-chrome [state]
  (let [w (q/width)
        h (q/height)
        sw layout/sidebar-w
        x (- w sw)
        ps (hierarchy/named-proposals (:doc state))
        n (count ps)
        selected (:proposal-id state)]
    (rgb [18 22 24] 230)
    (q/no-stroke)
    (q/rect x 0 sw h)
    (q/stroke 42 61 54)
    (q/stroke-weight 1)
    (q/line x 0 x h)
    (q/text-align :left :top)
    (q/text-size 16)
    (rgb gold)
    (q/text "Inspector" (+ x 16) 16)
    (draw-real-diagram-row state w selected)
    (q/text-align :left :top)
    (q/text-size 11)
    (rgb muted)
    (q/text "Proposals" (+ x 16) layout/proposals-label-y)
    (doseq [[i p] (map-indexed vector ps)]
      (draw-proposal-row w selected i p))
    (draw-btn (layout/new-proposal-rect w n) "New Proposal")
    (draw-btn (layout/declutter-rect w n) (declutter-button-label state))
    x))

(defn- draw-sidebar-empty [x y]
  (rgb muted)
  (q/text-align :left :top)
  (q/text-size 12)
  (q/text "Click a component for its card.\nDouble-click a component to open it.\nEsc (or ←) goes up a level.\nScroll to pan; Shift-scroll for horizontal.\nCtrl+/− zoom 10%; Ctrl+0 resets.\nR reloads.\nClick the real diagram above Proposals,\nor a proposal to show it."
          (+ x 16) y))

(defn- mutation-line [m]
  (when-let [s (layout/format-mutants (:killed m) (:survived m))]
    (let [u (:uncovered m)]
      (if (and u (pos? u))
        (str s "   " (long u) " uncovered")
        s))))

(defn- draw-sidebar-class [x y scene id]
  (when-let [c (hit/class-by-id scene id)]
    (q/text-align :left :top)
    (q/text-size 13)
    (rgb ink)
    (q/text (:name c) (+ x 16) y)
    (rgb muted)
    (q/text (if-let [p (:package c)]
              (str "package  " (name p))
              "foreign")
            (+ x 16) (+ y 18))
    (let [y (if (some? (:level c))
              (do
                (rgb muted)
                (q/text (str "Level " (:level c)) (+ x 16) (+ y 36))
                (+ y 54))
              (+ y 36))]
      (let [y (if-let [s (layout/format-crap (:crap c))]
                (do
                  (rgb gold)
                  (q/text s (+ x 16) y)
                  (+ y 18))
                y)
            y (if-let [s (mutation-line c)]
                (do
                  (rgb muted)
                  (q/text s (+ x 16) y)
                  (+ y 18))
                y)]
        (rgb ink)
        (q/text (str/join "\n" (keep :text (filter #(#{:field :op} (:kind %))
                                                   (:lines c))))
                (+ x 16) y)))))

(defn- draw-sidebar-package [x y scene id]
  (when-let [p (hit/package-by-id scene id)]
    (q/text-align :left :top)
    (q/text-size 13)
    (rgb ink)
    (q/text (:label p) (+ x 16) y)
    (let [y (if-let [s (layout/format-crap (:crap p))]
              (do
                (rgb gold)
                (q/text s (+ x 16) (+ y 24))
                (+ y 42))
              (+ y 24))
          y (if-let [s (mutation-line p)]
              (do
                (rgb muted)
                (q/text s (+ x 16) y)
                (+ y 18))
              y)]
      (rgb muted)
      (q/text (str (count (filter #(= (:id p) (:package %))
                                  (:classes scene)))
                   " classes")
              (+ x 16) y))))

(defn- draw-sidebar-error [x h err]
  (rgb [224 122 74])
  (q/text (str "IR error:\n" err) (+ x 16) (- h 120)))

(defn- draw-regen-button [state]
  (let [r (layout/regen-button (q/width) (q/height))]
    (rgb [42 61 54])
    (q/no-stroke)
    (q/rect (:x r) (:y r) (:w r) (:h r) 4)
    (rgb gold)
    (q/text-align :center :center)
    (q/text-size 13)
    (q/text "Regen" (geom/cx r) (geom/cy r))
    (when-let [s (:mail-status state)]
      (q/text-align :left :bottom)
      (q/text-size 11)
      (rgb muted)
      (q/text s (:x r) (- (:y r) 8)))))

(defn- draw-sidebar [state]
  (let [x (draw-sidebar-chrome state)
        y (layout/inspector-body-y (count (hierarchy/named-proposals (:doc state))))
        sel (:selected state)
        scene (:scene state)]
    (case (:kind sel)
      nil (draw-sidebar-empty x y)
      :class (draw-sidebar-class x y scene (:id sel))
      :package (draw-sidebar-package x y scene (:id sel))
      nil)
    (when-let [err (:error state)]
      (draw-sidebar-error x (q/height) err))
    (draw-regen-button state)))

(defn- in-view? [r cam-x cam-y vw vh]
  (and r
       (< (:x r) (+ cam-x vw))
       (> (geom/right r) cam-x)
       (< (:y r) (+ cam-y vh))
       (> (geom/bottom r) cam-y)))

(defn- draw-waiting []
  (let [vw (max 0 (- (q/width) layout/sidebar-w))
        vh (q/height)]
    (rgb muted)
    (q/text-align :center :center)
    (q/text-size 18)
    (q/text document/waiting-message (/ vw 2.0) (/ vh 2.0))))

(defn- world-view [state]
  (let [z (double (or (:zoom state) 1.0))
        cam-x (:cam-x state 0)
        cam-y (:cam-y state 0)
        vw (max 0 (- (q/width) layout/sidebar-w))
        vh (q/height)]
    {:z z
     :cam-x cam-x
     :cam-y cam-y
     :world-w (/ vw z)
     :world-h (/ vh z)}))

(defn- selected-box-id [hit]
  (cond
    (= :class (:kind hit)) (:id hit)
    (= :child (:kind hit)) (:parent hit)
    (= :port (:kind hit)) (:parent hit)
    (= :package (:kind hit)) (:id hit)
    :else nil))

(defn- hovered-box-id [hit]
  (cond
    (= :class (:kind hit)) (:id hit)
    (= :child (:kind hit)) (:parent hit)
    (= :port (:kind hit)) (:parent hit)
    :else nil))

(defn- package-chosen? [state p]
  (and (= :package (get-in state [:selected :kind]))
       (= (:id p) (get-in state [:selected :id]))))

(defn- edge-visible? [e cam-x cam-y world-w world-h]
  (let [b (:draw-bounds e)]
    (or (nil? b) (in-view? b cam-x cam-y world-w world-h))))

(defn- edge-hot? [e sel-id hover]
  (or (= sel-id (:from e))
      (= sel-id (:to e))
      (contains? (:via-ids e) sel-id)
      (and (= :edge (:kind hover))
           (= (:from e) (:from hover))
           (= (:to e) (:to hover)))))

(defn- class-visible? [c cam-x cam-y world-w world-h]
  (or (in-view? (:rect c) cam-x cam-y world-w world-h)
      (some #(in-view? (:rect %) cam-x cam-y world-w world-h)
            (concat (:in-ports c) (:out-ports c)))))

(defn- draw-package-bodies [scene state cam-x cam-y world-w world-h]
  (doseq [p (:packages scene)
          :when (in-view? (:rect p) cam-x cam-y world-w world-h)]
    (draw-package-body p (package-chosen? state p))))

(defn- draw-scene-edges [scene sel-id hover cam-x cam-y world-w world-h]
  (when-not (get-in scene [:diagram :hide-edges])
    (doseq [e (:edges scene)
            :when (edge-visible? e cam-x cam-y world-w world-h)]
      (draw-edge e (edge-hot? e sel-id hover) scene))))

(defn- draw-sections [scene]
  (doseq [sec (:sections scene)]
    (rgb gold)
    (q/text-align :left :top)
    (q/text-size 20)
    (q/text (or (:title sec) "") layout/pad (:title-y sec))))

(defn- draw-package-titles [scene cam-x cam-y world-w world-h]
  (doseq [p (:packages scene)
          :when (in-view? (:rect p) cam-x cam-y world-w world-h)]
    (draw-package-title p)))

(defn- draw-scene-classes [scene sel-id hover-id hover sel cam-x cam-y world-w world-h]
  (doseq [c (remove :dummy? (:classes scene))
          :when (class-visible? c cam-x cam-y world-w world-h)]
    (draw-class c
                (= sel-id (:id c))
                (= hover-id (:id c))
                hover
                sel)))

(defn- draw-dep-indicators [scene]
  (doseq [ind (:dep-indicators scene)]
    (draw-dep-triangle ind)))

(defn- draw-world [state]
  (let [{:keys [z cam-x cam-y world-w world-h]} (world-view state)
        scene (:scene state)
        sel (:selected state)
        hover (:hover state)
        sel-id (selected-box-id sel)
        hover-id (hovered-box-id hover)]
    (q/scale z)
    (q/translate (- cam-x) (- cam-y))
    (draw-package-bodies scene state cam-x cam-y world-w world-h)
    (draw-scene-edges scene sel-id hover cam-x cam-y world-w world-h)
    (draw-sections scene)
    (draw-package-titles scene cam-x cam-y world-w world-h)
    (draw-scene-classes scene sel-id hover-id hover sel cam-x cam-y world-w world-h)
    (draw-dep-indicators scene)))

(defn- draw-diagram-banner [state]
  (when (and (not (:waiting state))
             (get-in state [:scene :diagram :title]))
    (q/text-align :left :top)
    (if (get-in state [:scene :diagram :proposal])
      (do
        (rgb gold)
        (q/text-size 16)
        (q/text (or (get-in state [:scene :diagram :title])
                    "PROPOSAL — not instantiated in code")
                12 8))
      (do
        (rgb muted)
        (q/text-size 12)
        (q/text (get-in state [:scene :diagram :title]) 12 8)))))

(defn- draw-nav-label [state]
  (when (or (seq (:focus state)) (:open-layer state))
    (rgb gold)
    (q/text-align :left :top)
    (q/text-size 14)
    (let [label (if (seq (:focus state))
                  (str/join "." (map name (:focus state)))
                  (or (get-in state [:scene :diagram :title])
                      (name (:open-layer state))))]
      (q/text (str "← " label) 12 28))))

(defn draw-state [state]
  (apply q/background bg)
  (when (:waiting state)
    (draw-waiting))
  (q/push-matrix)
  (draw-world state)
  (q/pop-matrix)
  (draw-sidebar state)
  (draw-diagram-banner state)
  (draw-nav-label state)
  (draw-edge-popup (:hover state) (:pointer state)))

(defn- detail-row-color [row]
  (case (:kind row)
    :name ink
    :module ink
    :crap gold
    :heading gold
    :field ink
    :rel ink
    :stats ink
    muted))

(defn- count-ink [n]
  (if (pos? (or n 0)) violation good))

(defn- cell-color [row col]
  (case (:id col)
    :cov (coverage-ink (:coverage row))
    :crap (stroke-for (config/crap-grade (:crap-n row)))
    :killed white
    :survived (count-ink (:survived row))
    :uncovered (count-ink (:uncovered row))
    :cc muted
    muted))

(defn- detail-column-visible? [row col]
  (or (= :col-header (:kind row))
      (not (and (:mut-note row) (= :mutation (:group col))))))

(defn- detail-cell-text [row col]
  (if (= :col-header (:kind row))
    (:label col)
    (get row (:key col))))

(defn- detail-cell-ink [row col]
  (if (= :col-header (:kind row))
    gold
    (cell-color row col)))

(defn- draw-detail-cell [row col y]
  (when-let [s (detail-cell-text row col)]
    (q/text-align :right :top)
    (q/text-size 13)
    (rgb (detail-cell-ink row col))
    (q/text s (:right col) y)))

(defn- draw-detail-columns [row y]
  (doseq [col (detail/column-layout)
          :when (detail-column-visible? row col)]
    (draw-detail-cell row col y)))

(defn- draw-mut-note [row y]
  (when (and (not= :col-header (:kind row)) (:mut-note row))
    (when-let [g (first (filter #(= :mutation (:id %)) (detail/group-layout)))]
      (q/text-align :right :top)
      (q/text-size 13)
      (rgb muted)
      (q/text (:mut-note row) (:right g) y))))

(defn- draw-detail-cells [row y]
  (draw-detail-columns row y)
  (draw-mut-note row y))

(defn- draw-detail-groups [y]
  (doseq [g (detail/group-layout)]
    (when (:label g)
      (q/text-align :center :top)
      (q/text-size 13)
      (rgb gold)
      (q/text (:label g) (/ (+ (:left g) (:right g)) 2.0) y))))

(defn- draw-hover-wash [row]
  (q/no-stroke)
  (q/fill 232 196 72 48)
  (q/rect 0 (:y row) detail/width (:h row)))

(defn- draw-row-label [row hover?]
  (when (:text row)
    (let [x detail/pad
          y (:y row)
          cols (detail/column-layout)
          name-right (if (seq cols)
                       (- (:left (first cols)) detail/col-gap)
                       (- detail/width detail/pad))]
      (q/text-align :left :top)
      (q/text-size (if (= :name (:kind row)) 20 13))
      (rgb (cond
             hover? gold
             (:private row) muted
             :else (detail-row-color row)))
      (q/text (:text row) x y
              (max 0 (- name-right x)) (:h row)))))

(defn- draw-detail-row [row hover?]
  (when hover? (draw-hover-wash row))
  (case (:kind row)
    :group-header (draw-detail-groups (:y row))
    :col-header (draw-detail-cells row (:y row))
    :stats (do (draw-row-label row hover?)
               (draw-detail-cells row (:y row)))
    (draw-row-label row hover?)))

(defn draw-detail
  ([model scroll] (draw-detail model scroll nil))
  ([model scroll hover]
   (apply q/background bg)
   (q/push-matrix)
   (q/translate 0 (- scroll))
   (doseq [row (detail/rows model)]
     (draw-detail-row row (or (and hover (= hover (:op-name row)))
                               (and (= hover :module) (:module row)))))
   (q/pop-matrix)))
