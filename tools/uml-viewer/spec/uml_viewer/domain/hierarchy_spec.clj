(ns uml-viewer.domain.hierarchy-spec
  (:require [speclj.core :refer :all]
            [uml-viewer.domain.hierarchy :as hierarchy]
            [uml-viewer.domain.policy :as policy]))

(def graph
  {:classes [{:id :ir :name "Ir" :ns "uml-viewer.domain.ir"}
             {:id :source :name "Source" :ns "uml-viewer.source"
              :stereotype :interface}
             {:id :source.clojure :name "SourceClojure"
              :ns "uml-viewer.source.clojure"}
             {:id :layout :name "Layout" :ns "uml-viewer.engine.layout"}
             {:id :quil.core :name "quil.core" :foreign true}
             {:id :javax.swing :name "javax.swing" :foreign true}]
   :edges [{:from :source.clojure :to :source :kind :implements}
           {:from :layout :to :ir :kind :dependency}
           {:from :layout :to :quil.core :kind :dependency}
           {:from :source.clojure :to :javax.swing :kind :dependency}]})

(def policy
  {:title "Demo"
   :hierarchical true
   :foreign [:quil :javax.swing]
   :order [:source :layout :ir]})

(describe "hierarchy proposal"
  (it "groups top-level nses into named packages and marks the view as a proposal"
    (let [p (assoc policy
              :order [:source :layout :ir]
              :proposal [{:id :kernel :label "Kernel" :nses [:ir :source]}
                         {:id :engine :label "Engine" :nses [:layout]}])
          doc (policy/apply-policy p graph)
          view (hierarchy/proposal-view doc)
          pkgs (:packages view)
          ids (fn [pkg] (mapv :id (:classes pkg)))]
      (should (:proposal view))
      (should= policy/proposal-notice (:title view))
      (should= [:proposal.engine :proposal.kernel] (mapv :id pkgs))
      (should= ["Engine" "Kernel"] (mapv :label pkgs))
      (should= [:layout] (ids (first pkgs)))
      (should= [:ir :source] (ids (second pkgs)))
      (should (some #(= :quil (:id %)) (:foreign view)))))

  (it "puts leftover top-level nses in Unassigned"
    (let [p (assoc policy
              :proposal [{:id :engine :label "Engine" :nses [:layout]}])
          doc (policy/apply-policy p graph)
          view (hierarchy/proposal-view doc)
          last-pkg (first (:packages view))]
      (should= :proposal.unassigned (:id last-pkg))
      (should= "Unassigned" (:label last-pkg))
      (should (some #{:ir} (map :id (:classes last-pkg))))))

  (it "gives a component the max level of its elements"
    (let [doc {:hierarchical true
               :classes [{:id :engine :name "Engine" :level 0}
                         {:id :engine.layout :name "Layout" :level 2}
                         {:id :ir :name "Ir" :level 0}]
               :edges []
               :order [:engine :ir]}
          view (hierarchy/view-at doc [])
          box (fn [id]
                (first (filter #(= id (:id %))
                               (mapcat :classes (:packages view)))))]
      (should= 2 (:level (box :engine)))
      (should= 0 (:level (box :ir)))))

  (it "hides policy-omitted nses from the namespace tree"
    (let [doc (assoc (policy/apply-policy policy graph) :omit [:layout])
          view (hierarchy/view-at doc [])
          ids (map :id (mapcat :classes (:packages view)))]
      (should-not (some #{:layout} ids))
      (should (some #{:ir} ids))))

  (it "omits listed nses from Unassigned"
    (let [p (assoc policy
              :proposals [{:id :engine :name "Engine"
                           :omit [:ir]
                           :layers [{:id :engine :label "Engine" :nses [:layout]}]}])
          doc (policy/apply-policy p graph)
          view (hierarchy/proposal-view doc :engine)
          ids (mapcat #(map :id (:classes %)) (:packages view))]
      (should-not (some #{:ir} ids))
      (should (some #{:source} ids))))

  (it "groups nested class ids into a proposal layer"
    (let [p (assoc policy
              :proposals [{:id :split :name "split"
                           :layers [{:id :host :label "JVM host" :nses [:jvm.cli]}
                                    {:id :ui :label "JVM UI" :nses [:jvm.sketch]}]}]
              :order [:jvm :ir])
          g {:classes [{:id :jvm.cli :name "Cli" :ns "demo.jvm.cli"}
                       {:id :jvm.sketch :name "Sketch" :ns "demo.jvm.sketch"}
                       {:id :ir :name "Ir" :ns "demo.ir"}]
             :edges [{:from :jvm.sketch :to :jvm.cli :kind :dependency}]}
          doc (policy/apply-policy p g)
          view (hierarchy/proposal-view doc :split)
          host (first (filter #(= :proposal.host (:id %)) (:packages view)))
          ui (first (filter #(= :proposal.ui (:id %)) (:packages view)))]
      (should= [:jvm.cli] (mapv :id (:classes host)))
      (should= [:jvm.sketch] (mapv :id (:classes ui)))))

  (it "keeps leaf arrows after a nested-id split"
    (let [p (assoc policy
              :proposals [{:id :split :name "split"
                           :layers [{:id :host :label "JVM host" :nses [:jvm.cli]}
                                    {:id :ui :label "JVM UI" :nses [:jvm.sketch]}]}]
              :order [:jvm :ir])
          g {:classes [{:id :jvm.cli :name "Cli" :ns "demo.jvm.cli"}
                       {:id :jvm.sketch :name "Sketch" :ns "demo.jvm.sketch"}
                       {:id :quil.core :name "quil.core" :foreign true}]
             :edges [{:from :jvm.sketch :to :jvm.cli :kind :dependency}
                     {:from :jvm.sketch :to :quil.core :kind :dependency}]}
          view (hierarchy/proposal-view (policy/apply-policy p g) :split)
          ends (set (map (juxt :from :to) (:edges view)))]
      (should (contains? ends [:jvm.sketch :jvm.cli]))
      (should (some #{[:jvm.sketch :quil] [:jvm.sketch :quil.core]} ends))))

  (it "nests a group map as a child component of a proposal layer"
    (let [p (assoc policy
              :proposals [{:id :split :name "split"
                           :layers [{:id :jvm :label "JVM"
                                     :nses [:jvm.cli
                                            {:id :quil-swing
                                             :label "Quil/Swing"
                                             :nses [:jvm.sketch]}]}]}]
              :order [:jvm])
          g {:classes [{:id :jvm.cli :name "Cli" :ns "demo.jvm.cli"}
                       {:id :jvm.sketch :name "Sketch" :ns "demo.jvm.sketch"}
                       {:id :quil.core :name "quil.core" :foreign true}]
             :edges [{:from :jvm.sketch :to :jvm.cli :kind :dependency}
                     {:from :jvm.sketch :to :quil.core :kind :dependency}]}
          view (hierarchy/proposal-view (policy/apply-policy p g) :split)
          jvm (first (filter #(= :proposal.jvm (:id %)) (:packages view)))
          ids (mapv :id (:classes jvm))
          quil (first (filter #(= :quil-swing (:id %)) (:classes jvm)))
          ends (set (map (juxt :from :to) (:edges view)))]
      (should= [:jvm.cli :quil-swing] ids)
      (should-not (some #(= :proposal.quil-swing (:id %)) (:packages view)))
      (should= "Quil/Swing" (:name quil))
      (should= [:jvm.sketch] (mapv :id (:contents quil)))
      (should (contains? ends [:quil-swing :jvm.cli]))
      (should (some #{[:quil-swing :quil] [:quil-swing :quil.core]} ends))))

  (it "drills a nested group into its member classes"
    (let [p (assoc policy
              :proposals [{:id :split :name "split"
                           :layers [{:id :jvm :label "JVM"
                                     :nses [:jvm.cli
                                            {:id :quil-swing
                                             :label "Quil/Swing"
                                             :nses [:jvm.sketch]}]}]}]
              :order [:jvm])
          g {:classes [{:id :jvm.cli :name "Cli" :ns "demo.jvm.cli"}
                       {:id :jvm.sketch :name "Sketch" :ns "demo.jvm.sketch"}
                       {:id :quil.core :name "quil.core" :foreign true}]
             :edges [{:from :jvm.sketch :to :jvm.cli :kind :dependency}
                     {:from :jvm.sketch :to :quil.core :kind :dependency}]}
          doc (policy/apply-policy p g)
          view (hierarchy/layer-view doc :split :quil-swing)
          pkg (first (:packages view))
          ends (set (map (juxt :from :to) (:edges view)))]
      (should= :quil-swing (:id pkg))
      (should= [:jvm.sketch] (mapv :id (:classes pkg)))
      (should-not (some #{:jvm.cli :quil-swing} (map :id (:classes pkg))))
      (should (some #{[:jvm.sketch :quil] [:jvm.sketch :quil.core]} ends))))

  (it "names a nested proposal group from its label"
    (let [p (assoc policy
              :proposals [{:id :split :name "split"
                           :layers [{:id :jvm :label "JVM"
                                     :nses [{:id :outer :label "Outer"
                                             :nses [{:id :quil-swing
                                                     :label "Quil/Swing"
                                                     :nses [:jvm.sketch]}]}]}]}]
              :order [:jvm])
          g {:classes [{:id :jvm.sketch :name "Sketch" :ns "demo.jvm.sketch"}]}
          doc (policy/apply-policy p g)
          view (hierarchy/proposal-view doc :split)
          outer (first (filter #(= :outer (:id %))
                               (mapcat :classes (:packages view))))]
      (should= "Quil/Swing" (:name (first (:contents outer))))))

  (it "restamps from the first proposal when none is chosen"
    (let [restamp (ns-resolve 'uml-viewer.domain.hierarchy 'restamp-visible-edges)
          doc {:proposals [{:id :first :name "first"
                            :layers [{:id :a :nses [:left]}
                                     {:id :b :nses [:right]}]}
                           {:id :second :name "second"
                            :layers [{:id :b :nses [:right]}
                                     {:id :a :nses [:left]}]}]
               :edges [{:from :left :to :right :kind :dependency}]}
          edges (restamp doc nil {:foreign []} [{:id :left} {:id :right}])]
      (should (some :violating edges))))

  (it "does not wrap a top-level layer in a same-named inner component"
    (let [p (assoc policy
              :proposals [{:id :layers :name "layers"
                           :layers [{:id :game-api :label "Game API"
                                     :nses [:game-api]}]}]
              :order [:game-api])
          g {:classes [{:id :game-api.core :name "Core"
                        :ns "demo.game-api.core"}]}
          view (hierarchy/proposal-view (policy/apply-policy p g) :layers)
          pkg (first (filter #(= :proposal.game-api (:id %))
                             (:packages view)))
          ids (mapv :id (:classes pkg))]
      (should= [:game-api.core] ids)
      (should-not (some #{:game-api} ids))))

  (it "collapses arrows between proposal packages to one per direction"
    (let [p (assoc policy
              :proposal [{:id :kernel :label "Kernel" :nses [:ir :source]}
                         {:id :engine :label "Engine" :nses [:layout]}])
          doc (policy/apply-policy p
                                   (update graph :edges conj
                                           {:from :ir :to :layout :kind :dependency}
                                           {:from :source :to :layout :kind :association}))
          view (hierarchy/apply-declutter (hierarchy/proposal-view doc) :arrows)
          edges (:edges view)
          e (first (filter #(and (= :proposal.kernel (:from %))
                                 (= :proposal.engine (:to %)))
                           edges))]
      (should e)
      (should (contains? (:via-ids e) :ir))
      (should (contains? (:via-ids e) :source))
      (should (some #(and (= :ir (:from %)) (= :layout (:to %))) (:deps e)))
      (should (some #(and (= :source (:from %)) (= :layout (:to %))) (:deps e))))))

  (it "hides arrows in remove-arrows declutter"
    (let [doc (policy/apply-policy policy graph)
          view (hierarchy/apply-declutter (hierarchy/view-at doc []) :triangles)]
      (should (:hide-edges view))
      (should (seq (:edges view)))))

  (it "hides nested names, members, and ports then classes as declutter progresses"
    (let [doc (policy/apply-policy policy graph)
          elements (hierarchy/apply-declutter (hierarchy/view-at doc []) :elements)
          classes (hierarchy/apply-declutter (hierarchy/view-at doc []) :classes)
          box (fn [v] (first (:classes (first (:packages v)))))]
      (should (:hide-members (box elements)))
      (should-not (seq (:contents (box elements))))
      (should-be-nil (:in-deps (box elements)))
      (should-be-nil (:out-deps (box elements)))
      (should (:hide-members (box classes)))
      (should-not (seq (:contents (box classes))))))

  (it "keeps rolled CRAP and mutation on a collapsed proposal layer"
    (let [g (assoc graph :classes
                   (mapv #(cond
                            (= :ir (:id %)) (assoc % :crap {:mu 3.0 :max 3.0 :sigma 0} :killed 2 :survived 0)
                            :else %)
                         (:classes graph)))
          p (assoc policy
              :proposal [{:id :kernel :label "Kernel" :nses [:ir :source]}
                         {:id :engine :label "Engine" :nses [:layout]}])
          doc (policy/apply-policy p g)
          view (hierarchy/apply-declutter (hierarchy/proposal-view doc) :classes)
          kernel (first (filter #(= :proposal.kernel (:id %)) (:packages view)))
          dummy (first (:classes kernel))]
      (should (:dummy? dummy))
      (should (:crap dummy))
      (should= 2 (:killed dummy))))

  (it "re-evaluates violating arrows from the proposal's layer order"
    (let [p (assoc policy
              :levels [[:ir] [:layout]]
              :proposals [{:id :rev :name "reversed"
                           :layers [{:id :engine :label "Engine" :nses [:layout]}
                                    {:id :kernel :label "Kernel" :nses [:ir]}]}]
              :order [:source :layout :ir])
          g (update graph :edges conj
                    {:from :ir :to :layout :kind :dependency}
                    {:from :layout :to :ir :kind :dependency})
          doc (policy/apply-policy p g)
          as-is (hierarchy/view-at doc [])
          view (hierarchy/proposal-view doc :rev)
          box (fn [v id]
                (first (filter #(= id (:id %))
                               (mapcat :classes (:packages v)))))
          edge (fn [v from to]
                 (first (filter #(and (= from (:from %)) (= to (:to %)))
                                (:edges v))))]
      (should (:violating (edge as-is :ir :layout)))
      (should-not (:violating (edge as-is :layout :ir)))
      (should-not (:violating (edge view :ir :layout)))
      (should (:violating (edge view :layout :ir)))
      (should= 1 (:level (box view :ir)))
      (should= 0 (:level (box view :layout)))))

(describe "hierarchy"
  (it "collapses a violating leaf dependency onto the parent segments"
    (let [g (update graph :edges conj {:from :ir :to :layout :kind :dependency})
          p (assoc policy :levels [[:ir] [:layout]])
          doc (policy/apply-policy p g)
          view (hierarchy/view-at doc [])
          e (first (filter #(and (= :ir (:from %)) (= :layout (:to %)))
                           (:edges view)))]
      (should (:violating (first (filter #(and (= :ir (:from %)) (= :layout (:to %)))
                                         (:edges doc)))))
      (should (:violating e))))

  (it "collapses leaf edges onto the first namespace segment"
    (let [doc (policy/apply-policy policy graph)
          view (hierarchy/view-at doc [])
          ids (set (map :id (mapcat :classes (:packages view))))
          edges (set (map (juxt :from :to :kind) (:edges view)))]
      (should (:hierarchical doc))
      (should= #{:source :layout :ir} ids)
      (should (contains? edges [:layout :ir :dependency]))
      (should (contains? edges [:layout :quil :dependency]))
      (should (contains? edges [:source :javax.swing :dependency]))
      (should= #{:quil :javax.swing} (set (map :id (:foreign view))))
      (should-be-nil (:out-deps (first (filter #(= :layout (:id %))
                                              (mapcat :classes (:packages view))))))
      (should-be-nil (:out-deps (first (filter #(= :source (:id %))
                                              (mapcat :classes (:packages view))))))
      (should-not (some #(= :source.clojure (% 0)) edges))
      (should-not (some #(and (= :source (first %)) (= :source (second %)))
                        edges))))

  (it "lists nested namespaces as clickable contents of a layer"
    (let [doc (policy/apply-policy policy graph)
          view (hierarchy/view-at doc [])
          source (first (filter #(= :source (:id %))
                                (mapcat :classes (:packages view))))]
      (should (:drill? source))
      (should= #{:source :source.clojure} (set (map :id (:contents source))))
      (should-not (:drill? (first (filter #(= :source (:id %)) (:contents source)))))
      (should (:hide-members source))))

  (it "drills into a namespace and shows the next level"
    (let [doc (policy/apply-policy policy graph)
          view (hierarchy/view-at doc [:source])
          ids (set (map :id (mapcat :classes (:packages view))))
          edges (set (map (juxt :from :to :kind) (:edges view)))]
      (should= #{:source :source.clojure} ids)
      (should (contains? edges [:source.clojure :source :implements]))
      (should-not (contains? ids :layout))))

  (it "keeps Source as an interface on the module box"
    (let [doc (policy/apply-policy policy graph)
          view (hierarchy/view-at doc [:source])
          source (first (filter #(= :source (:id %))
                                (mapcat :classes (:packages view))))]
      (should= :interface (:stereotype source))))

  (it "shows foreign deps as ovals with arrows at the nested level that requires them"
    (let [doc (policy/apply-policy policy graph)
          view (hierarchy/view-at doc [:source])
          edges (set (map (juxt :from :to :kind) (:edges view)))
          impl (first (filter #(= :source.clojure (:id %))
                              (mapcat :classes (:packages view))))]
      (should (contains? edges [:source.clojure :source :implements]))
      (should (contains? edges [:source.clojure :javax.swing :dependency]))
      (should= #{:javax.swing} (set (map :id (:foreign view))))
      (should-be-nil (:out-deps impl))
      (should-not (some #(= :quil (% 1)) edges))))

  (it "rolls a parent layer's CRAP by function count"
    (let [g (update graph :classes
                    (fn [cs]
                      (mapv (fn [c]
                              (case (:id c)
                                :layout (assoc c :crap {:mu 2.0 :max 3.0 :sigma 1.0})
                                :source.clojure (assoc c :crap {:mu 8.0 :max 9.0 :sigma 2.0})
                                :source (assoc c :crap {:mu 1.0 :max 1.0 :sigma 0.0})
                                :ir (assoc c :crap {:mu 1.0 :max 1.0 :sigma 0.0})
                                c))
                            cs)))
          doc (policy/apply-policy policy g)
          view (hierarchy/view-at doc [])
          source (first (filter #(= :source (:id %))
                                (mapcat :classes (:packages view))))
          layout (first (filter #(= :layout (:id %))
                                (mapcat :classes (:packages view))))]
      (should= {:mu 4.5 :max 9.0 :sigma 3.8 :n 2} (:crap source))
      (should= {:mu 2.0 :max 3.0 :sigma 1.0} (:crap layout))))

  (it "weights a directory's CRAP by function count, not by module count"
    (let [g {:classes [{:id :engine :ns "demo.engine"
                        :ops [{:crap 2} {:crap 2} {:crap 2}]}
                       {:id :engine.layout :ns "demo.engine.layout"
                        :ops [{:crap 20}]}]
             :edges []}
          doc (policy/apply-policy {:hierarchical true} g)
          view (hierarchy/view-at doc [])
          engine (first (filter #(= :engine (:id %))
                                (mapcat :classes (:packages view))))]
      (should= {:mu 6.5 :max 20.0 :sigma 7.8 :n 4} (:crap engine))))

  (it "sums a parent layer's mutants and keeps an unscored sibling as a gap"
    (let [g (update graph :classes
                    (fn [cs]
                      (mapv (fn [c]
                              (case (:id c)
                                :layout (assoc c :killed 9 :survived 1)
                                :source.clojure (assoc c :killed 1 :survived 1)
                                :source (assoc c :killed 8 :survived 0)
                                :ir (assoc c :killed 4 :survived 0)
                                c))
                            cs)))
          doc (policy/apply-policy policy g)
          view (hierarchy/view-at doc [])
          source (first (filter #(= :source (:id %))
                                (mapcat :classes (:packages view))))
          layout (first (filter #(= :layout (:id %))
                                (mapcat :classes (:packages view))))]
      (should= 9 (:killed source))
      (should= 1 (:survived source))
      (should-be-nil (:mut-gap source))
      (should= 9 (:killed layout))
      (should= 1 (:survived layout))))

  (it "counts an unscored sibling as one failed trial per function"
    (let [g (update graph :classes
                    (fn [cs]
                      (mapv (fn [c]
                              (case (:id c)
                                :layout (assoc c :killed 9 :survived 1)
                                :source.clojure (assoc c :ops [{:name "a"} {:name "b"}])
                                :source (assoc c :killed 8 :survived 0)
                                c))
                            cs)))
          doc (policy/apply-policy policy g)
          view (hierarchy/view-at doc [])
          source (first (filter #(= :source (:id %))
                                (mapcat :classes (:packages view))))
          layout (first (filter #(= :layout (:id %))
                                (mapcat :classes (:packages view))))]
      (should= 8 (:killed source))
      (should= 0 (:survived source))
      (should= 2 (:mut-gap source))
      (should= 9 (:killed layout))
      (should= 1 (:survived layout))))

  (it "keeps a source file's own CRAP when a nested file is worse"
    (let [g (update graph :classes
                    (fn [cs]
                      (mapv (fn [c]
                              (case (:id c)
                                :source.clojure (assoc c :crap {:mu 8.0 :max 9.0 :sigma 2.0})
                                :source (assoc c :crap {:mu 1.0 :max 1.0 :sigma 0.0})
                                c))
                            cs)))
          doc (policy/apply-policy policy g)
          inner (hierarchy/view-at doc [:source])
          source (first (filter #(= :source (:id %))
                                (mapcat :classes (:packages inner))))
          child (first (filter #(= :source.clojure (:id %))
                               (mapcat :classes (:packages inner))))]
      (should= {:mu 1.0 :max 1.0 :sigma 0.0} (:crap source))
      (should= {:mu 8.0 :max 9.0 :sigma 2.0} (:crap child))))

  (it "keeps a source file's own CRAP when a nested file has none"
    (let [g (update graph :classes
                    (fn [cs]
                      (mapv (fn [c]
                              (if (= :source (:id c))
                                (assoc c :crap {:mu 1.0 :max 1.0 :sigma 0.0})
                                c))
                            cs)))
          doc (policy/apply-policy policy g)
          inner (hierarchy/view-at doc [:source])
          root (hierarchy/view-at doc [])
          source (first (filter #(= :source (:id %))
                                (mapcat :classes (:packages inner))))
          directory (first (filter #(= :source (:id %))
                                   (mapcat :classes (:packages root))))]
      (should= {:mu 1.0 :max 1.0 :sigma 0.0} (:crap source))
      (should= {:mu 10.5 :max 20.0 :sigma 9.5 :n 2} (:crap directory))))

  (it "keeps a source file's own mutants when a nested file is worse"
    (let [g (update graph :classes
                    (fn [cs]
                      (mapv (fn [c]
                              (case (:id c)
                                :source.clojure (assoc c :killed 1 :survived 1)
                                :source (assoc c :killed 8 :survived 0)
                                c))
                            cs)))
          doc (policy/apply-policy policy g)
          inner (hierarchy/view-at doc [:source])
          source (first (filter #(= :source (:id %))
                                (mapcat :classes (:packages inner))))]
      (should= 8 (:killed source))
      (should= 0 (:survived source))))

  (it "names every Rust file by its filename and the directory by the namespace"
    (let [g {:classes [{:id :rust :name "Rust"
                        :ns "bookwriter.rust"
                        :file "src-tauri/src/lib.rs"}
                       {:id :rust.main :name "Main"
                        :ns "bookwriter.rust.main"
                        :file "src-tauri/src/main.rs"}
                       {:id :rust.proto :name "Proto"
                        :ns "bookwriter.rust.proto"
                        :file "src-tauri/src/proto.rs"}]
             :edges []}
          doc (policy/apply-policy {:title "Bookwriter" :hierarchical true} g)
          root (hierarchy/view-at doc [])
          inner (hierarchy/view-at doc [:rust])
          directory (first (filter #(= :rust (:id %))
                                   (mapcat :classes (:packages root))))
          named (fn [id classes]
                  (:name (first (filter #(= id (:id %)) classes))))]
      (should= "Rust" (:name directory))
      (should= "lib.rs" (named :rust (mapcat :classes (:packages inner))))
      (should= "main.rs" (named :rust.main (mapcat :classes (:packages inner))))
      (should= "proto.rs" (named :rust.proto (mapcat :classes (:packages inner))))
      (should= "lib.rs" (named :rust (:contents directory)))
      (should= "main.rs" (named :rust.main (:contents directory)))
      (should= "proto.rs" (named :rust.proto (:contents directory)))))

  (it "keeps a hyphenated Clojure filename as the namespace segment"
    (let [g {:classes [{:id :graph-python
                        :name "GraphPython"
                        :ns "uml-viewer.python-language.graph-python"
                        :file "src/uml_viewer/python_language/graph_python.clj"}]
             :edges []}
          doc (policy/apply-policy {:hierarchical true} g)
          view (hierarchy/view-at doc [])
          c (first (filter #(= :graph-python (:id %))
                           (mapcat :classes (:packages view))))]
      (should= "GraphPython" (:name c))))

  (it "tints a parent with a child that has no CRAP, once per function"
    (let [g (update graph :classes
                    (fn [cs]
                      (mapv (fn [c]
                              (case (:id c)
                                :layout (assoc c :crap {:mu 2.0 :max 3.0 :sigma 1.0})
                                :source (assoc c :crap {:mu 1.0 :max 1.0 :sigma 0.0})
                                c))
                            cs)))
          doc (policy/apply-policy policy g)
          view (hierarchy/view-at doc [])
          source (first (filter #(= :source (:id %))
                                (mapcat :classes (:packages view))))
          layout (first (filter #(= :layout (:id %))
                                (mapcat :classes (:packages view))))]
      (should= {:mu 10.5 :max 20.0 :sigma 9.5 :n 2} (:crap source))
      (should= {:mu 2.0 :max 3.0 :sigma 1.0} (:crap layout))))

  (it "titles modules with the last ns segment, not the component prefix"
    (let [g {:classes [{:id :engine.layout :name "EngineLayout"
                        :ns "demo.engine.layout"}
                       {:id :engine.route :name "EngineRoute"
                        :ns "demo.engine.route"}]
             :edges []}
          pol {:title "Demo" :hierarchical true :order [:engine]}
          doc (policy/apply-policy pol g)
          root (hierarchy/view-at doc [])
          inner (hierarchy/view-at doc [:engine])
          engine (first (filter #(= :engine (:id %))
                                (mapcat :classes (:packages root))))
          layout (first (filter #(= :engine.layout (:id %))
                                (mapcat :classes (:packages inner))))]
      (should= "Engine" (:name engine))
      (should= "Layout" (:name layout))
      (should= "Layout" (:name (first (filter #(= :engine.layout (:id %))
                                              (:contents engine)))))))

  (it "keeps a capital already present in a module name"
    (let [g {:classes [{:id :tauriFs :name "ignored" :ns "bookwriter.tauriFs"}]
             :edges []}
          doc (policy/apply-policy {:title "Demo" :hierarchical true} g)
          box (first (mapcat :classes (:packages (hierarchy/view-at doc []))))]
      (should= "TauriFs" (:name box))))

  (it "keeps arrows between classes in the same view"
    (let [g {:classes [{:id :engine :name "Engine" :ns "demo.engine"}
                       {:id :engine.layout :name "Layout" :ns "demo.engine.layout"}
                       {:id :engine.route :name "Route" :ns "demo.engine.route"}
                       {:id :domain :name "Domain" :ns "demo.domain"}
                       {:id :domain.ir :name "Ir" :ns "demo.domain.ir"}]
             :edges [{:from :engine.layout :to :engine.route :kind :dependency}
                     {:from :engine.layout :to :domain.ir :kind :dependency}]}
          pol {:title "Demo" :hierarchical true :order [:engine :domain]}
          doc (policy/apply-policy pol g)
          root (hierarchy/view-at doc [])
          inner (hierarchy/view-at doc [:engine])
          layout (first (filter #(= :engine.layout (:id %))
                                (mapcat :classes (:packages inner))))
          root-edges (set (map (juxt :from :to) (:edges root)))
          inner-edges (set (map (juxt :from :to) (:edges inner)))]
      (should (contains? root-edges [:engine :domain]))
      (should (contains? inner-edges [:engine.layout :engine.route]))
      (should= [{:id :domain :name "Domain"}] (:out-deps layout))
      (should-not (contains? inner-edges [:engine.layout :domain])))))

