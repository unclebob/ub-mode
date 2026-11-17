(ns uml-viewer.adapters.core
  (:require [uml-viewer.adapters.sketch :as sketch]))

(def help-text
  (str "Usage: clj -M:run [options] [edn-file]\n"
       "\n"
       "  edn-file          Diagram to watch (default: examples/library.edn).\n"
       "                    A fresh start waits for the companion Grok to send\n"
       "                    :display unless the associated agent recycles\n"
       "                    the window with :uml-viewer-restart, or you press R.\n"
       "\n"
       "  --restart         Associated agent only (via :uml-viewer-restart).\n"
       "                    New JVM, keep the existing Grok tmux session.\n"
       "                    Reloads the last view (depth, pan, zoom, proposal).\n"
       "                    Do not use this if no companion is attached.\n"
       "\n"
       "  -h, --help        Print this help and exit.\n"))

(defn parse-args
  "EDN path and flags. `--restart` skips spawning a new agent."
  [args]
  (let [args (keep identity args)
        help? (boolean (some #{"--help" "-h"} args))
        restart? (boolean (some #{"--restart"} args))
        path (->> args (remove #{"--help" "-h" "--restart"}) first)]
    {:help? help?
     :restart? restart?
     :path (or path "examples/library.edn")}))

(defn start!
  "Launch the viewer. `source-impl` satisfies `LanguageSource`."
  [source-impl & args]
  (let [{:keys [path restart? help?]} (parse-args args)]
    (if help?
      (do (print help-text) :help)
      (do
        (sketch/start! path source-impl restart?)
        (println "Watching" path)
        (println "Double-click a class for its card. Scroll to pan (Shift-scroll for horizontal). Ctrl+/− zoom; Ctrl+0 resets. R reloads. Click the real diagram above Proposals, or a proposal to show it.")))))
