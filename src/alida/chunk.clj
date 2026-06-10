(ns alida.chunk
  (:require [alida.text :as text]
            [alida.token :as token]
            [clojure.string :as str]))

(defn- heading-context
  [block]
  (when (and (not= :heading (:type block))
             (seq (:heading_path block)))
    (str/join " > " (:heading_path block))))

(defn- part-starting-chunk
  [max-tokens block part]
  (if-let [context (heading-context block)]
    (let [prefixed (str context "\n" part)]
      (if (<= (token/estimate prefixed) max-tokens)
        prefixed
        part))
    part))

(defn- chunk-starting-parts
  [max-tokens block]
  (if-let [context (heading-context block)]
    (let [context-tokens (token/estimate context)
          initial-budget (max 1 (- max-tokens context-tokens))]
      (if (>= context-tokens max-tokens)
        (token/pieces max-tokens (:text block))
        (loop [budget initial-budget]
          (let [parts (token/pieces budget (:text block))]
            (if (or (= 1 budget)
                    (every? #(<= (token/estimate (part-starting-chunk max-tokens block %))
                                 max-tokens)
                            parts))
              parts
              (recur (dec budget)))))))
    (token/pieces max-tokens (:text block))))

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
                    :content_hash (text/sha-256 content)
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
                     parts (chunk-starting-parts max-tokens block)]
                 (reduce
                  (fn [{:keys [chunks current heading-path]} part]
                    (let [part (if (seq (:parts current))
                                 part
                                 (part-starting-chunk max-tokens block part))
                          candidate-parts (conj (:parts current) part)
                          candidate-content (str/join "\n\n" candidate-parts)
                          candidate-tokens (token/estimate candidate-content)]
                      (if (and (seq (:parts current))
                               (> candidate-tokens max-tokens))
                        {:chunks (flush-chunk chunks current document heading-path)
                         :current {:parts [(part-starting-chunk max-tokens block part)]}
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
