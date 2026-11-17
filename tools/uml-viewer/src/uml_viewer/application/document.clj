(ns uml-viewer.application.document
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [uml-viewer.engine.compose :as compose]
            [uml-viewer.domain.hierarchy :as hierarchy]
            [uml-viewer.engine.hit :as hit]
            [uml-viewer.domain.ir :as ir]
            [uml-viewer.domain.log :as log]
            [uml-viewer.domain.mailbox :as mailbox]
            [uml-viewer.application.overlay :as overlay]
            [uml-viewer.domain.policy :as policy]))

(defn- compile-opts [extra]
  (if (map? extra) extra {:proposal (boolean extra)}))

(defn- compile-proposal-id [opts doc]
  (or (:proposal-id opts)
      (when (:proposal opts)
        (:id (first (hierarchy/named-proposals doc))))))

(defn- compile-declutter [opts]
  (let [mode (or (:declutter opts) :full)]
    (if (:open-layer opts)
      (if (= mode :classes) :elements mode)
      mode)))

(defn- layer-focus? [pid open-layer focus]
  (and pid open-layer (empty? focus)))

(defn- proposal-root? [doc pid focus]
  (and pid (empty? focus) (seq (hierarchy/named-proposals doc))))

(defn- compile-source [doc focus pid open-layer]
  (cond
    (layer-focus? pid open-layer focus)
    (hierarchy/layer-view doc pid open-layer)
    (proposal-root? doc pid focus)
    (hierarchy/proposal-view doc pid)
    :else
    (hierarchy/view-at doc focus)))

(defn compile-view
  "Compile the hierarchical view at `focus`, or the stacked document.
  `extra` is a boolean (legacy proposal?) or
  `{:proposal-id :declutter :proposal}`."
  ([doc] (compile-view doc (System/getProperty "user.dir") []))
  ([doc metrics-root] (compile-view doc metrics-root []))
  ([doc metrics-root focus] (compile-view doc metrics-root focus {}))
  ([doc metrics-root focus extra]
   (let [opts (compile-opts extra)
         painted (overlay/apply-metrics doc (overlay/load-metrics metrics-root))
         focus (or focus [])
         pid (compile-proposal-id opts painted)
         open-layer (:open-layer opts)]
     (if (:hierarchical painted)
       (compose/compile-diagram
         (hierarchy/apply-declutter
           (compile-source painted focus pid open-layer)
           (compile-declutter opts)))
       (compose/compile-document painted)))))

(defn compile-document
  ([doc] (compile-view doc (System/getProperty "user.dir") []))
  ([doc metrics-root] (compile-view doc metrics-root [])))

(def empty-scene
  {:classes []
   :packages []
   :edges []
   :sections []
   :size {:w 800 :h 600}
   :diagram {:title "UML"}})

(def waiting-message "Waiting for agent to create diagram.")

(defn- mail-seen-at [root]
  (mailbox/last-id (mailbox/to-viewer root)))

(defn waiting-state
  "Blank canvas until the companion sends :display. R still loads `path`."
  ([path] (waiting-state path (overlay/metrics-root path)))
  ([path root]
   {:path path
    :mtime 0
    :metrics-stamp nil
    :waiting true
    :scene empty-scene
    :selected nil
    :hover nil
    :detail-id nil
    :cam-x 0
    :cam-y 0
    :mail-seen (mail-seen-at root)}))

(defn- blank-state [path error]
  {:path path
   :mtime (.lastModified (java.io.File. path))
   :metrics-stamp (overlay/metrics-stamp (overlay/metrics-root path))
   :scene empty-scene
   :error error
   :selected nil
   :hover nil
   :detail-id nil
   :cam-x 0
   :cam-y 0
   :mail-seen (mail-seen-at (overlay/metrics-root path))})

(defn- paint
  "IR plus .metrics members. The class card reads this, not the scene."
  [doc path]
  (let [root (overlay/metrics-root path)]
    (overlay/apply-metrics doc (overlay/load-metrics root))))

(def session-keys
  [:focus :proposal-id :proposal :open-layer :declutter
   :cam-x :cam-y :zoom :selected :detail-id])

(defn save-session!
  "Write the current view (depth, pan, zoom, proposal) for --restart."
  [state]
  (when-let [path (:path state)]
    (mailbox/write-session! (overlay/metrics-root path)
                            (select-keys state session-keys)))
  state)

