(ns uml-viewer.engine.hit-spec
  (:require [speclj.core :refer :all]
            [uml-viewer.engine.compose :as compose]
            [uml-viewer.domain.geom :as geom]
            [uml-viewer.engine.hit :as hit]
            [uml-viewer.domain.ir :as ir]))

(defn scene []
  (compose/compile-diagram
    (ir/normalize
      {:packages
       [{:id :p :label "P"
         :classes [{:id :a :name "A"} {:id :b :name "B"}]}]
       :edges [{:from :a :to :b :kind :association}]})))

(describe "hit"
  (it "selects a package when the point is in the frame but not a class"
    (let [s (scene)
          p (first (:packages s))
          r (:rect p)
          x (+ (:x r) 4)
          y (+ (:y r) 4)]
      (should= {:kind :package :id :p} (hit/at s x y))))

  (it "finds classes and packages by id"
    (let [s (scene)]
      (should= :a (:id (hit/class-by-id s :a)))
      (should= :p (:id (hit/package-by-id s :p)))
      (should-be-nil (hit/class-by-id s :nope))))

  (it "hits an incoming port"
    (let [s (scene)
          a (first (filter #(= :a (:id %)) (:classes s)))
          port {:id :app :name "Application"
                :rect {:x 10 :y 10 :w 40 :h 18}}
          s (assoc s :classes [(assoc a :in-ports [port])])
          r (:rect port)
          hit (hit/at s (geom/cx r) (geom/cy r))]
      (should= :port (:kind hit))
      (should= :app (:id hit))
      (should= :in (:dir hit))))

  (it "hits an outgoing port before the package frame"
    (let [s (scene)
          a (first (filter #(= :a (:id %)) (:classes s)))
          port {:id :domain :name "Domain"
                :rect {:x 10 :y 10 :w 40 :h 18}}
          s (assoc s :classes [(assoc a :out-ports [port])])
          r (:rect port)
          hit (hit/at s (geom/cx r) (geom/cy r))]
      (should= :port (:kind hit))
      (should= :domain (:id hit))
      (should= :a (:parent hit))
      (should= :out (:dir hit))))

  (it "builds incoming and outgoing triangles and hits them"
    (let [a {:id :a :rect {:x 0 :y 0 :w 40 :h 20}}
          b {:id :b :rect {:x 0 :y 80 :w 40 :h 20}}
          e {:from :a :to :b :kind :dependency :violating true
             :deps [{:from :a :to :b :violating true}]}
          scene {:classes [a b] :edges [e]}
          inds (hit/dep-indicators scene)
          out (first (filter #(and (= :a (:id %)) (= :out (:dir %))) inds))
          in (first (filter #(and (= :b (:id %)) (= :in (:dir %))) inds))
          [ox oy] (nth (:triangle out) 2)]
      (should (:violating out))
      (should (:violating in))
      (should= :dep (:kind (hit/indicator-at (assoc scene :dep-indicators inds)
                                            ox oy)))))

  (it "puts triangles on a child whose deps ride a collapsed arrow"
    (let [child {:id :sketch :rect {:x 10 :y 10 :w 40 :h 20}}
          dummy {:id :ui :dummy? true :rect {:x 0 :y 0 :w 80 :h 80}}
          other {:id :host :rect {:x 0 :y 120 :w 40 :h 20}}
          e {:from :ui :to :host :kind :dependency
             :deps [{:from :sketch :to :cli :violating true}]}
          scene {:classes [child dummy other] :edges [e]}
          inds (hit/dep-indicators scene)
          child-out (first (filter #(and (= :sketch (:id %)) (= :out (:dir %))) inds))
          box-out (first (filter #(and (= :ui (:id %)) (= :out (:dir %))) inds))]
      (should child-out)
      (should box-out)
      (should (:violating child-out))
      (should= [{:from :sketch :to :cli :violating true}] (:deps child-out))))

  (it "hits an arrow and lists its leaf deps"
    (let [e {:from :a :to :b :points [[0 0] [100 0] [200 0]]
             :deps [{:from :a :to :b :violating false}]}
          s {:classes [] :packages [] :edges [e]}
          h (hit/at s 100 2)]
      (should= :edge (:kind h))
      (should= :a (:from h))
      (should= :b (:to h))
      (should= [{:from :a :to :b :violating false}] (:deps h))))

  (it "lists edges touching a class"
    (let [s (scene)
          es (hit/connected-edges s :a)]
      (should= 1 (count es))
      (should= :b (:to (first es)))
      (should= :a (:from (first (hit/connected-edges s :b))))))

  (it "uses leaf deps, or the arrow endpoints when there are none"
    (should= [{:from :a :to :b}]
             (hit/deps-of {:deps [{:from :a :to :b}] :from :x :to :y}))
    (should= [{:from :a :to :b :violating true}]
             (hit/deps-of {:from :a :to :b :violating true}))
    (should= [{:from :a :to :b :violating false}]
             (hit/deps-of {:deps [] :from :a :to :b}))
    (should-be-nil (hit/deps-of {}))
    (should-be-nil (hit/deps-of {:from :a}))
    (should-be-nil (hit/deps-of {:to :b}))))
