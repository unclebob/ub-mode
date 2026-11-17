(ns uml-viewer.engine.curve-spec
  (:require [speclj.core :refer :all]
            [uml-viewer.engine.curve :as curve]
            [uml-viewer.domain.geom :as geom]
            [uml-viewer.domain.ir :as ir]
            [uml-viewer.engine.layout :as layout]
            [uml-viewer.engine.route :as route]))

(describe "class box sizing"
  (it "grows with the longest text line"
    (let [[w1] (layout/class-box-size {:name "A"})
          [w2] (layout/class-box-size {:name "AVeryLongClassName"})]
      (should (> w2 w1))))

  (it "grows taller when members are added"
    (let [[_ h1] (layout/class-box-size {:name "A"})
          [_ h2] (layout/class-box-size {:name "A"
                                         :fields [{:text "x : int"}]
                                         :ops [{:text "go() : void"}]})]
      (should (> h2 h1)))))

(describe "curve"
  (it "bounds a cubic by its control hull, not just the polyline"
    (let [path {:start [0.0 0.0]
                :ops [{:op :cubic :c1 [-80.0 10.0] :c2 [-80.0 70.0] :p [0.0 80.0]}]}
          b (curve/path-bounds path)]
      (should (<= (:x b) -80.0))
      (should (>= (geom/right b) 0.0))))

  (it "chamfers elbows so the spline does not pass through the raw corner"
    (let [rounded (curve/round-corners [[0 0] [0 80] [80 80]] 24)]
      (should= [0.0 0.0] (mapv double (first rounded)))
      (should= [80.0 80.0] (mapv double (last rounded)))
      (should-not (some (fn [p] (and (< (abs (- (first p) 0.0)) 0.01)
                                     (< (abs (- (second p) 80.0)) 0.01)))
                        (rest (butlast rounded))))
      (should= 4 (count rounded))))

  (it "handles short polylines"
    (should= [] (:ops (curve/basis-path [])))
    (should= [] (:ops (curve/basis-path [[0 0]])))
    (should= :line (:op (first (:ops (curve/basis-path [[0 0] [1 1]]))))))

  (it "emits cubics for a polyline with elbows"
    (let [pts [[0 0] [0 48] [80 48] [80 96] [160 96] [160 120]]
          path (curve/basis-path pts)
          ops (:ops path)]
      (should= [0 0] (:start path))
      (should (some #(= :cubic (:op %)) ops))
      (should= :cubic (:op (last ops)))
      (should= (mapv double (last pts)) (:p (last ops)))))

  (it "reports an end tangent from the last stroke, not a forced stub"
    (let [path (curve/basis-path [[0 0] [0 40] [80 40] [80 80]])
          [behind tip] (curve/end-tangent path)]
      (should= [80.0 80.0] (mapv double tip))
      (should-not= behind tip)))

  (it "orients the end tangent along the spline, not the last elbow"
    (let [path (curve/basis-path [[0 0] [40 0] [40 80] [120 80]])
          [behind tip] (curve/end-tangent path)
          dx (- (first tip) (first behind))
          dy (- (second tip) (second behind))]
      (should= [120.0 80.0] (mapv double tip))
      (should (> (abs dy) 1.0))
      (should (> (abs dx) 1.0))))

  (it "lifts a shallow end tangent to 45 degrees from the class edge"
    (let [box (geom/rect 40 360 80 80)
          path (curve/basis-path [[0 0] [10 0] [10 400] [40 400]])
          constrained (curve/constrain-ends path nil box)
          [behind tip] (curve/end-tangent constrained)
          dx (- (first tip) (first behind))
          dy (- (second tip) (second behind))]
      (should= [40.0 400.0] (mapv double tip))
      (should (>= (+ (abs dx) 0.01) (abs dy)))))

  (it "leaves a steep end tangent alone"
    (let [box (geom/rect 120 40 40 80)
          path (curve/basis-path [[0 40] [60 80] [120 80]])
          raw (curve/end-tangent path)
          constrained (curve/end-tangent (curve/constrain-ends path nil box))]
      (should= (mapv double (second raw)) (mapv double (second constrained)))))

  (it "samples a path from the first waypoint to the last"
    (let [path (curve/basis-path [[0 0] [0 40] [80 40] [80 80]])
          pts (curve/flatten-path path)]
      (should= [0.0 0.0] (mapv double (first pts)))
      (should= [80.0 80.0] (mapv double (last pts)))
      (should (> (count pts) 4))))

  (it "meets class edges at 45 degrees or steeper"
    (let [scene (route/route (layout/layout
                              (ir/normalize
                                {:direction :lr
                                 :packages [{:id :p :label "P"
                                             :classes [{:id :hub :name "Hub"}
                                                       {:id :a :name "A"}
                                                       {:id :b :name "B"}
                                                       {:id :c :name "C"}
                                                       {:id :d :name "D"}
                                                       {:id :e :name "E"}]}]
                                 :edges [{:from :hub :to :a :kind :association}
                                         {:from :hub :to :b :kind :association}
                                         {:from :hub :to :c :kind :association}
                                         {:from :hub :to :d :kind :association}
                                         {:from :hub :to :e :kind :association}]})))]
      (doseq [e (:edges scene)
              :let [from (first (filter #(= (:id %) (:from e)) (:classes scene)))
                    to (first (filter #(= (:id %) (:to e)) (:classes scene)))
                    path (curve/constrain-ends (curve/basis-path (:points e))
                                               (:rect from) (:rect to))
                    [behind tip] (curve/end-tangent path)
                    dx (- (first tip) (first behind))
                    dy (- (second tip) (second behind))
                    face (curve/face-of (:rect to) tip)
                    along (if (#{:left :right} face) (abs dy) (abs dx))
                    across (if (#{:left :right} face) (abs dx) (abs dy))]]
        (should (>= (+ across 0.01) along)))))

  (it "clamps a stroke onto the 45° cone of every face"
    (let [clamp (ns-resolve 'uml-viewer.engine.curve 'clamp-to-face-cone)
          axis {:left [-1.0 0.0] :right [1.0 0.0] :top [0.0 -1.0] :bottom [0.0 1.0]}
          half (/ 10.0 (Math/sqrt 2.0))]
      (doseq [[face [ox oy]] axis
              arriving? [false true]]
        (should= (if arriving? [(- ox) (- oy)] [ox oy])
                 (clamp 0.0 0.0 face arriving?)))
      (should= [10.0 10.0] (clamp 10.0 10.0 :right false))
      (should= [half half] (clamp 0.0 10.0 :right false))
      (should= [(- half) half] (clamp 0.0 10.0 :right true))
      (let [[x y] (clamp 10.0 0.0 :bottom false)]
        (should (< (abs (- (Math/hypot x y) 10.0)) 1.0e-9))
        (should (< (abs (- (abs x) (abs y))) 1.0e-9)))
      (let [[x y] (clamp -10.0 0.0 :right false)]
        (should (< (abs (- (Math/hypot x y) 10.0)) 1.0e-9))
        (should (< (abs (- (abs x) (abs y))) 1.0e-9)))))

  (it "rotates a line leaving a face out to 45 degrees"
    (let [box (geom/rect 0 0 100 40)
          path {:start [100.0 20.0]
                :ops [{:op :line :p [100.0 80.0]}
                      {:op :line :p [140.0 20.0]}]}
          out (curve/constrain-ends path box nil)
          p (:p (first (:ops out)))
          dx (- (first p) 100.0)
          dy (- (second p) 20.0)]
      (should (> dx 0.0))
      (should (< (abs (- (abs dx) (abs dy))) 1.0e-6))
      (should= [140.0 20.0] (mapv double (:p (second (:ops out)))))))

  (it "rotates a cubic handle leaving a face out to 45 degrees"
    (let [box (geom/rect 0 0 100 40)
          path {:start [100.0 20.0]
                :ops [{:op :cubic
                       :c1 [100.0 80.0]
                       :c2 [120.0 20.0]
                       :p [140.0 20.0]}
                      {:op :line :p [160.0 20.0]}]}
          out (curve/constrain-ends path box nil)
          c1 (:c1 (first (:ops out)))
          dx (- (first c1) 100.0)
          dy (- (second c1) 20.0)]
      (should (> dx 0.0))
      (should (< (abs (- (abs dx) (abs dy))) 1.0e-6))
      (should= [160.0 20.0] (mapv double (:p (second (:ops out)))))))

  (it "insets an elbow back along the incoming leg"
    (let [rounded (curve/round-corners [[0 0] [0 80] [80 80]] 24)]
      (should (some (fn [p]
                      (and (< (abs (first p)) 0.01)
                           (< (abs (- (second p) 56.0)) 0.01)))
                    rounded)))))
