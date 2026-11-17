(ns uml-viewer.engine.layout-spec
  (:require [speclj.core :refer :all]
            [uml-viewer.engine.curve :as curve]
            [uml-viewer.domain.geom :as geom]
            [uml-viewer.domain.ir :as ir]
            [uml-viewer.engine.layout :as layout]
            [uml-viewer.engine.route :as route]))

(describe "formatters"
  (it "formats coverage, mutants, and crap"
    (should= "90%" (layout/format-coverage 0.9))
    (should-be-nil (layout/format-coverage nil))
    (should= "3 killed / 1 survived" (layout/format-mutants 3 1))
    (should-be-nil (layout/format-mutants nil nil))
    (should= "Crap μ 1.2   max 2.0   σ 0.4"
             (layout/format-crap {:mu 1.2 :max 2.0 :sigma 0.4}))
    (should-be-nil (layout/format-crap nil))))

(def sample
  (ir/normalize
    {:packages
     [{:id :top
       :label "Top"
       :classes [{:id :parent :name "Parent" :stereotype :interface}]}
      {:id :bot
       :label "Bottom"
       :classes [{:id :child :name "Child"}]}]
     :edges [{:from :child :to :parent :kind :implements}]}))

(def hub
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
             {:from :hub :to :e :kind :association}]}))

