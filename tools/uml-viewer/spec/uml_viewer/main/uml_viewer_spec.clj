(ns uml-viewer.main.uml-viewer-spec
  (:require [clojure.java.io :as io]
            [speclj.core :refer :all]
            [uml-viewer.domain.log :as log]
            [uml-viewer.graph :as graph]
            [uml-viewer.main.ir-generator]
            [uml-viewer.main.uml-viewer :as main]))

(describe "viewer process"
  (it "logs exceptions to uml-viewer-log.txt in the working directory"
    (should= "uml-viewer-log.txt" log/log-name)
    (should main/-main)))

(describe "ir generator arguments"
  (it "chooses the policy, output, and language without writing"
    (let [plan (ns-resolve 'uml-viewer.main.ir-generator 'generation-plan)
          bare (plan [])
          path (doto (java.io.File/createTempFile "uv-plan" ".edn")
                 (spit (pr-str {:lang :python})))
          plain (doto (java.io.File/createTempFile "uv-plan" ".edn")
                  (spit (pr-str {:title "T"})))
          missing (doto (java.io.File/createTempFile "uv-plan" ".edn")
                    (spit (pr-str {:lang :not-a-language})))]
      (try
        (let [chosen (plan [(.getPath path) "out.edn"])]
          (should= "examples/uml-viewer.policy.edn" (:policy-path bare))
          (should= "examples/uml-viewer.edn" (:out bare))
          (should (satisfies? graph/LanguageGraph (:impl bare)))
          (should= (.getPath path) (:policy-path chosen))
          (should= "out.edn" (:out chosen))
          (should (satisfies? graph/LanguageGraph (:impl chosen)))
          (should (satisfies? graph/LanguageGraph (:impl (plan [(.getPath plain)]))))
          (should-throw
            (plan [(.getPath missing)])))
        (finally
          (io/delete-file path true)
          (io/delete-file plain true)
          (io/delete-file missing true))))))
