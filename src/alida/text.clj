(ns alida.text
  (:require [clojure.string :as str])
  (:import [java.math BigInteger]
           [java.text Normalizer Normalizer$Form]
           [java.security MessageDigest]))

(def zero-width-pattern
  (re-pattern "[\\u200B\\u200C\\u200D\\uFEFF]"))

(def control-pattern #"\p{Cntrl}")
(def whitespace-pattern #"\s+")

(def quote-replacements
  {(str \u2018) "'"
   (str \u2019) "'"
   (str \u201A) "'"
   (str \u201B) "'"
   (str \u201C) "\""
   (str \u201D) "\""
   (str \u201E) "\""
   (str \u201F) "\""})

(defn sha-256
  [s]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (or s "") "UTF-8"))]
    (format "%064x" (BigInteger. 1 digest))))

(defn normalize-text
  [s]
  (when s
    (-> s
        (Normalizer/normalize Normalizer$Form/NFKC)
        (str/replace zero-width-pattern "")
        (str/replace control-pattern " ")
        (as-> value (reduce-kv str/replace value quote-replacements))
        (str/replace whitespace-pattern " ")
        str/trim)))

(defn meaningful?
  [s min-length]
  (>= (count (or (normalize-text s) "")) min-length))
