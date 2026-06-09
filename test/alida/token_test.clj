(ns alida.token-test
  (:require [alida.token :as token]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest token-estimator-is-conservative
  (is (< (token/estimate "short words") (token/estimate "short words with punctuation!")))
  (is (< (token/estimate "plain ascii") (token/estimate "plain ascii plus cafe\u0301"))))

(deftest splits-sentences-and-overlong-sentences
  (is (= ["First sentence." "Second sentence!" "Third?"]
         (token/split-sentences "First sentence. Second sentence! Third?")))
  (let [long-sentence (str/join "" (repeat 100 "x"))
        pieces (token/pieces 5 long-sentence)]
    (is (< 1 (count pieces)))
    (is (every? #(<= (token/estimate %) 5) pieces))))

(deftest split-overlong-keeps-estimated-tokens-under-limit
  (let [pieces (token/pieces 5 (apply str (repeat 100 "x")))]
    (is (< 1 (count pieces)))
    (is (every? #(<= (token/estimate %) 5) pieces))))
