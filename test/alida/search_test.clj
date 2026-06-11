(ns alida.search-test
  (:require [alida.db.postgres :as db]
            [alida.embed :as embed]
            [alida.search :as search]
            [clojure.test :refer [deftest is]]))

(def sys
  {:alida/config
   {:indexes [{:name "docs"
               :embedding {:provider "openai"
                           :model "text-embedding-3-small"
                           :embedding_dimensions 1536}}
              {:name "support"
               :embedding {:provider "openai"
                           :model "text-embedding-3-large"
                           :embedding_dimensions 3072}}]}})

(defn- index-cfg
  [name]
  (first (filter #(= name (:name %))
                 (get-in sys [:alida/config :indexes]))))

(defn- run-row
  [index-name]
  {:id #uuid "018c9099-041d-7f5b-9b65-5b8f08f8e61d"
   :index_name index-name
   :embedding_dimensions (get-in (index-cfg index-name) [:embedding :embedding_dimensions])
   :embedding_fingerprint (embed/fingerprint (:embedding (index-cfg index-name)))})

(deftest live-search-embeds-per-index-and-sorts-combined-results
  (let [embedded (atom [])
        searched (atom [])]
    (with-redefs [embed/embed-batch (fn [_ provider-cfg texts]
                                      (swap! embedded conj [(:model provider-cfg) texts])
                                      [(repeat (:embedding_dimensions provider-cfg) 0.1)])
                  db/get-live-run (fn [_ index-name]
                                    (run-row index-name))
                  db/search-live-chunks (fn [_ dimensions _ opts]
                                          (swap! searched conj [dimensions opts])
                                          (if (= 1536 dimensions)
                                            [{:index_name "docs" :score 0.7}]
                                            [{:index_name "support" :score 0.9}]))]
      (is (= [{:index_name "support" :score 0.9}]
             (search/search-live sys nil "vacation balance" {:limit 1})))
      (is (= [["text-embedding-3-small" ["vacation balance"]]
              ["text-embedding-3-large" ["vacation balance"]]]
             @embedded))
      (is (= [[1536 {:index_names ["docs"] :limit 1}]
              [3072 {:index_names ["support"] :limit 1}]]
             @searched)))))

(deftest live-search-can-be-limited-to-one-index
  (with-redefs [embed/embed-batch (fn [_ provider-cfg _]
                                    [(repeat (:embedding_dimensions provider-cfg) 0.1)])
                db/get-live-run (fn [_ index-name]
                                  (run-row index-name))
                db/search-live-chunks (fn [_ dimensions _ opts]
                                        [{:dimensions dimensions
                                          :opts opts
                                          :score 0.8}])]
    (is (= [{:dimensions 1536
             :opts {:index_names ["docs"] :limit 10}
             :score 0.8}]
           (search/search-live sys nil "policy" {:index-name "docs"})))))

(deftest search-fails-before-embedding-when-live-run-embedding-space-differs
  (let [embedded? (atom false)]
    (with-redefs [db/get-live-run (fn [_ _]
                                    (assoc (run-row "docs")
                                           :embedding_fingerprint "old-fingerprint"))
                  embed/embed-batch (fn [& _]
                                      (reset! embedded? true)
                                      [])]
      (let [result (try
                     (search/search-live sys nil "policy" {:index-name "docs"})
                     :searched
                     (catch clojure.lang.ExceptionInfo e
                       (ex-data e)))]
        (is (= :alida.search/embedding-space-mismatch (:type result)))
        (is (false? @embedded?))))))

(deftest run-search-uses-run-index-config
  (with-redefs [db/get-run (fn [_ run-id]
                             (assoc (run-row "support")
                                    :id (parse-uuid run-id)))
                embed/embed-batch (fn [_ provider-cfg texts]
                                    (is (= "text-embedding-3-large" (:model provider-cfg)))
                                    (is (= ["how to create a ticket"] texts))
                                    [(repeat 3072 0.1)])
                db/search-run-chunks (fn [_ dimensions run-id _ opts]
                                       [{:dimensions dimensions
                                         :run-id run-id
                                         :opts opts
                                         :score 0.5}])]
    (is (= [{:dimensions 3072
             :run-id #uuid "018c9099-041d-7f5b-9b65-5b8f08f8e61d"
             :opts {:limit 5}
             :score 0.5}]
           (search/search-run sys
                              nil
                              "018c9099-041d-7f5b-9b65-5b8f08f8e61d"
                              "how to create a ticket"
                              {:limit 5})))))

(deftest search-run-fails-before-embedding-when-run-embedding-space-differs
  (let [embedded? (atom false)]
    (with-redefs [db/get-run (fn [_ _]
                               (assoc (run-row "docs")
                                      :embedding_dimensions 3072))
                  embed/embed-batch (fn [& _]
                                      (reset! embedded? true)
                                      [])]
      (let [result (try
                     (search/search-run sys
                                        nil
                                        "018c9099-041d-7f5b-9b65-5b8f08f8e61d"
                                        "policy"
                                        {})
                     :searched
                     (catch clojure.lang.ExceptionInfo e
                       (ex-data e)))]
        (is (= :alida.search/embedding-space-mismatch (:type result)))
        (is (false? @embedded?))))))
