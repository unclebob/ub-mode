(ns uml-viewer.go-language.source-go-spec
  (:require [clojure.java.io :as io]
            [speclj.core :refer :all]
            [uml-viewer.go-language.source-go]
            [uml-viewer.source :as source]))

(defn- package-dir []
  (let [dir (io/file (System/getProperty "java.io.tmpdir") (str "uml-go-src-" (System/nanoTime)))]
    (io/make-parents (io/file dir "x"))
    (spit (io/file dir "cart.go")
          (str "package cart\n\n"
               "type Totaler interface {\n\tTotal() int\n}\n\n"
               "type Cart struct{ n int }\n\n"
               "func New() *Cart { return &Cart{} }\n"))
    (spit (io/file dir "total.go")
          (str "package cart\n\n"
               "type Bill struct{}\n\n"
               "func (b Bill) Total() int { return 1 }\n\n"
               "func (c *Cart) Total() int { return c.n }\n"))
    (spit (io/file dir "total_test.go") "package cart\n\nfunc Total() {}\n")
    dir))

(defn- find-member [dir ns-name member]
  (source/member-source {:lang :go
                         :ns ns-name
                         :file (.getPath (io/file dir "cart.go"))
                         :name member}))

(describe "go extractor"
  (it "opens the file of the package that declares the method on that receiver"
    (let [dir (package-dir)
          found (find-member dir "example.com/shop/cart.Cart" "Total")]
      (should= :go (:lang found))
      (should= (.getPath (io/file dir "total.go")) (:file found))
      (should= 7 (:line found))))

  (it "finds an interface method in the interface, not in an implementation"
    (let [found (find-member (package-dir) "example.com/shop/cart.Totaler" "Total")]
      (should= 4 (:line found))
      (should (re-find #"cart\.go:4$" (:title found)))))

  (it "finds a package function, even when the module path has a dot"
    (let [dir (package-dir)]
      (should= 9 (:line (find-member dir "example.com/shop/cart" "New")))
      (should= 9 (:line (find-member dir "gopkg.in/cart.v3" "New")))))

  (it "finds a method of a generic interface"
    (let [dir (package-dir)]
      (spit (io/file dir "box.go")
            "package cart\n\ntype Box[T any] interface {\n\tPut(v T)\n}\n")
      (should= 4 (:line (source/member-source {:lang :go
                                                :ns "example.com/shop/cart.Box"
                                                :file (.getPath (io/file dir "box.go"))
                                                :name "Put"})))))

  (it "opens a root-package file named relative to the working directory"
    (let [file (io/file (str "uml-go-root-" (System/nanoTime) ".go"))]
      (try
        (spit file "package root\n\nfunc Run() {}\n")
        (should= 3 (:line (source/member-source {:lang :go :ns "example.com/root"
                                                  :file (.getPath file) :name "Run"})))
        (finally (.delete file)))))

  (it "finds a type declaration"
    (should= 7 (:line (find-member (package-dir) "example.com/shop/cart" "Cart"))))

  (it "returns nil when no file of the package declares the member"
    (should-be-nil (find-member (package-dir) "example.com/shop/cart" "Missing")))

  (it "opens the package file at the top when no member is named"
    (let [dir (package-dir)
          found (source/member-source {:lang :go
                                       :ns "example.com/shop/cart"
                                       :file (.getPath (io/file dir "cart.go"))})]
      (should= (.getPath (io/file dir "cart.go")) (:file found))
      (should-be-nil (:line found)))))
