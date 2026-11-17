(ns uml-viewer.domain.mailbox
  "File mailbox between the viewer and the companion Grok.
  Payload is durable EDN; tmux is only a wake-up."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.nio.file Files StandardCopyOption]))

(def dir-name ".uml-viewer")
(def to-viewer-name "to-viewer.edn")
(def to-agent-name "to-agent.edn")
(def session-name "session.edn")
(def companion-name "companion.edn")

(defn dir [root]
  (io/file root dir-name))

(defn to-viewer [root]
  (io/file (dir root) to-viewer-name))

(defn to-agent [root]
  (io/file (dir root) to-agent-name))

(defn session-file [root]
  (io/file (dir root) session-name))

(defn companion-file [root]
  (io/file (dir root) companion-name))

(defn read-command
  [file]
  (when (and file (.isFile (io/file file)))
    (try
      (edn/read-string (slurp file))
      (catch Exception _ nil))))

(def ^:private keep-n 32)

(defn read-mailbox
  "Envelope `{:next-id n :queue [cmd …]}`. A legacy single command becomes a queue."
  [file]
  (let [raw (read-command file)]
    (cond
      (nil? raw) {:next-id 1 :queue []}
      (vector? (:queue raw)) {:next-id (long (or (:next-id raw) 1))
                              :queue (vec (:queue raw))}
      (:op raw) {:next-id (inc (long (or (:id raw) 0)))
                 :queue [raw]}
      :else {:next-id 1 :queue []})))

(defn last-id
  [file]
  (long (or (:id (last (:queue (read-mailbox file)))) 0)))

(defn- atomic-write!
  [file m]
  (let [file (io/file file)
        tmp (io/file (str (.getPath file) ".tmp"))]
    (io/make-parents file)
    (spit tmp (pr-str m))
    (try
      (Files/move (.toPath tmp)
                  (.toPath file)
                  (into-array StandardCopyOption
                              [StandardCopyOption/REPLACE_EXISTING
                               StandardCopyOption/ATOMIC_MOVE]))
      (catch Exception _
        (Files/move (.toPath tmp)
                    (.toPath file)
                    (into-array StandardCopyOption
                                [StandardCopyOption/REPLACE_EXISTING]))))))

(defn write-command!
  "Append `op` (and extra keys) onto the mailbox queue."
  [file op extra]
  (let [box (read-mailbox file)
        id (long (or (:next-id box) 1))
        cmd (merge {:id id :op (keyword op)} extra)
        kept (vec (filter #(> (long (:id %)) (- id keep-n)) (:queue box)))]
    (atomic-write! file {:next-id (inc id)
                         :queue (conj kept cmd)})
    cmd))

(defn pending
  "Queued commands with :id greater than `seen-id`, oldest first."
  [file seen-id]
  (vec (filter #(> (long (:id %)) (long (or seen-id 0)))
               (:queue (read-mailbox file)))))

(defn unread
  "Next queued command after `seen-id`, or nil. Does not remove it."
  [file seen-id]
  (first (pending file seen-id)))

(defn take-command!
  "Remove and return the oldest command newer than `seen-id`, or nil.
  Stale items at or below `seen-id` are dropped from the file."
  ([file] (take-command! file 0))
  ([file seen-id]
   (let [box (read-mailbox file)
         seen (long (or seen-id 0))
         q (vec (filter #(> (long (:id %)) seen) (:queue box)))]
     (when (seq q)
       (atomic-write! file {:next-id (long (or (:next-id box) 1))
                            :queue (vec (rest q))})
       (first q)))))

(defn write-session!
  "Persist viewer scene keys for --restart."
  [root m]
  (atomic-write! (session-file root) (or m {}))
  m)

(defn read-session
  [root]
  (let [raw (read-command (session-file root))]
    (when (map? raw) raw)))

(defn write-companion!
  "Remember this viewer's tmux session and Terminal window."
  [root m]
  (atomic-write! (companion-file root) (or m {}))
  m)

(defn read-companion
  [root]
  (let [raw (read-command (companion-file root))]
    (when (map? raw) raw)))
