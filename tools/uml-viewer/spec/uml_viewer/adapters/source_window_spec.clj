(ns uml-viewer.adapters.source-window-spec
  (:require [clojure.string :as str]
            [speclj.core :refer :all]
            [uml-viewer.adapters.source-window :as source-window]))

(describe "source html"
  (it "escapes html-sensitive characters"
    (should= "&lt;a&amp;b&gt;" (source-window/html-escape "<a&b>")))

  (it "colorizes strings, keywords, and comments"
    (let [out (source-window/colorize-clojure-html "(println \"x\" :k) ; c")]
      (should (str/includes? out "class='str'"))
      (should (str/includes? out "class='kw'"))
      (should (str/includes? out "class='cmt'"))))

  (it "renders a titled document with line numbers"
    (let [doc (source-window/source->html "demo.clj" "(ns demo)\n")]
      (should (str/includes? doc "<div class='hdr'>demo.clj</div>"))
      (should (str/includes? doc "class='ln'>1</td>"))))

  (it "anchors and highlights the member line"
    (let [doc (source-window/source->html "f.clj" "(ns f)\n(defn go [])\n" 2)]
      (should (str/includes? doc "name='here'"))
      (should (str/includes? doc "class='hl'")))))

(describe "open-member-window!"
  (it "chooses the ident lang and does not open a frame when source is missing"
    (let [calls (atom [])
          shown (atom nil)]
      (with-redefs [uml-viewer.source/member-source
                    (fn [impl ident]
                      (swap! calls conj [impl ident])
                      (when (= "go" (:name ident))
                        {:title "T" :body "(defn go [])" :line 4}))
                    uml-viewer.adapters.source-window/show-member-window!
                    (fn [title body line]
                      (reset! shown [title body line]))]
        (should-be-nil (source-window/open-member-window! :fallback {:ns "demo.a"}))
        (should= [[:fallback {:ns "demo.a"}]] @calls)
        (should-be-nil @shown)
        (should= true (source-window/open-member-window!
                        :fallback {:ns "demo.a" :name "go"}))
        (should= ["T" "(defn go [])" 4] @shown)
        (source-window/open-member-window!
          :fallback {:ns "demo.a" :name "go" :lang :rust})
        (should= [:rust {:ns "demo.a" :name "go" :lang :rust}] (last @calls))
        (source-window/open-member-window! :fallback "demo.a" "go")
        (should= [:fallback {:ns "demo.a" :name "go"}] (last @calls))))))
