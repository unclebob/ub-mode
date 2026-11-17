(ns uml-viewer.python-language.graph-python-spec
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [speclj.core :refer :all]
            [uml-viewer.application.ir-generator :as ir-generator]
            [uml-viewer.graph :as graph]
            [uml-viewer.python-language.graph-python :as py]))

(defn- spit-file [dir rel content]
  (let [f (io/file dir rel)]
    (io/make-parents f)
    (spit f content)
    f))

(defn- temp-root []
  (io/file (System/getProperty "java.io.tmpdir")
           (str "uml-py-" (System/nanoTime))))

(describe "python module surface"
  (it "reads imports, bases, and return names"
    (let [surface (py/read-module
                    (str "\"\"\"\nimport nope\n\"\"\"\n"
                         "s = f\"say {'import nope'}\"\n"
                         "# import nope\n"
                         "from app.model \\\n"
                         "    import Animal\n"
                         "from app.model import (\n"
                         "    Walk,\n"
                         ")\n"
                         "import os.path\n"
                         "def load() -> Animal:\n"
                         "    import json\n"
                         "    return Animal()\n"
                         "def all_animals() -> list[Animal]:\n"
                         "    return []\n"
                         "def _hidden():\n"
                         "    return 1\n"
                         "class Dog(Animal):\n"
                         "    def speak(self):\n"
                         "        return 'woof'\n"))]
      (should= ["app.model" "app.model" "os.path" "json"]
               (mapcat (fn [imp]
                         (if (= :from (:kind imp))
                           [(:module imp)]
                           (map :name (:modules imp))))
                       (:imports surface)))
      (should= ["Animal"] (map :name (:names (first (:imports surface)))))
      (should= ["load" "all_animals" "Dog"] (map :name (:defs surface)))
      (should= "Animal" (:return (first (:defs surface))))
      (should-be-nil (:return (second (:defs surface))))
      (should= ["Animal"] (:bases (nth (:defs surface) 2)))))

  (it "keeps escapes, raw strings, and brackets from hiding the next import"
    (let [surface (py/read-module
                    (str "s = \"say \\\"import hidden\\\"\"\n"
                         "t = r'raw\\nimport hidden'\n"
                         "q = r'it\\'import hidden'\n"
                         "u = f\"a {'import hidden'}\"\n"
                         "def load(xs=[1], row={}):\n"
                         "    return xs\n"
                         "import os"))]
      (should= ["os"] (mapcat #(map :name (:modules %)) (:imports surface)))
      (should= ["load"] (map :name (:defs surface)))))

  (it "reads string prefixes before the quote"
    (let [open (ns-resolve 'uml-viewer.python-language.graph-python 'string-open)]
      (should= {:raw false :f true} (open "f\"hi\"" 1))
      (should= {:raw false :f true} (open "F\"hi\"" 1))
      (should= {:raw true :f true} (open "rf\"hi\"" 2))
      (should= {:raw true :f false} (open "r'hi'" 1))
      (should= {:raw false :f false} (open "u\"hi\"" 1))
      (should= {:raw false :f false} (open "b'hi'" 1))))

  (it "stops an unclosed string at the end of the file"
    (let [surface (py/read-module "s = \"import hidden")]
      (should= [] (mapcat #(map :name (:modules %)) (:imports surface)))
      (should= [] (:defs surface))))

  (it "keeps a quote inside an f-string from swallowing the next import"
    (let [surface (py/read-module
                    (str "s = f\"say {'import nope'}\"\n"
                         "import os\n"))]
      (should= ["os"] (mapcat #(map :name (:modules %)) (:imports surface))))))

(describe "python graph"
  (it "scans a package, skips tests, and marks inheritance and protocols"
    (let [dir (temp-root)]
      (spit-file dir "src/app/__init__.py" "from .model import Animal, walk\n")
      (spit-file dir "src/app/model.py"
                 (str "class Animal:\n    def speak(self):\n        return 'woof'\n"
                      "def walk(animal):\n    return animal\n"
                      "def _hidden():\n    return 1\n"))
      (spit-file dir "src/app/dogs/__init__.py"
                 (str "from ..model import Animal\n"
                      "from .kennel import Kennel\n"
                      "class Pack(Animal):\n    pass\n"))
      (spit-file dir "src/app/dogs/kennel.py"
                 (str "from ..model import Animal\n"
                      "import os.path\n"
                      "class Kennel(Animal):\n    pass\n"))
      (spit-file dir "src/app/service.py"
                 (str "from .model import Animal\n"
                      "def load() -> Animal:\n    return Animal()\n"
                      "def all_animals() -> list[Animal]:\n    return []\n"))
      (spit-file dir "src/app/fs.py"
                 (str "from typing import Protocol\n"
                      "class Fs(Protocol):\n"
                      "    def read(self, path: str) -> str:\n"
                      "        ...\n"))
      (spit-file dir "src/cli.py" "from app.model import Animal\n")
      (spit-file dir "src/test_model.py" "import app.model\n")
      (spit-file dir "src/app/foo_test.py" "import app.model\n")
      (spit-file dir "src/conftest.py" "import app.model\n")
      (spit-file dir "src/tests/helper.py" "import app.model\n")
      (spit-file dir "src/app/__pycache__/model.py" "import nope\n")
      (spit-file dir "src/venv/noise.py" "import nope\n")
      (let [g (graph/scan (graph/lookup :python) (io/file dir "src")
                          {:prefix "app"})
            by-id (into {} (map (juxt :id identity) (:classes g)))
            project (set (remove #(get-in by-id [% :foreign]) (keys by-id)))
            edges (set (map (juxt :from :to :kind) (:edges g)))]
        (should= #{:app :model :dogs :dogs.kennel :service :fs :cli} project)
        (should= "app.model" (:ns (by-id :model)))
        (should= "app" (:ns (by-id :app)))
        (should= :python (:lang (by-id :model)))
        (should (str/ends-with? (:file (by-id :model)) "src/app/model.py"))
        (should= "Model" (:name (by-id :model)))
        (should= "Kennel" (:name (by-id :dogs.kennel)))
        (should= ["Animal" "walk"] (map :name (:ops (by-id :model))))
        (should= ["Fs"] (map :name (:ops (by-id :fs))))
        (should= :interface (:stereotype (by-id :fs)))
        (should-be-nil (:stereotype (by-id :model)))
        (should (contains? edges [:app :model :dependency]))
        (should (contains? edges [:dogs :model :dependency]))
        (should (contains? edges [:dogs :dogs.kennel :dependency]))
        (should (contains? edges [:dogs.kennel :model :dependency]))
        (should (contains? edges [:dogs.kennel :model :inheritance]))
        (should (contains? edges [:dogs :model :inheritance]))
        (should (contains? edges [:service :model :dependency]))
        (should (contains? edges [:service :model :implements]))
        (should (contains? edges [:cli :model :dependency]))
        (should (contains? edges [:dogs.kennel :os.path :dependency]))
        (should (contains? edges [:fs :typing :dependency]))
        (should-not (contains? edges [:service :model :inheritance]))
        (let [doc (ir-generator/document
                    (graph/lookup :python)
                    {:title "App"
                     :prefix "app"
                     :lang :python
                     :src (str (io/file dir "src"))
                     :hierarchical true
                     :foreign [:os]})
              ids (set (map :id (:classes doc)))
              doc-edges (set (map (juxt :from :to :kind) (:edges doc)))]
          (should= :python (:lang (some #(when (= :model (:id %)) %) (:classes doc))))
          (should (contains? ids :os))
          (should-not (contains? ids :os.path))
          (should-not (contains? ids :typing))
          (should (contains? doc-edges [:service :model :implements]))
          (should (contains? doc-edges [:dogs.kennel :model :inheritance]))
          (should (contains? doc-edges [:dogs.kennel :os :dependency]))
          (should-not (contains? doc-edges [:service :model :dependency]))))
      (let [nested (graph/scan (graph/lookup :python) (io/file dir "src/app")
                               {:prefix "app"})
            by-id (into {} (map (juxt :id identity) (:classes nested)))
            edges (set (map (juxt :from :to :kind) (:edges nested)))]
        (should= "app.model" (:ns (by-id :model)))
        (should= "app.dogs.kennel" (:ns (by-id :dogs.kennel)))
        (should (contains? edges [:dogs.kennel :model :inheritance]))
        (should-not (contains? (set (keys by-id)) :cli)))))

  (it "resolves a dotted base through the imported module"
    (let [dir (temp-root)]
      (spit-file dir "src/app/__init__.py" "")
      (spit-file dir "src/app/model.py" "class Animal:\n    pass\n")
      (spit-file dir "src/app/extra.py"
                 (str "import app.model\n"
                      "from . import model\n"
                      "class Dog(app.model.Animal):\n"
                      "    pass\n"))
      (let [g (graph/scan (graph/lookup :python) (io/file dir "src")
                          {:prefix "app"})
            edges (set (map (juxt :from :to :kind) (:edges g)))]
        (should (contains? edges [:extra :model :dependency]))
        (should (contains? edges [:extra :model :inheritance]))))))
