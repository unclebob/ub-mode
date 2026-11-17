(ns uml-viewer.domain.ir
  (:require [clojure.string :as str]
            [clojure.edn :as edn]))

(defn- as-id [x]
  (cond
    (keyword? x) x
    (string? x) (keyword (str/replace (str/lower-case x) #"\s+" "-"))
    (symbol? x) (keyword (name x))
    :else (throw (ex-info "id must be a keyword or string" {:value x}))))

(defn- as-crap [x]
  (cond
    (nil? x) nil
    (number? x) {:mu (double x) :max (double x) :sigma 0.0}
    (map? x) (cond-> {:mu (some-> (:mu x) double)
                      :max (double (or (:max x) (:mu x) 0))
                      :sigma (double (or (:sigma x) (:sd x) 0))}
               (and (:n x) (pos? (:n x))) (assoc :n (long (:n x))))
    :else (throw (ex-info "crap must be a number or {:mu :max :sigma}" {:value x}))))

(defn- as-coverage [x]
  (cond
    (nil? x) nil
    (number? x) (let [v (double x)]
                  (when (or (Double/isNaN v) (neg? v) (> v 1.0))
                    (throw (ex-info "coverage must be between 0 and 1" {:value x})))
                  v)
    :else (throw (ex-info "coverage must be a number 0–1" {:value x}))))

(defn- as-count [x]
  (cond
    (nil? x) nil
    (and (number? x) (>= x 0) (= (double x) (double (long x)))) (long x)
    :else (throw (ex-info "killed/survived/uncovered must be a non-negative integer" {:value x}))))

(defn- as-cc [x]
  (cond
    (nil? x) nil
    (and (number? x) (>= x 0)) (if (= (double x) (double (long x)))
                                 (long x)
                                 (double x))
    :else (throw (ex-info "cc must be a non-negative number" {:value x}))))

(defn- member-text [x]
  (or (:text x)
      (str (:name x)
           (when (seq (:args x))
             (str "(" (str/join ", " (:args x)) ")"))
           (when (:type x) (str " : " (:type x)))
           (when (:returns x) (str " : " (:returns x))))))

(defn- with-member-counts [m x]
  (cond-> m
    (contains? x :coverage) (assoc :coverage (as-coverage (:coverage x)))
    (contains? x :killed) (assoc :killed (as-count (:killed x)))
    (contains? x :survived) (assoc :survived (as-count (:survived x)))
    (contains? x :uncovered) (assoc :uncovered (as-count (:uncovered x)))
    (contains? x :sites) (assoc :sites (as-count (:sites x)))))

(defn- with-member-metrics [m x]
  (cond-> m
    (contains? x :cc) (assoc :cc (as-cc (:cc x)))
    (contains? x :crap) (assoc :crap (as-crap (:crap x)))
    (true? (:private x)) (assoc :private true)
    (contains? x :name) (assoc :name (:name x))))

(defn- as-member [x]
  (cond
    (string? x) {:text x}
    (map? x) (-> {:text (member-text x)}
                 (with-member-counts x)
                 (with-member-metrics x))
    :else (throw (ex-info "member must be a string or map" {:value x}))))

(defn- with-class-source [m c]
  (cond-> m
    (:ns c) (assoc :ns (str (:ns c)))
    (:lang c) (assoc :lang (keyword (:lang c)))
    (:file c) (assoc :file (str (:file c)))
    (some? (:level c)) (assoc :level (long (:level c)))))

(defn- as-class [c]
  (let [name (or (:name c) (some-> (:id c) name))]
    (when-not name
      (throw (ex-info "class needs :name or :id" {:class c})))
    (with-class-source
      {:id (as-id (or (:id c) name))
       :name name
       :shape (when (or (:foreign c) (= :oval (keyword (:shape c)))) :oval)
       :stereotype (:stereotype c)
       :crap (as-crap (:crap c))
       :coverage (as-coverage (:coverage c))
       :cc (as-cc (:cc c))
       :killed (as-count (:killed c))
       :survived (as-count (:survived c))
       :uncovered (as-count (:uncovered c))
       :sites (as-count (:sites c))
       :hide-members (boolean (:hide-members c))
       :fields (mapv as-member (:fields c))
       :ops (mapv as-member (:ops c))}
      c)))

(defn- as-package [p]
  (let [label (or (:label p) (some-> (:id p) name))]
    (when-not label
      (throw (ex-info "package needs :label or :id" {:package p})))
    {:id (as-id (or (:id p) label))
     :label label
     :crap (as-crap (:crap p))
     :classes (mapv as-class (:classes p))}))

(defn- as-edge [e]
  (when-not (and (:from e) (:to e))
    (throw (ex-info "edge needs :from and :to" {:edge e})))
  (cond-> {:from (as-id (:from e))
           :to (as-id (:to e))
           :kind (keyword (or (:kind e) :association))
           :label (:label e)}
    (true? (:violating e)) (assoc :violating true)))

(defn normalize [raw]
  (let [diagram {:title (or (:title raw) "UML")
                 :direction (keyword (or (:direction raw) :tb))
                 :packages (mapv as-package (:packages raw))
                 :foreign (mapv as-class (:foreign raw))
                 :edges (mapv as-edge (:edges raw))}
        class-ids (concat (mapcat (fn [p] (map :id (:classes p))) (:packages diagram))
                          (map :id (:foreign diagram)))
        dup (ffirst (filter #(> (val %) 1) (frequencies class-ids)))]
    (when dup
      (throw (ex-info (str "duplicate class id: " dup) {:id dup})))
    (let [ids (set class-ids)]
      (doseq [e (:edges diagram)]
        (when-not (ids (:from e))
          (throw (ex-info (str "edge :from unknown class " (:from e)) {:edge e})))
        (when-not (ids (:to e))
          (throw (ex-info (str "edge :to unknown class " (:to e)) {:edge e})))))
    diagram))

(defn read-diagram [s]
  (normalize (edn/read-string s)))

(defn load-document [path]
  (let [raw (edn/read-string (slurp path))]
    (cond
      (:hierarchical raw) raw
      (:diagrams raw)
      {:title (or (:title raw) "UML")
       :diagrams (mapv normalize (:diagrams raw))}
      :else
      {:title (or (:title raw) "UML")
       :diagrams [(normalize raw)]})))

(defn load-diagram [path]
  (first (:diagrams (load-document path))))

(defn class-index [diagram]
  (into {}
        (concat
          (for [p (:packages diagram)
                c (:classes p)]
            [(:id c) (assoc c :package (:id p))])
          (for [c (:foreign diagram)]
            [(:id c) c]))))

(defn package-of [diagram class-id]
  (:package (get (class-index diagram) class-id)))
