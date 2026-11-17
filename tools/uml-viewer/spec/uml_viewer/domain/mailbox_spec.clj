(ns uml-viewer.domain.mailbox-spec
  (:require [clojure.java.io :as io]
            [speclj.core :refer :all]
            [uml-viewer.application.document :as document]
            [uml-viewer.domain.mailbox :as mailbox]))

(defn- tmp-root []
  (doto (io/file "target" (str "mailbox-" (System/nanoTime)))
    (.mkdirs)))

(describe "mailbox"
  (it "round-trips a restart session"
    (let [root (tmp-root)]
      (mailbox/write-session! root {:focus [:engine] :zoom 1.1 :cam-x 12})
      (let [snap (mailbox/read-session root)]
        (should= [:engine] (:focus snap))
        (should= 1.1 (:zoom snap))
        (should= 12 (:cam-x snap)))))

  (it "round-trips companion session identity"
    (let [root (tmp-root)]
      (mailbox/write-companion! root {:session "uml-viewer-proj-ab" :window-id "42"})
      (let [info (mailbox/read-companion root)]
        (should= "uml-viewer-proj-ab" (:session info))
        (should= "42" (:window-id info))
        (should= "companion.edn" mailbox/companion-name))))

  (it "treats a map without a queue or a command as an empty mailbox"
    (let [f (io/file (tmp-root) "to-agent.edn")]
      (spit f "{:nope 1}")
      (should= {:next-id 1 :queue []} (mailbox/read-mailbox f))))

  (it "writes commands atomically with rising ids"
    (let [root (tmp-root)
          f (mailbox/to-agent root)
          a (mailbox/write-command! f :regen {})
          b (mailbox/write-command! f :regen {})]
      (should= 1 (:id a))
      (should= 2 (:id b))
      (should= 2 (count (:queue (mailbox/read-mailbox f))))
      (should= :regen (:op (last (:queue (mailbox/read-mailbox f)))))
      (should-not (.exists (io/file (str (.getPath f) ".tmp"))))))

  (it "queues later commands instead of overwriting"
    (let [root (tmp-root)
          f (mailbox/to-agent root)]
      (mailbox/write-command! f :context {:context :real})
      (mailbox/write-command! f :omit {:target {:id :a}})
      (let [q (mailbox/pending f 0)]
        (should= [:context :omit] (mapv :op q))
        (should= :context (:op (mailbox/unread f 0)))
        (should= :omit (:op (mailbox/unread f 1))))))

  (it "returns unread commands only once past seen-id"
    (let [root (tmp-root)
          f (mailbox/to-viewer root)]
      (mailbox/write-command! f :display {:path "a.edn"})
      (should= :display (:op (mailbox/unread f 0)))
      (should-be-nil (mailbox/unread f 1))))

  (it "removes a command from the queue when it is taken"
    (let [root (tmp-root)
          f (mailbox/to-agent root)]
      (mailbox/write-command! f :context {:context :real})
      (mailbox/write-command! f :omit {:target {:id :a}})
      (should= :context (:op (mailbox/take-command! f)))
      (should= [:omit] (mapv :op (:queue (mailbox/read-mailbox f))))
      (should= :omit (:op (mailbox/take-command! f)))
      (should= [] (:queue (mailbox/read-mailbox f)))
      (should-be-nil (mailbox/take-command! f))))

  (it "drains a viewer queue in order"
    (let [root (tmp-root)
          f (mailbox/to-viewer root)
          ops (atom [])]
      (mailbox/write-command! f :nope {})
      (mailbox/write-command! f :quit-for-restart {})
      (loop []
        (when-let [cmd (mailbox/take-command! f)]
          (swap! ops conj (:op cmd))
          (recur)))
      (should= [:nope :quit-for-restart] @ops)
      (should= [] (:queue (mailbox/read-mailbox f)))))

  (it "switches the viewer path on :display"
    (let [s {:path "examples/library.edn" :mail-seen 0 :waiting true}
          next (document/apply-mail s {:id 3 :op :display :path "examples/library.edn"})]
      (should= 3 (:mail-seen next))
      (should (.endsWith (:path next) "examples/library.edn"))
      (should (seq (get-in next [:scene :classes])))
      (should-not (:waiting next))))

  (it "ignores unknown ops after recording the id"
    (let [s {:path "examples/library.edn" :mail-seen 0 :mtime 99}
          next (document/apply-mail s {:id 4 :op :nope})]
      (should= 4 (:mail-seen next))
      (should= "examples/library.edn" (:path next))
      (should= 99 (:mtime next))))

  (it "marks the viewer to quit for restart"
    (let [s {:path "examples/library.edn" :mail-seen 0 :mtime 99}
          next (document/apply-mail s {:id 5 :op :quit-for-restart})]
      (should (:quit-for-restart next))
      (should= 5 (:mail-seen next))
      (should= "examples/library.edn" (:path next))
      (should= 99 (:mtime next)))))
