(ns alida.integration.gcs-crawl-test
  (:require [alida.crawl :as crawl]
            [alida.source.gcs]
            [clojure.test :refer [deftest is]]))

(def index-cfg
  {:name "gcs-fixtures"
   :languages {:allowed ["en" "nl" "de" "fr"]
               :fallback "en"}
   :embedding {:provider "noop"
               :embedding_dimensions 1536}
   :chunking {:max_input_tokens 8192
              :max_tokens 128
              :safety_multiplier 1.2}
   :sources []})

(defn- fake-gcs
  [list-pages objects requests]
  {:alida/gcs-list-page
   (fn [_client request]
     (swap! requests conj (assoc request :op :list))
     (or (get list-pages request)
         {:cognitect.anomalies/category :cognitect.anomalies/fault
          :message (str "missing fake list response for " request)}))
   :alida/gcs-fetch-object
   (fn [_client request]
     (swap! requests conj (assoc request :op :fetch))
     (or (get objects request)
         {:cognitect.anomalies/category :cognitect.anomalies/fault
          :message (str "missing fake fetch response for " request)}))})

(deftest ^:integration mocked-gcs-source-runs-through-crawl-pipeline
  (let [requests (atom [])
        source-cfg {:id "objects"
                    :type "gcs"
                    :bucket "alida-gcs-fixtures"
                    :prefix "fixtures/docs/"
                    :include_globs ["fixtures/docs/*.json"
                                    "fixtures/docs/**/*.json"]
                    :exclude_globs ["fixtures/docs/private/**"]}
        sys (fake-gcs {{:bucket "alida-gcs-fixtures"
                        :prefix "fixtures/docs/"
                        :max_results 1000}
                       {:items [{:name "fixtures/docs/guide.json"}
                                {:name "fixtures/docs/manual.json"}
                                {:name "fixtures/docs/private/ignored.json"}]}}
                      {{:bucket "alida-gcs-fixtures"
                        :key "fixtures/docs/guide.json"}
                       {:body "{\"title\":\"Guide\",\"body\":\"First fixture content.\"}"
                        :content_type "application/octet-stream"}

                       {:bucket "alida-gcs-fixtures"
                        :key "fixtures/docs/manual.json"}
                       {:body "{\"title\":\"Manual\",\"body\":\"Second fixture content.\"}"
                        :content_type "application/json"}}
                      requests)
        result (crawl/process-source sys index-cfg source-cfg)]
    (is (= 2 (:discovered_count result)))
    (is (= 2 (:unique_discovered_count result)))
    (is (= 2 (:document_count result)))
    (is (= 0 (:error_count result)))
    (is (= #{"application/json"}
           (set (map (comp :content_type :document) (:documents result)))))
    (is (every? seq (mapcat :chunks (:documents result))))
    (is (= [:list :fetch :fetch]
           (mapv :op @requests)))))
