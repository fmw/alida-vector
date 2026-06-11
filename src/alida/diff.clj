(ns alida.diff
  (:require [clojure.set :as set]))

(defn- identity-key
  [document]
  [(:source_id document) (:canonical_url document)])

(defn- by-identity
  [documents]
  (into {} (map (juxt identity-key identity)) documents))

(defn- url-entry
  [document]
  (select-keys document [:source_id :canonical_url :title :locale :normalized_content_hash]))

(defn- changed-entry
  [previous current]
  (assoc (url-entry current)
         :previous_normalized_content_hash (:normalized_content_hash previous)
         :current_normalized_content_hash (:normalized_content_hash current)))

(defn- moved-entries
  [removed added]
  (let [removed-by-hash (group-by :normalized_content_hash removed)]
    (vec
     (for [current added
           previous (get removed-by-hash (:normalized_content_hash current))
           :when (:normalized_content_hash current)]
       {:source_id (:source_id current)
        :previous_canonical_url (:canonical_url previous)
        :current_canonical_url (:canonical_url current)
        :title (:title current)
        :locale (:locale current)
        :normalized_content_hash (:normalized_content_hash current)}))))

(defn compute
  [previous-documents current-documents]
  (let [previous-by-id (by-identity previous-documents)
        current-by-id (by-identity current-documents)
        previous-ids (set (keys previous-by-id))
        current-ids (set (keys current-by-id))
        added-docs (mapv current-by-id (sort-by second (remove previous-ids current-ids)))
        removed-docs (mapv previous-by-id (sort-by second (remove current-ids previous-ids)))
        changed (vec
                 (for [id (sort-by second (set/intersection previous-ids current-ids))
                       :let [previous (previous-by-id id)
                             current (current-by-id id)]
                       :when (not= (:normalized_content_hash previous)
                                   (:normalized_content_hash current))]
                   (changed-entry previous current)))
        added (mapv url-entry added-docs)
        removed (mapv url-entry removed-docs)
        moved (moved-entries removed-docs added-docs)]
    {:summary {:previous_document_count (count previous-documents)
               :current_document_count (count current-documents)
               :added_count (count added)
               :removed_count (count removed)
               :changed_count (count changed)
               :moved_count (count moved)}
     :added_urls added
     :removed_urls removed
     :changed_urls changed
     :moved_urls moved}))
