(ns uml-viewer.typescript-language.source-typescript
  "TypeScript LanguageSource: open :file and find an exported declaration."
  (:require [uml-viewer.source :as source]))

(def ^:private pattern-fmts
  ["export\\s+default\\s+async\\s+function\\s+%s\\b"
   "export\\s+async\\s+function\\s+%s\\b"
   "export\\s+default\\s+function\\s+%s\\b"
   "export\\s+function\\s+%s\\b"
   "export\\s+default\\s+class\\s+%s\\b"
   "export\\s+class\\s+%s\\b"
   "export\\s+const\\s+%s\\b"
   "export\\s+let\\s+%s\\b"
   "export\\s+var\\s+%s\\b"
   "export\\s+enum\\s+%s\\b"
   "async\\s+function\\s+%s\\b"
   "function\\s+%s\\b"
   "class\\s+%s\\b"
   "(?:const|let|var)\\s+%s\\b"])

(def impl (source/file-source pattern-fmts))

(source/register! :typescript impl)
