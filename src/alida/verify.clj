(ns alida.verify)

(def verdict-rank
  {"pass" 0
   "caution" 1
   "fail" 2})

(defn strictest-verdict
  [& verdicts]
  (or (last (sort-by verdict-rank (remove nil? verdicts)))
      "pass"))

(defn- ratio
  [numerator denominator]
  (when (pos? denominator)
    (/ (double numerator) denominator)))

(defn- finding
  [check verdict message details]
  {:check check
   :verdict verdict
   :message message
   :details details})

(defn- threshold-finding
  [check actual threshold details]
  (finding check
           "caution"
           (str (name check) " exceeded deterministic threshold")
           (assoc details
                  :actual actual
                  :threshold threshold)))

(defn- check-max-removed-absolute
  [threshold summary]
  (let [removed-count (:removed_count summary 0)]
    (when (and (some? threshold)
               (> removed-count threshold))
      (threshold-finding :max_removed_absolute
                         removed-count
                         threshold
                         {:removed_count removed-count}))))

(defn- check-max-removed-percentage
  [threshold summary]
  (let [previous-count (:previous_document_count summary 0)
        removed-count (:removed_count summary 0)
        actual (ratio removed-count previous-count)]
    (when (and (some? threshold)
               actual
               (> actual threshold))
      (threshold-finding :max_removed_percentage
                         actual
                         threshold
                         {:removed_count removed-count
                          :previous_document_count previous-count}))))

(defn- check-max-changed-percentage
  [threshold summary]
  (let [previous-count (:previous_document_count summary 0)
        changed-count (:changed_count summary 0)
        actual (ratio changed-count previous-count)]
    (when (and (some? threshold)
               actual
               (> actual threshold))
      (threshold-finding :max_changed_percentage
                         actual
                         threshold
                         {:changed_count changed-count
                          :previous_document_count previous-count}))))

(defn- check-max-item-failure-percentage
  [threshold crawl-summary]
  (let [document-count (:document_count crawl-summary 0)
        error-count (:error_count crawl-summary 0)
        item-count (+ document-count error-count)
        actual (ratio error-count item-count)]
    (when (and (some? threshold)
               actual
               (> actual threshold))
      (threshold-finding :max_item_failure_percentage
                         actual
                         threshold
                         {:document_count document-count
                          :error_count error-count
                          :item_count item-count}))))

(defn- check-max-empty-or-short-document-percentage
  [threshold crawl-summary]
  (let [document-count (:document_count crawl-summary 0)
        error-count (:error_count crawl-summary 0)
        empty-or-short-count (:empty_or_short_document_count crawl-summary 0)
        item-count (+ document-count error-count)
        actual (ratio empty-or-short-count item-count)]
    (when (and (some? threshold)
               actual
               (> actual threshold))
      (threshold-finding :max_empty_or_short_document_percentage
                         actual
                         threshold
                         {:document_count document-count
                          :error_count error-count
                          :empty_or_short_document_count empty-or-short-count
                          :item_count item-count}))))

(defn deterministic-gate
  [{:keys [deterministic_thresholds]} crawl-summary run-diff]
  (let [thresholds deterministic_thresholds
        diff-summary (:summary run-diff)
        findings (vec (keep identity
                            [(check-max-removed-absolute (:max_removed_absolute thresholds) diff-summary)
                             (check-max-removed-percentage (:max_removed_percentage thresholds) diff-summary)
                             (check-max-changed-percentage (:max_changed_percentage thresholds) diff-summary)
                             (check-max-item-failure-percentage (:max_item_failure_percentage thresholds)
                                                                crawl-summary)
                             (check-max-empty-or-short-document-percentage
                              (:max_empty_or_short_document_percentage thresholds)
                              crawl-summary)]))]
    {:deterministic_verdict (apply strictest-verdict (map :verdict findings))
     :deterministic_findings findings}))

(defn- dispatch-provider
  [_sys provider-cfg & _]
  (keyword (:provider provider-cfg)))

(defmulti complete dispatch-provider)

(defmethod complete :default
  [_sys provider-cfg _prompt]
  (throw (ex-info (str "Unsupported verification provider: " (:provider provider-cfg))
                  {:type :alida.verify/unsupported-provider
                   :provider (:provider provider-cfg)})))
