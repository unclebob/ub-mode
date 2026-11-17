(ns uml-viewer.application.events
  (:require [clojure.string :as str]
            [uml-viewer.application.detail :as detail]
            [uml-viewer.application.document :as document]
            [uml-viewer.domain.hierarchy :as hierarchy]
            [uml-viewer.engine.hit :as hit]
            [uml-viewer.engine.layout :as layout]
            [uml-viewer.application.overlay :as overlay]
            [uml-viewer.domain.policy :as policy]))

(defn- last-seg [id]
  (keyword (last (str/split (name id) #"\."))))

(defn- view-opts [state]
  {:proposal-id (:proposal-id state)
   :declutter (or (:declutter state) :full)
   :open-layer (:open-layer state)})

(defn- rebuild [state]
  (if (and (:doc state) (:hierarchical (:doc state)))
    (assoc state
      :scene (document/compile-view (:doc state)
                                    (overlay/metrics-root (:path state))
                                    (or (:focus state) [])
                                    (view-opts state))
      :cam-x 0
      :cam-y 0
      :selected nil)
    state))

(defn show-proposal
  "Show named proposal `id` at the root. Nil `id` is the namespace tree."
  [state id]
  (rebuild (assoc state :proposal-id id :proposal (boolean id) :focus []
             :open-layer nil)))

(defn cycle-declutter
  [state]
  (rebuild (assoc state :declutter (hierarchy/next-declutter (:declutter state)))))

(defn- persist-doc [state doc]
  (let [path (:path state)
        doc (document/write-proposals! path doc)]
    (rebuild (assoc state
               :doc doc
               :mtime (if path (.lastModified (java.io.File. path)) 0)))))

(defn- with-proposals [doc ps]
  (assoc doc :proposals (vec ps)))

(defn add-proposal
  "Append an empty proposal named with a timestamp and show it."
  [state]
  (if (nil? (:doc state))
    state
    (let [p {:id (keyword (str "p-" (System/currentTimeMillis)))
             :name (policy/timestamp-name)
             :layers []
             :notice policy/proposal-notice}
          doc (with-proposals (:doc state)
                (conj (vec (hierarchy/named-proposals (:doc state))) p))]
      (persist-doc (assoc state :proposal-id (:id p) :proposal true :focus [])
                   doc))))

(defn delete-proposal
  [state id]
  (if (or (nil? id) (nil? (:doc state)))
    state
    (let [doc (with-proposals (:doc state)
                (remove #(= id (:id %)) (hierarchy/named-proposals (:doc state))))
          state (cond-> state
                  (= id (:proposal-id state))
                  (assoc :proposal-id nil :proposal false))]
      (persist-doc state doc))))

(defn rename-proposal
  [state id new-name]
  (let [n (and new-name (not (str/blank? (str new-name))) (str new-name))]
    (if (or (nil? id) (nil? n) (nil? (:doc state)))
      state
      (let [doc (with-proposals (:doc state)
                  (map #(if (= id (:id %)) (assoc % :name n) %)
                       (hierarchy/named-proposals (:doc state))))]
        (persist-doc state doc)))))

(defn inspector-hit
  "Hit in screen space on inspector proposal UI, or nil."
  [state x y window-w]
  (let [ps (hierarchy/named-proposals (:doc state))
        n (count ps)
        row (some (fn [i]
                    (when (layout/in-rect? (layout/proposal-row-rect window-w i) x y)
                      {:kind :proposal :id (:id (nth ps i)) :index i}))
                  (range n))]
    (or (when (layout/in-rect? (layout/real-diagram-rect window-w) x y)
          {:kind :real-diagram})
        row
        (when (layout/in-rect? (layout/new-proposal-rect window-w n) x y)
          {:kind :new-proposal})
        (when (layout/in-rect? (layout/declutter-rect window-w n) x y)
          {:kind :declutter}))))

(defn on-inspector-press
  "Left click on inspector proposal UI."
  [state hit]
  (case (:kind hit)
    :real-diagram (show-proposal state nil)
    :proposal (show-proposal state (:id hit))
    :new-proposal (add-proposal state)
    :declutter (cycle-declutter state)
    state))

(defn layer-id
  "Namespace to drill from a hit: the layer box, a child's parent,
  or a proposal package when classes are collapsed."
  [sel]
  (cond
    (and (= :class (:kind sel)) (:drill? sel)) (:id sel)
    (= :child (:kind sel)) (:parent sel)
    (and (= :package (:kind sel))
         (hierarchy/proposal-package-id? (:id sel)))
    (:id sel)
    :else nil))

(defn drill
  "Open the namespace node `id` (next level down)."
  [state id]
  (if (or (hierarchy/proposal-package-id? id)
          (hierarchy/find-nested-group (:doc state) (:proposal-id state) id))
    (rebuild (assoc state :open-layer id :focus []))
    (rebuild (update state :focus (fnil conj []) (last-seg id)))))

(defn back
  "Return to the parent namespace view."
  [state]
  (cond
    (:open-layer state) (rebuild (dissoc state :open-layer))
    (seq (:focus state)) (rebuild (update state :focus pop))
    :else state))

(defn- view-classes [doc path]
  (mapcat :classes (:packages (hierarchy/view-at doc path))))

(defn card-scene
  "Scene used for the class card: the full hierarchical graph, or the view."
  [state]
  (if (and (:doc state) (:hierarchical (:doc state)))
    (let [doc (:doc state)
          by-id #(into {} (map (juxt :id identity) %))
          classes (->> (merge (by-id (view-classes doc []))
                              (by-id (view-classes doc (or (:focus state) [])))
                              (by-id (:classes doc)))
                       vals vec)]
      {:classes classes
       :edges (:edges doc)
       :packages []
       :diagram {:title (:title doc)}})
    (:scene state)))

(defn select-class [state id]
  (if (or (hit/class-by-id (:scene state) id)
          (some #(= id (:id %)) (:classes (card-scene state))))
    (assoc state :selected {:kind :class :id id} :detail-id id)
    state))

(defn close-detail [state]
  (dissoc state :detail-id))

(defn on-detail-press [state model scroll y]
  (if-let [id (detail/rel-at (detail/rows model) (+ y scroll))]
    (select-class state id)
    state))

(def zoom-step 1.1)
(def zoom-min 0.1)
(def zoom-max 10.0)

(defn zoom-of
  "Current diagram scale. 1 is unzoomed."
  [state]
  (double (or (:zoom state) 1.0)))

(defn world-xy [state x y]
  (let [z (zoom-of state)]
    [(+ (:cam-x state 0) (/ (double x) z))
     (+ (:cam-y state 0) (/ (double y) z))]))

(defn on-move [state x y]
  (let [[wx wy] (world-xy state x y)]
    (assoc state
      :hover (hit/at (:scene state) wx wy)
      :pointer [x y])))

(defn regen-hit?
  [x y window-w window-h]
  (let [r (layout/regen-button window-w window-h)]
    (and (>= x (:x r)) (< x (+ (:x r) (:w r)))
         (>= y (:y r)) (< y (+ (:y r) (:h r))))))

(defn on-press [state x y]
  (if (and (or (seq (:focus state)) (:open-layer state))
           (< y 44) (< x 320))
    (back state)
    (let [[wx wy] (world-xy state x y)
          hit (hit/at (:scene state) wx wy)]
      (assoc state :selected hit))))

(defn- scroll-opts [opts]
  (let [opts (if (map? opts) opts {:window-h opts :window-w 1500})
        window-w (or (:window-w opts) 1500)]
    {:horizontal? (:horizontal? opts)
     :window-w window-w
     :window-h (or (:window-h opts) 900)
     :view-w (or (:view-w opts) window-w)}))

(defn- scroll-amount [amount]
  (cond
    (number? amount) amount
    (map? amount) (or (:count amount) 0)
    :else 0))

(defn- scroll-limits [state view-w window-h z]
  (let [size (get-in state [:scene :size] {:w 800 :h 600})
        min-x (or (:min-x size) 0)
        min-y (or (:min-y size) 0)
        vis-w (/ (double view-w) z)
        vis-h (/ (double window-h) z)]
    {:min-x min-x
     :min-y min-y
     :max-x (max min-x (- (:w size) vis-w))
     :max-y (max min-y (- (:h size) vis-h))}))

(defn on-scroll [state amount opts]
  (let [{:keys [horizontal? window-h view-w]} (scroll-opts opts)
        z (zoom-of state)
        amount (scroll-amount amount)
        {:keys [min-x min-y max-x max-y]} (scroll-limits state view-w window-h z)
        delta (/ (* amount 48.0) z)]
    (if horizontal?
      (update state :cam-x #(max min-x (min max-x (+ % delta))))
      (update state :cam-y #(max min-y (min max-y (+ % delta)))))))

(defn- view-center [state dims]
  (let [z (zoom-of state)
        vw (or (:view-w dims) (:window-w dims) 1500)
        vh (or (:window-h dims) 900)]
    [(+ (:cam-x state 0) (/ (double vw) 2.0 z))
     (+ (:cam-y state 0) (/ (double vh) 2.0 z))]))

(defn- set-zoom
  "Set zoom, keeping the world point at the view center still."
  [state z dims]
  (let [z (max zoom-min (min zoom-max (double z)))
        [cx cy] (view-center state dims)
        vw (or (:view-w dims) (:window-w dims) 1500)
        vh (or (:window-h dims) 900)]
    (assoc state
      :zoom z
      :cam-x (- cx (/ (double vw) 2.0 z))
      :cam-y (- cy (/ (double vh) 2.0 z)))))

(defn- raw-char [raw]
  (cond
    (char? raw) raw
    (string? raw) (first raw)
    :else nil))

(defn- key-name [k]
  (cond
    (keyword? k) (name k)
    (char? k) (str k)
    (string? k) k
    :else nil))

(defn zoom-dir
  "`:in`, `:out`, or `:reset` for a ctrl zoom key; nil otherwise.
  Ctrl-minus is often `:unknown-key` with key-code 45, not `:-`."
  [k dims]
  (let [n (key-name k)
        code (or (:key-code dims) 0)
        ch (raw-char (:raw-key dims))]
    (cond
      (or (#{"+" "="} n) (#{61 107 521} code) (#{\+ \=} ch)) :in
      (or (#{"-" "_" "minus" "subtract"} n)
          (#{45 109} code)
          (#{\- \_ \u001f} ch)) :out
      (or (= n "0") (#{48 96} code) (= ch \0)) :reset
      :else nil)))

(defn on-key
  ([state k] (on-key state k {:window-w 1500 :window-h 900}))
  ([state k dims]
   (if-let [dir (and (:control? dims) (zoom-dir k dims))]
     (case dir
       :in (set-zoom state (* (zoom-of state) zoom-step) dims)
       :out (set-zoom state (/ (zoom-of state) zoom-step) dims)
       :reset (set-zoom state 1.0 dims))
     (case k
       :left (on-scroll state -2 (assoc dims :horizontal? true))
       :right (on-scroll state 2 (assoc dims :horizontal? true))
       :up (on-scroll state -2 dims)
       :down (on-scroll state 2 dims)
       :esc (if (or (seq (:focus state)) (:open-layer state))
              (back state)
              (assoc state :selected nil))
       :r (-> state (dissoc :waiting) (assoc :mtime 0 :metrics-stamp nil))
       state))))
