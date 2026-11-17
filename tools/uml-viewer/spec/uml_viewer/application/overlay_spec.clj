(ns uml-viewer.application.overlay-spec
  (:require [clojure.java.io :as io]
            [speclj.core :refer :all]
            [uml-viewer.domain.ir :as ir]
            [uml-viewer.application.overlay :as overlay]))

(describe "overlay"
  (it "paints class and op metrics from crap and mutate snapshots"
    (let [root (.getCanonicalPath (io/file "target" "overlay-demo"))
          crap-dir (io/file root ".metrics")
          mut-dir (io/file root ".metrics" "mutate" "uml_viewer")]
      (.mkdirs mut-dir)
      (spit (io/file crap-dir "crap.edn")
            (pr-str {:entries [{:name "go" :namespace "demo.app"
                                :complexity 3 :coverage 50.0 :crap 6.4}
                               {:name "hide" :namespace "demo.app"
                                :complexity 2 :coverage 100.0 :crap 2.0}]}))
      (spit (io/file mut-dir "demo.edn")
            (pr-str {:source "src/demo/app.clj"
                     :forms [{:id "defn/go" :hash "a" :killed 4 :survived 1 :uncovered 2 :sites 7}
                             {:id "defn-/hide" :hash "b" :killed 2 :survived 0 :uncovered 0 :sites 2}]}))
      (try
        (let [metrics (overlay/load-metrics root)
              d (ir/normalize {:packages
                               [{:id :p :label "P"
                                 :classes [{:id :demo :name "Demo"
                                            :ns "demo.app"
                                            :ops [{:name "go"}]}]}]
                               :edges []})
              painted (overlay/apply-metrics d metrics)
              c (get-in painted [:packages 0 :classes 0])
              go (first (filter #(= "go" (:name %)) (:ops c)))
              hide (first (filter #(= "hide" (:name %)) (:ops c)))]
          (should= 5 (:cc c))
          (should= 6 (:killed c))
          (should= 1 (:survived c))
          (should= 2 (:uncovered c))
          (should= 3 (:cc go))
          (should= 4 (:killed go))
          (should= 1 (:survived go))
          (should= 2 (:uncovered go))
          (should (:private hide))
          (should= 2 (:killed hide))
          (should= 0 (:uncovered hide))
          (should= 7 (:sites go))
          (should= 9 (:sites c)))
        (finally
          (doseq [f (reverse (file-seq (io/file root)))]
            (io/delete-file f true))))))

  (it "derives sites from killed, survived, and uncovered when sites is absent"
    (let [root (.getCanonicalPath (io/file "target" "overlay-legacy-sites"))
          mut-dir (io/file root ".metrics" "mutate" "skillBoard" "gateways")]
      (.mkdirs mut-dir)
      (spit (io/file mut-dir "wind_data.edn")
            (pr-str {:source "src/skillBoard/gateways/wind_data.clj"
                     :forms [{:id "defn/radius-bounds" :killed 5 :survived 0 :uncovered 0}
                             {:id "defn/synthetic-grid" :killed 1 :survived 5 :uncovered 0}]}))
      (try
        (let [metrics (overlay/load-metrics root)
              d {:hierarchical true
                   :classes [{:id :gateways.wind-data
                              :name "WindData"
                              :ns "skillBoard.gateways.wind-data"
                              :ops [{:name "radius-bounds"}]}]
                   :edges []}
              painted (overlay/apply-metrics d metrics)
              c (first (:classes painted))
              radius (first (filter #(= "radius-bounds" (:name %)) (:ops c)))
              grid (first (filter #(= "synthetic-grid" (:name %)) (:ops c)))]
          (should= 6 (:killed c))
          (should= 5 (:survived c))
          (should= 11 (:sites c))
          (should= 5 (:killed radius))
          (should= 5 (:sites radius))
          (should= 1 (:killed grid))
          (should= 5 (:survived grid))
          (should= 6 (:sites grid)))
        (finally
          (doseq [f (reverse (file-seq (io/file root)))]
            (io/delete-file f true))))))

  (it "matches snapshots by class :ns after normalize"
    (let [root (.getCanonicalPath (io/file "target" "overlay-ns"))
          crap-dir (io/file root ".metrics")]
      (.mkdirs crap-dir)
      (spit (io/file crap-dir "crap.edn")
            (pr-str {:entries [{:name "place" :namespace "demo.board"
                                :complexity 1 :coverage 100.0 :crap 1.0}]}))
      (try
        (let [metrics (overlay/load-metrics root)
              d (ir/normalize {:packages
                               [{:id :p :label "P"
                                 :classes [{:id :board :name "Board"
                                            :ns "demo.board"}]}]
                               :edges []})
              painted (overlay/apply-metrics d metrics)
              c (get-in painted [:packages 0 :classes 0])
              place (first (filter #(= "place" (:name %)) (:ops c)))]
          (should= "demo.board" (:ns c))
          (should place)
          (should= 1 (:cc place)))
        (finally
          (doseq [f (reverse (file-seq (io/file root)))]
            (io/delete-file f true))))))

  (it "finds .metrics by walking up from an EDN path"
    (let [root (io/file "target" "overlay-walk" "examples")
          metrics-dir (io/file "target" "overlay-walk" ".metrics")]
      (.mkdirs root)
      (.mkdirs metrics-dir)
      (spit (io/file root "diagram.edn") "{}")
      (try
        (should= (.getCanonicalPath (io/file "target" "overlay-walk"))
                 (overlay/metrics-root (io/file root "diagram.edn")))
        (finally
          (doseq [f (reverse (file-seq (io/file "target" "overlay-walk")))]
            (io/delete-file f true))))))

  (it "keeps the newer mutation snapshot when two files name one namespace"
    (let [root (.getCanonicalPath (io/file "target" "overlay-newer-snapshot"))
          dotted (io/file root ".metrics" "mutate" "demo.config.edn")
          fresh (io/file root ".metrics" "mutate" "demo" "config.edn")]
      (.mkdirs (.getParentFile fresh))
      (spit fresh (pr-str {:source "src/demo/config.clj"
                           :forms [{:id "defn/pool-crap" :killed 2 :survived 0}]}))
      (spit dotted (pr-str {:namespace "demo.config"
                            :forms [{:id "defn/worse-crap" :killed 1 :survived 9}]}))
      (try
        (.setLastModified dotted 2000)
        (.setLastModified fresh 1000)
        (should= "defn/worse-crap"
                 (:id (first (:forms (get (overlay/load-mutate root) "demo.config")))))
        (.setLastModified fresh 3000)
        (should= "defn/pool-crap"
                 (:id (first (:forms (get (overlay/load-mutate root) "demo.config")))))
        (finally
          (doseq [f (reverse (file-seq (io/file root)))]
            (io/delete-file f true))))))

  (it "leaves a document alone when there is no snapshot"
    (let [d (ir/normalize {:packages [{:id :p :label "P"
                                       :classes [{:id :a :name "A"}]}]
                           :edges []})
          painted (overlay/apply-metrics d {:crap {} :mutate {}})]
      (should= d painted)))

  (it "stamps metrics files so a rewrite is visible"
    (let [root (.getCanonicalPath (io/file "target" (str "overlay-stamp-" (System/nanoTime))))
          crap-dir (io/file root ".metrics")]
      (.mkdirs crap-dir)
      (try
        (should= [] (overlay/metrics-stamp root))
        (spit (io/file crap-dir "crap.edn") (pr-str {:entries []}))
        (let [a (overlay/metrics-stamp root)]
          (should (seq a))
          (spit (io/file crap-dir "crap.edn")
                (pr-str {:entries [{:name "go" :namespace "demo.x"
                                    :complexity 2 :coverage 10.0 :crap 9.0}]}))
          (should-not= a (overlay/metrics-stamp root)))
        (finally
          (doseq [f (reverse (file-seq (io/file root)))]
            (io/delete-file f true))))))

  (it "joins module and crate snapshots onto the prefixed class ns"
    (let [doc {:hierarchical true
               :prefix "bookwriter"
               :classes [{:id :model :name "Model" :ns "bookwriter.model" :lang :typescript}
                         {:id :pdf :name "Pdf" :ns "bookwriter.pdf" :lang :typescript}
                         {:id :rust :name "Rust" :ns "bookwriter.rust" :lang :rust
                          :file "src-tauri/src/lib.rs"}
                         {:id :rust.main :name "Main" :ns "bookwriter.rust.main" :lang :rust
                          :file "src-tauri/src/main.rs"}]
               :edges []}
          metrics {:crap {"model" [{:name "slugify" :namespace "model"
                                    :complexity 2 :coverage 100.0 :crap 2.0}]
                          "pdf" [{:name "styled" :namespace "pdf"
                                  :complexity 4 :coverage 100.0 :crap 4.0}]
                          "pdf.Layout" [{:name "figure" :namespace "pdf.Layout"
                                         :complexity 8 :coverage 100.0 :crap 8.0}]
                          "bookwriter" [{:name "read_text" :namespace "bookwriter"
                                         :complexity 1 :coverage 100.0 :crap 1.0}
                                        {:name "main" :namespace "bookwriter"
                                         :complexity 1 :coverage 0.0 :crap 2.0}]
                          "bookwriter::build" [{:name "main" :namespace "bookwriter::build"
                                                :complexity 1 :coverage nil :crap nil}]}
                   :mutate {"model" {:namespace "model"
                                     :forms [{:id "defn/slugify" :killed 1 :survived 0
                                              :uncovered 0 :sites 1}]}}}
          painted (overlay/apply-metrics doc metrics)
          by-id (into {} (map (juxt :id identity) (:classes painted)))
          model (:model by-id)
          pdf (:pdf by-id)
          rust (:rust by-id)
          main (:rust.main by-id)]
      (should= 2 (:cc model))
      (should= 1 (:killed model))
      (should= 1 (:sites model))
      (should (some #(= "slugify" (:name %)) (:ops model)))
      (should= 12 (:cc pdf))
      (should (some #(= "figure" (:name %)) (:ops pdf)))
      (should (some #(= "read_text" (:name %)) (:ops rust)))
      (should= 2 (:cc rust))
      (should= 2 (count (:ops rust)))
      (should-not (:crap main))
      (should-not (some #(= "main" (:name %)) (:ops main))))))
