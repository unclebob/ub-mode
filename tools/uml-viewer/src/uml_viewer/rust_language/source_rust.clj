(ns uml-viewer.rust-language.source-rust
  "Rust LanguageSource: open :file and find a fn, struct, or trait."
  (:require [clojure.string :as str]
            [uml-viewer.source :as source]))

(def ^:private pattern-fmts
  ["#\\[\\s*tauri\\s*::\\s*command[^\\]]*\\]\\s*(?:pub(?:\\([^)]*\\))?\\s+)?(?:async\\s+)?fn\\s+%s\\b"
   "pub(?:\\([^)]*\\))?\\s+async\\s+fn\\s+%s\\b"
   "pub(?:\\([^)]*\\))?\\s+fn\\s+%s\\b"
   "async\\s+fn\\s+%s\\b"
   "fn\\s+%s\\b"
   "pub(?:\\([^)]*\\))?\\s+(?:struct|enum|trait|type|const|static)\\s+%s\\b"
   "(?:struct|enum|trait|type|const|static)\\s+%s\\b"])

(defn member-line
  "1-based line of `member-name` in `source`, or nil."
  [source member-name]
  (source/member-line-of source member-name pattern-fmts))

(defn extract-member
  "Declaration of `member-name`, including a leading attribute, or nil."
  [source member-name]
  (when-let [start (source/pattern-start source member-name pattern-fmts)]
    (let [name-at (or (str/index-of source (str member-name) start) start)
          end (or (str/index-of source \newline name-at) (count source))]
      (str/trimr (subs source start end)))))

(def impl (source/file-source pattern-fmts extract-member))

(source/register! :rust impl)
