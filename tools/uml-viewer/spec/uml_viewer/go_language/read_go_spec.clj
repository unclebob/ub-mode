(ns uml-viewer.go-language.read-go-spec
  (:require [speclj.core :refer :all]
            [uml-viewer.go-language.read-go :as read-go]))

(describe "go file surface"
  (it "reads the package, imports, functions, methods, and types"
    (let [surface (read-go/read-file
                    (str "// Package shop sells things.\n"
                         "package shop\n\n"
                         "import (\n"
                         "\t\"fmt\"\n"
                         "\tst \"example.com/shop/store\"\n"
                         "\t_ \"embed\"\n"
                         ")\n\n"
                         "import \"os\"\n\n"
                         "type Cart struct {\n"
                         "\tItems []st.Item `json:\"items\"`\n"
                         "\tLedger\n"
                         "}\n\n"
                         "type (\n"
                         "\tTotaler interface {\n"
                         "\t\tTotal(tax float64) (int, error)\n"
                         "\t\tfmt.Stringer\n"
                         "\t}\n"
                         "\tcount int\n"
                         ")\n\n"
                         "func (c *Cart) Total(rate float64) (n int, err error) { return 0, nil }\n"
                         "func (c Cart) label() string { return \"type Fake struct {}\" }\n"
                         "func NewCart() *Cart { return &Cart{} }\n"
                         "func helper() {}\n"))]
      (should= "shop" (:package surface))
      (should= [{:alias nil :path "fmt"}
                {:alias "st" :path "example.com/shop/store"}
                {:alias "_" :path "embed"}
                {:alias nil :path "os"}]
               (:imports surface))
      (should= ["NewCart" "helper"] (map :name (:funcs surface)))
      (should= [["Cart" "Total" 1 2] ["Cart" "label" 0 1]]
               (map (juxt :receiver :name :params :results) (:methods surface)))
      (should= [["Cart" :struct] ["Totaler" :interface] ["count" :other]]
               (map (juxt :name :kind) (:types surface)))
      (let [cart (first (:types surface))
            totaler (second (:types surface))]
        (should= ["Ledger"] (:embeds cart))
        (should= ["fmt.Stringer"] (:embeds totaler))
        (should= [["Total" 1 2]] (map (juxt :name :params :results) (:specs totaler))))))

  (it "keeps generic type parameters apart from array and slice types"
    (let [surface (read-go/read-file
                    (str "package box\n"
                         "type Stack[T any] struct { items []T }\n"
                         "type Bytes []byte\n"
                         "type Grid [9]int\n"
                         "func (s *Stack[T]) Push(v T) { s.items = append(s.items, v) }\n"
                         "func Map[T, U any](xs []T, f func(T) U) []U { return nil }\n"))]
      (should= ["Stack" "Bytes" "Grid"] (map :name (:types surface)))
      (should= [["Stack" "Push" 1 0]] (map (juxt :receiver :name :params :results) (:methods surface)))
      (should= [["Map" 2 1]] (map (juxt :name :params :results) (:funcs surface)))))

  (it "counts a result that is an interface or struct literal"
    (let [surface (read-go/read-file
                    (str "package p\n"
                         "func Any() interface{} { return nil }\n"
                         "func Pair() struct{ a, b int } { return struct{ a, b int }{} }\n"
                         "func Both() (interface{}, error) { return nil, nil }\n"))]
      (should= [["Any" 0 1] ["Pair" 0 1] ["Both" 0 2]]
               (map (juxt :name :params :results) (:funcs surface)))))

  (it "ignores declarations inside comments, strings, and function bodies"
    (let [surface (read-go/read-file
                    (str "package p\n"
                         "/*\ntype Hidden struct{}\n*/\n"
                         "var s = `\ntype Raw struct{}\n`\n"
                         "func F() {\n\ttype local struct{}\n}\n"))]
      (should= [] (:types surface))
      (should= ["F"] (map :name (:funcs surface))))))
