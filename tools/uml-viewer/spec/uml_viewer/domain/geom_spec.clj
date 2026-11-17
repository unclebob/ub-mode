(ns uml-viewer.domain.geom-spec
  (:require [speclj.core :refer :all]
            [uml-viewer.domain.geom :as geom]))

(describe "geom"
  (it "detects overlapping rectangles"
    (let [a (geom/rect 0 0 10 10)
          b (geom/rect 5 5 10 10)
          c (geom/rect 20 0 5 5)]
      (should (geom/overlaps? a b))
      (should-not (geom/overlaps? a c))))

  (it "clips a ray from the center onto the rectangle border"
    (let [r (geom/rect 0 0 100 50)
          [x y] (geom/intersect-rect r [200 25])]
      (should= 100.0 x)
      (should= 25.0 y)))

  (it "reports containment on the closed rectangle"
    (let [r (geom/rect 10 20 30 40)]
      (should (geom/inside? r 10 20))
      (should (geom/inside? r 40 60))
      (should-not (geom/inside? r 9 20))
      (should-not (geom/inside? r 10 61))))

  (it "clips onto the top of a rectangle when dy dominates"
    (let [r (geom/rect 0 0 100 50)
          [x y] (geom/intersect-rect r [50 -100])]
      (should= 50.0 x)
      (should= 0.0 y)))

  (it "unions rectangles"
    (let [u (geom/union [(geom/rect 0 0 10 10) (geom/rect 5 5 10 10)])]
      (should= 0 (:x u))
      (should= 15 (:w u))
      (should= 15 (:h u))))

  (it "hits a point near a polyline"
    (should (geom/near-polyline? [5 1] [[0 0] [10 0]] 2))
    (should-not (geom/near-polyline? [5 10] [[0 0] [10 0]] 2)))

  (it "hits inside a triangle and near its edge"
    (let [t [[0 0] [10 0] [5 8]]]
      (should (geom/point-in-triangle? [5 2] t))
      (should (geom/near-triangle? [5 -1] t 2))
      (should-not (geom/near-triangle? [20 20] t 2))))

  (it "opens a pad-sized gap where a line passes through a class"
    (let [r (geom/rect 40 0 20 20)
          paths (geom/gap-polyline [[0 10] [100 10]] [r] 5)
          left (first paths)
          right (last paths)]
      (should= 2 (count paths))
      (should= [0.0 10.0] (mapv double (first left)))
      (should= 35.0 (first (last left)))
      (should= 65.0 (first (first right)))
      (should= [100.0 10.0] (mapv double (last right)))))

  (it "does not gap a line that misses the class"
    (let [r (geom/rect 40 40 20 20)]
      (should= [[[0 10] [100 10]]]
               (geom/gap-polyline [[0 10] [100 10]] [r] 5))))

  (it "keeps an unobstructed polyline as one path"
    (should= [[[0 0] [10 0] [10 10]]]
             (geom/gap-polyline [[0 0] [10 0] [10 10]] [] 5)))

  (it "merges overlapping gaps into one opening"
    (let [a (geom/rect 40 0 20 20)
          b (geom/rect 50 0 40 20)
          paths (geom/gap-polyline [[0 10] [200 10]] [a b] 0)
          left (first paths)
          right (last paths)]
      (should= 2 (count paths))
      (should= 40.0 (first (last left)))
      (should= 90.0 (first (first right)))))

  (it "measures distance to a zero-length segment as distance to that point"
    (should= 5.0 (geom/point-seg-dist [4 5] [1 1] [1 1]))))
