(ns uml-viewer.adapters.sketch-spec
  (:require [quil.applet :as applet]
            [quil.core :as q]
            [speclj.core :refer :all]
            [uml-viewer.application.detail :as detail]
            [uml-viewer.adapters.draw :as draw]
            [uml-viewer.engine.compose :as compose]
            [uml-viewer.application.document :as document]
            [uml-viewer.application.events :as events]
            [uml-viewer.domain.geom :as geom]
            [uml-viewer.engine.layout :as layout]
            [uml-viewer.domain.ir :as ir]
            [uml-viewer.adapters.sketch :as sketch]
            [uml-viewer.adapters.source-window :as source-window]
            [uml-viewer.domain.mailbox :as mailbox])
  (:import [java.awt Frame]
           [java.util.concurrent CountDownLatch TimeUnit]
           [javax.swing JWindow]
           [processing.event Event MouseEvent]))

(defn- sk [sym]
  (ns-resolve 'uml-viewer.adapters.sketch sym))

(defn- call [sym & args]
  (apply (sk sym) args))

(defn- empty-bridge []
  {:applet nil :model nil :pick nil :closed? false :exiting false})

(defprotocol SurfaceOps
  (getNative [this])
  (setAlwaysOnTop [this on?]))

(defprotocol AppletOps
  (getSurface [this]))

(defrecord FakeSurface [native !top]
  SurfaceOps
  (getNative [_] native)
  (setAlwaysOnTop [_ on?]
    (when !top (reset! !top on?))
    on?))

(defrecord FakeApplet [finished surface]
  AppletOps
  (getSurface [_] surface))

(defrecord Finished [finished])

(defrecord FakeCurrent [mouseEvent])

(defprotocol ShiftOps
  (isShiftDown [this]))

(defrecord FakeShift [on?]
  ShiftOps
  (isShiftDown [_] on?))

(defn- scene []
  (compose/compile-diagram
    (ir/normalize
      {:packages
       [{:id :p :label "P"
         :classes [{:id :a :name "A"
                    :ns "demo.a"
                    :ops [{:name "go" :args ["x"] :returns "void"}]}
                   {:id :b :name "B"}]}]
       :edges [{:from :a :to :b :kind :association}]})))

(defn- state []
  {:scene (scene)
   :selected nil
   :hover nil
   :cam-x 0
   :cam-y 0
   :path nil
   :mtime 0})

(defn- class-xy [s id]
  (let [c (first (filter #(= id (:id %)) (:classes (:scene s))))]
    [(geom/cx (:rect c)) (geom/cy (:rect c))]))

(defn- a-model []
  (detail/model (:scene (state)) :a))

(defn- at [r button]
  (cond-> {:x (+ (:x r) 1) :y (+ (:y r) 1) :count 1}
    button (assoc :button button)))

(defn- quiet-quil [f]
  (with-redefs [q/frame-rate (fn [_])
                q/color-mode (fn [_])
                q/smooth (fn [])
                q/create-font (fn [& _] :font)
                q/text-font (fn [& _])
                q/exit (fn [])
                q/width (fn [] 1500)
                q/height (fn [] 920)]
    (f)))

(describe "sketch"
  (before (reset! sketch/!bridge (empty-bridge)))

  (it "treats a missing or finished applet as dead"
    (should-not (call 'live? nil))
    (should-not (call 'live? (->Finished true)))
    (should (call 'live? (->Finished false)))
    (should-not (call 'live? "not-an-applet")))

  (it "reads the native window and swallows surface errors"
    (should= :native (call 'native-window (->FakeApplet false (->FakeSurface :native nil))))
    (should-be-nil (call 'native-window nil)))

  (it "brings a Frame or Window to the front and ignores other natives"
    (let [frame-calls (atom [])
          window-calls (atom [])
          frame (proxy [Frame] []
                  (setExtendedState [s] (swap! frame-calls conj [:extended s]))
                  (setVisible [v] (swap! frame-calls conj [:visible v]))
                  (toFront [] (swap! frame-calls conj :to-front))
                  (requestFocus [] (swap! frame-calls conj :focus) true)
                  (requestFocusInWindow [] (swap! frame-calls conj :focus-win) true))
          window (proxy [JWindow] []
                   (setVisible [v] (swap! window-calls conj [:visible v]))
                   (toFront [] (swap! window-calls conj :to-front))
                   (requestFocus [] (swap! window-calls conj :focus) true)
                   (requestFocusInWindow [] (swap! window-calls conj :focus-win) true))]
      (try
        (call 'front! frame)
        (call 'front! window)
        (should-be-nil (call 'front! :not-a-window))
        (should= [[:extended Frame/NORMAL] [:visible true] :to-front :focus :focus-win]
                 @frame-calls)
        (should= [[:visible true] :to-front :focus :focus-win]
                 @window-calls)
        (finally
          (.dispose frame)
          (.dispose window)))))

  (it "pins the detail card on top and leaves it alone without an applet"
    (let [top (atom nil)
          brought (atom nil)]
      (call 'pin-card! true)
      (reset! sketch/!bridge {:applet (->FakeApplet false (->FakeSurface :native top))})
      (with-redefs [uml-viewer.adapters.sketch/front! (fn [n] (reset! brought n))]
        (call 'pin-card! true)
        (should= true @top)
        (should= :native @brought)
        (reset! brought nil)
        (call 'pin-card! false)
        (should= false @top)
        (should-be-nil @brought))
      (reset! sketch/!bridge {:applet (->FakeApplet false nil)})
      (call 'pin-card! true)
      (reset! sketch/!bridge {:applet (reify AppletOps
                                        (getSurface [_] (throw (Exception. "gone"))))})
      (call 'pin-card! true)))

  (it "titles the class card with the class name"
    (should= "Layout" (call 'class-title {:class {:name "Layout"}}))
    (should= "Class" (call 'class-title nil))
    (let [titles (atom [])
          frame (proxy [Frame] []
                  (setTitle [t] (swap! titles conj t)))]
      (try
        (reset! sketch/!bridge
                {:applet (->FakeApplet false (->FakeSurface frame (atom nil)))})
        (call 'set-card-title! "Draw")
        (should= ["Draw"] @titles)
        (finally
          (.dispose frame)))))

  (it "closes the detail applet by disposing its window"
    (let [disposed (atom false)
          frame (proxy [Frame] []
                  (dispose [] (reset! disposed true)))]
      (call 'close-detail-window!)
      (should-be-nil (:applet @sketch/!bridge))
      (reset! sketch/!bridge
              (assoc (empty-bridge)
                :applet (->FakeApplet false (->FakeSurface frame (atom nil)))))
      (call 'close-detail-window!)
      (should @disposed)
      (should-be-nil (:applet @sketch/!bridge))
      (should (:exiting @sketch/!bridge)))))

  (it "runs a function on the swing thread"
    (let [done (CountDownLatch. 1)
          ran (atom false)]
      (call 'later! (fn []
                      (reset! ran true)
                      (.countDown done)))
      (should (.await done 2 TimeUnit/SECONDS))
      (should @ran)))

  (it "shuts down grok children then halts the VM"
    (let [order (atom [])]
      (reset! sketch/!bridge (empty-bridge))
      (with-redefs [uml-viewer.adapters.sketch/shutdown-children! (fn [] (swap! order conj :grok))
                    uml-viewer.adapters.sketch/halt-vm! (fn [] (swap! order conj :halt))]
        (call 'exit-app!)
        (should= [:grok :halt] @order))))

  (it "keeps grok alive when the bridge says keep-agent"
    (let [order (atom [])]
      (reset! sketch/!bridge (assoc (empty-bridge) :keep-agent true))
      (with-redefs [uml-viewer.adapters.sketch/shutdown-children! (fn [] (swap! order conj :grok))
                    uml-viewer.adapters.sketch/halt-vm! (fn [] (swap! order conj :halt))]
        (call 'exit-app!)
        (should= [:halt] @order))))

  (it "exits the JVM without killing grok when quitting for restart"
    (let [order (atom [])]
      (reset! sketch/!bridge (empty-bridge))
      (with-redefs [uml-viewer.adapters.sketch/close-detail-window! (fn [] (swap! order conj :detail))
                    q/exit (fn [] (swap! order conj :quil))
                    uml-viewer.adapters.sketch/shutdown-children! (fn [] (swap! order conj :grok))
                    uml-viewer.adapters.sketch/halt-vm! (fn [] (swap! order conj :halt))]
        (call 'quit-for-restart!)
        (should (:keep-agent @sketch/!bridge))
        (should= [:detail :quil :halt] @order))))

  (it "takes a one-shot flag from the bridge"
    (reset! sketch/!bridge (assoc (empty-bridge) :closed? true :pick :a))
    (should (call 'take-flag! :closed?))
    (should-not (:closed? @sketch/!bridge))
    (should= :a (call 'take-flag! :pick))
    (should-be-nil (:pick @sketch/!bridge))
    (should-be-nil (call 'take-flag! :pick)))

  (it "sets up the detail sketch state"
    (quiet-quil
      (fn []
        (should= {:scroll 0 :shown nil :hover nil}
                 (call 'detail-setup)))))

  (it "resets scroll when the shown class changes"
    (reset! sketch/!bridge {:model {:class {:id :b}}})
    (should= {:scroll 0 :shown :b :hover nil}
             (call 'detail-update {:scroll 40 :shown :a :hover :go}))
    (should= {:scroll 40 :shown :b :hover :go}
             (call 'detail-update {:scroll 40 :shown :b :hover :go})))

  (it "scrolls the detail card from a number, map, or junk amount"
    (reset! sketch/!bridge {:model (a-model)})
    (with-redefs [detail/height 10]
      (let [down (call 'detail-scroll {:scroll 0} 2)
            mapped (call 'detail-scroll {:scroll 0} {:count 1})
            empty-map (call 'detail-scroll {:scroll 0} {})
            junk (call 'detail-scroll {:scroll 12} :nope)]
        (should (pos? (:scroll down)))
        (should (pos? (:scroll mapped)))
        (should= 0 (:scroll empty-map))
        (should= 12 (:scroll junk))
        (should= 0 (:scroll (call 'detail-scroll {:scroll 0} -10))))))

  (it "draws the open model and skips drawing when none is open"
    (let [drawn (atom nil)]
      (with-redefs [draw/draw-detail (fn [& args] (reset! drawn args))]
        (call 'detail-draw {:scroll 3 :hover :go})
        (should-be-nil @drawn)
        (reset! sketch/!bridge {:model (a-model)})
        (call 'detail-draw {:scroll 3 :hover :go})
        (should= (a-model) (first @drawn))
        (should= 3 (second @drawn))
        (should= :go (nth @drawn 2)))))

  (it "tracks member hover and clears it when the pointer leaves"
    (let [model (a-model)
          go (first (filter :op-name (detail/rows model)))]
      (should= {:scroll 0 :hover nil} (call 'detail-mouse-moved {:scroll 0} {:y 0}))
      (reset! sketch/!bridge {:model model})
      (should= "go" (:hover (call 'detail-mouse-moved {:scroll 0}
                                 {:y (+ (:y go) 1)})))
      (should-be-nil (:hover (call 'detail-mouse-moved {:scroll 0} {:y 0})))
      (should= {:hover nil} (call 'detail-mouse-exited {:hover :go} :evt))))

  (it "opens source on click of a member and picks a related class"
    (let [model (a-model)
          rows (detail/rows model)
          go (first (filter :op-name rows))
          mod (first (filter :module rows))
          rel (first (filter #(= :rel (:kind %)) rows))
          opened (atom nil)
          go-y (+ (:y go) 1)]
      (should= {:scroll 0} (call 'detail-mouse-pressed {:scroll 0} {:y 0}))
      (reset! sketch/!bridge {:model model})
      (with-redefs [source-window/open-member-window! (fn
                                                        ([_src ident]
                                                         (reset! opened ident))
                                                        ([_src ns op]
                                                         (reset! opened [ns op])))]
        (call 'detail-mouse-pressed {:scroll 0} {:y go-y})
        (should= (detail/member-ident model "go") @opened)
        (call 'detail-mouse-pressed {:scroll 0} {:y (+ (:y mod) 1)})
        (should= (detail/member-ident model) @opened)
        (call 'detail-mouse-pressed {:scroll 0} {:y (+ (:y rel) 1)})
        (should= (:id rel) (:pick @sketch/!bridge))
        (reset! sketch/!bridge (assoc (empty-bridge) :model model :pick nil))
        (call 'detail-mouse-pressed {:scroll 0} {:y 0})
        (should-be-nil (:pick @sketch/!bridge)))))

(describe "sketch keys and lifecycle"
  (before (reset! sketch/!bridge (empty-bridge)))

  (it "closes the detail window on escape without quitting Processing"
    (let [closed (atom false)]
      (with-redefs [uml-viewer.adapters.sketch/close-detail-window!
                    (fn [] (reset! closed true))
                    uml-viewer.adapters.sketch/swallow-esc! (fn [_])]
        (should= :state (call 'detail-key-pressed :state {:key :x}))
        (should-not @closed)
        (call 'detail-key-pressed :state {:key :esc})
        (should @closed)
        (should (:closed? @sketch/!bridge)))))

  (it "marks the card closed unless the main window is exiting it"
    (reset! sketch/!bridge (assoc (empty-bridge) :applet :ap :exiting true))
    (should= :s (call 'detail-on-close :s))
    (should-be-nil (:applet @sketch/!bridge))
    (should-not (:exiting @sketch/!bridge))
    (should-not (:closed? @sketch/!bridge))
    (reset! sketch/!bridge (assoc (empty-bridge) :applet :ap :exiting false))
    (call 'detail-on-close :s)
    (should (:closed? @sketch/!bridge)))

  (it "starts a detail sketch and stores the applet"
    (let [opts (atom nil)]
      (reset! sketch/!bridge (assoc (empty-bridge)
                               :model {:class {:name "Layout"}}))
      (with-redefs [q/sketch (fn [& args]
                               (reset! opts (apply hash-map args))
                               :detail-applet)]
        (call 'start-detail-window!)
        (should= :detail-applet (:applet @sketch/!bridge))
        (should-not (:closed? @sketch/!bridge))
        (should-not (:exiting @sketch/!bridge))
        (should= "Layout" (:title @opts))
        (should= [detail/width detail/height] (:size @opts))
        (quiet-quil
          (fn []
            (should= {:scroll 0 :shown nil :hover nil}
                     ((:setup @opts))))))))

  (it "starts the detail window once, and still clears the starting flag on error"
    (let [started (atom 0)
          pinned (atom [])]
      (with-redefs [uml-viewer.adapters.sketch/later! (fn [f] (f))
                    uml-viewer.adapters.sketch/start-detail-window! (fn [] (swap! started inc))
                    uml-viewer.adapters.sketch/pin-card! (fn [on?] (swap! pinned conj on?))]
        (call 'ensure-detail-window! {:class {:id :a :name "A"}})
        (should= 1 @started)
        (should= [true] @pinned)
        (should= {:id :a :name "A"} (get-in @sketch/!bridge [:model :class]))
        (should-not (:starting @sketch/!bridge))
        (reset! sketch/!bridge (assoc @sketch/!bridge :applet (->Finished false)))
        (call 'ensure-detail-window! {:class {:id :b}})
        (should= 1 @started)
        (reset! sketch/!bridge (assoc (empty-bridge) :starting true))
        (call 'ensure-detail-window! {:class {:id :c}})
        (should= 1 @started))
      (reset! sketch/!bridge (empty-bridge))
      (with-redefs [uml-viewer.adapters.sketch/later! (fn [f] (f))
                    uml-viewer.adapters.sketch/start-detail-window! (fn [] (throw (Exception. "boom")))
                    uml-viewer.adapters.sketch/pin-card! (fn [_])]
        (should-throw Exception (call 'ensure-detail-window! {:class {:id :a}}))
        (should-not (:starting @sketch/!bridge)))))

  (it "starts waiting instead of loading the EDN"
    (quiet-quil
      (fn []
        (with-redefs [document/waiting-state (fn [p] {:path p :waiting true})]
          (should= {:path "doc.edn" :waiting true} (sketch/setup "doc.edn"))))))

  (it "loads the EDN immediately on restart"
    (quiet-quil
      (fn []
        (with-redefs [document/restart-state (fn [p] {:path p :loaded true})]
          (should= {:path "doc.edn" :loaded true} (sketch/setup "doc.edn" true))))))

  (it "quits for restart when mail says so"
    (let [quit (atom 0)
          continued (atom 0)
          saved (atom nil)]
      (with-redefs [document/maybe-reload identity
                    document/poll-mail (fn [s] (assoc s :quit-for-restart true))
                    document/save-session! (fn [s] (reset! saved s) s)
                    uml-viewer.adapters.sketch/quit-for-restart! (fn [] (swap! quit inc))
                    uml-viewer.adapters.sketch/apply-bridge-flags (fn [s] (swap! continued inc) s)
                    uml-viewer.adapters.sketch/halt-vm! (fn [])]
        (should= true (:quit-for-restart (sketch/update-state (state))))
        (should= 1 @quit)
        (should= 0 @continued)
        (should @saved))))

  (it "does not quit for restart on ordinary updates"
    (let [quit (atom 0)
          continued (atom 0)]
      (with-redefs [document/maybe-reload identity
                    document/poll-mail identity
                    uml-viewer.adapters.sketch/quit-for-restart! (fn [] (swap! quit inc))
                    uml-viewer.adapters.sketch/apply-bridge-flags (fn [s] (swap! continued inc) s)
                    uml-viewer.adapters.sketch/halt-vm! (fn [])]
        (sketch/update-state (state))
        (should= 0 @quit)
        (should= 1 @continued))))

  (it "applies closed and pick flags and keeps the detail model in sync"
    (let [s (assoc (state) :detail-id :a)
          closed (atom false)]
      (with-redefs [document/maybe-reload identity
                    document/poll-mail identity
                    uml-viewer.adapters.sketch/quit-for-restart! (fn [])
                    uml-viewer.adapters.sketch/halt-vm! (fn [])
                    uml-viewer.adapters.sketch/close-detail-window! (fn [] (reset! closed true))]
        (reset! sketch/!bridge (assoc (empty-bridge) :closed? true))
        (let [next (sketch/update-state s)]
          (should-not (:detail-id next))
          (should @closed))
        (reset! closed false)
        (reset! sketch/!bridge (assoc (empty-bridge) :pick :b))
        (let [next (sketch/update-state (assoc s :detail-id :a))]
          (should= :b (:detail-id next))
          (should= :b (get-in @sketch/!bridge [:model :class :id]))
          (should-not @closed))
        (reset! sketch/!bridge (empty-bridge))
        (sketch/update-state (dissoc s :detail-id))
        (should @closed)
        (reset! closed false)
        (reset! sketch/!bridge (empty-bridge))
        (sketch/update-state (assoc s :detail-id :missing))
        (should-not @closed)
        (should-be-nil (:model @sketch/!bridge)))))

  (it "updates an open card when overlay metrics change"
    (let [s (assoc (state) :detail-id :a)
          next-scene (update (:scene s) :classes
                             (fn [cs]
                               (mapv #(if (= :a (:id %))
                                        (assoc % :crap {:mu 9.0 :max 9.0 :sigma 0.0}
                                                 :killed 1 :survived 0)
                                        %)
                                     cs)))]
      (with-redefs [document/maybe-reload (fn [_] (assoc s :scene next-scene))
                    document/poll-mail identity
                    uml-viewer.adapters.sketch/quit-for-restart! (fn [])
                    uml-viewer.adapters.sketch/halt-vm! (fn [])
                    uml-viewer.adapters.sketch/close-detail-window! (fn [])]
        (reset! sketch/!bridge (assoc (empty-bridge) :model (a-model)))
        (sketch/update-state s)
        (should= {:mu 9.0 :max 9.0 :sigma 0.0}
                 (get-in @sketch/!bridge [:model :class :crap]))
        (should= 1 (get-in @sketch/!bridge [:model :class :killed]))))))

(describe "sketch main window"
  (before (reset! sketch/!bridge (empty-bridge)))

  (it "opens a class card on a click of a dependency port"
    (let [s (state)
          ensured (atom nil)]
      (with-redefs [uml-viewer.adapters.sketch/ensure-detail-window! (fn [m] (reset! ensured m))
                    uml-viewer.adapters.sketch/pin-card! (fn [_])
                    events/on-press (fn [state _ _]
                                      (assoc state :selected {:kind :port
                                                              :id :b
                                                              :parent :a
                                                              :dir :out}))]
        (let [next (call 'on-main-press s {:x 10 :y 10 :count 1})]
          (should= :b (:detail-id next))
          (should= :b (get-in @ensured [:class :id]))))))

  (it "opens a component card on a single click of a child row"
    (let [s (state)
          ensured (atom nil)]
      (with-redefs [uml-viewer.adapters.sketch/ensure-detail-window! (fn [m] (reset! ensured m))
                    uml-viewer.adapters.sketch/pin-card! (fn [_])
                    events/on-press (fn [state _ _]
                                      (assoc state :selected {:kind :child
                                                              :id :a
                                                              :parent :engine}))]
        (let [next (call 'on-main-press s {:x 10 :y 10 :count 1})]
          (should= :a (:detail-id next))
          (should= :a (get-in @ensured [:class :id]))))))

  (it "drills the layer on double-click of a child or a layer box"
    (let [s (assoc (state) :doc {:hierarchical true :classes [] :edges []} :focus [])
          drilled (atom nil)]
      (with-redefs [uml-viewer.adapters.sketch/ensure-detail-window! (fn [_])
                    uml-viewer.adapters.sketch/pin-card! (fn [_])
                    events/drill (fn [state id] (reset! drilled id) (assoc state :focus [id]))
                    events/on-press (fn [state _ _]
                                      (assoc state :selected {:kind :child
                                                              :id :engine.layout
                                                              :parent :engine
                                                              :drill? true}))]
        (call 'on-main-press s {:x 10 :y 10 :count 2})
        (should= :engine @drilled))))

  (it "opens the detail card on double-click of a class and unpins otherwise"
    (let [s (state)
          [x y] (class-xy s :a)
          ensured (atom nil)
          pinned (atom [])]
      (with-redefs [uml-viewer.adapters.sketch/ensure-detail-window! (fn [m] (reset! ensured m))
                    uml-viewer.adapters.sketch/pin-card! (fn [on?] (swap! pinned conj on?))]
        (let [single (call 'on-main-press s {:x x :y y :count 1})]
          (should= :class (get-in single [:selected :kind]))
          (should= :a (get-in single [:selected :id]))
          (should-be-nil (:detail-id single))
          (should-be-nil @ensured)
          (should= [] @pinned))
        (let [next (call 'on-main-press s {:x x :y y :count 2})]
          (should= :class (get-in next [:selected :kind]))
          (should= :a (get-in next [:selected :id]))
          (should= :a (:detail-id next))
          (should= :a (get-in @ensured [:class :id]))
          (should= [true] @pinned))
        (reset! ensured nil)
        (with-redefs [detail/model (fn [_ _] nil)
                      uml-viewer.adapters.sketch/ensure-detail-window! (fn [m] (reset! ensured m))
                      uml-viewer.adapters.sketch/pin-card! (fn [on?] (swap! pinned conj on?))]
          (call 'on-main-press s {:x x :y y :count 2})
          (should-be-nil @ensured)
          (should= [true true] @pinned))
        (call 'on-main-press s {:x 0 :y 0})
        (should= [true true false] @pinned))))

  (it "right-clicks a class for the element menu instead of opening the card"
    (let [shown (atom nil)
          s (state)
          [x y] (class-xy s :a)]
      (with-redefs [uml-viewer.adapters.sketch/popup-element-menu!
                    (fn [_ _ _ _ sel] (reset! shown sel) nil)
                    uml-viewer.adapters.sketch/ensure-detail-window! (fn [_])
                    uml-viewer.adapters.sketch/pin-card! (fn [_])]
        (let [next (call 'on-main-press s {:x x :y y :count 1 :button :right})]
          (should= :a (:id @shown))
          (should= :class (:kind @shown))
          (should-be-nil (:detail-id next))))))

  (it "names a class target and a component target"
    (let [s (state)]
      (should= :class (:kind (call 'element-target s {:kind :class :id :a})))
      (should= :a (:id (call 'element-target s {:kind :class :id :a})))
      (should= :component
               (:kind (call 'element-target s {:kind :class :id :a :drill? true})))))

  (it "anchors the proposal menu at the AWT mouse-down"
    (let [panel (javax.swing.JPanel.)
          awt (java.awt.event.MouseEvent.
                panel java.awt.event.MouseEvent/MOUSE_PRESSED
                0 0 41 17 1 false)
          pe (MouseEvent. awt 0 MouseEvent/PRESS 0 100 200 3 1)
          anchor (call 'popup-anchor pe 100 200)]
      (should= panel (:invoker anchor))
      (should= 41 (:x anchor))
      (should= 17 (:y anchor))))

  (it "treats shift on the wheel event or the current applet as horizontal pan"
    (let [got (atom nil)
          wheel (MouseEvent. nil 0 MouseEvent/WHEEL Event/SHIFT 0 0 0 1)
          no-shift (MouseEvent. nil 0 MouseEvent/WHEEL 0 0 0 0 1)
          shift-ev (MouseEvent. nil 0 MouseEvent/WHEEL Event/SHIFT 0 0 0 1)]
      (with-redefs [q/width (fn [] 100)
                    q/height (fn [] 200)
                    events/on-scroll (fn [state event opts]
                                       (reset! got {:event event :opts opts})
                                       state)
                    applet/current-applet (fn [] (throw (Exception. "none")))]
        (call 'on-main-wheel :s wheel)
        (should= true (get-in @got [:opts :horizontal?]))
        (should= 100 (get-in @got [:opts :window-w]))
        (should= 200 (get-in @got [:opts :window-h]))
        (call 'on-main-wheel :s no-shift)
        (should-not (get-in @got [:opts :horizontal?]))
        (call 'on-main-wheel :s {:count 1})
        (should-not (get-in @got [:opts :horizontal?])))
      (with-redefs [q/width (fn [] 100)
                    q/height (fn [] 200)
                    events/on-scroll (fn [state event opts]
                                       (reset! got opts)
                                       state)
                    applet/current-applet
                    (fn [] (->FakeCurrent shift-ev))]
        (should (call 'applet-shift?))
        (call 'on-main-wheel :s {:count 1})
        (should (:horizontal? @got)))
      (with-redefs [applet/current-applet (fn [] (->FakeCurrent :not-a-mouse))]
        (should-not (call 'applet-shift?)))))

  (it "closes the detail window and exits the process"
    (let [closed (atom false)
          exited (atom false)]
      (with-redefs [uml-viewer.adapters.sketch/close-detail-window! (fn [] (reset! closed true))
                    uml-viewer.adapters.sketch/exit-app! (fn [] (reset! exited true))]
        (should= :s (call 'on-main-close :s))
        (should @closed)
        (should @exited))))

  (it "starts the main sketch and wires the handlers"
    (let [opts (atom nil)
          moved (atom nil)
          keyed (atom nil)]
      (with-redefs [q/sketch (fn [& args]
                               (reset! opts (apply hash-map args))
                               :main-applet)
                    uml-viewer.adapters.sketch/open-in-terminal! (fn [& _])]
        (should= :main-applet (sketch/start! "doc.edn" :source-impl))
        (should= "UML viewer" (:title @opts))
        (should= [sketch/window-width sketch/window-height] (:size @opts))
        (should= [:resizable] (:features @opts))
        (quiet-quil
          (fn []
            (with-redefs [document/waiting-state (fn [p] {:path p :waiting true})
                          events/on-move (fn [state x y]
                                           (reset! moved [state x y])
                                           state)
                          events/on-key (fn [state k dims]
                                          (reset! keyed [state k dims])
                                          state)
                          uml-viewer.adapters.sketch/on-main-close (fn [state] state)
                          uml-viewer.adapters.sketch/exit-app! (fn [])]
              (should= {:path "doc.edn" :waiting true} ((:setup @opts)))
              ((:mouse-moved @opts) :s {:x 4 :y 5})
              (should= [:s 4 5] @moved)
              ((:key-pressed @opts) :s {:key :r})
              (should= [:s :r {:window-w 1500 :window-h 920 :view-w 1220
                               :control? false :key-code nil :raw-key nil}] @keyed)
              ((:key-pressed @opts) :s {:key :+ :modifiers #{:control}})
              (should= [:s :+ {:window-w 1500 :window-h 920 :view-w 1220
                               :control? true :key-code nil :raw-key nil}] @keyed)
              ((:key-pressed @opts) :s {:key :unknown-key :key-code 45
                                        :raw-key \- :modifiers #{:control}})
              (should= [:s :unknown-key {:window-w 1500 :window-h 920 :view-w 1220
                                         :control? true :key-code 45 :raw-key \-}]
                       @keyed)
              (should= :s ((:on-close @opts) :s))
              (should (fn? (:key-released @opts)))
              (should= :s ((:key-released @opts) :s {:key :esc}))))))))

(describe "grok session"
  (it "mails discussion context for the real diagram and a proposal"
    (let [got (atom nil)]
      (with-redefs [sketch/request-agent! (fn [_ op extra]
                                            (reset! got {:op op :extra extra})
                                            {:cmd extra :woke? true})]
        (sketch/mail-context! {:path "examples/library.edn" :doc {}})
        (should= :context (:op @got))
        (should= :real (get-in @got [:extra :context]))
        (sketch/mail-context!
          {:path "examples/library.edn"
           :proposal-id :ccp
           :doc {:proposals [{:id :ccp :name "CCP"}]}})
        (should= :proposal (get-in @got [:extra :context]))
        (should= :ccp (get-in @got [:extra :proposal-id]))
        (should= "CCP" (get-in @got [:extra :name])))))

  (it "queues element mail and wakes the companion"
    (let [root (str (System/getProperty "java.io.tmpdir")
                    "/uv-mail-" (System/nanoTime))
          woke (atom false)]
      (with-redefs [sketch/notify-agent! (fn [& _] (reset! woke true) true)]
        (let [{:keys [cmd woke?]}
              (sketch/request-agent! root :refresh-crap {:target {:id :a}})]
          (should= :refresh-crap (:op cmd))
          (should= {:id :a} (:target cmd))
          (should woke?)
          (should @woke)))))

  (it "gives each project its own tmux session"
    (let [a (sketch/session-id "/tmp/proj-a")
          b (sketch/session-id "/tmp/proj-b")]
      (should (re-find #"^uml-viewer-proj-a-" a))
      (should (re-find #"^uml-viewer-proj-b-" b))
      (should-not= a b)
      (should= a (sketch/session-id "/tmp/proj-a"))
      (should-not (re-find #"uml-viewer-grok" a))))

  (it "names a tmux session and attaches Terminal to it"
    (let [cwd "/tmp/proj"
          sid (sketch/session-id cwd)
          args (sketch/new-session-args cwd)
          script (sketch/osascript (sketch/attach-command sid) sid)
          [br bg bb] (sketch/rgb-16 draw/bg)
          [gr gg gb] (sketch/rgb-16 draw/gold)]
      (should (some #{sid} args))
      (should= ["kill-session" "-t" sid] (sketch/kill-session-args sid))
      (should (some #{"new-session"} args))
      (should (some #{"--yolo"} args))
      (should (some #{"--rules"} args))
      (should (some #{sketch/standing-rules} args))
      (should (some #{sketch/launch-prompt} args))
      (should (re-find #"On launch" sketch/standing-rules))
      (should (re-find #"invent" sketch/standing-rules))
      (should (re-find #":proposals" sketch/standing-rules))
      (should (re-find #"to-agent.edn" sketch/standing-rules))
      (should (re-find #":refresh-crap" sketch/standing-rules))
      (should (re-find #":refresh-mutate-all" sketch/standing-rules))
      (should (re-find #":omit" sketch/standing-rules))
      (should (re-find #":context" sketch/standing-rules))
      (should (re-find #":queue" sketch/standing-rules))
      (should (re-find #":quit-for-restart" sketch/standing-rules))
      (should (re-find #"\./uml --restart" sketch/standing-rules))
      (should (re-find #"kills only this companion" sketch/standing-rules))
      (should (re-find #"respawns" sketch/standing-rules))
      (should-not (re-find #":reload" sketch/standing-rules))
      (should (some #{"GROK_THEME=terminal"} args))
      (should-not (some #{"status"} args))
      (should (re-find (re-pattern (str "tmux attach -t " sid)) (sketch/attach-command sid)))
      (should (re-find #"tell application \"Terminal\"" script))
      (should-not (re-find #"activate" script))
      (should (re-find #"^tell application \"Terminal\"\nlaunch" script))
      (should (re-find #"AXRaise" script))
      (should (re-find (re-pattern (str "custom title of grokTab to \"" sid "\"")) script))
      (should-not (re-find #"custom title of grokTab to \"Grok\"" script))
      (should (re-find #"return winID" script))
      (should (re-find (re-pattern (str "background color of grokTab to \\{" br ", " bg ", " bb "\\}"))
                       script))
      (should (re-find (re-pattern (str "cursor color of grokTab to \\{" gr ", " gg ", " gb "\\}"))
                       script))))

  (it "closes only this viewer's Terminal window by id"
    (let [script (sketch/close-terminal-script "42")]
      (should (re-find #"exists process \"Terminal\"" script))
      (should (re-find #"whose id is 42" script))
      (should-not (re-find #"custom title" script))
      (should-not (re-find #"Grok" script))
      (should-not (re-find #"repeat with w" script))))

  (it "does not scan other Terminal windows when id is missing"
    (let [script (sketch/close-terminal-script)]
      (should-not (re-find #"close" script))
      (should-not (re-find #"Grok" script))))

  (it "wakes Grok with text, a pause, then Enter as separate keys"
    (let [sid "uml-viewer-proj-abc"
          steps (sketch/notify-steps sid)]
      (should= ["send-keys" "-t" sid "-l" sketch/wake-message]
               (first steps))
      (should= [:sleep 150] (second steps))
      (should= ["send-keys" "-t" sid "C-m"] (nth steps 2))
      (should= [:sleep 50] (nth steps 3))
      (should= ["send-keys" "-t" sid "C-j"] (nth steps 4))))

  (it "scales theme RGB into Terminal's 16-bit colors"
    (should= [5654 7196 8224] (sketch/rgb-16 [22 28 32]))
    (should= [0 65535 257] (sketch/rgb-16 [0 255 1])))

  (it "skips a new agent on restart"
    (let [opened (atom 0)
          remembered (atom 0)]
      (with-redefs [q/sketch (fn [& _] :applet)
                    uml-viewer.adapters.sketch/open-in-terminal! (fn [& _] (swap! opened inc))
                    uml-viewer.adapters.sketch/remember-companion! (fn [_] (swap! remembered inc))]
        (should= :applet (sketch/start! "doc.edn" :src true))
        (should= 0 @opened)
        (should= 1 @remembered)
        (sketch/start! "doc.edn" :src)
        (should= 1 @opened)
        (should= 1 @remembered))))

  (it "wakes the legacy grok session when the per-project session is missing"
    (let [root (str (System/getProperty "java.io.tmpdir")
                    "/uv-wake-" (System/nanoTime))
          calls (atom [])]
      (.mkdirs (java.io.File. root))
      (reset! sketch/!session-name nil)
      (with-redefs [uml-viewer.adapters.sketch/tmux!
                    (fn [& args]
                      (swap! calls conj (vec args))
                      (cond
                        (not= "has-session" (first args)) 0
                        (= sketch/legacy-session (last args)) 0
                        :else 1))]
        (should (sketch/notify-agent! root))
        (should (some #(= ["send-keys" "-t" sketch/legacy-session "-l" sketch/wake-message] %)
                      @calls))
        (should= sketch/legacy-session @sketch/!session-name))))

  (it "wakes the companion session instead of the legacy name when it is live"
    (let [root (str (System/getProperty "java.io.tmpdir")
                    "/uv-wake2-" (System/nanoTime))
          calls (atom [])]
      (.mkdirs (java.io.File. root))
      (mailbox/write-companion! root {:session "uml-viewer-mine-abc"})
      (reset! sketch/!session-name nil)
      (with-redefs [uml-viewer.adapters.sketch/tmux!
                    (fn [& args]
                      (swap! calls conj (vec args))
                      (cond
                        (not= "has-session" (first args)) 0
                        (= "uml-viewer-mine-abc" (last args)) 0
                        :else 1))]
        (should (sketch/notify-agent! root))
        (should (some #(= ["send-keys" "-t" "uml-viewer-mine-abc" "-l" sketch/wake-message] %)
                      @calls))
        (should-not (some #(and (= "send-keys" (first %))
                                (some #{sketch/legacy-session} %))
                          @calls)))))

  (it "on restart binds the live tmux session so later mail can wake it"
    (let [root (str (System/getProperty "java.io.tmpdir")
                    "/uv-remember-" (System/nanoTime))]
      (.mkdirs (java.io.File. root))
      (reset! sketch/!session-name nil)
      (reset! sketch/!terminal-window-id nil)
      (with-redefs [uml-viewer.adapters.sketch/tmux!
                    (fn [& args]
                      (if (and (= "has-session" (first args))
                               (= sketch/legacy-session (last args)))
                        0
                        1))]
        (should= sketch/legacy-session (call 'remember-companion! root))
        (should= sketch/legacy-session @sketch/!session-name)
        (should= sketch/legacy-session
                 (:session (mailbox/read-companion root))))))

  (it "kills only this project's tmux session and its Terminal window"
    (let [root (str (System/getProperty "java.io.tmpdir")
                    "/uv-comp-" (System/nanoTime))
          tmux-calls (atom [])
          scripts (atom [])]
      (mailbox/write-companion! root {:session "uml-viewer-mine-abc" :window-id "99"})
      (reset! sketch/!terminal-window-id "88")
      (reset! sketch/!session-name "other-session")
      (with-redefs [uml-viewer.adapters.sketch/tmux! (fn [& args] (swap! tmux-calls conj (vec args)) 0)
                    uml-viewer.adapters.sketch/run-osascript (fn [s] (swap! scripts conj s) "")]
        (sketch/shutdown-children! root)
        (should (some #(= ["kill-session" "-t" "uml-viewer-mine-abc"] %) @tmux-calls))
        (should (some #(= ["set-hook" "-t" "uml-viewer-mine-abc" "-u" "pane-died"] %) @tmux-calls))
        (should-not (some #(some #{"uml-viewer-grok"} %) @tmux-calls))
        (should-not (some #(some #{"other-session"} %) @tmux-calls))
        (should= 1 (count @scripts))
        (should (re-find #"whose id is 99" (first @scripts)))
        (should-not (re-find #"Grok" (first @scripts)))
        (should-be-nil @sketch/!terminal-window-id)
        (should-be-nil @sketch/!session-name))))

  (it "starts a per-project session, arms respawn, and records companion.edn"
    (let [root (str (System/getProperty "java.io.tmpdir")
                    "/uv-open-" (System/nanoTime))
          calls (atom [])]
      (.mkdirs (java.io.File. root))
      (mailbox/write-companion! root {:session "uml-viewer-old-ffff" :window-id "1"})
      (with-redefs [uml-viewer.adapters.sketch/tmux! (fn [& args] (swap! calls conj (vec args)) 0)
                    uml-viewer.adapters.sketch/run-osascript (fn [_] "1234")]
        (let [sid (sketch/session-id root)
              out (sketch/open-in-terminal! root)
              flat (mapcat identity @calls)]
          (should= sid (:session out))
          (should= "1234" (:window-id out))
          (should= sid (:session (mailbox/read-companion root)))
          (should= "1234" (:window-id (mailbox/read-companion root)))
          (should (some #(= ["kill-session" "-t" "uml-viewer-old-ffff"] %) @calls))
          (should (some #(= ["kill-session" "-t" sid] %) @calls))
          (should (some #(= "new-session" (first %)) @calls))
          (should (some #(= ["set-option" "-p" "-t" (str sid ":0.0") "remain-on-exit" "on"] %) @calls))
          (should (some #(= ["set-hook" "-t" sid "pane-died" "respawn-pane -k"] %) @calls))
          (should-not (some #{"uml-viewer-grok"} flat))))))))

(describe "detail card pointer"
  (before (reset! sketch/!bridge (empty-bridge)))

  (it "clamps detail scroll to the card"
    (let [model (a-model)]
      (reset! sketch/!bridge {:model model})
      (with-redefs [detail/height 10]
        (let [max-y (max 0 (- (detail/content-h (detail/rows model)) 10))
              capped (:scroll (call 'detail-scroll {:scroll 0} 100000))]
          (should= max-y capped)
          (should (pos? max-y))
          (should= max-y (:scroll (call 'detail-scroll {:scroll max-y} 3)))
          (should= 0 (:scroll (call 'detail-scroll {:scroll 4} {:count -100})))))))

  (it "hovers the module row and defaults a missing scroll"
    (let [model (a-model)
          rows (detail/rows model)
          go (first (filter :op-name rows))
          mod (first (filter :module rows))]
      (reset! sketch/!bridge {:model model})
      (should= :module (:hover (call 'detail-mouse-moved {:scroll 0}
                                     {:y (+ (:y mod) 1)})))
      (should= "go" (:hover (call 'detail-mouse-moved {}
                                  {:y (+ (:y go) 1)})))
      (should= (detail/member-ident model)
               (let [opened (atom nil)]
                 (with-redefs [source-window/open-member-window!
                               (fn [_ ident] (reset! opened ident))]
                   (call 'detail-mouse-pressed {} {:y (+ (:y mod) 1)})
                   @opened))))))

(describe "popup anchor and proposal ops"
  (before (reset! sketch/!bridge (empty-bridge)))

  (it "reads a processing event, the applet event, or nothing"
    (let [pe (MouseEvent. nil 0 MouseEvent/PRESS 0 1 2 1 1)]
      (should= pe (call 'processing-mouse pe))
      (with-redefs [applet/current-applet (fn [] (->FakeCurrent pe))]
        (should= pe (call 'processing-mouse {:x 1})))
      (with-redefs [applet/current-applet (fn [] (->FakeCurrent nil))]
        (should-be-nil (call 'processing-mouse {:x 1})))
      (with-redefs [applet/current-applet (fn [] nil)]
        (should-be-nil (call 'processing-mouse {})))
      (with-redefs [applet/current-applet (fn [] (throw (Exception. "gone")))]
        (should-be-nil (call 'processing-mouse {})))))

  (it "anchors on the awt event, a container, a component, or nothing"
    (let [panel (javax.swing.JPanel.)
          canvas (java.awt.Canvas.)
          awt (java.awt.event.MouseEvent.
                panel java.awt.event.MouseEvent/MOUSE_PRESSED
                0 0 41 17 1 false)]
      (should= panel (call 'popup-invoker awt nil))
      (should= panel (call 'popup-invoker nil panel))
      (should-be-nil (call 'popup-invoker nil :native))
      (should-be-nil (call 'popup-invoker nil nil))
      (should= {:invoker panel :x 41 :y 17} (call 'anchor-at awt panel 0 0))
      (let [in (.getInsets panel)
            anchor (call 'anchor-at nil panel 10 20)]
        (should= panel (:invoker anchor))
        (should= (+ 10 (.left in)) (:x anchor))
        (should= (+ 20 (.top in)) (:y anchor)))
      (should= {:invoker canvas :x 8 :y 9} (call 'anchor-at nil canvas 8 9))
      (should= {:invoker nil :x 4 :y 5} (call 'anchor-at nil nil 4 5))
      (with-redefs [applet/current-applet
                    (fn [] (->FakeApplet false (->FakeSurface panel nil)))]
        (let [anchor (call 'popup-anchor {} 10 20)
              in (.getInsets panel)]
          (should= panel (:invoker anchor))
          (should= (+ 10 (.left in)) (:x anchor))
          (should= (+ 20 (.top in)) (:y anchor))))
      (with-redefs [applet/current-applet
                    (fn [] (->FakeApplet false (->FakeSurface canvas nil)))]
        (should= {:invoker canvas :x 8 :y 9} (call 'popup-anchor {} 8 9)))
      (with-redefs [applet/current-applet (fn [] nil)]
        (should= {:invoker nil :x 4 :y 5} (call 'popup-anchor {} 4 5)))
      (with-redefs [applet/current-applet (fn [] (throw (Exception. "gone")))]
        (should= {:invoker nil :x 1 :y 2} (call 'popup-anchor {} 1 2)))
      (with-redefs [applet/current-applet
                    (fn [] (->FakeApplet false (->FakeSurface panel nil)))]
        (should= panel (call 'popup-native)))))

  (it "renames, deletes, or ignores a proposal op"
    (should-be-nil (call 'rename-op :ccp nil))
    (should-be-nil (call 'rename-op :ccp ""))
    (should-be-nil (call 'rename-op :ccp "   "))
    (should= {:op :rename :id :ccp :name "New"}
             (call 'rename-op :ccp "  New  "))
    (with-redefs [uml-viewer.adapters.sketch/ask-proposal-name (fn [_] "  New  ")]
      (call 'choose-rename! :ccp "Old")
      (should= {:op :rename :id :ccp :name "New"} (:proposal-op @sketch/!bridge)))
    (reset! sketch/!bridge (empty-bridge))
    (with-redefs [uml-viewer.adapters.sketch/ask-proposal-name (fn [_] nil)]
      (call 'choose-rename! :ccp "Old")
      (should-be-nil (:proposal-op @sketch/!bridge)))
    (call 'choose-delete! :ccp)
    (should= {:op :delete :id :ccp} (:proposal-op @sketch/!bridge))
    (let [ops (atom [])]
      (with-redefs [events/rename-proposal
                    (fn [s id name]
                      (swap! ops conj [:rename id name])
                      (assoc s :renamed [id name]))
                    events/delete-proposal
                    (fn [s id]
                      (swap! ops conj [:delete id])
                      (assoc s :deleted id))]
        (should= [:a "N"] (:renamed (call 'apply-proposal-op {} {:op :rename :id :a :name "N"})))
        (should= :a (:deleted (call 'apply-proposal-op {} {:op :delete :id :a})))
        (should= {:kept true} (call 'apply-proposal-op {:kept true} {:op :nope}))
        (should= [[:rename :a "N"] [:delete :a]] @ops))))

  (it "builds the proposal menu and shows it at the anchor"
    (let [shown (atom nil)]
      (with-redefs [uml-viewer.adapters.sketch/later! (fn [f] (f))
                    uml-viewer.adapters.sketch/show-popup!
                    (fn [menu invoker x y]
                      (let [items (vec (.getSubElements menu))
                            text #(.getText ^javax.swing.JMenuItem %)]
                        (reset! shown {:labels (mapv text items)
                                       :invoker invoker :x x :y y})
                        (with-redefs [uml-viewer.adapters.sketch/ask-proposal-name (fn [_] "Zed")]
                          (.doClick ^javax.swing.JMenuItem (first items)))
                        (.doClick ^javax.swing.JMenuItem (second items))))
                    applet/current-applet (fn [] nil)]
        (call 'popup-proposal-menu! {} 12 34 :ccp "CCP")
        (should= ["Rename" "Delete"] (:labels @shown))
        (should-be-nil (:invoker @shown))
        (should= 12 (:x @shown))
        (should= 34 (:y @shown))
        (should= {:op :delete :id :ccp} (:proposal-op @sketch/!bridge))))))

(describe "main window press"
  (before (reset! sketch/!bridge (empty-bridge)))

  (it "requests regen from the inspector button"
    (quiet-quil
      (fn []
        (let [r (layout/regen-button 1500 920)
              woke (atom nil)]
          (with-redefs [uml-viewer.adapters.sketch/request-regen!
                        (fn [root] (reset! woke root) {:woke? true})]
            (should= "Regen requested."
                     (:mail-status (call 'on-main-press (state) (at r nil)))))
          (with-redefs [uml-viewer.adapters.sketch/request-regen!
                        (fn [_root] {:woke? false})]
            (should= "Regen queued; Grok session not attached."
                     (:mail-status (call 'on-main-press (state) (at r nil)))))
          (should (string? @woke))))))

  (it "handles inspector clicks and a proposal right-click"
    (quiet-quil
      (fn []
        (let [s (assoc (state) :doc {:proposals [{:id :ccp :name "CCP"}]})
              mailed (atom [])
              menus (atom [])
              w 1500]
          (with-redefs [uml-viewer.adapters.sketch/mail-context! (fn [st] (swap! mailed conj st) st)
                        uml-viewer.adapters.sketch/popup-proposal-menu!
                        (fn [_e x y id pname] (swap! menus conj [x y id pname]))]
            (let [next (call 'on-main-press s (at (layout/proposal-row-rect w 0) :right))]
              (should= [[(+ (:x (layout/proposal-row-rect w 0)) 1)
                         (+ (:y (layout/proposal-row-rect w 0)) 1)
                         :ccp "CCP"]]
                       @menus)
              (should= s next)
              (should= [] @mailed))
            (let [next (call 'on-main-press s (at (layout/proposal-row-rect w 0) nil))]
              (should= :ccp (:proposal-id next))
              (should= :ccp (:proposal-id (last @mailed))))
            (let [next (call 'on-main-press (assoc s :proposal-id :ccp)
                             (at (layout/real-diagram-rect w) nil))]
              (should-be-nil (:proposal-id next))
              (should (some #(nil? (:proposal-id %)) @mailed)))
            (reset! menus [])
            (call 'on-main-press s (at (layout/real-diagram-rect w) :right))
            (should= [] @menus)
            (let [before (count @mailed)
                  next (call 'on-main-press s (at (layout/declutter-rect w 1) nil))]
              (should= :arrows (:declutter next))
              (should= before (count @mailed)))
            (let [bare (assoc (state) :doc nil)
                  before (count @mailed)]
              (call 'on-main-press bare (at (layout/new-proposal-rect w 0) nil))
              (should= (inc before) (count @mailed)))
            (let [next (call 'on-main-press s {:x 1400 :y 0 :count 1})]
              (should= s next)))))))

  (it "right-clicks a child, a proposal package, or anything else"
    (let [shown (atom [])
          pinned (atom [])
          s (state)]
      (with-redefs [uml-viewer.adapters.sketch/popup-element-menu!
                    (fn [_ _ _ _ sel] (swap! shown conj sel))
                    uml-viewer.adapters.sketch/pin-card! (fn [on?] (swap! pinned conj on?))
                    uml-viewer.adapters.sketch/ensure-detail-window! (fn [_])]
        (with-redefs [events/on-press
                      (fn [state _ _]
                        (assoc state :selected {:kind :child :id :child :parent :engine}))]
          (call 'on-main-press s {:x 10 :y 10 :count 1 :button :right})
          (should= :child (:kind (last @shown))))
        (with-redefs [events/on-press
                      (fn [state _ _]
                        (assoc state :selected {:kind :package :id :proposal.ccp}))]
          (call 'on-main-press s {:x 10 :y 10 :count 1 :button :right})
          (should= :proposal.ccp (:id (last @shown))))
        (with-redefs [events/on-press
                      (fn [state _ _]
                        (assoc state :selected {:kind :package :id :p}))]
          (call 'on-main-press s {:x 10 :y 10 :count 1 :button :right})
          (should= [false] @pinned)
          (should= 2 (count @shown)))))))

