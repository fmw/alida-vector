(ns alida.diff-test
  (:require [alida.diff :as diff]
            [clojure.test :refer [deftest is]]))

(defn- doc
  [source-id url hash]
  {:source_id source-id
   :canonical_url url
   :title url
   :locale "en"
   :normalized_content_hash hash})

(deftest computes-added-removed-changed-and-moved-documents
  (let [previous [(doc "docs" "https://example.test/removed" "removed-hash")
                  (doc "docs" "https://example.test/changed" "old-hash")
                  (doc "docs" "https://example.test/moved-from" "moved-hash")
                  (doc "docs" "https://example.test/unchanged" "same-hash")]
        current [(doc "docs" "https://example.test/added" "added-hash")
                 (doc "docs" "https://example.test/changed" "new-hash")
                 (doc "docs" "https://example.test/moved-to" "moved-hash")
                 (doc "docs" "https://example.test/unchanged" "same-hash")]
        result (diff/compute previous current)]
    (is (= {:previous_document_count 4
            :current_document_count 4
            :added_count 2
            :removed_count 2
            :changed_count 1
            :moved_count 1}
           (:summary result)))
    (is (= #{"https://example.test/added"
             "https://example.test/moved-to"}
           (set (map :canonical_url (:added_urls result)))))
    (is (= #{"https://example.test/removed"
             "https://example.test/moved-from"}
           (set (map :canonical_url (:removed_urls result)))))
    (is (= [{:source_id "docs"
             :canonical_url "https://example.test/changed"
             :title "https://example.test/changed"
             :locale "en"
             :normalized_content_hash "new-hash"
             :previous_normalized_content_hash "old-hash"
             :current_normalized_content_hash "new-hash"}]
           (:changed_urls result)))
    (is (= [{:source_id "docs"
             :previous_canonical_url "https://example.test/moved-from"
             :current_canonical_url "https://example.test/moved-to"
             :title "https://example.test/moved-to"
             :locale "en"
             :normalized_content_hash "moved-hash"}]
           (:moved_urls result)))))
