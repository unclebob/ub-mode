(ns uml-viewer.adapters.sketch
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [quil.applet :as applet]
            [quil.core :as q]
            [quil.middleware :as m]
            [uml-viewer.application.detail :as detail]
            [uml-viewer.application.document :as document]
            [uml-viewer.adapters.draw :as draw]
            [uml-viewer.application.events :as events]
            [uml-viewer.engine.hit :as hit]
            [uml-viewer.engine.layout :as layout]
            [uml-viewer.domain.mailbox :as mailbox]
            [uml-viewer.application.overlay :as overlay]
            [uml-viewer.adapters.source-window :as source-window]
            [uml-viewer.domain.policy :as policy])
  (:import [java.awt Component Container Frame]
           [java.awt.event ActionListener]
           [javax.swing JMenuItem JOptionPane JPopupMenu SwingUtilities]
           [processing.event MouseEvent]))

(def window-width 1500)
(def window-height 920)

(defonce !bridge
  (atom {:applet nil :model nil :pick nil :closed? false :exiting false}))

(def standing-rules
  (str "You are the UML-viewer companion. The current working directory is the\n"
       "project being examined (the one whose diagram is on screen), not\n"
       "necessarily uml-viewer itself.\n"
       "On launch: inspect this directory's source, write or update a\n"
       "hierarchical policy from the real namespaces (dots after the ns prefix\n"
       "are the tree; do not invent Domain/Engine/Adapters packages), then\n"
       "regenerate the IR (`clj -M:ir` or this project's equivalent) so the\n"
       "diagram matches. Do not edit the generated EDN by hand.\n"
       "After every later source or policy change:\n"
       "1. Keep the policy as namespace nesting only. Do not re-home a ns to\n"
       "   fake a layer/component. Layer and component mean the same thing.\n"
       "   If the tree is wrong, change the requires or the ns.\n"
       "   Preserve :proposals (named components that are not namespaces). Do not\n"
       "   invent :proposals on launch. The inspector lists them; click the real\n"
       "   diagram above Proposals to return to the namespace tree. If instructed,\n"
       "   add a named proposal to :proposals in the policy (default name is a\n"
       "   timestamp) and regenerate the IR.\n"
       "TypeScript, Rust, and Python are scanners beside Clojure. A Clojure project keeps\n"
       ":lang :clojure (the default) and the CRAP and mutation steps below.\n"
       "Another language sets :lang. More than one language sets :sources, each\n"
       "entry {:lang :root :prefix?}. The policy :prefix is stripped to make\n"
       "ids. A source :prefix is that tree's namespace root, for example\n"
       "bookwriter.rust beside TypeScript modules under bookwriter. Dots in the\n"
       "module id are still the tree. Do not invent packages. A TypeScript\n"
       "invoke(\"name\") depends on the Rust #[tauri::command] of that name.\n"
       "Python is one class per .py module. import and from-import are dependencies.\n"
       "class Child(Base) is inheritance when Base is a project module. A base of\n"
       "Protocol or ABC is implements.\n"
       "Go is one class per package directory plus one per package-level type.\n"
       "import is a dependency. A type whose methods cover a project interface\n"
       "implements it. Class :ns is crapper's Go name (import path, path.Type).\n"
       "For a non-Clojure language, run that language's CRAP and mutation tools\n"
       "when they exist. Snapshots join by the class :ns, then by the class id.\n"
       "Then regenerate the IR.\n"
       "2. Run clj -M:crap.\n"
       "3. Run clj -M:mutate on each changed file under src/ (differential).\n"
       "   Uncovered mutants are coverage gaps: keep the snapshot; do not\n"
       "   re-run the file or pass --mutate-all because of them.\n"
       "4. Regenerate the IR so the EDN mtime updates.\n"
       "Mailbox: .uml-viewer/to-agent.edn and to-viewer.edn are queues\n"
       "{:next-id n :queue [cmd …]} (atomic: tmp then rename). Pop the head of\n"
       ":queue as you handle it (rewrite the file). Oldest first. Ops are\n"
       "{:id n :op :display :path \"...\"}, {:id n :op :regen},\n"
       "{:id n :op :quit-for-restart}, {:id n :op :context ...},\n"
       "and right-click element ops:\n"
       "{:id n :op :refresh-crap :target {...}}, :refresh-mutate,\n"
       ":refresh-mutate-all, :omit. :target is {:id :ns :kind :class|:component\n"
       " :proposal-id?}. For :refresh-crap run clj -M:crap (that class or the\n"
       " files under that component) then IR. For :refresh-mutate run\n"
       " clj -M:mutate on those src files (differential). For\n"
       " :refresh-mutate-all pass --mutate-all on those files. For :omit, if\n"
       " :proposal-id is set add :id to that proposal's :omit; otherwise add it\n"
       " to policy :omit. Then regenerate the IR.\n"
       ":context means the inspector selection is the discussion context:\n"
       "{:context :real} for the namespace tree, or {:context :proposal\n"
       " :proposal-id id :name \"...\"} for a named proposal. Treat that as\n"
       "the architecture under discussion until a later :context arrives.\n"
       "A tmux wake-up means mail is waiting. If idle, pop and handle each\n"
       "to-agent command in order. If busy, finish first. Do not send tmux yourself.\n"
       "After regen, write :display with the generated EDN path.\n"
       "Prefer ./uml (fresh start: spawn this companion, wait for :display)\n"
       "and ./uml --restart (new JVM, keep this session, restore last view).\n"
       "If ./uml is missing, use aliases :uml-viewer and :uml-viewer-restart,\n"
       "or tell the user to run get-uml-viewer in this directory (it clones\n"
       "uml-viewer into gitignored .uml-viewer/uml-viewer/).\n"
       "Do not start the viewer on launch; it reloads EDN when the file mtime\n"
       "changes. To restart it: write :quit-for-restart, wait for the JVM to\n"
       "exit, then ./uml --restart. Do not pass --restart except through that\n"
       "wrapper (or :uml-viewer-restart). Do not SIGKILL. Closing the viewer\n"
       "kills only this companion's tmux session, not other Grok agents. If\n"
       "this Grok process dies, tmux respawns it in the same pane.\n"
       "Do not commit or push unless asked.\n"))

