(ns alida.attestation-test
  (:require [alida.attestation :as attestation]
            [alida.db.postgres :as db]
            [clojure.test :refer [deftest is]]))

(def cached-record
  {:verification_input_hash "input-hash"
   :attestor "pre-production"
   :llm_verdict "pass"
   :reasoning "Previously verified"
   :llm_findings [{:type "consistent"}]
   :llm_security_findings []
   :raw_response {:verdict "pass"}})

(deftest trusted-attestations-take-precedence-over-the-local-cache
  (let [opened-config (atom nil)
        closed? (atom false)
        trusted-ds (reify java.io.Closeable
                     (close [_] (reset! closed? true)))
        verification-cfg {:attestations
                          {:attestor "production"
                           :trusted_sources
                           [{:name "pre-production"
                             :type "postgres"
                             :jdbc_url "jdbc:postgresql://example.test/attestations"
                             :user "reader"
                             :password "secret"
                             :attestors ["pre-production"]}]}}]
    (with-redefs [db/datasource (fn [database]
                                  (reset! opened-config database)
                                  trusted-ds)
                  db/find-verification-attestation
                  (fn [ds input-hash attestors]
                    (is (= trusted-ds ds))
                    (is (= "input-hash" input-hash))
                    (is (= ["pre-production"] attestors))
                    cached-record)]
      (is (= {:llm-result {:verdict "pass"
                           :reasoning "Previously verified"
                           :findings [{:type "consistent"}]
                           :security_findings []
                           :raw_response {:verdict "pass"}}
              :source "trusted:pre-production"
              :attestor "pre-production"}
             (attestation/find-result :local-ds verification-cfg "input-hash"))))
    (is (= {:jdbc_url "jdbc:postgresql://example.test/attestations"
            :user "reader"
            :password "secret"
            :read_only true
            :max_pool_size 1}
           @opened-config))
    (is @closed?)))

(deftest local-cache-is-used-when-no-trusted-attestation-matches
  (let [touched (atom nil)
        verification-cfg {:attestations {:attestor "candidate"}}]
    (with-redefs [db/find-verification-attestation
                  (fn [ds input-hash attestors]
                    (is (= :local-ds ds))
                    (is (= "input-hash" input-hash))
                    (is (= ["candidate"] attestors))
                    (assoc cached-record :attestor "candidate"))
                  db/touch-verification-attestation!
                  (fn [ds input-hash attestor]
                    (reset! touched [ds input-hash attestor]))]
      (is (= "cache"
             (:source (attestation/find-result :local-ds
                                               verification-cfg
                                               "input-hash"))))
      (is (= [:local-ds "input-hash" "candidate"] @touched)))))

(deftest provider-results-are-saved-as-local-attestations
  (let [saved (atom nil)
        verification-cfg {:provider "openai"
                          :model "gpt-test"
                          :prompt_policy_version "policy-1"
                          :deterministic_gate_version "gate-1"
                          :attestations {:attestor "candidate"}}
        llm-result {:verdict "caution"
                    :reasoning "Review this"
                    :findings [{:type "possible-issue"}]
                    :security_findings []
                    :raw_response {:verdict "caution"}}]
    (with-redefs [db/save-verification-attestation!
                  (fn [ds record]
                    (is (= :local-ds ds))
                    (reset! saved record))]
      (is (= "candidate"
             (attestation/save-result! :local-ds
                                       verification-cfg
                                       "input-hash"
                                       llm-result))))
    (is (= {:verification_input_hash "input-hash"
            :attestor "candidate"
            :provider "openai"
            :model "gpt-test"
            :prompt_policy_version "policy-1"
            :deterministic_gate_version "gate-1"
            :verification_input_version "2"
            :llm_verdict "caution"
            :reasoning "Review this"
            :llm_findings [{:type "possible-issue"}]
            :llm_security_findings []
            :raw_response {:verdict "caution"}}
           @saved))))

(deftest attestations-can-be-disabled
  (with-redefs [db/find-verification-attestation
                (fn [& _] (throw (ex-info "cache should not be read" {})))
                db/save-verification-attestation!
                (fn [& _] (throw (ex-info "cache should not be written" {})))]
    (is (nil? (attestation/find-result :local-ds
                                       {:attestations {:enabled false}}
                                       "input-hash")))
    (is (nil? (attestation/save-result! :local-ds
                                        {:attestations {:enabled false}}
                                        "input-hash"
                                        {:verdict "pass"})))))
