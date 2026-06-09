(ns alida.source.local-test
  (:require [alida.source :as source]
            [alida.source.local]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(defn- temp-file
  [suffix content]
  (let [file (java.io.File/createTempFile "alida-source-local" suffix)]
    (spit file content)
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
        ignored (doto (io/file (.toFile dir) "image.bin") (spit "ignored"))]
    (try
      (let [items (source/discover {} {:id "fixtures"
                                       :type "local"
                                       :root (str dir)})]
        (is (= #{(.getCanonicalPath html)}
               (set (map :path items))))
        (is (not-any? #(= (.getCanonicalPath ignored) (:path %)) items)))
      (finally
        (.delete html)
        (.delete ignored)
        (.delete (.toFile dir))))))

(deftest missing-local-files-are-recoverable-anomalies
  (let [item (first (source/discover {} {:id "fixtures"
                                         :type "local"
                                         :paths ["/tmp/alida-does-not-exist.html"]}))]
    (is (source/anomaly? item))
    (is (= :cognitect.anomalies/not-found
           (get-in item [:alida/error :cognitect.anomalies/category])))))
