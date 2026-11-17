(ns uml-viewer.application.document-spec
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [speclj.core :refer :all]
            [uml-viewer.engine.compose :as compose]
            [uml-viewer.application.document :as document]
            [uml-viewer.domain.geom :as geom]
            [uml-viewer.domain.ir :as ir]
            [uml-viewer.engine.layout :as layout]
            [uml-viewer.domain.log :as log]
            [uml-viewer.domain.mailbox :as mailbox]))

(defn state []
  {:scene document/empty-scene
   :selected nil
   :hover nil
   :cam-x 0
   :cam-y 0
   :path "examples/library.edn"
   :mtime 0})

(describe "document"
  (around [it]
    (let [f (str (System/getProperty "java.io.tmpdir")
                 "/uv-doc-log-" (System/nanoTime) ".txt")]
      (try
        (binding [log/*log-file* f]
          (it))
        (finally
          (io/delete-file f true)))))

  (it "bakes edge strokes so the draw loop does not recompute splines"
    (let [scene (compose/compile-diagram
                  (ir/normalize
                    {:packages [{:id :p :label "P"
                                 :classes [{:id :a :name "A"}
                                           {:id :b :name "B"}]}]
                     :edges [{:from :a :to :b :kind :dependency}]}))
          e (first (:edges scene))]
      (should (seq (:strokes e)))
      (should (:tip e))
      (should (:draw-bounds e))))

  (it "loads a document from disk"
    (let [s (document/load-path "examples/library.edn")]
      (should (seq (:classes (:scene s))))
      (should (pos? (:mtime s)))))

  (it "restores pan, zoom, and declutter after restart"
    (let [root (doto (io/file "target" (str "restart-" (System/nanoTime)))
                 (.mkdirs))
          metrics (io/file root ".metrics")
          edn (io/file root "d.edn")]
      (.mkdirs metrics)
      (spit edn (slurp "examples/library.edn"))
      (mailbox/write-session! root {:cam-x 40 :cam-y 20 :zoom 1.1
                                    :declutter :arrows :focus []})
      (let [s (document/restart-state (.getPath edn))]
        (should= 40 (:cam-x s))
        (should= 20 (:cam-y s))
        (should= 1.1 (:zoom s))
        (should= :arrows (:declutter s))
        (should-not (:waiting s))
        (should (seq (:classes (:scene s)))))))

  (it "compiles named proposal packages at the root and the ns tree otherwise"
    (let [doc {:hierarchical true
               :title "Demo"
               :proposal {:notice "PROPOSAL — not instantiated in code"
                          :layers [{:id :kernel :label "Kernel" :nses [:domain]}]}
               :classes [{:id :domain :name "Domain" :ns "demo.domain"}
                         {:id :engine :name "Engine" :ns "demo.engine"}]
               :edges []
               :order [:domain :engine]}
          proposed (document/compile-view doc "target" [] true)
          tree (document/compile-view doc "target" [] false)
          drilled (document/compile-view doc "target" [:domain] true)]
      (should (get-in proposed [:diagram :proposal]))
      (should= "PROPOSAL — not instantiated in code"
               (get-in proposed [:diagram :title]))
      (should (some #(= "Kernel" (:label %)) (:packages proposed)))
      (should-not (get-in tree [:diagram :proposal]))
      (should-not (get-in drilled [:diagram :proposal]))))

  (it "routes every declutter mode on the ns tree and a proposal without throwing"
    (let [doc {:hierarchical true
               :title "Demo"
               :proposal {:layers [{:id :kernel :label "Kernel" :nses [:domain]}
                                   {:id :shell :label "Shell" :nses [:engine]}]}
               :classes [{:id :domain :name "Domain" :ns "demo.domain"
                          :crap {:mu 1 :max 1 :sigma 0} :killed 2 :survived 0}
                         {:id :engine :name "Engine" :ns "demo.engine"}]
               :edges [{:from :engine :to :domain :kind :dependency}
                       {:from :domain :to :missing :kind :dependency}]
               :order [:domain :engine]
               :levels [[:domain] [:engine]]}]
      (doseq [mode [:full :arrows :triangles :elements :classes]]
        (let [tree (document/compile-view doc "target" [] {:declutter mode})
              prop (document/compile-view doc "target" [] {:declutter mode
                                                          :proposal true})]
          (should (map? tree))
          (should (map? prop))
          (should (every? #(number? (get-in % [:rect :x])) (:classes tree)))
          (should (every? #(number? (get-in % [:rect :x])) (:classes prop)))))))

  (it "hides nested names, members, and ports when declutter is :elements"
    (let [doc {:hierarchical true
               :title "Demo"
               :classes [{:id :domain :name "Domain" :ns "demo.domain"
                          :ops [{:name "go" :text "go()"}]}]
               :edges []
               :order [:domain]}
          scene (document/compile-view doc "target" [] {:declutter :elements})
          c (first (filter #(= :domain (:id %)) (:classes scene)))]
      (should-not (some #(= :op (:kind %)) (:lines c)))
      (should-not (some #(= :child (:kind %)) (:lines c)))
      (should-not (seq (:in-ports c)))
      (should-not (seq (:out-ports c)))))

  (it "starts waiting without loading the EDN"
    (let [root (io/file "target" (str "wait-" (System/nanoTime)))]
      (.mkdirs root)
      (try
        (let [s (document/waiting-state "examples/library.edn" (.getPath root))]
          (should (:waiting s))
          (should= "examples/library.edn" (:path s))
          (should= [] (get-in s [:scene :classes]))
          (should-be-nil (:error s))
          (should= 0 (:mail-seen s))
          (should= document/waiting-message "Waiting for agent to create diagram."))
        (finally
          (.delete root)))))

  (it "ignores mailbox already present when waiting starts"
    (let [root (io/file "target" (str "wait-mail-" (System/nanoTime)))
          edn (io/file root "diagram.edn")]
      (.mkdirs (io/file root ".metrics"))
      (.mkdirs (io/file root ".uml-viewer"))
      (spit edn (pr-str {:packages [] :edges []}))
      (try
        (let [path (.getPath edn)
              cmd (mailbox/write-command! (mailbox/to-viewer root) :display
                                          {:path path})
              s (document/waiting-state path)]
          (should= (:id cmd) (:mail-seen s))
          (should (:waiting (document/poll-mail s))))
        (finally
          (doseq [f (reverse (file-seq root))]
            (io/delete-file f true))))))

  (it "does not re-apply quit-for-restart after loading the EDN"
    (let [root (io/file "target" (str "load-mail-" (System/nanoTime)))
          edn (io/file root "diagram.edn")]
      (.mkdirs (io/file root ".metrics"))
      (.mkdirs (io/file root ".uml-viewer"))
      (spit edn (pr-str {:packages [] :edges []}))
      (try
        (let [path (.getPath edn)
              cmd (mailbox/write-command! (mailbox/to-viewer root)
                                          :quit-for-restart {})
              s (document/load-path path)]
          (should= (:id cmd) (:mail-seen s))
          (should-not (:quit-for-restart (document/poll-mail s))))
        (finally
          (doseq [f (reverse (file-seq root))]
            (io/delete-file f true))))))

  (it "does not auto-reload while waiting for the agent"
    (let [s (assoc (document/waiting-state "examples/library.edn" "target")
              :mtime 0)]
      (should (:waiting (document/maybe-reload s)))
      (should= [] (get-in (document/maybe-reload s) [:scene :classes]))))

  (it "loads the current EDN once waiting is cleared"
    (let [s (-> (document/waiting-state "examples/library.edn" "target")
                (dissoc :waiting)
                (assoc :mtime 0))
          next (document/maybe-reload s)]
      (should-not (:waiting next))
      (should (seq (:classes (:scene next))))
      (should (pos? (:mtime next)))))

  (it "does not throw on a missing file"
    (let [s (document/load-path "no-such-diagram.edn")]
      (should (re-find #"not found" (:error s)))
      (should= [] (get-in s [:scene :classes]))))

  (it "does not throw on invalid EDN"
    (let [f (java.io.File/createTempFile "bad" ".edn")]
      (spit f "{:packages")
      (let [s (document/load-path (.getPath f))]
        (should (string? (:error s)))
        (should= [] (get-in s [:scene :classes])))))

  (it "reloads when the file mtime changes"
    (let [s (assoc (document/load-path "examples/library.edn") :mtime 0)
          next (document/maybe-reload s)]
      (should (pos? (:mtime next)))
      (should-not (:error next))))

  (it "leaves state alone when mtime is unchanged"
    (let [s (document/load-path "examples/library.edn")]
      (should= s (document/maybe-reload s))))

  (it "reloads overlay when metrics snapshots change, keeping an open card"
    (let [root (io/file "target" (str "doc-metrics-" (System/nanoTime)))
          examples (io/file root "examples")
          metrics (io/file root ".metrics")]
      (.mkdirs examples)
      (.mkdirs metrics)
      (spit (io/file examples "diagram.edn")
            (pr-str {:hierarchical true
                     :title "Demo"
                     :classes [{:id :board :name "Board" :ns "demo.board"}]
                     :edges []}))
      (spit (io/file metrics "crap.edn")
            (pr-str {:entries [{:name "place" :namespace "demo.board"
                                :complexity 1 :coverage 100.0 :crap 1.0}]}))
      (try
        (let [path (.getPath (io/file examples "diagram.edn"))
              s (assoc (document/load-path path) :detail-id :board)
              before (first (filter #(= :board (:id %)) (:classes (:doc s))))]
          (should= 1.0 (get-in before [:crap :mu]))
          (spit (io/file metrics "crap.edn")
                (pr-str {:entries [{:name "place" :namespace "demo.board"
                                    :complexity 4 :coverage 50.0 :crap 12.5
                                    :updated true}]}))
          (let [next (document/maybe-reload s)
                after (first (filter #(= :board (:id %)) (:classes (:doc next))))]
            (should= :board (:detail-id next))
            (should= 12.5 (get-in after [:crap :mu]))
            (should-not= (:metrics-stamp s) (:metrics-stamp next))))
        (finally
          (doseq [f (reverse (file-seq root))]
            (io/delete-file f true))))))

  (it "records an error when reloaded IR is invalid"
    (let [f (java.io.File/createTempFile "bad" ".edn")]
      (spit f "{:packages [{:classes [{}]}]}")
      (let [next (document/maybe-reload (assoc (state) :path (.getPath f) :mtime 0))]
        (should (string? (:error next)))
        (should (re-find #"reload" (slurp log/*log-file*))))))

  (it "drops a detail id whose class vanished on reload"
    (let [f (java.io.File/createTempFile "uml" ".edn")]
      (spit f "{:packages [{:id :p :label \"P\" :classes [{:id :a :name \"A\"} {:id :b :name \"B\"}]}] :edges []}")
      (let [s (document/load-path (.getPath f))
            gone (first (filter #(= "B" (:name %)) (:classes (:scene s))))
            kept-name "A"]
        (spit f "{:packages [{:id :p :label \"P\" :classes [{:id :a :name \"A\"}]}] :edges []}")
        (let [next (document/maybe-reload (assoc s :mtime 0 :detail-id (:id gone)))]
          (should-not (:detail-id next))
          (should (some #(= kept-name (:name %)) (:classes (:scene next))))))))

  (it "paints class-card ops from .metrics keyed by :ns"
    (let [root (io/file "target" "doc-ns")
          examples (io/file root "examples")
          metrics (io/file root ".metrics")]
      (.mkdirs examples)
      (.mkdirs metrics)
      (spit (io/file metrics "crap.edn")
            (pr-str {:entries [{:name "place" :namespace "demo.board"
                                :complexity 1 :coverage 100.0 :crap 1.0}]}))
      (spit (io/file examples "diagram.edn")
            (pr-str {:packages [{:id :p :label "P"
                                 :classes [{:id :board :name "Board"
                                            :ns "demo.board"}]}]
                     :edges []}))
      (try
        (let [s (document/load-path (.getPath (io/file examples "diagram.edn")))
              c (first (filter #(= "Board" (:name %))
                               (get-in s [:scene :classes])))]
          (should (some #(= "place" (:name %)) (:ops c)))
          (should= "demo.board" (:ns c)))
        (finally
          (doseq [f (reverse (file-seq root))]
            (io/delete-file f true))))))

  (it "paints hierarchical :doc so the class card lists methods"
    (let [root (io/file "target" "doc-hier")
          examples (io/file root "examples")
          metrics (io/file root ".metrics")]
      (.mkdirs examples)
      (.mkdirs metrics)
      (spit (io/file metrics "crap.edn")
            (pr-str {:entries [{:name "place" :namespace "demo.board"
                                :complexity 1 :coverage 100.0 :crap 1.0}]}))
      (spit (io/file examples "diagram.edn")
            (pr-str {:hierarchical true
                     :title "Demo"
                     :classes [{:id :board :name "Board" :ns "demo.board"}]
                     :edges []}))
      (try
        (let [s (document/load-path (.getPath (io/file examples "diagram.edn")))
              c (first (filter #(= :board (:id %)) (:classes (:doc s))))]
          (should (some #(= "place" (:name %)) (:ops c)))
          (should= "demo.board" (:ns c)))
        (finally
          (doseq [f (reverse (file-seq root))]
            (io/delete-file f true))))))

  (it "shifts a scene so routes that swing left of the boxes stay on canvas"
    (let [fit (ns-resolve 'uml-viewer.engine.compose 'fit-scene)
          scene {:packages [{:id :p :rect (geom/rect 40 40 200 80)}]
                 :classes [{:id :a :rect (geom/rect 50 50 80 40)}]
                 :edges [{:from :a :to :a
                          :points [[50 70] [-80 70] [-80 120] [50 120]]}]
                 :size {:w 280 :h 160}}
          fitted (fit scene)
          xs (mapcat #(map first (:points %)) (:edges fitted))]
      (should (<= layout/margin (apply min xs)))
      (should (<= (apply max xs) (get-in fitted [:size :w])))
      (should (< 40 (get-in fitted [:classes 0 :rect :x])))
      (should (>= 0.0 (get-in fitted [:size :min-x] 0)))))

  (it "stacks diagrams top to bottom"
    (let [d {:packages [{:id :p :label "P" :classes [{:id :a :name "A"}]}] :edges []}
          doc {:title "Doc"
               :diagrams [(assoc (ir/normalize d) :title "One")
                          (assoc (ir/normalize d) :title "Two")]}
          scene (document/compile-document doc)
          titles (map :title (:sections scene))]
      (should= ["One" "Two"] titles)
      (should (apply < (map :title-y (:sections scene))))))

  (it "opens one proposal layer and treats a nil focus as the root"
    (let [doc {:hierarchical true
               :title "Demo"
               :proposals [{:id :ccp :name "CCP"
                            :layers [{:id :kernel :label "Kernel" :nses [:domain]}
                                     {:id :shell :label "Shell" :nses [:engine]}]}]
               :classes [{:id :domain :name "Domain" :ns "demo.domain"}
                         {:id :engine :name "Engine" :ns "demo.engine"}]
               :edges [{:from :engine :to :domain :kind :dependency}]
               :order [:domain :engine]}
          rooted (document/compile-view doc "target" [] false)
          nil-focus (document/compile-view doc "target" nil false)
          layer (document/compile-view doc "target" []
                                       {:proposal-id :ccp
                                        :open-layer :proposal.kernel
                                        :declutter :classes})]
      (should= (map :id (:classes rooted)) (map :id (:classes nil-focus)))
      (should= "Kernel" (get-in layer [:diagram :title]))
      (should (some #(= :domain (:id %)) (:classes layer)))))

  (it "finds a sibling policy file only when that file exists"
    (let [root (io/file "target" (str "policy-path-" (System/nanoTime)))
          edn (io/file root "diagram.edn")
          policy (io/file root "diagram.policy.edn")]
      (.mkdirs root)
      (spit policy "{:title \"T\"}")
      (try
        (should-be-nil (document/policy-path-for nil))
        (should-be-nil (document/policy-path-for (.getPath (io/file root "notes.txt"))))
        (should-be-nil (document/policy-path-for (.getPath (io/file root "missing.edn"))))
        (should= (.getPath policy) (document/policy-path-for (.getPath edn)))
        (finally
          (doseq [f (reverse (file-seq root))]
            (io/delete-file f true))))))

  (it "writes proposals to the IR and the policy file"
    (let [root (io/file "target" (str "proposals-" (System/nanoTime)))
          edn (io/file root "diagram.edn")
          policy (io/file root "diagram.policy.edn")
          explicit (io/file root "explicit.policy.edn")
          doc {:title "T"
               :proposal {:name "old" :layers []}
               :proposals [{:id :one :name "One" :layers []}
                           {:id :two :name "Two"
                            :layers [{:id :kernel :label "Kernel" :nses [:domain]}]}]}]
      (.mkdirs root)
      (spit policy (pr-str {:title "Keep" :proposal {:name "old"}}))
      (spit explicit (pr-str {:title "Keep" :proposal {:name "old"}}))
      (try
        (let [out (document/write-proposals! (.getPath edn) doc)
              written (edn/read-string (slurp edn))
              pol (edn/read-string (slurp policy))
              by-file (document/write-proposals!
                        nil (assoc doc :policy-file (.getPath explicit)))
              pol2 (edn/read-string (slurp explicit))
              only (io/file root "only.edn")
              skipped (document/write-proposals!
                        (.getPath only)
                        (assoc doc :policy-file (.getPath (io/file root "nope.policy.edn"))))]
          (should= [:one :two] (map :id (:proposals out)))
          (should= ["One" "Two"] (map :name (:proposals out)))
          (should= (:proposals out) (:proposals written))
          (should= (:proposals out) (:proposals pol))
          (should-not (:proposal pol))
          (should= "Keep" (:title pol))
          (should (:proposal out))
          (should= (:proposals by-file) (:proposals pol2))
          (should-not (:proposal pol2))
          (should (.isFile only))
          (should= [:one :two] (map :id (:proposals skipped)))
          (should-not (.exists (io/file root "nope.policy.edn"))))
        (finally
          (doseq [f (reverse (file-seq root))]
            (io/delete-file f true)))))))
