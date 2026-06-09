(ns alida.text-test
  (:require [alida.text :as text]
            [clojure.test :refer [deftest is]]))

(deftest normalizes-whitespace-control-characters-and-quotes
  (is (= "Hello \"world\" 'again'"
         (text/normalize-text " Hello\n\u0000\u201Cworld\u201D  \u2018again\u2019 "))))

(deftest hashes-are-stable
  (is (= "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
         (text/sha-256 "hello"))))
