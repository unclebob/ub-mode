(ns uml-viewer.domain.log-spec
  (:require [clojure.java.io :as io]
            [speclj.core :refer :all]
            [uml-viewer.domain.log :as log]))

(defn- tmp-log []
  (str (System/getProperty "java.io.tmpdir")
       "/uv-log-" (System/nanoTime) ".txt"))

(describe "viewer log"
  (it "appends a stack trace and optional context"
    (let [f (tmp-log)]
      (try
        (binding [log/*log-file* f]
          (log/log-exception! (ex-info "boom" {:x 1}) "reload examples/x.edn")
          (log/log-exception! (Exception. "again")))
        (let [body (slurp f)]
          (should (re-find #"reload examples/x.edn" body))
          (should (re-find #"boom" body))
          (should (re-find #"again" body)))
        (finally
          (io/delete-file f true)))))

  (it "installs an uncaught handler that writes the log"
    (let [f (tmp-log)
          prev (Thread/getDefaultUncaughtExceptionHandler)]
      (try
        (binding [log/*log-file* f]
          (log/install-exception-log!)
          (let [h (Thread/getDefaultUncaughtExceptionHandler)]
            (should h)
            (.uncaughtException h (Thread/currentThread) (Exception. "untamed"))
            (should (re-find #"untamed" (slurp f)))
            (should (re-find #"uncaught on" (slurp f)))))
        (finally
          (Thread/setDefaultUncaughtExceptionHandler prev)
          (io/delete-file f true))))))