(def launch-prompt
  (str "On launch: from this working directory, update the hierarchical policy "
       "to match the project's namespaces (no invented layers/components), regenerate the "
       "IR, then wait for directives."))

(defn grok-executable
  []
  (let [home (System/getenv "HOME")
        named (System/getenv "GROK_BIN")
        candidates (filter identity
                           [named
                            (when home (str home "/.grok/bin/grok"))
                            "/usr/local/bin/grok"
                            "/opt/homebrew/bin/grok"])]
    (or (first (filter (fn [p]
                         (let [f (io/file p)]
                           (and (.isFile f) (.canExecute f))))
                       candidates))
        "grok")))

(defonce !session-name (atom nil))

(defn session-id
  "Tmux session unique to this project directory."
  [cwd]
  (let [path (.getCanonicalPath (io/file (or cwd ".")))
        base (-> (.getName (io/file path))
                 (str/replace #"[^A-Za-z0-9_-]" "-"))
        h (Integer/toHexString (hash path))]
    (str "uml-viewer-" base "-" h)))

(def legacy-session "uml-viewer-grok")

(defn current-session
  "Session name for this project, from companion.edn or the live atom."
  ([] (current-session (System/getProperty "user.dir")))
  ([root]
   (or (:session (mailbox/read-companion root))
       @!session-name
       (session-id root))))

(defn tmux!
  "Run tmux with `args`. Returns the process exit code (1 if tmux is missing)."
  [& args]
  (try
    (let [p (.start (ProcessBuilder. (into-array String (cons "tmux" args))))]
      (.waitFor p))
    (catch Exception _ 1)))

(defn session-candidates
  "Names to try, unique first, then the pre-isolation session."
  [root]
  (->> [(:session (mailbox/read-companion root))
        @!session-name
        (when root (session-id root))
        legacy-session]
       (filter seq)
       distinct
       vec))

(defn live-session
  "First candidate that tmux currently has, or nil."
  [root]
  (first (filter #(zero? (tmux! "has-session" "-t" %))
                 (session-candidates root))))

(defn rgb-16
  "Terminal.app AppleScript colors are 16-bit (0–65535)."
  [[r g b]]
  [(* (int r) 257) (* (int g) 257) (* (int b) 257)])

(defn- applescript-rgb [c]
  (let [[r g b] (rgb-16 c)]
    (str "{" r ", " g ", " b "}")))

(defn new-session-args
  ([cwd] (new-session-args cwd (session-id cwd)))
  ([cwd session]
   ["new-session" "-d" "-s" session "-c" cwd
    "-e" "GROK_THEME=terminal"
    "-e" "GROK_TERMINAL_THEME=1"
    "-e" "COLORTERM=truecolor"
    (grok-executable) "--yolo" "--trust" "--rules" standing-rules
    launch-prompt]))

(defn kill-session-args
  ([] (kill-session-args (current-session)))
  ([session]
   ["kill-session" "-t" session]))

(def wake-message
  "You have mail from the viewer. If idle, read .uml-viewer/to-agent.edn.")

(defn notify-steps
  "SwarmForge-style wake-up: literal text, pause, CR, pause, LF."
  ([] (notify-steps (current-session)))
  ([session]
   [["send-keys" "-t" session "-l" wake-message]
    [:sleep 150]
    ["send-keys" "-t" session "C-m"]
    [:sleep 50]
    ["send-keys" "-t" session "C-j"]]))

(defn notify-agent!
  "Wake the companion Grok session. Returns false if tmux/session is missing."
  ([] (notify-agent! (System/getProperty "user.dir")))
  ([root]
   (if-let [session (live-session root)]
     (do
       (reset! !session-name session)
       (doseq [step (notify-steps session)]
         (if (= :sleep (first step))
           (Thread/sleep (long (second step)))
           (apply tmux! step)))
       true)
     false)))

(defn request-agent!
  "Queue `op` for the companion and wake Grok. Returns {:cmd :woke?}."
  [root op extra]
  (let [cmd (mailbox/write-command! (mailbox/to-agent root) op extra)]
    {:cmd cmd :woke? (notify-agent! root)}))

(defn request-regen!
  "Queue a :regen command and wake Grok. Returns {:cmd :woke?}."
  [root]
  (request-agent! root :regen {}))

(defn mail-context!
  "Tell the companion which diagram is under discussion."
  [state]
  (when-let [path (:path state)]
    (let [root (overlay/metrics-root path)]
      (if-let [id (:proposal-id state)]
        (request-agent! root :context
                        {:context :proposal
                         :proposal-id id
                         :name (:name (policy/proposal-by-id (:doc state) id))})
        (request-agent! root :context {:context :real}))))
  state)

(defn terminal-title
  ([] (terminal-title (current-session)))
  ([session]
   (or session "UML Grok")))

(defonce !terminal-window-id (atom nil))

(defn attach-command
  ([] (attach-command (current-session)))
  ([session]
   (str "tmux attach -t " session "; exit")))

(defn osascript
  "AppleScript that opens a Terminal window on `shell-cmd`, painted like the diagram.
  Raises only that window, not every Terminal window. Returns the new window id."
  ([shell-cmd] (osascript shell-cmd (current-session)))
  ([shell-cmd session]
   (let [title (terminal-title session)]
     (str "tell application \"Terminal\"\n"
          "launch\n"
          "set grokTab to do script " (pr-str shell-cmd) "\n"
          "set background color of grokTab to " (applescript-rgb draw/bg) "\n"
          "set normal text color of grokTab to " (applescript-rgb draw/ink) "\n"
          "set bold text color of grokTab to " (applescript-rgb draw/gold) "\n"
          "set cursor color of grokTab to " (applescript-rgb draw/gold) "\n"
          "set font name of grokTab to \"Menlo\"\n"
          "set font size of grokTab to 13\n"
          "set custom title of grokTab to \"" title "\"\n"
          "set title displays custom title of grokTab to true\n"
          "set title displays device name of grokTab to false\n"
          "set title displays shell path of grokTab to false\n"
          "set title displays settings name of grokTab to false\n"
          "set winID to id of front window\n"
          "end tell\n"
          "tell application \"System Events\"\n"
          "tell process \"Terminal\"\n"
          "try\n"
          "perform action \"AXRaise\" of (first window whose name contains \"" title "\")\n"
          "end try\n"
          "end tell\n"
          "end tell\n"
          "return winID"))))

(defn close-terminal-script
  "AppleScript that closes only this viewer's Terminal window by id."
  ([] (close-terminal-script nil))
  ([win-id]
   (str "tell application \"System Events\"\n"
        "if not (exists process \"Terminal\") then return\n"
        "end tell\n"
        "tell application \"Terminal\"\n"
        (when win-id
          (str "try\n"
               "close (first window whose id is " win-id ") saving no\n"
               "end try\n"))
        "end tell")))

(defn run-osascript
  "Run `script` with osascript. Returns trimmed stdout, or \"\"."
  [script]
  (try
    (let [p (.start (doto (ProcessBuilder. (into-array String ["osascript" "-e" script]))
                      (.redirectErrorStream true)))
          out (slurp (.getInputStream p))]
      (.waitFor p)
      (str/trim out))
    (catch Exception _ "")))

(defn close-terminal-window!
  "Close the Terminal window that attached to the grok session."
  []
  (run-osascript (close-terminal-script @!terminal-window-id))
  (reset! !terminal-window-id nil))

(defn- kill-companion-session!
  "Drop this project's tmux session only. Unhook respawn so the pane stays dead."
  [session]
  (when (seq session)
    (tmux! "set-hook" "-t" session "-u" "pane-died")
    (apply tmux! (kill-session-args session))))

(defn- arm-respawn!
  "If Grok dies, tmux restarts that pane only — not other agents."
  [session]
  (let [pane (str session ":0.0")]
    (tmux! "set-option" "-p" "-t" pane "remain-on-exit" "on")
    (tmux! "set-hook" "-t" session "pane-died" "respawn-pane -k")
    (tmux! "set-option" "-t" session "status" "off")))

(defn open-in-terminal!
  "Start this project's companion Grok in its own tmux session."
  ([] (open-in-terminal! (System/getProperty "user.dir")))
  ([cwd]
   (let [session (session-id cwd)
         previous (:session (mailbox/read-companion cwd))]
     (reset! !session-name session)
     (when (and previous (not= previous session))
       (kill-companion-session! previous))
     (kill-companion-session! session)
     (let [code (apply tmux! (new-session-args cwd session))]
       (when-not (zero? code)
         (binding [*out* *err*]
           (println "UML viewer: could not start tmux session" session)))
       (when (zero? code)
         (arm-respawn! session)))
     (let [script (osascript (attach-command session) session)
           out (run-osascript script)
           win-id (re-find #"\d+" out)]
       (reset! !terminal-window-id win-id)
       (mailbox/write-companion! cwd {:session session :window-id win-id})
       {:script script :session session :window-id win-id}))))

(defn shutdown-children!
  "Kill only this viewer's tmux session and its Terminal window."
  ([] (shutdown-children! (System/getProperty "user.dir")))
  ([root]
   (let [info (mailbox/read-companion root)
         session (or (:session info) @!session-name)
         win (or (:window-id info) @!terminal-window-id)]
     (kill-companion-session! session)
     (when win
       (run-osascript (close-terminal-script win)))
     (reset! !terminal-window-id nil)
     (reset! !session-name nil))))

(defn- live? [applet]
  (boolean
    (and applet
         (try
           (not (.-finished applet))
           (catch Exception _ false)))))

(defn- native-window [applet]
  (try
    (.getNative (.getSurface applet))
    (catch Exception _ nil)))

(defn- front! [native]
  (cond
    (instance? Frame native)
    (doto ^Frame native
      (.setExtendedState Frame/NORMAL)
      (.setVisible true)
      (.toFront)
      (.requestFocus)
      (.requestFocusInWindow))
    (instance? java.awt.Window native)
    (doto ^java.awt.Window native
      (.setVisible true)
      (.toFront)
      (.requestFocus)
      (.requestFocusInWindow))))

(defn- later! [f]
  (SwingUtilities/invokeLater f))

(defn- halt-vm! []
  (System/exit 0))

(defn- exit-app! []
  (when-not (:keep-agent @!bridge)
    (shutdown-children!))
  (halt-vm!))

(defn- class-title [model]
  (or (get-in model [:class :name]) "Class"))

(defn- set-card-title! [title]
  (when-let [native (native-window (:applet @!bridge))]
    (when (instance? Frame native)
      (.setTitle ^Frame native (str title)))))

(defn- pin-card! [on?]
  (when-let [ap (:applet @!bridge)]
    (try
      (when-let [surface (.getSurface ap)]
        (.setAlwaysOnTop surface (boolean on?)))
      (when on?
        (front! (native-window ap)))
      (catch Exception _))))

(defn- swallow-esc!
  "Stop Processing from treating ESC as quit."
  [event]
  (when (or (= :esc (:key event)) (= 27 (:key-code event)))
    (try
      (when-let [ap (applet/current-applet)]
        (set! (.-key ap) (char 0)))
      (catch Exception _))))

(defn- close-detail-window! []
  (when-let [ap (:applet @!bridge)]
    (swap! !bridge assoc :exiting true :applet nil)
    (try
      (when-let [native (native-window ap)]
        (when (instance? java.awt.Window native)
          (.dispose ^java.awt.Window native)))
      (catch Exception _))))

(defn- quit-for-restart! []
  (swap! !bridge assoc :keep-agent true)
  (close-detail-window!)
  (try
    (q/exit)
    (catch Exception _))
  (halt-vm!))

(defn- take-flag! [k]
  (let [v (get @!bridge k)]
    (swap! !bridge assoc k (if (identical? v true) false nil))
    v))

(defn- detail-setup []
  (q/frame-rate 30)
  (q/color-mode :rgb)
  (q/smooth)
  (q/text-font (q/create-font "SansSerif" 14 true))
  {:scroll 0 :shown nil :hover nil})

(defn- detail-update [state]
  (let [id (get-in @!bridge [:model :class :id])]
    (if (not= id (:shown state))
      (assoc state :scroll 0 :shown id :hover nil)
      state)))

(defn- detail-scroll [state amount]
  (let [model (:model @!bridge)
        h (detail/content-h (detail/rows model))
        max-y (max 0 (- h detail/height))
        dy (* (cond
                (number? amount) amount
                (map? amount) (or (:count amount) 0)
                :else 0)
              24)]
    (update state :scroll #(max 0 (min max-y (+ % dy))))))

(defn- detail-draw [state]
  (when-let [model (:model @!bridge)]
    (draw/draw-detail model (:scroll state 0) (:hover state))))

(defn- detail-mouse-moved [state event]
  (if-let [model (:model @!bridge)]
    (assoc state :hover
           (let [y (+ (:y event) (:scroll state 0))
                 rows (detail/rows model)]
             (or (detail/member-at rows y)
                 (when (detail/module-at rows y) :module))))
    (assoc state :hover nil)))

(defn- detail-mouse-exited [state _event]
  (assoc state :hover nil))

(defn- click-count [event]
  (let [n (:count event)]
    (if (number? n)
      n
      (try
        (if-let [ev (.-mouseEvent (applet/current-applet))]
          (.getCount ^MouseEvent ev)
          1)
        (catch Exception _ 1)))))

(defn- detail-mouse-pressed [state event]
  (when-let [model (:model @!bridge)]
    (let [y (+ (:y event) (:scroll state 0))
          rows (detail/rows model)]
      (cond
        (detail/module-at rows y)
        (source-window/open-member-window! (:source @!bridge) (detail/member-ident model))

        (detail/member-at rows y)
        (source-window/open-member-window! (:source @!bridge)
                                           (detail/member-ident model (detail/member-at rows y)))

        (detail/rel-at rows y)
        (swap! !bridge assoc :pick (detail/rel-at rows y)))))
  state)

(defn- detail-key-pressed [state event]
  (when (= :esc (:key event))
    (swallow-esc! event)
    (swap! !bridge assoc :closed? true)
    (close-detail-window!))
  state)

(defn- detail-on-close [state]
  (let [exiting (:exiting @!bridge)]
    (swap! !bridge assoc
      :applet nil
      :exiting false
      :closed? (not exiting)))
  state)

(defn- start-detail-window! []
  (let [ap (q/sketch
             :title (class-title (:model @!bridge))
             :size [detail/width detail/height]
             :setup #'detail-setup
             :update #'detail-update
             :draw #'detail-draw
             :mouse-moved #'detail-mouse-moved
             :mouse-exited #'detail-mouse-exited
             :mouse-wheel #'detail-scroll
             :mouse-pressed #'detail-mouse-pressed
             :key-pressed #'detail-key-pressed
             :on-close #'detail-on-close
             :middleware [m/fun-mode])]
    (swap! !bridge assoc :applet ap :closed? false :exiting false)))

(defn- ensure-detail-window! [model]
  (swap! !bridge assoc :model model)
  (set-card-title! (class-title model))
  (when-not (or (live? (:applet @!bridge)) (:starting @!bridge))
    (swap! !bridge assoc :starting true)
    (later!
      (fn []
        (try
          (start-detail-window!)
          (pin-card! true)
          (finally
            (swap! !bridge assoc :starting false)))))))

(defn setup
  ([path] (setup path false))
  ([path restart?]
   (q/frame-rate 30)
   (q/color-mode :rgb)
   (q/smooth)
   (q/text-font (q/create-font "SansSerif" 14 true))
   (if restart?
     (document/restart-state path)
     (document/waiting-state path))))

(defn- view-dims []
  (let [w (q/width)
        h (q/height)]
    {:window-w w
     :window-h h
     :view-w (max 0 (- w layout/sidebar-w))}))

(defn- apply-proposal-op [state op]
  (case (:op op)
    :rename (events/rename-proposal state (:id op) (:name op))
    :delete (events/delete-proposal state (:id op))
    state))

(defn- apply-bridge-flags [state]
  (let [state (if (take-flag! :closed?)
                (events/close-detail state)
                state)
        state (if-let [id (take-flag! :pick)]
                (events/select-class state id)
                state)
        state (if-let [op (:proposal-op @!bridge)]
                (do (swap! !bridge dissoc :proposal-op)
                    (apply-proposal-op state op))
                state)]
    (if-let [id (:detail-id state)]
      (when-let [model (detail/model (events/card-scene state) id)]
        (swap! !bridge assoc :model model)
        (set-card-title! (class-title model)))
      (close-detail-window!))
    state))

(defn update-state [state]
  (let [state (-> state document/maybe-reload document/poll-mail)]
    (if (:quit-for-restart state)
      (do (document/save-session! state)
          (quit-for-restart!)
          state)
      (apply-bridge-flags state))))

(defn- open-card! [state id]
  (let [state (events/select-class state id)]
    (when-let [model (detail/model (events/card-scene state) (:detail-id state))]
      (ensure-detail-window! model))
    (pin-card! true)
    state))

(defn- applet-size []
  (try
    [(q/width) (q/height)]
    (catch Throwable _ [window-width window-height])))

(defn- right-click? [event]
  (or (= :right (:button event))
      (and (instance? MouseEvent event)
           (or (.isPopupTrigger ^MouseEvent event)
               (= 3 (.getButton ^MouseEvent event))))))

(defn- processing-mouse [event]
  (cond
    (instance? MouseEvent event) event
    :else (try
            (let [ap ^Object (applet/current-applet)]
              (some-> ap (.-mouseEvent)))
            (catch Exception _ nil))))

(defn- awt-mouse [event]
  (when-let [pe (processing-mouse event)]
    (try
      (let [n (.getNative ^MouseEvent pe)]
        (when (instance? java.awt.event.MouseEvent n) n))
      (catch Exception _ nil))))

(defn- popup-native []
  (try
    (some-> (applet/current-applet) native-window)
    (catch Exception _ nil)))

(defn- popup-invoker [awt native]
  (or (when awt (.getComponent ^java.awt.event.MouseEvent awt))
      (when (instance? Component native) native)))

(defn- anchor-at [awt invoker x y]
  (cond
    awt
    {:invoker invoker
     :x (.getX ^java.awt.event.MouseEvent awt)
     :y (.getY ^java.awt.event.MouseEvent awt)}

    (instance? Container invoker)
    (let [in (.getInsets ^Container invoker)]
      {:invoker invoker
       :x (+ (int x) (.left in))
       :y (+ (int y) (.top in))})

    :else
    {:invoker invoker :x (int x) :y (int y)}))

(defn- popup-anchor
  "Invoker and local x,y for JPopupMenu.show at the mouse-down."
  [event x y]
  (let [awt (awt-mouse event)]
    (anchor-at awt (popup-invoker awt (popup-native)) x y)))

(defn- element-target [state sel]
  (let [id (:id sel)
        c (when id (hit/class-by-id (:scene state) id))
        component? (boolean
                     (or (events/layer-id sel)
                         (:dummy? c)
                         (seq (:contents c))
                         (= :package (:kind sel))))]
    (cond-> {:id id :kind (if component? :component :class)}
      (:ns c) (assoc :ns (:ns c))
      (:proposal-id state) (assoc :proposal-id (:proposal-id state)))))

(defn- popup-element-menu! [event x y state sel]
  (let [anchor (popup-anchor event x y)
        root (overlay/metrics-root (:path state))
        target (element-target state sel)]
    (later!
      (fn []
        (let [menu (JPopupMenu.)
              add (fn [label op]
                    (let [item (JMenuItem. label)]
                      (.addActionListener item
                        (reify ActionListener
                          (actionPerformed [_ _]
                            (request-agent! root op {:target target}))))
                      (.add menu item)))]
          (add "Refresh CRAP" :refresh-crap)
          (add "Refresh Mutation" :refresh-mutate)
          (add "Refresh All Mutation" :refresh-mutate-all)
          (add "Omit" :omit)
          (.show menu (:invoker anchor) (int (:x anchor)) (int (:y anchor))))))))

(defn- rename-op [id raw]
  (when (and raw (seq (str/trim raw)))
    {:op :rename :id id :name (str/trim raw)}))

(defn- ask-proposal-name [pname]
  (JOptionPane/showInputDialog nil "Rename proposal" (str pname)))

(defn- remember-proposal-op! [op]
  (when op
    (swap! !bridge assoc :proposal-op op)))

(defn- choose-rename! [id pname]
  (remember-proposal-op! (rename-op id (ask-proposal-name pname))))

(defn- choose-delete! [id]
  (remember-proposal-op! {:op :delete :id id}))

(defn- show-popup! [menu invoker x y]
  (.show ^JPopupMenu menu invoker (int x) (int y)))

(defn- install-proposal-menu! [menu id pname]
  (let [rename (JMenuItem. "Rename")
        delete (JMenuItem. "Delete")]
    (.addActionListener rename
      (reify ActionListener
        (actionPerformed [_ _]
          (choose-rename! id pname))))
    (.addActionListener delete
      (reify ActionListener
        (actionPerformed [_ _]
          (choose-delete! id))))
    (.add menu rename)
    (.add menu delete)
    menu))

(defn- popup-proposal-menu! [event x y id pname]
  (let [anchor (popup-anchor event x y)]
    (later!
      (fn []
        (show-popup! (install-proposal-menu! (JPopupMenu.) id pname)
                     (:invoker anchor) (:x anchor) (:y anchor))))))

(defn- regen-press [state]
  (let [root (overlay/metrics-root (:path state))
        {:keys [woke?]} (request-regen! root)]
    (assoc state :mail-status (if woke?
                                "Regen requested."
                                "Regen queued; Grok session not attached."))))

(defn- proposal-menu-click? [event hit]
  (and (right-click? event) (= :proposal (:kind hit))))

(defn- sidebar-press [state event x y w]
  (let [hit (events/inspector-hit state x y w)]
    (cond
      (proposal-menu-click? event hit)
      (do (popup-proposal-menu!
            event x y (:id hit)
            (:name (policy/proposal-by-id (:doc state) (:id hit))))
          state)
      hit (let [next (events/on-inspector-press state hit)]
            (when (#{:real-diagram :proposal :new-proposal} (:kind hit))
              (mail-context! next))
            next)
      :else state)))

(defn- element-menu? [event sel]
  (and (right-click? event)
       (or (= :class (:kind sel))
           (= :child (:kind sel))
           (events/layer-id sel))))

(defn- drill-hit? [sel n]
  (and (>= n 2) (events/layer-id sel)))

(defn- card-hit? [sel n]
  (or (= :port (:kind sel))
      (= :child (:kind sel))
      (and (>= n 2) (= :class (:kind sel)))))

(defn- canvas-hit [state event x y sel n]
  (cond
    (element-menu? event sel)
    (do (popup-element-menu! event x y state sel)
        state)

    (drill-hit? sel n)
    (events/drill state (events/layer-id sel))

    (card-hit? sel n)
    (open-card! state (:id sel))

    (= :class (:kind sel))
    state

    :else
    (do (pin-card! false) state)))

(defn- canvas-press [state event x y]
  (let [state (events/on-press state x y)]
    (canvas-hit state event x y (:selected state) (click-count event))))

(defn- on-main-press [state event]
  (let [[w h] (applet-size)
        x (:x event)
        y (:y event)]
    (cond
      (events/regen-hit? x y w h)
      (regen-press state)

      (>= x (- w layout/sidebar-w))
      (sidebar-press state event x y w)

      :else
      (canvas-press state event x y))))

(defn- applet-shift? []
  (try
    (let [ap ^Object (applet/current-applet)
          ev (.-mouseEvent ap)]
      (boolean (and (instance? MouseEvent ev)
                    (.isShiftDown ^MouseEvent ev))))
    (catch Exception _ false)))

(defn- on-main-wheel [state event]
  (let [shift? (boolean
                 (or (when (instance? MouseEvent event)
                       (.isShiftDown ^MouseEvent event))
                     (applet-shift?)))]
    (events/on-scroll state event
                      (assoc (view-dims) :horizontal? shift?))))

(defn- on-main-close [state]
  (close-detail-window!)
  (exit-app!)
  state)

(defn- remember-companion!
  "On --restart, bind this JVM to the existing tmux session so mail can wake it."
  [root]
  (let [info (mailbox/read-companion root)
        session (live-session root)]
    (when session
      (reset! !session-name session)
      (when-let [win (:window-id info)]
        (reset! !terminal-window-id win))
      (mailbox/write-companion! root (merge (or info {}) {:session session}))
      session)))

(defn start!
  ([path source-impl]
   (start! path source-impl false))
  ([path source-impl restart?]
   (swap! !bridge assoc :source source-impl)
   (let [root (overlay/metrics-root path)]
     (if restart?
       (remember-companion! root)
       (open-in-terminal! root)))
   (q/sketch
    :title "UML viewer"
    :size [window-width window-height]
    :features [:resizable]
    :setup (fn [] (setup path restart?))
    :update #'update-state
    :draw #'draw/draw-state
    :mouse-pressed #'on-main-press
    :mouse-moved (fn [state event]
                   (events/on-move state (:x event) (:y event)))
    :mouse-wheel #'on-main-wheel
    :key-pressed (fn [state event]
                   (swallow-esc! event)
                   (events/on-key state (:key event)
                                  (assoc (view-dims)
                                    :control? (boolean
                                                (some #{:control :ctrl}
                                                      (:modifiers event)))
                                    :key-code (:key-code event)
                                    :raw-key (:raw-key event))))
    :key-released (fn [state event]
                    (swallow-esc! event)
                    state)
    :on-close #'on-main-close
    :middleware [m/fun-mode])))
