(ns alida.integration.s3-crawl-test
  (:require [alida.crawl :as crawl]
            [alida.source.s3]
            [clojure.test :refer [deftest is]]))

(def index-cfg
  {:name "s3-fixtures"
   :languages {:allowed ["en"]
               :fallback "en"}
   :embedding {:provider "noop"
               :embedding_dimensions 1536}
   :chunking {:max_input_tokens 8192
              :max_tokens 128
              :safety_multiplier 1.2}
   :sources []})

(defn- fake-s3
  [responses requests]
  {:alida/s3-invoke
   (fn [_client request]
     (swap! requests conj request)
     (or (get responses [(:op request) (:request request)])
         {:cognitect.anomalies/category :cognitect.anomalies/fault
          :message (str "missing fake response for " (:op request))}))})

(deftest ^:integration mocked-s3-source-runs-through-crawl-pipeline
  (let [requests (atom [])
        source-cfg {:id "objects"
                    :type "s3"
                    :bucket "alida-fixtures"
                    :prefix "fixtures/docs/"
                    :include_globs ["fixtures/docs/*.html"
                                    "fixtures/docs/*.md"
                                    "fixtures/docs/*.txt"
                                    "fixtures/docs/*.json"]
                    :exclude_globs ["fixtures/docs/private/**"]}
        sys (fake-s3 {[:ListObjectsV2 {:Bucket "alida-fixtures"
                                       :Prefix "fixtures/docs/"
                                       :MaxKeys 1000}]
                      {:Contents [{:Key "fixtures/docs/page.html"}
                                  {:Key "fixtures/docs/overview.md"}
                                  {:Key "fixtures/docs/plain.txt"}
                                  {:Key "fixtures/docs/api-limits.json"}
                                  {:Key "fixtures/docs/private/ignored.md"}]
                       :IsTruncated false}

                      [:GetObject {:Bucket "alida-fixtures"
                                   :Key "fixtures/docs/page.html"}]
                      {:Body "<html lang=\"en\"><head><title>S3 Page</title></head><body><h1>S3 Page</h1><p>HTML body text.</p></body></html>"
                       :ContentType "text/html"}

                      [:GetObject {:Bucket "alida-fixtures"
                                   :Key "fixtures/docs/overview.md"}]
                      {:Body "# Overview\n\nMarkdown body text."
                       :ContentType "application/octet-stream"}

                      [:GetObject {:Bucket "alida-fixtures"
                                   :Key "fixtures/docs/plain.txt"}]
                      {:Body "Plain body text."
                       :ContentType "text/plain"}

                      [:GetObject {:Bucket "alida-fixtures"
                                   :Key "fixtures/docs/api-limits.json"}]
                      {:Body "{\"title\":\"API limits\",\"body\":\"JSON body text.\"}"
                       :ContentType "application/json"}}
                     requests)
        result (crawl/process-source sys index-cfg source-cfg)]
    (is (= 4 (:discovered_count result)))
    (is (= 4 (:unique_discovered_count result)))
    (is (= 4 (:document_count result)))
    (is (= 0 (:error_count result)))
    (is (= #{"text/html" "text/markdown" "text/plain" "application/json"}
           (set (map (comp :content_type :document) (:documents result)))))
    (is (every? seq (mapcat :chunks (:documents result))))
    (is (= [:ListObjectsV2 :GetObject :GetObject :GetObject :GetObject]
           (mapv :op @requests)))))
