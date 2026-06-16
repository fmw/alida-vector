(ns alida.source.local-test
  (:require [alida.source :as source]
            [alida.source.local]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(defn- temp-file
  [suffix content]
  (let [file (java.io.File/createTempFile "alida-source-local" suffix)]
    (spit file content :encoding "UTF-8")
    file))

(deftest discovers-and-fetches-configured-local-files
  (let [file (temp-file ".html" "<h1>Hello</h1>")]
    (try
      (let [items (source/discover {} {:id "fixtures"
                                       :type "local"
                                       :paths [(.getPath file)]})
            fetched (source/fetch {} {:id "fixtures" :type "local"} (first items))]
        (is (= 1 (count items)))
        (is (= "fixtures" (:source_id fetched)))
        (is (= "local" (:source_type fetched)))
        (is (= "text/html" (:content_type fetched)))
        (is (.startsWith (:canonical_url fetched) "file:"))
        (is (= "<h1>Hello</h1>" (:body fetched)))
        (is (= (.getName file) (:title fetched))))
      (finally
        (.delete file)))))

(deftest discovers-root-files-by-extension
  (let [dir (java.nio.file.Files/createTempDirectory "alida-source-local-root" (make-array java.nio.file.attribute.FileAttribute 0))
        html (doto (io/file (.toFile dir) "article.html") (spit "<p>Article</p>"))
        text (doto (io/file (.toFile dir) "notes.txt") (spit "Notes"))
        ignored (doto (io/file (.toFile dir) "image.bin") (spit "ignored"))]
    (try
      (let [items (source/discover {} {:id "fixtures"
                                       :type "local"
                                       :root (str dir)})]
        (is (= #{(.getCanonicalPath html)}
               (set (map :path items))))
        (is (not-any? #(= (.getCanonicalPath text) (:path %)) items))
        (is (not-any? #(= (.getCanonicalPath ignored) (:path %)) items)))
      (finally
        (.delete html)
        (.delete text)
        (.delete ignored)
        (.delete (.toFile dir))))))

(deftest explicit-root-extensions-can-include-non-html-files
  (let [dir (java.nio.file.Files/createTempDirectory "alida-source-local-root" (make-array java.nio.file.attribute.FileAttribute 0))
        html (doto (io/file (.toFile dir) "article.html") (spit "<p>Article</p>"))
        text (doto (io/file (.toFile dir) "notes.txt") (spit "Notes"))]
    (try
      (let [items (source/discover {} {:id "fixtures"
                                       :type "local"
                                       :root (str dir)
                                       :include_extensions ["txt"]})]
        (is (= #{(.getCanonicalPath text)}
               (set (map :path items))))
        (is (= ["text/plain"] (mapv :content_type items)))
        (is (not-any? #(= (.getCanonicalPath html) (:path %)) items)))
      (finally
        (.delete html)
        (.delete text)
        (.delete (.toFile dir))))))

(deftest root-files-support-shared-glob-filters
  (let [dir (java.nio.file.Files/createTempDirectory "alida-source-local-root" (make-array java.nio.file.attribute.FileAttribute 0))
        public-dir (doto (io/file (.toFile dir) "public") .mkdir)
        private-dir (doto (io/file (.toFile dir) "private") .mkdir)
        included (doto (io/file public-dir "guide.json") (spit "{}"))
        excluded (doto (io/file private-dir "secret.json") (spit "{}"))
        ignored (doto (io/file (.toFile dir) "notes.txt") (spit "Notes"))]
    (try
      (let [items (source/discover {} {:id "fixtures"
                                       :type "local"
                                       :root (str dir)
                                       :include_extensions ["json" "txt"]
                                       :include_globs ["public/*.json"]
                                       :exclude_globs ["private/**"]})]
        (is (= #{(.getCanonicalPath included)}
               (set (map :path items))))
        (is (not-any? #(= (.getCanonicalPath excluded) (:path %)) items))
        (is (not-any? #(= (.getCanonicalPath ignored) (:path %)) items)))
      (finally
        (.delete included)
        (.delete excluded)
        (.delete ignored)
        (.delete public-dir)
        (.delete private-dir)
        (.delete (.toFile dir))))))

(deftest fetches-local-files-as-utf-8
  (let [file (temp-file ".html" "<p>Résumé</p>")]
    (try
      (let [item (first (source/discover {} {:id "fixtures"
                                             :type "local"
                                             :paths [(.getPath file)]}))
            fetched (source/fetch {} {:id "fixtures" :type "local"} item)]
        (is (= "<p>Résumé</p>" (:body fetched))))
      (finally
        (.delete file)))))

(deftest missing-local-files-are-recoverable-anomalies
  (let [item (first (source/discover {} {:id "fixtures"
                                         :type "local"
                                         :paths ["/tmp/alida-does-not-exist.html"]}))]
    (is (source/anomaly? item))
    (is (= :cognitect.anomalies/not-found
           (get-in item [:alida/error :cognitect.anomalies/category])))))
