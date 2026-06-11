(ns alida.source.local
  (:require [alida.source :as source]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def extension-content-types
  {"html" "text/html"
   "htm" "text/html"
   "txt" "text/plain"
   "md" "text/markdown"
   "markdown" "text/markdown"
   "json" "application/json"})

(def default-extensions
  #{"html" "htm"})

(defn- extension
  [path]
  (some-> (re-find #"\.([^.]+)$" (str path))
          second
          str/lower-case))

(defn- content-type
  [path]
  (get extension-content-types (extension path) "application/octet-stream"))

(defn- file-uri
  [^java.io.File file]
  (str (.toURI file)))

(defn- configured-files
  [source-cfg]
  (let [paths (concat (when-let [path (:path source-cfg)] [path])
                      (:paths source-cfg))]
    (map io/file paths)))

(defn- root-files
  [source-cfg]
  (when-let [root (:root source-cfg)]
    (let [extensions (set (or (:include_extensions source-cfg) default-extensions))]
      (->> (file-seq (io/file root))
           (filter #(.isFile ^java.io.File %))
           (filter #(contains? extensions (extension (.getPath ^java.io.File %))))))))

(defn- discover-file
  [source-cfg file]
  (let [file (.getCanonicalFile ^java.io.File file)]
    (if (.exists file)
      {:source_id (:id source-cfg)
       :source_type (:type source-cfg)
       :canonical_url (file-uri file)
       :path (.getPath file)
       :content_type (content-type (.getPath file))}
      (source/anomaly :cognitect.anomalies/not-found
                      {:type :alida.source.local/file-not-found
                       :source-id (:id source-cfg)
                       :path (.getPath file)}))))

(defmethod source/discover :local
  [_sys source-cfg]
  (let [files (concat (configured-files source-cfg)
                      (root-files source-cfg))]
    (when-not (seq files)
      (throw (ex-info "Local source requires path, paths, or root"
                      {:type :alida.source.local/missing-path
                       :source-id (:id source-cfg)})))
    (mapv #(discover-file source-cfg %) files)))

(defmethod source/fetch :local
  [_sys source-cfg discovered-item]
  (if (source/anomaly? discovered-item)
    discovered-item
    (let [file (io/file (:path discovered-item))]
      (if (.exists file)
        (assoc discovered-item
               :body (slurp file :encoding "UTF-8")
               :title (or (:title discovered-item) (.getName file)))
        (source/anomaly :cognitect.anomalies/not-found
                        {:type :alida.source.local/file-not-found
                         :source-id (:id source-cfg)
                         :canonical-url (:canonical_url discovered-item)
                         :path (:path discovered-item)})))))
