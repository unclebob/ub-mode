(ns uml-viewer.application.detail-spec
  (:require [speclj.core :refer :all]
            [uml-viewer.application.detail :as detail]
            [uml-viewer.engine.compose :as compose]
            [uml-viewer.domain.ir :as ir]))

(defn- call [sym & args]
  (apply (ns-resolve 'uml-viewer.application.detail sym) args))

(describe "detail height"
  (it "is two pads when there are no rows"
    (should= 32 (detail/content-h []))))

(defn scene []
  (compose/compile-diagram
    (ir/normalize
      {:title "Tiny"
       :packages
       [{:id :p :label "Domain"
         :classes [{:id :a :name "A"
                    :ns "demo.a"
                    :coverage 0.9
                    :crap 1.2
                    :ops [{:name "go" :args ["x"] :returns "void"
                           :coverage 0.75
                           :cc 2
                           :crap 1.8
                           :killed 3
                           :survived 1
                           :uncovered 2
                           :sites 6}
                          {:name "hide" :private true :crap 4.0}]}
                   {:id :b :name "B"}]}]
       :edges [{:from :a :to :b :kind :dependency}]})))

(describe "rel-phrase"
  (it "names each edge kind in both directions"
    (doseq [[kind out in]
            [[:inheritance "extends" "extended by"]
             [:implements "implements" "implemented by"]
             [:association "associates with" "associated from"]
             [:dependency "depends on" "used by"]
             [:aggregation "aggregates" "aggregated by"]
             [:composition "composes" "composed in"]]]
      (should= out (call 'rel-phrase kind true))
      (should= in (call 'rel-phrase kind false))))

  (it "falls back to to/from for an unknown kind"
    (should= "to" (call 'rel-phrase :other true))
    (should= "from" (call 'rel-phrase :other false))))

(describe "detail"
  (it "builds a class card with coverage, ops, and relationships"
    (let [s (scene)
          model (detail/model s :a)
          rows (detail/rows model)
          kinds (map :kind rows)
          go (first (filter #(= "+ go(x) : void" (:text %)) rows))
          hide (first (filter #(= "- hide" (:text %)) rows))
          cls (first (filter #(and (= :stats (:kind %)) (= "A" (:text %))) rows))
          rel (first (filter #(= :rel (:kind %)) rows))]
      (should= "A" (get-in model [:class :name]))
      (should= "demo.a" (:ns model))
      (should= 0.9 (get-in model [:class :coverage]))
      (should (some #{:name :module :stats :group-header :col-header :rel} kinds))
      (should= "demo.a" (:text (first (filter :module rows))))
      (let [mod (first (filter :module rows))]
        (should (detail/module-at rows (+ (:y mod) 1)))
        (should-not (detail/module-at rows (+ (:y go) 1))))
      (should= "90%" (:cov-s cls))
      (should= "1.2μ" (:crap-s cls))
      (should-be-nil (:cc-s cls))
      (should= "75%" (:cov-s go))
      (should= "2" (:cc-s go))
      (should= "1.8" (:crap-s go))
      (should hide)
      (should (:private hide))
      (should= "4.0" (:crap-s hide))
      (should= "3" (:killed-s go))
      (should= "1" (:survived-s go))
      (should= "2" (:uncovered-s go))
      (should= "3" (:killed-s cls))
      (should= "1" (:survived-s cls))
      (should= "2" (:uncovered-s cls))
      (should= "---no mutation sites---" (:mut-note hide))
      (should= :b (:id rel))
      (should= :b (detail/rel-at rows (+ (:y rel) 1)))
      (should= "go" (:op-name go))
      (should= "go" (detail/member-at rows (+ (:y go) 1)))
      (should-be-nil (detail/member-at rows (:y cls)))
      (should= "hide" (detail/member-at rows (+ (:y hide) 1)))))

  (it "names the file in the card by the source file, not its directory"
    (let [s (compose/compile-diagram
              (ir/normalize
                {:title "Bookwriter"
                 :packages
                 [{:id :p :label "rust"
                   :classes [{:id :rust :name "lib.rs"
                              :ns "bookwriter.rust"
                              :file "src-tauri/src/lib.rs"}]}]}))
          rows (detail/rows (detail/model s :rust))]
      (should= "lib.rs" (:text (first (filter #(= :name (:kind %)) rows))))
      (should= "lib.rs" (:text (first (filter :module rows))))))

  (it "names every Rust file on the card by its filename"
    (let [s (compose/compile-diagram
              (ir/normalize
                {:packages
                 [{:id :p :label "rust"
                   :classes [{:id :rust.proto :name "proto.rs"
                              :ns "bookwriter.rust.proto"
                              :file "src-tauri/src/proto.rs"}]}]}))
          rows (detail/rows (detail/model s :rust.proto))]
      (should= "proto.rs" (:text (first (filter #(= :name (:kind %)) rows))))
      (should= "proto.rs" (:text (first (filter :module rows))))))

  (it "keeps the namespace on the card when the file stem matches it"
    (let [s (compose/compile-diagram
              (ir/normalize
                {:packages
                 [{:id :p :label "internals"
                   :classes [{:id :internals.model :name "Model"
                              :ns "bookwriter.internals.model"
                              :file "src/internals/model.ts"}]}]}))
          rows (detail/rows (detail/model s :internals.model))]
      (should= "bookwriter.internals.model"
               (:text (first (filter :module rows))))))

  (it "keeps the namespace on the card for a hyphenated Clojure file"
    (let [s (compose/compile-diagram
              (ir/normalize
                {:packages
                 [{:id :p :label "python-language"
                   :classes [{:id :graph-python :name "GraphPython"
                              :ns "uml-viewer.python-language.graph-python"
                              :file "src/uml_viewer/python_language/graph_python.clj"}]}]}))
          rows (detail/rows (detail/model s :graph-python))]
      (should= "GraphPython" (:text (first (filter #(= :name (:kind %)) rows))))
      (should= "uml-viewer.python-language.graph-python"
               (:text (first (filter :module rows))))))

  (it "shows killed and survived when an older snapshot omitted sites"
    (let [s (compose/compile-diagram
              (ir/normalize
                {:packages
                 [{:id :p :label "P"
                   :classes [{:id :w :name "WindData"
                              :killed 5 :survived 1 :uncovered 0 :sites 0
                              :ops [{:name "radius-bounds" :text "radius-bounds"
                                     :killed 5 :survived 0 :uncovered 0 :sites 0}]}]}]
                 :edges []}))
          rows (detail/rows (detail/model s :w))
          cls (first (filter #(and (= :stats (:kind %)) (= "WindData" (:text %))) rows))
          op (first (filter #(= "radius-bounds" (:op-name %)) rows))]
      (should-be-nil (:mut-note cls))
      (should-be-nil (:mut-note op))
      (should= "5" (:killed-s cls))
      (should= "1" (:survived-s cls))
      (should= "5" (:killed-s op))
      (should= "0" (:survived-s op))))

  (it "does not treat untested operators as no mutation sites"
    (let [s (compose/compile-diagram
              (ir/normalize
                {:packages
                 [{:id :p :label "P"
                   :classes [{:id :d :name "D"
                              :ops [{:name "go" :text "go()"
                                     :killed 0 :survived 0 :uncovered 0 :sites 4}]}]}]
                 :edges []}))
          rows (detail/rows (detail/model s :d))
          go (first (filter #(= "go" (:op-name %)) rows))]
      (should-be-nil (:mut-note go))
      (should= "0" (:killed-s go))))

  (it "shows class CRAP as μ, omits CC, and prefixes μ/max/σ with Crap"
    (let [s (compose/compile-diagram
              (ir/normalize
                {:packages
                 [{:id :p :label "P"
                   :classes [{:id :d :name "Detail"
                              :crap {:mu 14.2 :max 134.6 :sigma 34.9}
                              :cc 65}]}]
                 :edges []}))
          rows (detail/rows (detail/model s :d))
          cls (first (filter #(and (= :stats (:kind %)) (= "Detail" (:text %))) rows))
          header (first (filter #(= :crap (:kind %)) rows))]
      (should= "14.2μ" (:crap-s cls))
      (should-be-nil (:cc-s cls))
      (should (re-find #"^Crap μ" (:text header)))
      (should (re-find #"max 134\.6" (:text header)))))

  (it "returns nil for an unknown class"
    (should-be-nil (detail/model (scene) :nope)))

  (it "lays out Crap, CC, Cov, killed, survived, and uncovered columns"
    (let [cols (detail/column-layout)]
      (should= ["Crap" "CC" "Cov" "killed" "survived" "uncovered"] (map :label cols))
      (should (apply < (map :left cols)))))

  (it "spans --crap-- and --mutation-- over their columns"
    (let [cols (detail/column-layout)
          groups (detail/group-layout)
          crap-cols (filter #(= :crap (:group %)) cols)
          mut-cols (filter #(= :mutation (:group %)) cols)]
      (should= ["--crap--" "--mutation--"] (map :label groups))
      (should= (:left (first crap-cols)) (:left (first groups)))
      (should= (:right (last crap-cols)) (:right (first groups)))
      (should= (:left (first mut-cols)) (:left (second groups)))
      (should= (:right (last mut-cols)) (:right (second groups)))
      (should (< (:right (first groups)) (:left (second groups))))))

  (it "does not treat a field row as a relationship hit"
    (let [rows (detail/rows (detail/model (scene) :a))
          name-row (first (filter #(= :name (:kind %)) rows))]
      (should-be-nil (detail/rel-at rows (:y name-row)))))

  (it "lays out level, stereotype, fields, and a labeled relationship"
    (let [s (compose/compile-diagram
              (ir/normalize
                {:title "Card"
                 :packages [{:id :p :label "Domain"
                             :classes [{:id :a :name "A" :ns "demo.a"
                                        :level 2 :stereotype :bean
                                        :fields ["n"]}
                                       {:id :b :name "B"}]}]
                 :edges [{:from :a :to :b :kind :dependency :label "db"}]}))
          texts (map :text (detail/rows (detail/model s :a)))]
      (should-contain "Level 2" texts)
      (should-contain "«bean»" texts)
      (should-contain "n" texts)
      (should-contain "Fields" texts)
      (should-contain "depends on  B  «db»" texts)))

  (it "omits optional sections and falls back to the package id"
    (let [orphan (detail/rows {:class {:name "Solo" :package :orphan}})
          bare (detail/rows {:class {:name "Bare"}})
          kept (detail/rows {:class {:name "A" :package :orphan}
                             :package {:label "Kept"}
                             :title "Card"})]
      (should-be-nil (detail/rows nil))
      (should= ["Solo" "package  orphan"] (map :text orphan))
      (should= ["Bare" "package  "] (map :text bare))
      (should-contain "package  Kept" (map :text kept))
      (should-contain "Card" (map :text kept))
      (should-not (some #{:stats :field :rel :crap :heading} (map :kind orphan)))))

  (it "opens the metrics table only when an operator or a metric is present"
    (should (call 'metrics-row? {:ops [{:name "go"}]}))
    (should (call 'metrics-row? {:crap 1}))
    (should (call 'metrics-row? {:coverage 0.2}))
    (should (call 'metrics-row? {:cc 3}))
    (should (call 'metrics-row? {:killed 0}))
    (should (call 'metrics-row? {:survived 0}))
    (should-not (call 'metrics-row? {}))
    (should-not (call 'metrics-row? {:uncovered 4 :fields [{:text "n"}]}))))

(describe "member-ident"
  (it "includes name, lang, and file only when they are present"
    (should= {:ns "demo.a"} (detail/member-ident {:ns "demo.a"}))
    (should= {:ns "demo.a"} (detail/member-ident {:ns "demo.a"} nil))
    (should= {:ns "demo.a"} (detail/member-ident {:ns "demo.a"} ""))
    (should= {:ns "demo.a" :name "go"} (detail/member-ident {:ns "demo.a"} "go"))
    (should= {:ns "demo.a" :lang :rust}
             (detail/member-ident {:ns "demo.a" :lang :rust} nil))
    (should= {:ns "demo.a" :file "src/lib.rs"}
             (detail/member-ident {:ns "demo.a" :file "src/lib.rs"} nil))
    (should= {:ns "demo.a" :name "go" :lang :rust :file "src/lib.rs"}
             (detail/member-ident {:ns "demo.a" :lang :rust :file "src/lib.rs"} "go"))))
