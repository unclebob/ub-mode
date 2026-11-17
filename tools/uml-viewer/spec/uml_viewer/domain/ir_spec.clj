(ns uml-viewer.domain.ir-spec
  (:require [speclj.core :refer :all]
            [uml-viewer.domain.ir :as ir]))

(def tiny
  {:title "Tiny"
   :packages
   [{:id :dom
     :label "Domain"
     :crap 1.2
     :classes
     [{:id :a :name "A" :crap {:mu 1.0 :max 2 :sigma 0.5}
       :ops [{:name "go" :returns "void"}]}
      {:name "B"}]}]
   :edges
   [{:from :a :to :b :kind :association}]})

(describe "IR"
  (it "keywordizes ids and fills omitted class ids from the name"
    (let [d (ir/normalize tiny)]
      (should= :a (get-in d [:packages 0 :classes 0 :id]))
      (should= :b (get-in d [:packages 0 :classes 1 :id]))
      (should= 1.2 (get-in d [:packages 0 :crap :mu]))))

  (it "rejects edges to unknown classes"
    (should-throw
      (ir/normalize (assoc tiny :edges [{:from :a :to :nope}]))))

  (it "keeps :violating on a dependency edge"
    (let [d (ir/normalize
              (assoc tiny :edges [{:from :a :to :b :kind :dependency
                                   :violating true}]))]
      (should (:violating (first (:edges d))))))

  (it "keeps top-level foreign ovals and allows edges to them"
    (let [d (ir/normalize
              {:packages
               [{:id :p :label "P"
                 :classes [{:id :a :name "A"}]}]
               :foreign [{:id :quil :name "quil" :shape :oval}]
               :edges [{:from :a :to :quil :kind :dependency}]})]
      (should= :oval (get-in d [:foreign 0 :shape]))
      (should= :quil (get-in d [:foreign 0 :id]))
      (should= :p (:package (get (ir/class-index d) :a)))
      (should-be-nil (:package (get (ir/class-index d) :quil)))))

  (it "rejects duplicate class ids"
    (should-throw
      (ir/normalize (assoc-in tiny [:packages 0 :classes 1 :id] :a))))

  (it "loads the sample library diagram"
    (let [d (ir/load-diagram "examples/library.edn")]
      (should= "Lending library" (:title d))
      (should= 3 (count (:packages d)))
      (should (seq (:edges d)))))

  (it "keeps class coverage and op coverage plus mutant counts"
    (let [d (ir/normalize
              {:packages
               [{:id :p :label "P"
                 :classes [{:id :a :name "A"
                            :coverage 0.87
                            :cc 4
                            :ops [{:name "go" :returns "void"
                                   :coverage 0.5
                                   :cc 3
                                   :crap 2.1
                                   :killed 4
                                   :survived 1}]}]}]
               :edges []})
          op (get-in d [:packages 0 :classes 0 :ops 0])]
      (should= 0.87 (get-in d [:packages 0 :classes 0 :coverage]))
      (should= 4 (get-in d [:packages 0 :classes 0 :cc]))
      (should= 0.5 (:coverage op))
      (should= 3 (:cc op))
      (should= 2.1 (get-in op [:crap :mu]))
      (should= 4 (:killed op))
      (should= 1 (:survived op))))

  (it "keeps a fractional complexity"
    (let [d (ir/normalize
              {:packages [{:id :p :classes [{:id :a :name "A" :cc 2.5}]}]
               :edges []})]
      (should= 2.5 (get-in d [:packages 0 :classes 0 :cc]))))

  (it "rejects a negative complexity"
    (should-throw
      (ir/normalize
        {:packages [{:id :p :classes [{:id :a :name "A" :cc -1}]}]
         :edges []})))

  (it "keeps class :ns so overlay can key any project's snapshots"
    (let [d (ir/normalize
              {:packages
               [{:id :p :label "P"
                 :classes [{:id :board :name "Board" :ns "demo.board"}]}]
               :edges []})]
      (should= "demo.board" (get-in d [:packages 0 :classes 0 :ns]))))

  (it "keeps :private on an op"
    (let [d (ir/normalize
              {:packages
               [{:id :p :label "P"
                 :classes [{:id :a :name "A"
                            :ops [{:name "hide" :private true}
                                  {:name "show"}]}]}]
               :edges []})
          ops (get-in d [:packages 0 :classes 0 :ops])]
      (should (:private (first ops)))
      (should-not (:private (second ops)))))

  (it "rejects coverage outside 0–1 and non-integer mutant counts"
    (should-throw
      (ir/normalize {:packages [{:id :p :label "P"
                                 :classes [{:id :a :name "A" :coverage 1.2}]}]
                     :edges []}))
    (should-throw
      (ir/normalize {:packages [{:id :p :label "P"
                                 :classes [{:id :a :name "A"
                                            :ops [{:name "go" :killed -1}]}]}]
                     :edges []})))

  (it "builds member text from name, args, type, and returns"
    (let [d (ir/normalize
              {:packages
               [{:id :p :label "P"
                 :classes [{:id :a :name "A"
                            :fields [{:name "n" :type "int"}]
                            :ops [{:name "go" :args ["x"] :returns "void"}]}]}]
               :edges []})
          c (get-in d [:packages 0 :classes 0])]
      (should= "n : int" (get-in c [:fields 0 :text]))
      (should= "go(x) : void" (get-in c [:ops 0 :text]))))

  (it "accepts symbol ids and :sd as sigma"
    (let [d (ir/normalize
              {:packages
               [{:id 'dom :label "D"
                 :crap {:mu 1 :sd 0.2 :n 4}
                 :classes [{:id 'a :name "A"}]}]
               :edges []})]
      (should= :dom (get-in d [:packages 0 :id]))
      (should= 0.2 (get-in d [:packages 0 :crap :sigma]))
      (should= 4 (get-in d [:packages 0 :crap :n]))))

  (it "defaults title and direction"
    (let [d (ir/normalize
              {:packages [{:id :p :label "P" :classes [{:id :a :name "A"}]}]
               :edges []})]
      (should= "UML" (:title d))
      (should= :tb (:direction d))))

  (it "rejects malformed ids, crap, members, classes, packages, and edges"
    (should-throw (ir/normalize {:packages [{:id 1 :label "P" :classes [{:id :a :name "A"}]}]}))
    (should-throw (ir/normalize {:packages [{:id :p :label "P" :crap "bad"
                                            :classes [{:id :a :name "A"}]}]}))
    (should-throw (ir/normalize {:packages [{:id :p :label "P"
                                            :classes [{:id :a :name "A" :fields [1]}]}]}))
    (should-throw (ir/normalize {:packages [{:id :p :label "P" :classes [{}]}]}))
    (should-throw (ir/normalize {:packages [{}]}))
    (should-throw (ir/normalize {:packages [{:id :p :label "P" :classes [{:id :a :name "A"}]}]
                                :edges [{}]})))

  (it "reads a diagram from an EDN string"
    (should= "Tiny" (:title (ir/read-diagram "{:title \"Tiny\" :packages [{:id :p :label \"P\" :classes [{:id :a :name \"A\"}]}] :edges []}"))))

  (it "loads a document of stacked diagrams"
    (let [f (java.io.File/createTempFile "uml" ".edn")]
      (spit f (str "{:title \"Doc\" :diagrams ["
                   "{:title \"One\" :packages [{:id :p :label \"P\" :classes [{:id :a :name \"A\"}]}] :edges []}"
                   "{:title \"Two\" :packages [{:id :p :label \"P\" :classes [{:id :a :name \"A\"}]}] :edges []}"
                   "]}"))
      (let [doc (ir/load-document (.getPath f))]
        (should= "Doc" (:title doc))
        (should= ["One" "Two"] (map :title (:diagrams doc)))))))
