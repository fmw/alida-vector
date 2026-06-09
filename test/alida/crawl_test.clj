(ns alida.crawl-test
  (:require [alida.crawl :as crawl]
            [alida.source.local]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- temp-file
  [suffix body]
  (let [file (java.io.File/createTempFile "alida-crawl" suffix)]
    (.deleteOnExit file)
    (spit file body)
    file))

(def index-cfg
  {:name "docs"
   :languages {:allowed ["en" "nl"]
               :fallback "en"}
   :embedding {:provider "openai"
               :model "text-embedding-3-small"
               :embedding_dimensions 1536}
   :chunking {:max_tokens 24}
   :sources []})

(deftest process-source-extracts-language-and-chunks-html
  (let [file (temp-file ".html"
                        "<html lang=\"nl\"><head><title>Welkom</title></head>
                         <body><h1>Welkom</h1><p>Dit is een Nederlandse hulppagina met nuttige informatie.</p></body></html>")
        result (crawl/process-source
                {}
                index-cfg
                {:id "fixtures"
                 :type "local"
                 :path (.getPath file)})
        document-result (-> result :documents first)
        document (:document document-result)]
    (is (= 1 (:discovered_count result)))
    (is (= 1 (:document_count result)))
    (is (pos-int? (:chunk_count result)))
    (is (= 0 (:error_count result)))
    (is (str/ends-with? (:title document) ".html"))
    (is (= "nl" (:locale document)))
    (is (= :html (:language_source document)))
    (is (= 64 (count (:normalized_content_hash document))))
    (is (pos-int? (-> document-result :chunks first :estimated_tokens)))))

(deftest process-source-keeps-item-level-errors
  (let [html-file (temp-file ".html" "<h1>Hello</h1><p>This document can be processed.</p>")
        text-file (temp-file ".txt" "plain text is not supported by the HTML extractor")
        result (crawl/process-source
                {}
                index-cfg
                {:id "fixtures"
                 :type "local"
                 :paths [(.getPath html-file)
                         (.getPath text-file)
                         "/tmp/alida-crawl-missing.html"]})]
    (is (= 3 (:discovered_count result)))
    (is (= 1 (:document_count result)))
    (is (= 1 (:chunk_count result)))
    (is (= 2 (:error_count result)))
    (is (= #{:alida.crawl/unsupported-content-type
             :alida.source.local/file-not-found}
           (set (map :type (:errors result)))))))

(deftest crawl-continues-with-other-indexes-after-one-index-fails
  (let [sys {:alida/config {:indexes [{:name "broken"} {:name "ok"}]}}]
    (with-redefs [crawl/crawl-index! (fn [_ _ index-cfg]
                                       (if (= "broken" (:name index-cfg))
                                         (throw (ex-info "boom" {:reason :test}))
                                         {:run_id #uuid "018c9099-041d-7f5b-9b65-5b8f08f8e61d"
                                          :index_name (:name index-cfg)}))]
      (let [result (crawl/crawl! sys :ignored {})]
        (is (= ["ok"] (mapv :index_name (:succeeded result))))
        (is (= ["broken"] (mapv :index_name (:failed result))))
        (is (= "boom" (-> result :failed first :message)))))))