(describe "layout"
  (it "places enter/leave ports outside the class with room for arrows"
    (let [d (ir/normalize
              {:packages
               [{:id :p :label "P"
                 :classes [{:id :engine :name "Engine"}]}]
               :edges []})
          d (update-in d [:packages 0 :classes 0] assoc
                       :in-deps [{:id :app :name "Application"}]
                       :out-deps [{:id :domain :name "Domain"}])
          scene (layout/layout d)
          c (first (filter #(= :engine (:id %)) (:classes scene)))
          in (first (:in-ports c))
          out (first (:out-ports c))]
      (should (< (geom/bottom (:rect in)) (:y (:rect c))))
      (should (< (geom/bottom (:rect c)) (:y (:rect out))))
      (should (<= layout/port-link-out
                  (- (:y (:rect out)) (geom/bottom (:rect c)))))
      (should (< (:y (:rect (first (:packages scene))))
                 (:y (:rect in))))))

  (it "stairsteps many outgoing ports to keep them narrow"
    (let [deps (mapv (fn [i] {:id (keyword (str "d" i)) :name (str "Dep" i)})
                     (range 4))
          d (ir/normalize
              {:packages
               [{:id :p :label "P"
                 :classes [{:id :engine :name "Engine"}]}]
               :edges []})
          d (update-in d [:packages 0 :classes 0] assoc :out-deps deps)
          scene (layout/layout d)
          c (first (filter #(= :engine (:id %)) (:classes scene)))
          ports (:out-ports c)
          xs (map (comp :x :rect) ports)
          ys (map (comp :y :rect) ports)
          span (- (apply max (map (fn [p]
                                    (+ (:x (:rect p)) (:w (:rect p))))
                                  ports))
                  (apply min xs))]
      (should= 4 (count ports))
      (should (apply < ys))
      (should (< (nth xs 0) (nth xs 1)))
      (should= (nth xs 0) (nth xs 2))
      (should (< span (apply + (map (comp :w :rect) ports))))))

  (it "omits private ops from the class box"
    (let [d (ir/normalize
              {:packages
               [{:id :p :label "P"
                 :classes [{:id :a :name "A"
                            :ops [{:name "show"}
                                  {:name "hide" :private true}]}]}]
               :edges []})
          lines (layout/class-lines (get-in d [:packages 0 :classes 0]))
          texts (keep :text lines)]
      (should (some #{"show"} texts))
      (should-not (some #{"hide"} texts))))

  (it "lists a stereotype, children, fields, and public ops"
    (should= [{:kind :stereo :text "«interface»"}
              {:kind :name :text "Box"}
              {:kind :rule :text nil}
              {:kind :child :text "Kid" :id :kid :drill? true}
              {:kind :child :text "Other" :id :other :drill? false}]
             (layout/class-lines
               {:name "Box"
                :stereotype :interface
                :fields [{:text "x : int"}]
                :ops [{:text "go()"}]
                :contents [{:name "Kid" :id :kid :drill? true}
                           {:name "Other" :id :other}]}))
    (should= [{:kind :name :text "Box"}
              {:kind :rule :text nil}
              {:kind :field :text "x : int"}
              {:kind :rule :text nil}
              {:kind :op :text "go()"}]
             (layout/class-lines
               {:name "Box"
                :fields [{:text "x : int"}]
                :ops [{:text "go()"} {:text "hide()" :private true}]}))
    (should= [{:kind :name :text "Box"}]
             (layout/class-lines
               {:name "Box"
                :hide-members true
                :fields [{:text "x : int"}]
                :ops [{:text "go()"}]
                :contents [{:name "Kid" :id :kid}]}))
    (should= [{:kind :name :text "A"}]
             (layout/class-lines {:name "A"})))

  (it "omits μ/max/σ from the class box"
    (let [d (ir/normalize
              {:packages
               [{:id :p :label "P"
                 :classes [{:id :a :name "A"
                            :crap {:mu 1.2 :max 2.0 :sigma 0.4}}]}]
               :edges []})
          texts (keep :text (layout/class-lines (get-in d [:packages 0 :classes 0])))]
      (should (some #{"A"} texts))
      (should-not (some #(re-find #"μ" %) texts))))

  (it "paints a package title without μ/max/σ and pools child CRAP by function count"
    (let [d (ir/normalize
              {:packages
               [{:id :p :label "P"
                 :classes [{:id :a :name "A"
                            :crap {:mu 2.0 :max 2.0 :sigma 0 :n 3}
                            :killed 9 :survived 1}
                           {:id :b :name "B"
                            :crap {:mu 20.0 :max 20.0 :sigma 0 :n 1}
                            :killed 1 :survived 1}]}]
               :edges []})
          scene (layout/layout d)
          p (first (:packages scene))]
      (should= "P" (:title p))
      (should-not (re-find #"μ" (or (:title p) "")))
      (should= {:mu 6.5 :max 20.0 :sigma 7.8 :n 4} (:crap p))
      (should= 10 (:killed p))
      (should= 2 (:survived p))))

  (it "counts a child with no mutant data as one failed trial per function"
    (let [d (ir/normalize
              {:packages
               [{:id :p :label "P"
                 :classes [{:id :a :name "A"
                            :crap {:mu 2.0 :max 2.0 :sigma 0}
                            :killed 9 :survived 1}
                           {:id :b :name "B"
                            :crap {:mu 20.0 :max 20.0 :sigma 0 :n 1}
                            :ops [{:name "a"} {:name "b"}]}]}]
               :edges []})
          scene (layout/layout d)
          p (first (:packages scene))]
      (should= {:mu 11.0 :max 20.0 :sigma 9.0 :n 2} (:crap p))
      (should= 9 (:killed p))
      (should= 1 (:survived p))
      (should= 2 (:mut-gap p))))

  (it "tints CRAP when a child has no CRAP data"
    (let [d (ir/normalize
              {:packages
               [{:id :p :label "P"
                 :classes [{:id :a :name "A"
                            :crap {:mu 2.0 :max 2.0 :sigma 0}
                            :killed 9 :survived 1}
                           {:id :b :name "B"
                            :killed 1 :survived 1}]}]
               :edges []})
          scene (layout/layout d)
          p (first (:packages scene))]
      (should= {:mu 11.0 :max 20.0 :sigma 9.0 :n 2} (:crap p))
      (should= 10 (:killed p))
      (should= 2 (:survived p))))

  (it "lays an LR hub to the left of its targets"
    (let [scene (layout/layout hub)
          h (first (filter #(= :hub (:id %)) (:classes scene)))
          targets (filter #(#{:a :b :c :d :e} (:id %)) (:classes scene))]
      (should= 5 (count targets))
      (should (every? #(< (geom/cx (:rect h)) (geom/cx (:rect %))) targets))))

  (it "stacks a level-0 class below a higher level"
    (let [d (ir/normalize
              {:packages
               [{:id :p :label "P"
                 :classes [{:id :inner :name "Inner" :level 0}
                           {:id :outer :name "Outer" :level 2}]}]
               :edges []})
          scene (layout/layout d)
          inner (first (filter #(= :inner (:id %)) (:classes scene)))
          outer (first (filter #(= :outer (:id %)) (:classes scene)))]
      (should (< (:y (:rect outer)) (:y (:rect inner))))))

  (it "puts the parent package above the implementing child"
    (let [scene (layout/layout sample)
          top (first (filter #(= :top (:id %)) (:packages scene)))
          bot (first (filter #(= :bot (:id %)) (:packages scene)))]
      (should (< (:y (:rect top)) (:y (:rect bot))))))

  (it "stacks packages in document order even when a later package holds the interface"
    (let [d (ir/normalize
              {:packages
               [{:id :impl
                 :label "Impl"
                 :classes [{:id :child :name "Child"}]}
                {:id :iface
                 :label "Iface"
                 :classes [{:id :parent :name "Parent" :stereotype :interface}]}]
               :edges [{:from :child :to :parent :kind :implements}]})
          scene (layout/layout d)
          impl (first (filter #(= :impl (:id %)) (:packages scene)))
          iface (first (filter #(= :iface (:id %)) (:packages scene)))]
      (should (< (:y (:rect impl)) (:y (:rect iface))))))

  (it "keeps class boxes inside their package"
    (let [scene (layout/layout (ir/load-diagram "examples/library.edn"))]
      (doseq [c (remove #(= :oval (:shape %)) (:classes scene))]
        (let [p (first (filter #(= (:package c) (:id %)) (:packages scene)))
              r (:rect c)
              pr (:rect p)]
          (should (geom/inside? pr (:x r) (:y r)))
          (should (geom/inside? pr (geom/right r) (geom/bottom r)))))))

  (it "places foreign ovals to the right of packages"
    (let [d (ir/normalize
              {:packages
               [{:id :p :label "P"
                 :classes [{:id :a :name "A"}]}]
               :foreign [{:id :quil :name "quil" :shape :oval}]
               :edges [{:from :a :to :quil :kind :dependency}]})
          scene (layout/layout d)
          a (first (filter #(= :a (:id %)) (:classes scene)))
          q (first (filter #(= :quil (:id %)) (:classes scene)))
          p (first (:packages scene))]
      (should= :oval (:shape q))
      (should-be-nil (:package q))
      (should (< (geom/right (:rect p)) (:x (:rect q))))
      (should (geom/inside? (:rect p) (geom/cx (:rect a)) (geom/cy (:rect a))))))

  (it "does not overlap class boxes"
    (let [scene (layout/layout (ir/load-diagram "examples/library.edn"))
          boxes (:classes scene)]
      (doseq [a boxes
              b boxes
              :when (not= (:id a) (:id b))]
        (let [ar (:rect a) br (:rect b)]
          (should-not
            (and (< (:x ar) (geom/right br))
                 (< (:x br) (geom/right ar))
                 (< (:y ar) (geom/bottom br))
                 (< (:y br) (geom/bottom ar)))))))))

(describe "routing"
  (it "marks each edge kind with the matching head"
    (let [d (ir/normalize
              {:packages
               [{:id :p :label "P"
                 :classes [{:id :a :name "A"} {:id :b :name "B"}]}]
               :edges [{:from :a :to :b :kind :association}
                       {:from :a :to :b :kind :dependency}
                       {:from :a :to :b :kind :aggregation}
                       {:from :a :to :b :kind :composition}
                       {:from :a :to :b :kind :inheritance}]})
          heads (map :head (:edges (route/route (layout/layout d))))]
      (should= [:open :open :diamond :diamond-fill :triangle] heads)))

  (it "starts and ends on the class boxes"
    (let [scene (route/route (layout/layout sample))
          e (first (:edges scene))
          child (first (filter #(= :child (:id %)) (:classes scene)))
          parent (first (filter #(= :parent (:id %)) (:classes scene)))
          start (first (:points e))
          end (last (:points e))]
      (should (geom/inside? (geom/inflate (:rect child) 1) start))
      (should (geom/inside? (geom/inflate (:rect parent) 1) end))
      (should= :triangle (:head e))))

  (it "gives a hub's five arrows distinct channel waypoints"
    (let [scene (route/route (layout/layout hub))
          outs (filter #(= :hub (:from %)) (:edges scene))
          mids (map (fn [e] (mapv #(Math/round (double %)) (second (:points e))))
                    outs)]
      (should= 5 (count outs))
      (should= 5 (count (distinct mids)))))

  (it "does not pass a downward target and reverse back to it"
    (let [d (ir/normalize
              {:packages
               [{:id :up :label "Up"
                 :classes [{:id :src :name "Src"}]}
                {:id :down :label "Down"
                 :classes [{:id :dst :name "Dst"}]}]
               :edges [{:from :src :to :dst :kind :dependency}]})
          scene (route/route (layout/layout d))
          src (first (filter #(= :src (:id %)) (:classes scene)))
          dst (first (filter #(= :dst (:id %)) (:classes scene)))
          e (first (filter #(and (= :src (:from %)) (= :dst (:to %)))
                           (:edges scene)))
          ys (map second (:points e))
          end-y (second (last (:points e)))]
      (should (< (geom/cy (:rect src)) (geom/cy (:rect dst))))
      (should (<= (apply max ys) (+ end-y 12.0)))))

  (it "leaves a downward cross-package edge from the source bottom"
    (let [d (ir/normalize
              {:packages
               [{:id :up :label "Up"
                 :classes [{:id :root :name "Root"}
                           {:id :src :name "Src"}]}
                {:id :down :label "Down"
                 :classes [{:id :dst :name "Dst"}]}]
               :edges [{:from :root :to :src :kind :association}
                       {:from :src :to :dst :kind :dependency}]})
          scene (route/route (layout/layout d))
          src (first (filter #(= :src (:id %)) (:classes scene)))
          dst (first (filter #(= :dst (:id %)) (:classes scene)))
          e (first (filter #(and (= :src (:from %)) (= :dst (:to %)))
                           (:edges scene)))
          p0 (first (:points e))
          p1 (second (:points e))]
      (should (< (geom/cy (:rect src)) (geom/cy (:rect dst))))
      (should (<= (abs (- (second p0) (geom/bottom (:rect src)))) 1.51))
      (should (>= (- (second p1) (second p0)) -0.51))))

  (it "does not reverse at the start of a long same-rank detour"
    (let [d (ir/normalize
              {:direction :lr
               :packages [{:id :p :label "P"
                           :classes [{:id :hub :name "Hub"}
                                     {:id :a :name "Above"}
                                     {:id :mid :name "Middle"}
                                     {:id :b :name "Below"}]}]
               :edges [{:from :hub :to :a :kind :association}
                       {:from :hub :to :mid :kind :association}
                       {:from :hub :to :b :kind :association}
                       {:from :a :to :b :kind :association}]})
          scene (route/route (layout/layout d))
          e (first (filter #(and (= :a (:from %)) (= :b (:to %)))
                           (:edges scene)))
          a (first (filter #(= :a (:id %)) (:classes scene)))
          b (first (filter #(= :b (:id %)) (:classes scene)))
          p0 (first (:points e))
          p1 (second (:points e))
          down? (> (geom/cy (:rect b)) (geom/cy (:rect a)))]
      (if down?
        (should (>= (- (second p1) (second p0)) -0.51))
        (should (<= (- (second p1) (second p0)) 0.51)))))

  (it "does not start or end two arrows at the same point on a class"
    (let [scene (route/route (layout/layout hub))
          round (fn [p] (mapv #(Math/round (double %)) p))
          starts (map (fn [e] [(:from e) (round (first (:points e)))])
                      (:edges scene))
          ends (map (fn [e] [(:to e) (round (last (:points e)))])
                    (:edges scene))]
      (doseq [group (vals (group-by first starts))]
        (should= (count group) (count (distinct (map second group)))))
      (doseq [group (vals (group-by first ends))]
        (should= (count group) (count (distinct (map second group)))))))

  (it "does not run segments through other classes"
    (let [scene (route/route (layout/layout hub))]
      (doseq [e (:edges scene)
              [a b] (partition 2 1 (:points e))
              c (:classes scene)
              :when (and (not= (:id c) (:from e))
                         (not= (:id c) (:to e)))]
        (should-not (geom/segment-hits-rect? a b (:rect c))))))

  (it "routes a stacked same-rank pair through the gap, not around the column"
    (let [d (ir/normalize
              {:direction :lr
               :packages [{:id :p :label "P"
                           :classes [{:id :hub :name "Hub"}
                                     {:id :a :name "Above"}
                                     {:id :b :name "Below"}]}]
               :edges [{:from :hub :to :a :kind :association}
                       {:from :hub :to :b :kind :association}
                       {:from :a :to :b :kind :association}]})
          scene (route/route (layout/layout d))
          e (first (filter #(= #{:a :b} #{(:from %) (:to %)}) (:edges scene)))
          a (first (filter #(= :a (:id %)) (:classes scene)))
          b (first (filter #(= :b (:id %)) (:classes scene)))
          xs (map first (:points e))
          pair-left (min (:x (:rect a)) (:x (:rect b)))
          pair-right (max (geom/right (:rect a)) (geom/right (:rect b)))]
      (should (every? #(<= pair-left % pair-right) xs))))

  (it "detours a blocked same-rank edge around the pair instead of the whole rank"
    (let [d (ir/normalize
              {:direction :lr
               :packages [{:id :p :label "P"
                           :classes [{:id :hub :name "Hub"}
                                     {:id :a :name "Above"}
                                     {:id :mid :name "Middle"}
                                     {:id :b :name "Below"}]}]
               :edges [{:from :hub :to :a :kind :association}
                       {:from :hub :to :mid :kind :association}
                       {:from :hub :to :b :kind :association}
                       {:from :a :to :b :kind :association}]})
          scene (route/route (layout/layout d))
          e (first (filter #(= #{:a :b} #{(:from %) (:to %)}) (:edges scene)))
          a (first (filter #(= :a (:id %)) (:classes scene)))
          b (first (filter #(= :b (:id %)) (:classes scene)))
          mid (first (filter #(= :mid (:id %)) (:classes scene)))
          pack (first (:packages scene))
          xs (map first (:points e))
          pair-right (max (geom/right (:rect a)) (geom/right (:rect b)))]
      (doseq [[p q] (partition 2 1 (:points e))]
        (should-not (geom/segment-hits-rect? p q (:rect mid))))
      (should (< (apply max xs) (+ pair-right (* 4 10))))
      (should (< (apply max xs) (geom/right (:rect pack))))))

  (it "does not dash any edge"
    (let [scene (route/route (layout/layout hub))]
      (should (every? #(nil? (:dashed? %)) (:edges scene)))))

  (it "ends the basis stroke on the last waypoint so the head follows the spline"
    (let [path (curve/basis-path [[0 0] [40 0] [40 80] [120 80]])
          last-op (last (:ops path))
          [behind tip] (curve/end-tangent path)]
      (should= :cubic (:op last-op))
      (should= [120.0 80.0] (:p last-op))
      (should= [120.0 80.0] (mapv double tip))
      (should (> (abs (- (second tip) (second behind))) 1.0)))))
