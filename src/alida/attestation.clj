(ns alida.attestation
  (:require [alida.db.postgres :as db]
            [alida.verify :as verify]))

(def default-attestor "local")

(defn enabled?
  [verification-cfg]
  (not= false (get-in verification-cfg [:attestations :enabled])))

(defn attestor
  [verification-cfg]
  (or (get-in verification-cfg [:attestations :attestor])
      default-attestor))

(defn- trusted-source-database
  [source]
  (-> source
      (dissoc :name :type :attestors)
      (update :max_pool_size #(or % 1))))

(defn- attestation->llm-result
  [{:keys [llm_verdict reasoning llm_findings llm_security_findings raw_response]}]
  {:verdict llm_verdict
   :reasoning (or reasoning "")
   :findings (vec (or llm_findings []))
   :security_findings (vec (or llm_security_findings []))
   :raw_response (or raw_response {})})

(defn- trusted-attestation
  [source verification-input-hash]
  (with-open [trusted-ds (db/datasource (trusted-source-database source))]
    (when-let [record (db/find-verification-attestation trusted-ds
                                                        verification-input-hash
                                                        (:attestors source))]
      {:llm-result (attestation->llm-result record)
       :source (str "trusted:" (:name source))
       :attestor (:attestor record)})))

(defn find-result
  [local-ds verification-cfg verification-input-hash]
  (when (enabled? verification-cfg)
    (or (some #(trusted-attestation % verification-input-hash)
              (get-in verification-cfg [:attestations :trusted_sources]))
        (when-let [record (db/find-verification-attestation
                           local-ds
                           verification-input-hash
                           [(attestor verification-cfg)])]
          (db/touch-verification-attestation! local-ds
                                              verification-input-hash
                                              (:attestor record))
          {:llm-result (attestation->llm-result record)
           :source "cache"
           :attestor (:attestor record)}))))

(defn save-result!
  [local-ds verification-cfg verification-input-hash llm-result]
  (when (enabled? verification-cfg)
    (let [local-attestor (attestor verification-cfg)]
      (db/save-verification-attestation!
       local-ds
       {:verification_input_hash verification-input-hash
        :attestor local-attestor
        :provider (:provider verification-cfg)
        :model (verify/verifier-model verification-cfg)
        :prompt_policy_version (:prompt_policy_version verification-cfg)
        :deterministic_gate_version (:deterministic_gate_version verification-cfg)
        :verification_input_version verify/verification-input-version
        :llm_verdict (:verdict llm-result)
        :reasoning (:reasoning llm-result)
        :llm_findings (:findings llm-result)
        :llm_security_findings (:security_findings llm-result)
        :raw_response (:raw_response llm-result)})
      local-attestor)))
