(ns uml-viewer.domain.log
  "Append viewer exceptions to uml-viewer-log.txt in the working directory."
  (:require [clojure.java.io :as io]))

(def log-name "uml-viewer-log.txt")

(def ^:dynamic *log-file* log-name)

(defn log-exception!
  "Append `t` (and optional context) to the viewer log. Never throws."
  ([^Throwable t] (log-exception! t nil))
  ([^Throwable t context]
   (try
     (let [f (io/file *log-file*)]
       (with-open [w (io/writer f :append true)]
         (.write w (str (java.time.Instant/now)))
         (when context
           (.write w (str " " context)))
         (.write w "\n")
         (.printStackTrace t (java.io.PrintWriter. w true))
         (.write w "\n")))
     (catch Exception _ nil))))

(defn install-exception-log!
  "Send uncaught thread exceptions to the viewer log."
  []
  (Thread/setDefaultUncaughtExceptionHandler
    (reify Thread$UncaughtExceptionHandler
      (uncaughtException [_ thread t]
        (log-exception! t (str "uncaught on " (.getName thread)))))))