(defn- known-proposal-id [doc id]
  (when (and id (some #(= id (:id %)) (hierarchy/named-proposals doc)))
    id))

(defn load-path [path]
  (let [file (java.io.File. path)]
    (cond
      (not (.exists file))
      (blank-state path (str "file not found: " path))

      (not (.isFile file))
      (blank-state path (str "not a file: " path))

      :else
      (try
        (let [root (overlay/metrics-root path)
              doc (paint (ir/load-document path) path)]
          {:path path
           :mtime (.lastModified file)
           :metrics-stamp (overlay/metrics-stamp root)
           :doc doc
           :focus []
           :scene (compile-view doc root [] false)
           :selected nil
           :hover nil
           :detail-id nil
           :cam-x 0
           :cam-y 0
           :mail-seen (mail-seen-at root)})
        (catch Exception e
          (log/log-exception! e (str "load " path))
          (blank-state path (or (.getMessage e) (.getSimpleName (class e)))))))))

(defn- drop-missing-detail [state]
  (let [id (:detail-id state)]
    (cond-> state
      (and id (nil? (hit/class-by-id (:scene state) id)))
      (dissoc :detail-id))))

(defn restore-session
  "Reapply a saved view onto a freshly loaded state."
  [state snap]
  (if (or (not (map? snap)) (nil? (:doc state)))
    state
    (let [focus (vec (or (:focus snap) []))
          pid (known-proposal-id (:doc state) (:proposal-id snap))
          open (:open-layer snap)
          declutter (:declutter snap)
          root (overlay/metrics-root (:path state))
          scene (compile-view (:doc state) root focus
                              {:proposal-id pid
                               :declutter declutter
                               :open-layer open})]
      (-> state
          (assoc :focus focus
                 :proposal-id pid
                 :proposal (boolean pid)
                 :open-layer open
                 :declutter declutter
                 :scene scene
                 :cam-x (or (:cam-x snap) 0)
                 :cam-y (or (:cam-y snap) 0)
                 :zoom (or (:zoom snap) 1.0)
                 :selected (:selected snap)
                 :detail-id (:detail-id snap))
          drop-missing-detail))))

(defn restart-state
  "Load `path` and restore the last saved view, if any."
  [path]
  (restore-session (dissoc (load-path path) :waiting)
                   (mailbox/read-session (overlay/metrics-root path))))

(defn- resolve-display-path [state p]
  (let [f (io/file p)]
    (if (.isAbsolute f)
      (.getPath f)
      (.getPath (io/file (overlay/metrics-root (:path state)) p)))))

(defn apply-mail
  "Act on one unread command from the companion Grok."
  [state cmd]
  (let [state (assoc state :mail-seen (:id cmd))]
    (case (keyword (:op cmd))
      :display (if-let [p (:path cmd)]
                 (-> (load-path (resolve-display-path state p))
                     (assoc :mail-seen (:id cmd))
                     (dissoc :waiting))
                 state)
      :quit-for-restart (assoc state :quit-for-restart true)
      state)))

(defn poll-mail
  [state]
  (if-not (:path state)
    state
    (let [root (overlay/metrics-root (:path state))
          f (mailbox/to-viewer root)]
      (loop [state state]
        (if-let [cmd (mailbox/take-command! f (:mail-seen state))]
          (recur (apply-mail state cmd))
          state)))))

(defn maybe-reload [state]
  (if (or (:waiting state) (nil? (:path state)))
    state
    (let [file (java.io.File. (:path state))]
      (if-not (.exists file)
        state
        (let [path (:path state)
              root (overlay/metrics-root path)
              mtime (.lastModified file)
              stamp (overlay/metrics-stamp root)]
          (if (and (= mtime (:mtime state))
                   (= stamp (:metrics-stamp state)))
            state
            (try
              (let [doc (paint (ir/load-document path) path)
                    focus (or (:focus state) [])]
                (-> state
                    (dissoc :waiting)
                    (assoc :mtime mtime
                           :metrics-stamp stamp
                           :doc doc
                           :scene (compile-view doc root focus
                                                {:proposal-id (:proposal-id state)
                                                 :declutter (:declutter state)
                                                 :open-layer (:open-layer state)})
                           :error nil)
                    drop-missing-detail))
              (catch Exception e
                (log/log-exception! e (str "reload " path))
                (assoc state :mtime mtime :metrics-stamp stamp
                       :error (.getMessage e))))))))))

(defn- emit-doc [doc]
  (str ";; Generated by clj -M:ir from the policy file. Do not edit.\n"
       (binding [*print-namespace-maps* false
                 pprint/*print-right-margin* 90]
         (with-out-str (pprint/pprint doc)))))

(defn policy-path-for
  "Sibling `.policy.edn` of an IR path, if that file exists."
  [edn-path]
  (when (and edn-path (str/ends-with? (str edn-path) ".edn"))
    (let [p (str (subs (str edn-path) 0 (- (count (str edn-path)) 4))
                 ".policy.edn")]
      (when (.isFile (io/file p)) p))))

(defn write-proposals!
  "Persist named proposals to the IR and, when present, the policy file."
  [edn-path doc]
  (let [proposals (mapv #(select-keys % [:id :name :layers])
                        (policy/named-proposals doc))
        doc (assoc doc :proposals proposals)
        policy-path (or (:policy-file doc) (policy-path-for edn-path))]
    (when edn-path
      (spit edn-path (emit-doc doc)))
    (when (and policy-path (.isFile (io/file policy-path)))
      (let [p (edn/read-string (slurp policy-path))
            p (-> p (assoc :proposals proposals) (dissoc :proposal))]
        (spit policy-path
              (binding [*print-namespace-maps* false
                        pprint/*print-right-margin* 90]
                (with-out-str (pprint/pprint p))))))
    doc))
