(ns alida.chunk-test
  (:require [alida.chunk :as chunk]
            [clojure.test :refer [deftest is]]))

(def document
  {:canonical_url "https://example.test/help"
   :title "Help"
   :locale "en"
   :language_source :detected
   :language_confidence 0.99
   :blocks [{:type :heading
             :text "Getting started"
             :heading_path ["Getting started"]}
            {:type :paragraph
             :text "Install the app. Configure the app."
             :heading_path ["Getting started"]}
            {:type :heading
             :text "Advanced"
             :heading_path ["Getting started" "Advanced"]}
            {:type :paragraph
             :text "Use advanced settings for larger teams."
             :heading_path ["Getting started" "Advanced"]}]})

(deftest builds-section-aware-chunks-with-metadata
  (let [chunks (chunk/section-aware document {:max_tokens 20})]
    (is (< 1 (count chunks)))
    (is (= (range (count chunks)) (map :chunk_index chunks)))
    (is (= #{(count chunks)} (set (map :chunk_count chunks))))
    (is (= "https://example.test/help" (:canonical_url (first chunks))))
    (is (= "en" (:locale (first chunks))))
    (is (= :detected (:language_source (first chunks))))
    (is (= 0.99 (:language_confidence (first chunks))))
    (is (not (re-find #"Getting started\nGetting started" (:content (first chunks)))))
    (is (every? pos-int? (map :estimated_tokens chunks)))
    (is (some #(= ["Getting started" "Advanced"] (:heading_path %)) chunks))))
