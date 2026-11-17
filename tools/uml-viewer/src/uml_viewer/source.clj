(ns uml-viewer.source
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.util.regex Pattern]))

(defprotocol LanguageSource
  (locate [this ident]
    "Path to the file that should contain `ident`, or nil.")
  (extract [this source ident]
    "Source span for `ident` from `source` text, or nil.")
  (start-line [this source ident]
    "1-based line of `ident` in `source`, or nil.")
  (title [this ident]
    "Window title for this member."))

(defonce ^:private languages (atom {}))

(defn register!
  "Install `impl` as the extractor for `lang` (e.g. `:clojure`)."
  [lang impl]
  (swap! languages assoc lang impl)
  lang)

(defn lookup
  [lang]
  (get @languages lang))

(defn pattern-start
  "Index of the first pattern in `fmts` that matches `member-name`, or nil."
  [source member-name fmts]
  (when (and source member-name)
    (let [quoted (Pattern/quote (str member-name))]
      (some (fn [fmt]
              (let [m (.matcher (Pattern/compile (format fmt quoted)) source)]
                (when (.find m) (.start m))))
            fmts))))

(defn line-number
  "1-based line of index `start` in `source`."
  [source start]
  (inc (count (re-seq #"\n" (subs source 0 start)))))

(defn declaration-line
  "Trimmed line that begins at `start`."
  [source start]
  (let [end (or (str/index-of source \newline start) (count source))]
    (str/trimr (subs source start end))))

(defn line-member
  "Declaration line of `member-name`, or nil."
  [source member-name fmts]
  (when-let [start (pattern-start source member-name fmts)]
    (declaration-line source start)))

(defn member-line-of
  "1-based line of `member-name`, or nil."
  [source member-name fmts]
  (when-let [start (pattern-start source member-name fmts)]
    (line-number source start)))

(defn existing-file
  "Forward-slash path when `ident` names a file that exists."
  [ident]
  (let [f (:file ident)]
    (when (and (seq (str f)) (.isFile (io/file f)))
      (str/replace (str f) #"\\" "/"))))

(defn file-title
  "Title from the file path and, when present, the member name."
  [ident]
  (str (or (:file ident) (:ns ident))
       (when (:name ident) (str "/" (:name ident)))))

(defn file-source
  "LanguageSource that opens `:file` and finds a member with `fmts`.
  `extract-fn` is `(fn [source member-name] string-or-nil)`."
  ([fmts]
   (file-source fmts (fn [text name] (line-member text name fmts))))
  ([fmts extract-fn]
   (reify LanguageSource
     (locate [_ ident] (existing-file ident))
     (extract [_ text ident] (extract-fn text (:name ident)))
     (start-line [_ text ident] (member-line-of text (:name ident) fmts))
     (title [_ ident] (file-title ident)))))

(defn- from-impl [impl ident lang]
  (when impl
    (when-let [path (locate impl ident)]
      (let [src (slurp path)
            named? (seq (str (:name ident)))]
        (when (or (not named?) (extract impl src ident))
          {:title (if named?
                    (str path ":" (or (start-line impl src ident) 1))
                    path)
           :file path
           :body src
           :line (when named? (start-line impl src ident))
           :lang lang})))))

(defn member-source
  "Locate a member. `ident` is a map with at least `:name`.
  One-arg form looks up the extractor by `:lang` (default `:clojure`).
  Two-arg form takes a `LanguageSource` impl, or a lang keyword.
  Returns `{:title :file :body :line :lang}` — `body` is the whole file,
  `line` is where the member starts — or nil."
  ([ident]
   (member-source (or (:lang ident) :clojure) ident))
  ([lang-or-impl ident]
   (if (keyword? lang-or-impl)
     (from-impl (lookup lang-or-impl) ident lang-or-impl)
     (from-impl lang-or-impl ident (or (:lang ident) :clojure)))))
