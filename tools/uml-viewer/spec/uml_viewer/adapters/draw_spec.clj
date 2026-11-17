(ns uml-viewer.adapters.draw-spec
  (:require [quil.core :as q]
            [speclj.core :refer :all]
            [uml-viewer.application.detail :as detail]
            [uml-viewer.application.document :as document]
            [uml-viewer.adapters.draw :as draw]
            [uml-viewer.engine.compose :as compose]
            [uml-viewer.domain.config :as config]
            [uml-viewer.domain.ir :as ir]
            [uml-viewer.engine.layout :as layout]))

(defn- call [sym & args]
  (apply (ns-resolve 'uml-viewer.adapters.draw sym) args))

(defn- record-quil [f]
  (let [log (atom [])
        rec (fn [op]
              (fn [& args]
                (swap! log conj (into [op] args))
                nil))]
    (with-redefs [q/fill (rec :fill)
                  q/stroke (rec :stroke)
                  q/stroke-weight (rec :stroke-weight)
                  q/no-fill (rec :no-fill)
                  q/no-stroke (rec :no-stroke)
                  q/stroke-cap (rec :stroke-cap)
                  q/rect (rec :rect)
                  q/ellipse (rec :ellipse)
                  q/line (rec :line)
                  q/text (rec :text)
                  q/text-align (rec :text-align)
                  q/text-size (rec :text-size)
                  q/text-font (rec :text-font)
                  q/create-font (fn [& _] :font)
                  q/triangle (rec :triangle)
                  q/quad (rec :quad)
                  q/background (rec :background)
                  q/push-matrix (rec :push-matrix)
                  q/pop-matrix (rec :pop-matrix)
                  q/translate (rec :translate)
                  q/scale (rec :scale)
                  q/width (fn [] 1500)
                  q/height (fn [] 920)]
      (f log))))

(defn- kinds [log]
  (map first @log))

(defn- of [log op]
  (filter #(= op (first %)) @log))

(defn- texts [log]
  (keep (fn [cmd]
          (when (and (= :text (first cmd)) (string? (second cmd)))
            (second cmd)))
        @log))

(defn- color-cmd? [cmd op color]
  (and (= op (first cmd))
       (= (vec color) (vec (take (count color) (rest cmd))))))

(defn- painted? [log op color]
  (boolean (some #(color-cmd? % op color) @log)))

(defn- scene []
  (compose/compile-diagram
    (ir/normalize
      {:title "Tiny"
       :packages
       [{:id :p :label "P"
         :classes [{:id :a :name "A"
                    :crap {:mu 1.0 :max 2.0 :sigma 0.1}
                    :ops [{:name "go" :args ["x"] :returns "void"
                           :coverage 0.75 :cc 2 :crap 1.8
                           :killed 3 :survived 1}
                          {:name "hide" :private true}]}
                   {:id :b :name "B"}
                   {:id :c :name "C"}]}]
       :edges [{:from :a :to :b :kind :association}
               {:from :a :to :c :kind :inheritance}]})))

(defn- a-class []
  {:id :a
   :name "A"
   :package :p
   :crap {:mu 1 :max 1 :sigma 0}
   :rect {:x 10 :y 20 :w 100 :h 90}
   :lines [{:kind :name :text "A"}
           {:kind :stereo :text "«bean»"}
           {:kind :rule}
           {:kind :op :text "go()"}]})

(defn- a-package []
  {:id :p :label "P" :title "P"
   :crap {:mu 2 :max 2 :sigma 0}
   :rect {:x 0 :y 0 :w 200 :h 120}})

(describe "draw color helpers"
  (it "maps detail row kinds onto theme inks"
    (should= draw/ink (call 'detail-row-color {:kind :name}))
    (should= draw/gold (call 'detail-row-color {:kind :crap}))
    (should= draw/gold (call 'detail-row-color {:kind :heading}))
    (should= draw/ink (call 'detail-row-color {:kind :field}))
    (should= draw/ink (call 'detail-row-color {:kind :rel}))
    (should= draw/ink (call 'detail-row-color {:kind :stats}))
    (should= draw/muted (call 'detail-row-color {:kind :muted})))

  (it "colors detail cells by column"
    (let [high {:coverage 0.9 :crap-n 0 :survived 0 :killed 4 :uncovered 0}
          low {:coverage 0.2 :crap-n 24 :survived 2 :uncovered 3}]
      (should= (draw/coverage-ink 0.9) (call 'cell-color high {:id :cov}))
      (should= (draw/stroke-for (config/crap-grade 0)) (call 'cell-color high {:id :crap}))
      (should= draw/good (call 'cell-color high {:id :survived}))
      (should= draw/white (call 'cell-color high {:id :killed}))
      (should= draw/good (call 'cell-color high {:id :uncovered}))
      (should= draw/muted (call 'cell-color high {:id :cc}))
      (should= draw/muted (call 'cell-color high {:id :name}))
      (should= draw/violation (call 'cell-color low {:id :survived}))
      (should= draw/white (call 'cell-color low {:id :killed}))
      (should= draw/violation (call 'cell-color low {:id :uncovered}))
      (should= (draw/stroke-for (config/crap-grade 24)) (call 'cell-color low {:id :crap}))
      (should= (draw/coverage-ink 0.2) (call 'cell-color low {:id :cov})))))

(describe "obstacle-rects"
  (it "drops dummy classes and the edge's own ends"
    (let [scene {:classes [{:id :a :rect :ra}
                           {:id :b :rect :rb}
                           {:id :c :rect :rc}
                           {:id :d :rect :rd :dummy? true}]}
          e {:from :a :to :b}]
      (should= [:rc] (call 'obstacle-rects scene e)))))

(describe "quil wrappers"
  (it "fills with rgb and optional alpha"
    (record-quil
      (fn [log]
        (call 'rgb draw/ink)
        (call 'rgb draw/gold 80)
        (should (painted? log :fill draw/ink))
        (should (painted? log :fill (conj (vec draw/gold) 80))))))

  (it "strokes with optional weight"
    (record-quil
      (fn [log]
        (call 'stroke-rgb draw/muted)
        (call 'stroke-rgb draw/gold 2.5)
        (should (painted? log :stroke draw/muted))
        (should (painted? log :stroke draw/gold))
        (should-contain [:stroke-weight 2.5] @log)))))

(describe "arrowhead"
  (it "fills a triangle for inheritance"
    (record-quil
      (fn [log]
        (call 'arrowhead :triangle [10 0] [0 0])
        (should (painted? log :fill draw/bg))
        (should-contain :triangle (kinds log))
        (should-not-contain :quad (kinds log))
        (should-not-contain :line (kinds log)))))

  (it "draws an open diamond for aggregation"
    (record-quil
      (fn [log]
        (call 'arrowhead :diamond [10 0] [0 0])
        (should (painted? log :fill draw/bg))
        (should-contain :quad (kinds log))
        (should-not-contain :triangle (kinds log)))))

  (it "fills a diamond for composition"
    (record-quil
      (fn [log]
        (call 'arrowhead :diamond-fill [10 0] [0 0])
        (should (painted? log :fill draw/ink))
        (should-contain :quad (kinds log)))))

  (it "draws an open chevron otherwise"
    (record-quil
      (fn [log]
        (call 'arrowhead :open [10 0] [0 0])
        (should= [:line :line] (filter #{:line} (kinds log)))
        (should-not-contain :triangle (kinds log))
        (should-not-contain :quad (kinds log))))))

(describe "draw-polyline"
  (it "emits a line per segment and skips a lone point"
    (record-quil
      (fn [log]
        (call 'draw-polyline [[0 0] [1 1] [2 0]])
        (should= 2 (count (of log :line)))
        (reset! log [])
        (call 'draw-polyline [[0 0]])
        (should= [] (of log :line))))))

(describe "draw-package"
  (it "uses gold stroke when selected and CRAP stroke otherwise"
    (record-quil
      (fn [log]
        (call 'draw-package (a-package) true)
        (should (painted? log :stroke draw/gold))
        (should-contain [:stroke-weight 2.5] @log)
        (should-contain "P" (texts log))
        (reset! log [])
        (call 'draw-package (a-package) false)
        (should (painted? log :stroke (draw/stroke-for (draw/grade-of (a-package)))))
        (should-contain [:stroke-weight 1.4] @log)
        (should-contain "C" (texts log))
        (should-contain "M" (texts log)))))

  (it "draws a back link when a proposal layer is open"
    (record-quil
      (fn [log]
        (call 'draw-state {:scene {:diagram {:title "Quil/Swing" :proposal true}
                                   :packages [] :classes [] :edges []}
                           :open-layer :quil-swing :cam-x 0 :cam-y 0})
        (should-contain "← Quil/Swing" (texts log)))))

  (it "paints package titles after arrows"
    (record-quil
      (fn [log]
        (call 'draw-state {:scene (scene) :cam-x 0 :cam-y 0})
        (let [ops @log
              idx (fn [pred]
                    (first (keep-indexed (fn [i e] (when (pred e) i)) ops)))
              edge-i (idx #(#{:line :bezier :quad :triangle} (first %)))
              title-i (idx #(and (= :text (first %)) (= "P" (second %))))]
          (should (number? edge-i))
          (should (number? title-i))
          (should (< edge-i title-i)))))))

(describe "class-line-ink"
  (it "maps line kinds onto theme inks"
    (should= draw/gold (call 'class-line-ink :crap))
    (should= draw/muted (call 'class-line-ink :stereo))
    (should= draw/ink (call 'class-line-ink :name))
    (should= draw/ink (call 'class-line-ink :field))
    (should= draw/ink (call 'class-line-ink :op))))

(describe "draw-rule"
  (it "strokes a CRAP-colored separator and advances by pad"
    (record-quil
      (fn [log]
        (let [r {:x 10 :y 20 :w 100 :h 90}
              y 40]
          (should= (+ y layout/pad) (call 'draw-rule r 10.0 y))
          (should (painted? log :stroke (draw/stroke-for 10.0)))
          (should-contain [:stroke-weight 1] @log)
          (should-contain [:line 16 44 104 44] @log))))))

(describe "draw-text-line"
  (it "sizes the name larger and colors by kind"
    (record-quil
      (fn [log]
        (let [r {:x 10 :y 20 :w 100 :h 90}
              y 36]
          (should= (+ y layout/line-h) (call 'draw-text-line r {:kind :name :text "A"} y))
          (should-contain [:text-size 14] @log)
          (should (painted? log :fill draw/ink))
          (should-contain [:text "A" 60.0 36] @log)
          (reset! log [])
          (call 'draw-text-line r {:kind :crap :text "μ 1.0"} y)
          (should-contain [:text-size 12] @log)
          (should (painted? log :fill draw/gold))
          (reset! log [])
          (call 'draw-text-line r {:kind :stereo :text "«bean»"} y)
          (should (painted? log :fill draw/muted))
          (reset! log [])
          (call 'draw-text-line r {:kind :op :text "go()"} y)
          (should (painted? log :fill draw/ink)))))))

(describe "draw-class-line"
  (it "dispatches a rule or a text line and returns the next y"
    (record-quil
      (fn [log]
        (let [r {:x 10 :y 20 :w 100 :h 90}
              y 40]
          (should= (+ y layout/pad) (call 'draw-class-line r 10.0 {:kind :rule} y))
          (should-contain [:line 16 44 104 44] @log)
          (reset! log [])
          (should= (+ y layout/line-h)
                   (call 'draw-class-line r 10.0 {:kind :name :text "A"} y))
          (should-contain "A" (texts log))
          (should-not-contain :line (kinds log)))))))

(describe "draw-class-ports"
  (it "highlights a hovered port"
    (record-quil
      (fn [log]
        (call 'draw-port
              {:id :domain :name "Domain"
               :rect {:x 20 :y 0 :w 50 :h 18}}
              {:kind :port :id :domain}
              nil)
        (should (some #(= :rect (first %)) @log))
        (should-contain "Domain" (texts log)))))

  (it "draws a port outside the class and a link arrow"
    (record-quil
      (fn [log]
        (call 'draw-class
              (assoc (a-class)
                :in-ports [{:id :app :name "Application"
                            :rect {:x 20 :y 0 :w 50 :h 18}}]
                :out-ports [{:id :domain :name "Domain"
                             :rect {:x 20 :y 120 :w 50 :h 18}}])
              false false)
        (should-contain "Application" (texts log))
        (should-contain "Domain" (texts log))
        (should (some #(= :line (first %)) @log))))))

(describe "draw-class-line highlight"
  (it "washes a hovered child row"
    (record-quil
      (fn [log]
        (let [r {:x 10 :y 20 :w 100 :h 80}
              line {:kind :child :id :layout :text "Layout"}]
          (call 'draw-class-line r nil line 40
                {:kind :child :id :layout} nil)
          (should (some #(= :rect (first %)) @log)))))))

(describe "draw-class"
  (it "strokes gold when selected, ink when hovered, CRAP otherwise"
    (record-quil
      (fn [log]
        (call 'draw-class (a-class) true false)
        (should (painted? log :stroke draw/gold))
        (should-contain [:stroke-weight 2.6] @log)
        (reset! log [])
        (call 'draw-class (a-class) false true)
        (should (painted? log :stroke draw/ink))
        (should-contain [:stroke-weight 1.3] @log)
        (reset! log [])
        (call 'draw-class (a-class) false false)
        (should (painted? log :stroke (draw/stroke-for (draw/grade-of (a-class))))))))

  (it "paints name, stereo, a rule, member text, and C/M dots"
    (record-quil
      (fn [log]
        (call 'draw-class (a-class) false false)
        (should-contain "A" (texts log))
        (should-not-contain "μ 1.0" (texts log))
        (should-contain "«bean»" (texts log))
        (should-contain "go()" (texts log))
        (should-contain "C" (texts log))
        (should-contain "M" (texts log))
        (should-not-contain "α" (texts log))
        (should (some #{:line} (kinds log)))
        (should (some #{:ellipse} (kinds log)))
        (should-contain [:text-size 14] @log)
        (should-contain [:text-size 12] @log))))

  (it "paints a white α in the upper-right of an abstract class"
    (record-quil
      (fn [log]
        (call 'draw-class (assoc (a-class) :stereotype :abstract) false false)
        (should-contain "α" (texts log))
        (should (painted? log :fill [255 255 255]))
        (should-contain [:text-align :right :top] @log)
        (let [cx (first (:c (call 'dot-centers (:rect (a-class)))))]
          (should-contain [:text "α" (- cx 10) 24] @log)))))

  (it "uses italics for names of non-class rectangles"
    (should-not (call 'italic-name? (a-class)))
    (should (call 'italic-name? (assoc (a-class) :stereotype :interface)))
    (should (call 'italic-name? (assoc (a-class) :stereotype :abstract)))
    (should (call 'italic-name? (assoc (a-class) :drill? true)))
    (should (call 'italic-name? (assoc (a-class) :contents [{:id :x :name "X"}]))))

  (it "paints a white I in the upper-right of an interface"
    (record-quil
      (fn [log]
        (call 'draw-class (assoc (a-class) :stereotype :interface) false false)
        (should-contain "I" (texts log))
        (should-not-contain "α" (texts log))
        (should (painted? log :fill [255 255 255]))
        (let [cx (first (:c (call 'dot-centers (:rect (a-class)))))]
          (should-contain [:text "I" (- cx 10) 24] @log)))))

  (it "paints a foreign dependency as an oval"
    (record-quil
      (fn [log]
        (call 'draw-class {:id :quil :name "quil" :shape :oval
                           :rect {:x 10 :y 20 :w 80 :h 44}}
              false false)
        (should-contain [:ellipse 50.0 42.0 80 44] @log)
        (should-contain "quil" (texts log))
        (should-not-contain :rect (kinds log))))))

(describe "draw-sidebar"
  (it "explains the empty inspector"
    (record-quil
      (fn [log]
        (call 'draw-sidebar {:selected nil :scene (scene)})
        (should-contain "Inspector" (texts log))
        (should (some #(re-find #"Click a component" %) (texts log)))
        (should (some #(re-find #"Double-click a component" %) (texts log))))))

  (it "shows class name, package, CRAP, and members"
    (record-quil
      (fn [log]
        (let [s {:selected {:kind :class :id :a} :scene (scene)}]
          (call 'draw-sidebar s)
          (should-contain "A" (texts log))
          (should (some #(re-find #"package" %) (texts log)))
          (should (some #(re-find #"μ 1\.0" %) (texts log)))
          (should (some #(re-find #"go" %) (texts log)))))))

  (it "shows package label, CRAP, and class count"
    (record-quil
      (fn [log]
        (let [s (update (scene) :packages
                        (fn [ps] (mapv #(assoc % :crap {:mu 3 :max 4 :sigma 0.2}) ps)))]
          (call 'draw-sidebar {:selected {:kind :package :id :p} :scene s})
          (should-contain "P" (texts log))
          (should (some #(re-find #"μ 3\.0" %) (texts log)))
          (should (some #(re-find #"classes" %) (texts log)))))))

  (it "skips a missing selection and paints an IR error"
    (record-quil
      (fn [log]
        (call 'draw-sidebar {:selected {:kind :class :id :nope}
                             :scene (scene)
                             :error "bad edn"})
        (should-not-contain "A" (texts log))
        (should (some #(re-find #"IR error" %) (texts log)))
        (should (some #(re-find #"bad edn" %) (texts log)))
        (reset! log [])
        (call 'draw-sidebar {:selected {:kind :package :id :nope}
                             :scene (scene)})
        (should-not-contain "P" (texts log))))))

(describe "draw-edge"
  (it "draws nothing without at least two points"
    (record-quil
      (fn [log]
        (call 'draw-edge {:points [] :from :a :to :b} false (scene))
        (call 'draw-edge {:points [[0 0]] :from :a :to :b} false (scene))
        (should= [] @log))))

  (it "uses gold when selected and muted otherwise, and draws a head"
    (record-quil
      (fn [log]
        (let [s (scene)
              e (first (filter #(= :inheritance (:kind %)) (:edges s)))]
          (call 'draw-edge e true s)
          (should (painted? log :stroke draw/gold))
          (should-contain [:stroke-weight 2.2] @log)
          (should-contain :triangle (kinds log))
          (reset! log [])
          (call 'draw-edge (dissoc e :head) false s)
          (should (painted? log :stroke draw/muted))
          (should-contain [:stroke-weight 1.4] @log)
          (should-not-contain :triangle (kinds log))))))

  (it "paints a violating dependency red, bold red when selected"
    (record-quil
      (fn [log]
        (let [s (scene)
              e (assoc (first (:edges s)) :violating true :kind :dependency)]
          (call 'draw-edge e false s)
          (should (painted? log :stroke draw/violation))
          (should-contain [:stroke-weight 2.0] @log)
          (reset! log [])
          (call 'draw-edge e true s)
          (should (painted? log :stroke draw/violation-hot))
          (should-contain [:stroke-weight 3.2] @log)))))

  (it "lists hovered deps as x -> y with violating rows in red"
    (record-quil
      (fn [log]
        (call 'draw-edge-popup
              {:kind :edge
               :deps [{:from :a :to :b :violating false}
                      {:from :ir :to :layout :violating true}]}
              [40 40])
        (should-contain "a -> b" (texts log))
        (should-contain "ir -> layout" (texts log))
        (should (painted? log :fill draw/violation))))))

(describe "draw-detail-row"
  (it "washes the hovered row and mutes private members"
    (record-quil
      (fn [log]
        (call 'draw-detail-row {:kind :stats :text "- hide" :private true
                                :y 10 :h 18} true)
        (should (painted? log :fill [232 196 72]))
        (should (some #{:rect} (kinds log)))
        (should (painted? log :fill draw/gold)))))

  (it "sizes the name and skips col-header name text"
    (record-quil
      (fn [log]
        (call 'draw-detail-row {:kind :name :text "A" :y 0 :h 22} false)
        (should-contain [:text-size 20] @log)
        (should-contain "A" (texts log))
        (reset! log [])
        (call 'draw-detail-row {:kind :col-header :text "" :y 0 :h 18} false)
        (should-not-contain "" (texts log))
        (should-contain "Crap" (texts log))
        (reset! log [])
        (call 'draw-detail-row {:kind :group-header :text "" :y 0 :h 18} false)
        (should-contain "--crap--" (texts log))
        (should-contain "--mutation--" (texts log))
        (reset! log [])
        (with-redefs [detail/column-layout (fn [] [])]
          (call 'draw-detail-row {:kind :name :text "Z" :y 0 :h 22} false)
          (should-contain "Z" (texts log)))
        (reset! log [])
        (call 'draw-detail-cells {:kind :stats} 10)
        (should= [] (of log :text))
        (reset! log [])
        (call 'draw-detail-row {:kind :stats :text "idle"
                                :crap-s "2.7" :crap-n 2.7 :cov-s "78%"
                                :mut-note "---no mutation sites---" :y 0 :h 18}
              false)
        (should-contain "---no mutation sites---" (texts log))
        (should-contain "2.7" (texts log))
        (should-contain "78%" (texts log)))))

  (it "paints stats cells with coverage and mutation colors"
    (record-quil
      (fn [log]
        (let [row {:kind :stats :text "go" :y 40 :h 18
                   :op-name "go"
                   :crap-s "1.8" :cc-s "2" :cov-s "75%"
                   :killed-s "3" :survived-s "1" :uncovered-s "2"
                   :coverage 0.75 :crap-n 1.8
                   :killed 3 :survived 1 :uncovered 2}]
          (call 'draw-detail-row row false)
          (should-contain "go" (texts log))
          (should-contain "75%" (texts log))
          (should-contain "1" (texts log))
          (should-contain "2" (texts log))
          (should (painted? log :fill (draw/coverage-ink 0.75)))
          (should (painted? log :fill draw/violation))
          (should (painted? log :fill draw/white)))))))

(describe "draw-state"
  (it "paints titles, skips dummy classes, and translates the camera"
    (record-quil
      (fn [log]
        (let [s (scene)
              dummy {:id :ghost :dummy? true :name "Ghost"
                     :rect {:x 0 :y 0 :w 10 :h 10}
                     :lines [{:kind :name :text "Ghost"}]}
              scene (-> s
                        (update :classes conj dummy)
                        (assoc :sections [{:title "Adapters" :title-y 8}
                                          {:title-y 40}]))
              state {:scene scene
                     :selected {:kind :class :id :a}
                     :hover {:kind :class :id :b}
                     :cam-x 7 :cam-y 9}]
          (call 'draw-state state)
          (should-contain :background (kinds log))
          (should-contain :push-matrix (kinds log))
          (should-contain :pop-matrix (kinds log))
          (should-contain [:scale 1.0] @log)
          (should-contain [:translate -7 -9] @log)
          (should-contain "Inspector" (texts log))
          (should-contain "Tiny" (texts log))
          (should-contain "Adapters" (texts log))
          (should-not-contain "Ghost" (texts log))
          (should-contain "A" (texts log))
          (reset! log [])
          (call 'draw-state (assoc state :selected {:kind :package :id :p}
                                   :hover nil))
          (should (painted? log :stroke draw/gold))
          (reset! log [])
          (call 'draw-state {:waiting true
                             :scene document/empty-scene
                             :cam-x 0 :cam-y 0})
          (should-contain document/waiting-message (texts log))
          (should-not-contain "Tiny" (texts log))
          (should-contain "Inspector" (texts log))))))

  (it "paints the class level number at the upper left"
    (record-quil
      (fn [log]
        (let [c {:id :a :name "A" :level 0
                 :rect {:x 10 :y 10 :w 80 :h 40}
                 :lines [{:kind :name :text "A"}]}]
          (call 'draw-class c false false)
          (should-contain "0" (texts log))))))

  (it "paints the proposal banner when the diagram is a proposal"
    (record-quil
      (fn [log]
        (let [state {:scene (update (scene) :diagram assoc
                                    :proposal true
                                    :title "PROPOSAL — not instantiated in code")
                     :cam-x 0 :cam-y 0}]
          (call 'draw-state state)
          (should-contain "PROPOSAL — not instantiated in code" (texts log))
          (should-not-contain "P returns to the namespace tree." (texts log))
          (should (painted? log :fill draw/gold)))))))

(describe "draw-detail"
  (it "scrolls content and highlights the hovered op"
    (record-quil
      (fn [log]
        (let [model (detail/model (scene) :a)]
          (call 'draw-detail model 12)
          (should-contain [:translate 0 -12] @log)
          (should-contain "A" (texts log))
          (reset! log [])
          (call 'draw-detail model 0 "go")
          (should (painted? log :fill [232 196 72]))
          (should-contain "+ go(x) : void" (texts log)))))))

(describe "red-green grades"
  (it "uses a default fill when the grade is missing"
    (should= [36 52 48] (draw/fill-for nil)))

  (it "is red at 0, gold at 5, green at 10"
    (should= [74 40 24] (draw/fill-for 0))
    (should= [61 58 24] (draw/fill-for 5))
    (should= [30 74 56] (draw/fill-for 10)))

  (it "strokes the same 0–10 ramp"
    (should= [90 110 100] (draw/stroke-for nil))
    (should= [224 122 74] (draw/stroke-for 0))
    (should= [212 192 90] (draw/stroke-for 5))
    (should= [95 181 138] (draw/stroke-for 10)))

  (it "averages CRAP and mutation grades on a box"
    (let [good {:crap {:mu 8 :sigma 0} :killed 10 :survived 0}
          mixed {:crap {:mu 8 :sigma 0} :killed 8 :survived 2}
          bad {:crap {:mu 20 :sigma 0} :killed 8 :survived 2}]
      (should= 10.0 (draw/grade-of good))
      (should= 5.5 (draw/grade-of mixed))
      (should= 1.0 (draw/grade-of bad))
      (should= 5.5 (draw/grade-of {:crap {:mu 1 :sigma 0}}))
      (should= 5.5 (draw/grade-of {:killed 10 :survived 0}))
      (should= 1.0 (draw/grade-of {}))
      (should= 1.0 (draw/crap-grade-of {}))
      (should= 1.0 (draw/mutation-grade-of {}))
      (should= 1.0 (draw/mutation-grade-of {:killed 0 :survived 0})))))

(describe "coverage colors"
  (it "bands ink by coverage, high to low"
    (should= draw/muted (draw/coverage-ink nil))
    (should= [95 181 138] (draw/coverage-ink 0.8))
    (should= draw/gold (draw/coverage-ink 0.5))
    (should= [224 122 74] (draw/coverage-ink 0.49))))

(describe "paint decisions"
  (it "names the selected and hovered box"
    (should= :klass (call 'selected-box-id {:kind :class :id :klass}))
    (should= :parent (call 'selected-box-id {:kind :child :parent :parent :id :child}))
    (should= :parent (call 'selected-box-id {:kind :port :parent :parent :id :port}))
    (should= :pkg (call 'selected-box-id {:kind :package :id :pkg}))
    (should-be-nil (call 'selected-box-id {:kind :edge :id :e}))
    (should-be-nil (call 'selected-box-id nil))
    (should= :klass (call 'hovered-box-id {:kind :class :id :klass}))
    (should= :parent (call 'hovered-box-id {:kind :child :parent :parent}))
    (should= :parent (call 'hovered-box-id {:kind :port :parent :parent}))
    (should-be-nil (call 'hovered-box-id {:kind :package :id :pkg}))
    (should-be-nil (call 'hovered-box-id nil)))

  (it "decides which packages, edges, and classes are drawn"
    (should (call 'package-chosen? {:selected {:kind :package :id :p}} {:id :p}))
    (should-not (call 'package-chosen? {:selected {:kind :package :id :p}} {:id :q}))
    (should-not (call 'package-chosen? {:selected {:kind :class :id :p}} {:id :p}))
    (should-not (call 'package-chosen? {} {:id :p}))
    (should (call 'edge-visible? {} 0 0 100 100))
    (should (call 'edge-visible? {:draw-bounds {:x 0 :y 0 :w 5 :h 5}} 0 0 100 100))
    (should-not (call 'edge-visible? {:draw-bounds {:x 500 :y 500 :w 5 :h 5}} 0 0 100 100))
    (let [on {:rect {:x 0 :y 0 :w 10 :h 10}}
          off {:rect {:x 500 :y 500 :w 10 :h 10}}
          port {:rect {:x 1 :y 1 :w 4 :h 4}}]
      (should (call 'class-visible? on 0 0 100 100))
      (should-not (call 'class-visible? off 0 0 100 100))
      (should (call 'class-visible? (assoc off :out-ports [port]) 0 0 100 100)))
    (should (call 'edge-hot? {:from :a :to :b} :a nil))
    (should (call 'edge-hot? {:from :a :to :b} :b nil))
    (should (call 'edge-hot? {:from :a :to :b :via-ids #{:c}} :c nil))
    (should (call 'edge-hot? {:from :a :to :b} nil {:kind :edge :from :a :to :b}))
    (should-not (call 'edge-hot? {:from :a :to :b} nil {:kind :edge :from :a :to :c}))
    (should-not (call 'edge-hot? {:from :a :to :b} nil {:kind :class :id :a}))
    (should-not (call 'edge-hot? {:from :a :to :b} :z nil)))

  (it "paints the real diagram row, proposal rows, and declutter label"
    (record-quil
      (fn [log]
        (should= "Declutter none" (call 'declutter-button-label {}))
        (should= "Declutter arrows" (call 'declutter-button-label {:declutter :arrows}))
        (should= "Declutter none" (call 'declutter-button-label {:declutter :missing}))
        (call 'draw-real-diagram-row {:doc {}} 1500 nil)
        (should-contain "Real diagram" (texts log))
        (let [r (layout/real-diagram-rect 1500)]
          (should-contain [:rect (:x r) (:y r) (:w r) (:h r) 3] @log))
        (reset! log [])
        (call 'draw-real-diagram-row {:doc {:title "Library"}} 1500 :ccp)
        (should-contain "Library" (texts log))
        (let [r (layout/real-diagram-rect 1500)]
          (should-not-contain [:rect (:x r) (:y r) (:w r) (:h r) 3] @log))
        (reset! log [])
        (call 'draw-proposal-row 1500 nil 0 {:id :bare})
        (should-contain "bare" (texts log))
        (let [r (layout/proposal-row-rect 1500 0)]
          (should-not-contain [:rect (:x r) (:y r) (:w r) (:h r) 3] @log))
        (reset! log [])
        (call 'draw-proposal-row 1500 :ccp 0 {:id :ccp :name "CCP"})
        (should-contain "CCP" (texts log))
        (let [r (layout/proposal-row-rect 1500 0)]
          (should-contain [:rect (:x r) (:y r) (:w r) (:h r) 3] @log))
        (reset! log [])
        (should= (- 1500 layout/sidebar-w)
                 (call 'draw-sidebar-chrome
                       {:doc {:title "Library"
                              :proposals [{:id :ccp :name "CCP"}]}
                        :proposal-id :ccp
                        :declutter :arrows}))
        (should-contain "Library" (texts log))
        (should-contain "CCP" (texts log))
        (should-contain "Declutter arrows" (texts log))
        (should-contain "New Proposal" (texts log))
        (should-contain "Proposals" (texts log)))))

  (it "draws a mutation note only beside non-header rows that have one"
    (record-quil
      (fn [log]
        (call 'draw-detail-cells {:kind :col-header :mut-note "note"} 8)
        (should-contain "Crap" (texts log))
        (should-not-contain "note" (texts log))
        (reset! log [])
        (call 'draw-detail-cells {:kind :stats :mut-note "note"
                                  :crap-s "1.0" :killed-s "9"} 8)
        (should-contain "1.0" (texts log))
        (should-contain "note" (texts log))
        (should-not-contain "9" (texts log))
        (reset! log [])
        (with-redefs [detail/group-layout (fn [] [])]
          (call 'draw-detail-cells {:kind :stats :mut-note "note"
                                    :crap-s "1.0" :killed-s "9"} 8)
          (should-contain "1.0" (texts log))
          (should-not-contain "note" (texts log))
          (should-not-contain "9" (texts log))))))

  (it "scales, labels focus, and still paints when edges are hidden"
    (record-quil
      (fn [log]
        (call 'draw-state {:scene (scene) :zoom 2})
        (should-contain [:scale 2.0] @log)
        (should-contain [:translate 0 0] @log)
        (reset! log [])
        (call 'draw-state {:scene (assoc (scene) :dep-indicators
                                         [{:triangle [[0 0] [4 0] [2 4]]}])
                           :focus [:engine :layout]
                           :cam-x 0 :cam-y 0})
        (should-contain :triangle (kinds log))
        (should-contain "← engine.layout" (texts log))
        (reset! log [])
        (call 'draw-diagram-banner {:waiting true :scene {:diagram {:title "T"}}})
        (should-not-contain "T" (texts log))
        (call 'draw-diagram-banner {:scene {:diagram {}}})
        (should-not-contain "T" (texts log))
        (call 'draw-diagram-banner {:scene {:diagram {:title "Real"}}})
        (should-contain "Real" (texts log))
        (should (painted? log :fill draw/muted))
        (reset! log [])
        (call 'draw-diagram-banner {:scene {:diagram {:title "Prop" :proposal true}}})
        (should-contain "Prop" (texts log))
        (should (painted? log :fill draw/gold))
        (reset! log [])
        (call 'draw-nav-label {})
        (should-not-contain "←" (texts log))
        (call 'draw-nav-label {:focus [:engine :layout] :open-layer :inner
                               :scene {:diagram {:title "Title"}}})
        (should-contain "← engine.layout" (texts log))
        (reset! log [])
        (call 'draw-nav-label {:open-layer :inner :scene {:diagram {:title "Title"}}})
        (should-contain "← Title" (texts log))
        (reset! log [])
        (call 'draw-nav-label {:open-layer :inner :scene {:diagram {}}})
        (should-contain "← inner" (texts log))
        (reset! log [])
        (call 'draw-state {:scene (assoc-in (scene) [:diagram :hide-edges] true)
                           :open-layer :inner
                           :cam-x 0 :cam-y 0})
        (should-contain "← Tiny" (texts log))
        (should-contain "Inspector" (texts log))))))
