(ns alida.extract.text-test
  (:require [alida.extract.text :as extract-text]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest extracts-plain-text-paragraphs
  (let [document (extract-text/extract
                  {}
                  {:canonical_url "file:/notes.txt"
                   :title "notes.txt"
                   :content_type "text/plain"
                   :body "First paragraph.\n\nSecond paragraph."})]
    (is (= "notes.txt" (:title document)))
    (is (= ["First paragraph." "Second paragraph."]
           (mapv :text (:blocks document))))
    (is (= [:paragraph :paragraph] (mapv :type (:blocks document))))
    (is (= 64 (count (:normalized_content_hash document))))))

(deftest extracts-markdown-headings-as-context
  (let [document (extract-text/extract
                  {}
                  {:canonical_url "file:/guide.md"
                   :title "guide.md"
                   :content_type "text/markdown"
                   :body "# Guide\n\nIntro text.\n\n## Install\n\nRun the installer."})]
    (is (= ["Guide" "Intro text." "Install" "Run the installer."]
           (mapv :text (:blocks document))))
    (is (= [[:heading ["Guide"]]
            [:paragraph ["Guide"]]
            [:heading ["Guide" "Install"]]
            [:paragraph ["Guide" "Install"]]]
           (mapv (juxt :type :heading_path) (:blocks document))))))

(deftest extracts-json-keys-and-values
  (let [document (extract-text/extract
                  {}
                  {:canonical_url "file:/data.json"
                   :title "data.json"
                   :content_type "application/json"
                   :body "{\"title\":\"API\",\"sections\":[{\"name\":\"Limits\"}]}"})]
    (is (= ["title: API" "sections" "0" "name: Limits"]
           (mapv :text (:blocks document))))
    (is (= [["sections"] ["sections" "0"] ["sections" "0"]]
           (mapv :heading_path (rest (:blocks document)))))))

(deftest extracts-configured-json-html-fields
  (let [document (extract-text/extract
                  {:json_extract {:mode "html-fields"
                                  :field_type_key "type"
                                  :field_type_value "content_text"
                                  :html_field "content"
                                  :title_path ["title"]
                                  :locale_from_filename {:pattern "^([A-Z]{2})-"
                                                         :mappings {:EN "en_US"}}}}
                  {:canonical_url "gs://fixtures/courses/EN-guide.json"
                   :title "courses/EN-guide.json"
                   :content_type "application/json"
                   :body (str "{\"title\":\"Guide\",\"sections\":["
                              "{\"content\":{\"type\":\"content_text\","
                              "\"content\":\"<h1>Intro</h1><p>Hello <strong>world</strong>.</p>\"}},"
                              "{\"content\":{\"type\":\"gallery\","
                              "\"content\":\"<p>Ignored gallery text.</p>\"}},"
                              "{\"content\":{\"type\":\"content_text\","
                              "\"content\":\"<p>Second section.</p>\"}}]}")})]
    (is (= "Guide" (:title document)))
    (is (= "en_US" (:html_locale document)))
    (is (= ["Intro" "Hello world." "Second section."]
           (mapv :text (:blocks document))))
    (is (not (str/includes? (:normalized_content document) "<strong>")))
    (is (not (str/includes? (:normalized_content document) "Ignored gallery text")))))

(deftest preserves-json-key-text
  (let [document (extract-text/extract
                  {}
                  {:canonical_url "file:/schema.json"
                   :title "schema.json"
                   :content_type "application/json"
                   :body "{\"a/b\":\"c\",\"http://schema.org/name\":\"Acme\"}"})]
    (is (= ["a/b: c" "http://schema.org/name: Acme"]
           (mapv :text (:blocks document))))))

(deftest strip-text-applies-to-text-markdown-and-json
  (let [source-cfg {:strip_text ["Boilerplate"]}
        texts (fn [content-type body]
                (->> (extract-text/extract
                      source-cfg
                      {:canonical_url (str "file:/example." content-type)
                       :title "example"
                       :content_type content-type
                       :body body})
                     :blocks
                     (mapv :text)))]
    (is (= ["Useful text."] (texts "text/plain" "Useful text. Boilerplate")))
    (is (= ["Title" "Useful text."] (texts "text/markdown" "# Title\n\nUseful text. Boilerplate")))
    (is (= ["body: Useful text."] (texts "application/json" "{\"body\":\"Useful text. Boilerplate\"}")))))

(deftest invalid-json-falls-back-to-plain-text
  (let [document (extract-text/extract
                  {}
                  {:canonical_url "file:/bad.json"
                   :title "bad.json"
                   :content_type "application/json"
                   :body "{not json}"})]
    (is (= ["{not json}"] (mapv :text (:blocks document))))))
