(ns alida.extract.html-test
  (:require [alida.extract.html :as html]
            [clojure.test :refer [deftest is]]))

(def html-body
  "<html>
     <head>
       <title>Support article</title>
       <meta property=\"og:locale\" content=\"en_US\">
       <script>bad()</script>
     </head>
     <body>
       <nav>Navigation noise</nav>
       <main>
         <h1>Getting started</h1>
         <p>Install the app.</p>
         <p>Did this article help? Yes No</p>
         <h2>Details</h2>
         <ul><li>First step</li><li>Second step</li></ul>
       </main>
     </body>
   </html>")

(deftest extracts-semantic-blocks-and-applies-cleaning-rules
  (let [document (html/extract {:remove_selectors ["nav"]
                                :strip_text ["Did this article help? Yes No"]}
                               {:canonical_url "https://example.test/help"
                                :body html-body
                                :content_type "text/html"})]
    (is (= "https://example.test/help" (:canonical_url document)))
    (is (= "Support article" (:title document)))
    (is (= "en_US" (:html_locale document)))
    (is (= ["Getting started" "Install the app." "Details" "First step" "Second step"]
           (mapv :text (:blocks document))))
    (is (= ["Getting started" "Details"]
           (:heading_path (last (:blocks document)))))
    (is (not (re-find #"Navigation noise|Did this article help|bad"
                      (:normalized_content document))))
    (is (= 64 (count (:raw_content_hash document))))
    (is (= 64 (count (:normalized_content_hash document))))))

(deftest extracts-html-lang-with-custom-selector
  (let [document (html/extract {:language {:html_selectors ["main[data-locale]"]}}
                               {:canonical_url "https://example.test/nl"
                                :body "<main data-locale=\"nl\"><p>Hallo wereld</p></main>"})]
    (is (= "nl" (:html_locale document)))))

(deftest nested-block-elements-are-not-duplicated
  (let [document (html/extract {}
                               {:canonical_url "https://example.test/nested"
                                :body "<main>
                                        <blockquote><p>Quoted text</p></blockquote>
                                        <pre><code>sample code</code></pre>
                                        <ul><li><p>List item</p></li></ul>
                                      </main>"})]
    (is (= ["Quoted text" "sample code" "List item"]
           (mapv :text (:blocks document))))
    (is (= "Quoted text sample code List item"
           (:normalized_content document)))))
