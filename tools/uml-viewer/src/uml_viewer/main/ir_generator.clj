(ns uml-viewer.main.ir-generator
  (:require [uml-viewer.application.ir-generator :as ir-generator]
            [uml-viewer.clojure-language.graph-clojure]
            [uml-viewer.go-language.graph-go]
            [uml-viewer.graph :as graph]
            [uml-viewer.python-language.graph-python]
            [uml-viewer.rust-language.graph-rust]
            [uml-viewer.typescript-language.graph-typescript])
  (:gen-class))

(defn- generation-plan
  "Policy path, output path, and language graph chosen from `args`.
   Does not write a file."
  [args]
  (let [policy-path (or (first args) "examples/uml-viewer.policy.edn")
        policy (ir-generator/read-policy policy-path)
        lang (or (:lang policy) :clojure)]
    {:policy-path policy-path
     :out (or (second args) (:out policy) "examples/uml-viewer.edn")
     :impl (or (graph/lookup lang)
               (throw (ex-info (str "no LanguageGraph for " lang) {:lang lang})))}))

(defn -main [& args]
  (let [{:keys [policy-path out impl]} (generation-plan args)]
    (println "Wrote" (ir-generator/generate impl policy-path out))))
