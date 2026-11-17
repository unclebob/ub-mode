(ns uml-viewer.python-language.source-python
  "Python LanguageSource: open :file and find a def or class."
  (:require [uml-viewer.source :as source]))

(def ^:private pattern-fmts
  ["async\\s+def\\s+%s\\b"
   "def\\s+%s\\b"
   "class\\s+%s\\b"])

(def impl (source/file-source pattern-fmts))

(source/register! :python impl)
