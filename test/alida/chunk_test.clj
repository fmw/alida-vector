(ns alida.chunk-test
  (:require [alida.chunk :as chunk]
            [alida.token :as token]
            [clojure.string :as str]
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
    (is (= 64 (count (:content_hash (first chunks)))))
    (is (not (re-find #"Getting started\nGetting started" (:content (first chunks)))))
    (is (every? pos-int? (map :estimated_tokens chunks)))
    (is (some #(= ["Getting started" "Advanced"] (:heading_path %)) chunks))))

(deftest includes-heading-context-once-per-chunk
  (let [chunks (chunk/section-aware document {:max_tokens 1000})
        content (:content (first chunks))]
    (is (= 1 (count chunks)))
    (is (re-find #"Getting started" content))
    (is (re-find #"Advanced" content))
    (is (not (re-find #"Getting started > Getting started" content)))
    (is (not (re-find #"Getting started > Advanced" content)))))

(deftest prefixes-continuation-chunks-with-section-context
  (let [document {:canonical_url "https://example.test/help"
                  :title "Help"
                  :locale "en"
                  :blocks [{:type :heading
                            :text "Troubleshooting"
                            :heading_path ["Troubleshooting"]}
                           {:type :paragraph
                            :text (str "First paragraph with enough text to fill a chunk. "
                                       "Second sentence with enough text to keep the section going. "
                                       "Third sentence with enough text to force continuation.")
                            :heading_path ["Troubleshooting"]}]}
        chunks (chunk/section-aware document {:max_tokens 18})]
    (is (< 1 (count chunks)))
    (is (every? #(<= (token/estimate (:content %)) 18) chunks))
    (is (some #(str/starts-with? (:content %) "Troubleshooting\n")
              (rest chunks)))
    (is (not-any? #(re-find #"Troubleshooting > Troubleshooting" (:content %))
                  chunks))))

(deftest omits-heading-context-when-it-cannot-fit
  (let [document {:canonical_url "https://example.test/help"
                  :title "Help"
                  :locale "en"
                  :blocks [{:type :paragraph
                            :text "Small sentence."
                            :heading_path ["Extremely verbose troubleshooting heading"
                                           "Another long nested section name"
                                           "Deep subsection with many words"]}]}
        chunks (chunk/section-aware document {:max_tokens 8})]
    (is (= 1 (count chunks)))
    (is (every? #(<= (token/estimate (:content %)) 8) chunks))
    (is (= "Small sentence." (:content (first chunks))))
    (is (= ["Extremely verbose troubleshooting heading"
            "Another long nested section name"
            "Deep subsection with many words"]
           (:heading_path (first chunks))))))
