(ns uml-viewer.domain.config)

(def crap-thresholds
  "CRAP μ+σ. Lower is better: green, yellow, then red."
  {:green 8
   :yellow 12
   :red 20})

(def mutation-thresholds
  "Mutation score as 0–1. Higher is better: red, yellow, then green."
  {:red 0.80
   :yellow 0.90
   :green 1.00})

(def grade-best 10.0)
(def grade-mid 5.5)
(def grade-worst 1.0)

(defn crap-band
  "Traffic light for a CRAP score. Missing counts as red."
  [score]
  (if (nil? score)
    :red
    (let [{:keys [green yellow]} crap-thresholds]
      (cond
        (<= score green) :green
        (<= score yellow) :yellow
        :else :red))))

(defn mutation-band
  "Traffic light for a mutation score in 0–1. Missing counts as red."
  [score]
  (if (nil? score)
    :red
    (let [{:keys [red yellow]} mutation-thresholds]
      (cond
        (<= score red) :red
        (<= score yellow) :yellow
        :else :green))))

(defn- lerp [x x0 x1 y0 y1]
  (if (= (double x0) (double x1))
    (double y0)
    (let [t (/ (- (double x) (double x0))
               (- (double x1) (double x0)))]
      (+ y0 (* t (- y1 y0))))))

(defn- along
  "Clamp `x` onto stops `[x0 x1 x2]` mapped to `[y0 y1 y2]`."
  [x x0 x1 x2 y0 y1 y2]
  (cond
    (<= x x0) y0
    (<= x x1) (lerp x x0 x1 y0 y1)
    (<= x x2) (lerp x x1 x2 y1 y2)
    :else y2))

(defn crap-risk
  "μ+σ from a CRAP map, or nil."
  [crap]
  (when (:mu crap)
    (+ (double (:mu crap)) (double (or (:sigma crap) 0)))))

(defn crap-grade
  "CRAP μ+σ on the 1–10 scale (10 is best). Missing counts as red (1)."
  [score]
  (if (nil? score)
    grade-worst
    (let [{:keys [green yellow red]} crap-thresholds]
      (along (double score) green yellow red
             grade-best grade-mid grade-worst))))

(defn mutation-ratio
  "Killed / (killed + survived + missing-function gap).
  Nil when there is no mutant data and no gap.
  `:mut-gap` counts unscored functions as failed trials."
  [m]
  (when (map? m)
    (let [k (:killed m)
          s (:survived m)
          gap (:mut-gap m)]
      (when (or (some? k) (some? s) (and gap (pos? gap)))
        (let [n (+ (double (or k 0))
                   (double (or s 0))
                   (double (or gap 0)))]
          (when (pos? n)
            (/ (double (or k 0)) n)))))))

(defn mutation-grade
  "Mutation ratio on the 1–10 scale (10 is best). Missing counts as red (1)."
  [score]
  (if (nil? score)
    grade-worst
    (let [{:keys [red yellow green]} mutation-thresholds]
      (along (double score) red yellow green
             grade-worst grade-mid grade-best))))

(defn combined-grade
  "Average of present 1–10 grades. Nil when both are missing."
  [crap-g mut-g]
  (let [xs (filter some? [crap-g mut-g])]
    (when (seq xs)
      (/ (double (reduce + xs)) (count xs)))))

(defn- round1
  "Nearest tenth. Divide the rounded integer so the double is exact."
  [x]
  (double (/ (Math/round (* 10.0 (double x))) 10)))

(defn- score-of [crap]
  (cond
    (number? crap) (double crap)
    (and (map? crap) (some? (:mu crap))) (double (:mu crap))))

