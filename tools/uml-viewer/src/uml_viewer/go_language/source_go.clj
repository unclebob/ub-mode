(ns uml-viewer.go-language.source-go
  "Go LanguageSource: find a func, method, or type in any file of the package."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [uml-viewer.source :as source])
  (:import [java.util.regex Pattern]))

(defn- receiver
  "Type named after the last `.` of the import path's last segment, or nil."
  [ns-name]
  (let [seg (last (str/split (str ns-name) #"/"))]
    (when (str/includes? seg ".")
      (subs seg (inc (str/last-index-of seg "."))))))

(defn- find-at [text pattern from]
  (let [m (.matcher (Pattern/compile pattern) text)]
    (when (.find m from) (.start m))))

(defn- interface-spec [text recv q]
  (when-let [at (find-at text (format "(?m)^type\\s+%s(?:\\[[^\\]]*\\])?\\s+interface\\b" recv) 0)]
    (find-at text (format "(?m)^\\s+%s\\s*\\(" q) at)))

(defn member-start
  "Index of the declaration of `member-name` in `text`, or nil.
  A method on the receiver from `ns-name` wins over other declarations."
  [text ns-name member-name]
  (when (and text (seq (str member-name)))
    (let [q (Pattern/quote (str member-name))
          recv (some-> (receiver ns-name) Pattern/quote)]
      (or (when recv
            (or (find-at text (format "(?m)^func\\s*\\([^)]*?\\b%s\\b[^)]*\\)\\s*%s\\s*\\(" recv q) 0)
                (interface-spec text recv q)))
          (find-at text (format "(?m)^func\\s+%s\\b" q) 0)
          (find-at text (format "(?m)^(?:type\\s+|\\t)%s\\b(?:\\[[^\\]]*\\])?\\s+(?:=\\s*)?[^=\\s]" q) 0)
          (find-at text (format "(?m)^func\\s*\\([^)]*\\)\\s*%s\\s*\\(" q) 0)))))

(defn- package-files [file]
  (let [f (io/file file)
        siblings (->> (.listFiles (.getParentFile (.getAbsoluteFile f)))
                      (filter #(str/ends-with? (.getName %) ".go"))
                      (remove #(str/ends-with? (.getName %) "_test.go"))
                      (sort-by #(.getName %)))]
    (cons f (remove #(= (.getName %) (.getName f)) siblings))))

(defn- declaring-file [ident path]
  (some (fn [f]
          (when (member-start (slurp f) (:ns ident) (:name ident))
            (str/replace (.getPath f) #"\\" "/")))
        (package-files path)))

(def impl
  (reify source/LanguageSource
    (locate [_ ident]
      (when-let [path (source/existing-file ident)]
        (if (seq (str (:name ident)))
          (declaring-file ident path)
          path)))
    (extract [_ text ident]
      (when-let [start (member-start text (:ns ident) (:name ident))]
        (source/declaration-line text start)))
    (start-line [_ text ident]
      (when-let [start (member-start text (:ns ident) (:name ident))]
        (source/line-number text start)))
    (title [_ ident] (source/file-title ident))))

(source/register! :go impl)
