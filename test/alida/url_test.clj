(ns alida.url-test
  (:require [alida.url :as url]
            [clojure.test :refer [are deftest is testing]]))

(deftest origin-and-host
  (are [u o h] (and (= o (url/origin u)) (= h (url/host u)))
    "https://example.com/a/b"      "https://example.com"      "example.com"
    "http://example.com:8080/x"    "http://example.com:8080"  "example.com"
    "https://Example.COM/x"        "https://Example.COM"      "example.com")
  (is (nil? (url/origin "not a url")))
  (is (nil? (url/host "not a url"))))

(deftest http-host-only-for-web-schemes
  (is (= "example.com" (url/http-host "https://example.com/x")))
  (is (nil? (url/http-host "file:///etc/passwd")))
  (is (nil? (url/http-host "ftp://example.com/x"))))

(deftest normalize-resolves-and-drops-fragment
  (is (= "https://example.com/a/c"
         (url/normalize "https://example.com/a/b" "c")))
  (is (= "https://example.com/a"
         (url/normalize "https://example.com/a/b" "/a#frag")))
  (is (nil? (url/normalize "https://example.com/a" nil))))

(deftest allowed?-applies-prefix-and-deny-rules
  (testing "empty allowed-prefixes permits any url"
    (is (url/allowed? {} "https://anything/x")))
  (testing "allowed prefix gate"
    (is (url/allowed? {:allowed-prefixes ["https://x/keep/"]} "https://x/keep/a"))
    (is (not (url/allowed? {:allowed-prefixes ["https://x/keep/"]} "https://x/drop/a"))))
  (testing "denied urls and prefixes"
    (is (not (url/allowed? {:denied-urls ["https://x/a"]} "https://x/a")))
    (is (not (url/allowed? {:denied-prefixes ["https://x/no/"]} "https://x/no/a"))))
  (testing "nil/blank url is never allowed"
    (is (not (url/allowed? {} nil)))
    (is (not (url/allowed? {} "")))))

(deftest article-id-is-numeric-only
  (are [u id] (= id (url/article-id u))
    "https://x/servicedesk/customer/portal/7/article/123"        "123"
    "https://x/rest/servicedesk/knowledgebase/latest/articles/view/456" "456"
    "https://x/portal/7/topic/t/article/789"                     "789"
    "https://x/wiki/spaces/KB/pages/2233761796"                  "2233761796")
  (testing "non-numeric action paths are rejected"
    (is (nil? (url/article-id "https://x/portal/7/article/resumedraft.action")))
    (is (nil? (url/article-id "https://x/no/article/here")))))

(deftest path-id-extracts-segment
  (is (= "7" (url/path-id "portal" "https://x/servicedesk/customer/portal/7/topic/t")))
  (is (= "t" (url/path-id "topic" "https://x/servicedesk/customer/portal/7/topic/t")))
  (is (nil? (url/path-id "topic" "https://x/no-topic-here"))))