(defn- op-scores [class]
  (keep #(score-of (:crap %)) (:ops class)))

(defn function-weight
  "Functions listed on a module, or one when it lists none."
  [class]
  (let [n (count (:ops class))]
    (if (pos? n) n 1)))

(defn- score-group [scores]
  (let [xs (vec scores)
        n (count xs)
        mu (/ (reduce + xs) n)
        mx (apply max xs)
        var (/ (reduce + (map #(let [d (- % mu)] (* d d)) xs)) n)]
    {:n n :mu mu :sigma (Math/sqrt var) :max mx}))

(defn- round-group [{:keys [n mu sigma max]}]
  {:mu (round1 mu)
   :max (round1 max)
   :sigma (round1 sigma)
   :n (long n)})

(defn summarize-scores
  "μ, max, population σ, and function count of CRAP scores.
  Rounded to 0.1. Empty input is nil."
  [scores]
  (let [xs (keep score-of scores)]
    (when (seq xs)
      (round-group (score-group xs)))))

(defn- red-group [n]
  (let [red (double (:red crap-thresholds))]
    {:n n :mu red :sigma 0.0 :max red}))

(defn- summary-weight [class summary scores]
  (let [n (:n summary)]
    (cond
      (and n (pos? n)) (long n)
      (seq scores) (count scores)
      :else (function-weight class))))

(defn- known-group [n summary]
  {:n n
   :mu (double (:mu summary))
   :sigma (double (or (:sigma summary) 0))
   :max (double (or (:max summary) (:mu summary)))})

(defn- crap-sample
  "One module as an unrounded CRAP group.
  Function scores are the sample. With `rolled?`, a summary that
  already covers the subtree wins over local ops.
  No CRAP data is the red threshold, once per function."
  [class rolled?]
  (let [scores (op-scores class)
        summary (:crap class)
        summary? (and (map? summary) (some? (:mu summary)))]
    (cond
      (and rolled? summary?)
      (known-group (summary-weight class summary scores) summary)

      (seq scores)
      (score-group scores)

      summary?
      (known-group (summary-weight class summary scores) summary)

      :else
      (red-group (function-weight class)))))

(defn- combine-groups [groups]
  (let [groups (filter #(and % (pos? (:n %))) groups)]
    (when (seq groups)
      (let [n (reduce + (map :n groups))
            sum-w (reduce + (map #(* (:n %) (:mu %)) groups))
            sum-m2 (reduce + (map #(let [{:keys [n mu sigma]} %]
                                      (* n (+ (* sigma sigma) (* mu mu))))
                                  groups))
            mx (apply max (map :max groups))
            mu (/ sum-w n)
            var (max 0.0 (- (/ sum-m2 n) (* mu mu)))]
        (round-group {:n n :mu mu :sigma (Math/sqrt var) :max mx})))))

(defn- module? [c]
  (not (or (:foreign c) (= :oval (:shape c)))))

(defn pool-crap
  "Function-weighted μ, max, and σ across modules.
  Each function weighs one. A module with no CRAP counts as the red
  threshold once per function (its ops, or one). `rolled?` is true when
  each `:crap` already pools that module's subtree."
  ([classes] (pool-crap classes false))
  ([classes rolled?]
   (combine-groups (map #(crap-sample % rolled?)
                        (filter module? classes)))))

(defn- has-mutants? [c]
  (or (some? (:killed c)) (some? (:survived c))))

(defn- mutant-gap
  "Failed trials already rolled, or one per function when this module
  has no mutant data."
  [c]
  (if (or (has-mutants? c) (some? (:mut-gap c)))
    (or (:mut-gap c) 0)
    (function-weight c)))

(defn pool-mutants
  "Summed killed, survived, and uncovered across modules.
  A module with no mutant data adds `:mut-gap` failed trials, one per
  function. Counts on the map stay the measured totals."
  [classes]
  (let [classes (filter module? classes)
        measured (filter has-mutants? classes)
        k (reduce + 0 (map #(or (:killed %) 0) measured))
        s (reduce + 0 (map #(or (:survived %) 0) measured))
        uncovered (keep :uncovered classes)
        gap (reduce + 0 (map mutant-gap classes))]
    (when (or (seq measured) (pos? gap))
      (cond-> {}
        (seq measured) (assoc :killed (long k) :survived (long s))
        (seq uncovered) (assoc :uncovered (long (reduce + uncovered)))
        (pos? gap) (assoc :mut-gap (long gap))))))
