(ns alida.token
  (:require [clojure.string :as str]))

(def punctuation-pattern #"[^\p{L}\p{N}\s]")
(def non-ascii-pattern #"[^\p{ASCII}]")
(def token-split-pattern #"[^\p{L}\p{N}]+")

(defn- word-tokens
  [word]
  (let [n (count word)]
    (cond
      (zero? n) 0
      (<= n 4) 1
      (<= n 8) 2
      (<= n 12) 3
      :else (long (Math/ceil (/ n 4.0))))))

(defn estimate
  "Conservative token estimate for embedding chunk limits."
  [s]
  (let [s (or s "")
        words (remove str/blank? (str/split s token-split-pattern))
        base (reduce + (map word-tokens words))
        punctuation-adjusted (if (re-find punctuation-pattern s)
                               (* base 1.2)
                               base)
        non-ascii-adjusted (if (re-find non-ascii-pattern s)
                             (* punctuation-adjusted 1.1)
                             punctuation-adjusted)
        safe (* non-ascii-adjusted 1.3)]
    (max 1 (long (Math/ceil safe)))))

(def sentence-pattern #"(?<=[.!?])\s+")

(defn split-sentences
  [s]
  (->> (str/split (or s "") sentence-pattern)
       (map str/trim)
       (remove str/blank?)
       vec))

(defn split-overlong
  [max-tokens s]
  (letfn [(split-by-chars [max-chars]
            (->> (range 0 (count s) max-chars)
                 (map #(subs s % (min (count s) (+ % max-chars))))
                 (map str/trim)
                 (remove str/blank?)
                 vec))]
    (loop [max-chars (max 1 (long (* max-tokens 3.5)))]
      (let [pieces (split-by-chars max-chars)]
        (cond
          (every? #(<= (estimate %) max-tokens) pieces)
          pieces

          (= 1 max-chars)
          pieces

          :else
          (recur (max 1 (long (Math/floor (/ max-chars 2.0))))))))))

(defn pieces
  [max-tokens s]
  (->> (split-sentences s)
       (mapcat (fn [sentence]
                 (if (> (estimate sentence) max-tokens)
                   (split-overlong max-tokens sentence)
                   [sentence])))
       vec))
