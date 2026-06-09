(ns alida.chunk
  (:require [alida.token :as token]
            [clojure.string :as str]))

(defn- prefixed-text
  [block]
  (let [heading-path (when-not (= :heading (:type block))
                       (:heading_path block))
        prefix (when (seq heading-path)
                 (str/join " > " heading-path))]
    (if (seq prefix)
      (str prefix "\n" (:text block))
      (:text block))))

(defn- flush-chunk
  [chunks current document heading-path]
  (if (seq (:parts current))
    (let [content (str/join "\n\n" (:parts current))
          chunk-index (count chunks)]
      (conj chunks {:canonical_url (:canonical_url document)
                    :title (:title document)
                    :locale (:locale document)
                    :language_source (:language_source document)
                    :language_confidence (:language_confidence document)
                    :heading_path heading-path
                    :chunk_index chunk-index
                    :content content
                    :estimated_tokens (token/estimate content)
                    :metadata {:source_title (:title document)
                               :canonical_url (:canonical_url document)
                               :locale (:locale document)
                               :language_source (:language_source document)
                               :language_confidence (:language_confidence document)
                               :heading_path heading-path}}))
    chunks))

(defn section-aware
  [document {:keys [max_tokens]}]
  (let [max-tokens (or max_tokens 6550)
        step (fn [{:keys [chunks current heading-path]} block]
               (let [block-heading-path (:heading_path block)
                     parts (token/pieces max-tokens (prefixed-text block))]
                 (reduce
                  (fn [{:keys [chunks current heading-path]} part]
                    (let [candidate-parts (conj (:parts current) part)
                          candidate-content (str/join "\n\n" candidate-parts)
                          candidate-tokens (token/estimate candidate-content)]
                      (if (and (seq (:parts current))
                               (> candidate-tokens max-tokens))
                        {:chunks (flush-chunk chunks current document heading-path)
                         :current {:parts [part]}
                         :heading-path block-heading-path}
                        {:chunks chunks
                         :current {:parts candidate-parts}
                         :heading-path block-heading-path})))
                  {:chunks chunks
                   :current current
                   :heading-path heading-path}
                  parts)))
        result (reduce step
                       {:chunks []
                        :current {:parts []}
                        :heading-path []}
                       (:blocks document))
        chunks (flush-chunk (:chunks result)
                            (:current result)
                            document
                            (:heading-path result))
        chunk-count (count chunks)]
    (mapv #(assoc % :chunk_count chunk-count) chunks)))
