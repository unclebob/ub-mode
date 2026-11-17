(ns uml-viewer.adapters.core-spec
  (:require [speclj.core :refer :all]
            [uml-viewer.adapters.core :as core]
            [uml-viewer.adapters.sketch :as sketch]))

(describe "cli args"
  (it "defaults the path and does not restart"
    (should= {:path "examples/library.edn" :restart? false :help? false}
             (core/parse-args nil))
    (should= {:path "examples/library.edn" :restart? false :help? false}
             (core/parse-args [])))

  (it "takes a path and the --restart flag in either order"
    (should= {:path "doc.edn" :restart? false :help? false}
             (core/parse-args ["doc.edn"]))
    (should= {:path "examples/library.edn" :restart? true :help? false}
             (core/parse-args ["--restart"]))
    (should= {:path "doc.edn" :restart? true :help? false}
             (core/parse-args ["--restart" "doc.edn"]))
    (should= {:path "doc.edn" :restart? true :help? false}
             (core/parse-args ["doc.edn" "--restart"])))

  (it "prints a description of the arguments on --help"
    (should= {:path "examples/library.edn" :restart? false :help? true}
             (core/parse-args ["--help"]))
    (should (:help? (core/parse-args ["-h" "doc.edn"])))
    (should (re-find #"edn-file" core/help-text))
    (should (re-find #"--restart" core/help-text))
    (with-redefs [sketch/start! (fn [& _] (throw (Exception. "should not start")))]
      (let [ret (atom nil)
            out (with-out-str (reset! ret (core/start! :unused "--help")))]
        (should= :help @ret)
        (should (re-find #"Usage: clj -M:run" out)))))

  (it "starts the sketch when not asking for help"
    (let [args (atom nil)]
      (with-redefs [sketch/start! (fn [& a] (reset! args a) :started)]
        (let [out (with-out-str (core/start! :src "doc.edn"))]
          (should= ["doc.edn" :src false] @args)
          (should (re-find #"Watching" out))
          (should (re-find #"real diagram above Proposals" out)))))))
