(ns alida.source.local
  (:require [alida.source :as source]
            [alida.source.object-storage :as object-storage]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def default-extensions
  #{"html" "htm"})

(defn- extension
  [path]
  (some-> (re-find #"\.([^.]+)$" (str path))
          second
          str/lower-case))

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
    (let [extensions (set (or (:include_extensions source-cfg) default-extensions))
          root-file (.getCanonicalFile (io/file root))]
      (->> (file-seq (io/file root))
           (filter #(.isFile ^java.io.File %))
           (filter #(contains? extensions (extension (.getPath ^java.io.File %))))
           (filter (fn [file]
                     (object-storage/object-included?
                      source-cfg
                      (str (.relativize (.toPath root-file)
                                        (.toPath (.getCanonicalFile ^java.io.File file)))))))))))

(defn- discover-file
  [source-cfg file]
  (let [file (.getCanonicalFile ^java.io.File file)]
    (if (.exists file)
      {:source_id (:id source-cfg)
       :source_type (:type source-cfg)
       :canonical_url (file-uri file)
       :path (.getPath file)
       :content_type (object-storage/content-type (.getPath file) nil)}
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
        (dissoc (object-storage/fetched-document
                 source-cfg
                 (assoc discovered-item :key (.getName file))
                 {:body (slurp file :encoding "UTF-8")
                  :content_type (:content_type discovered-item)}
                 {:body-fn :body
                  :content-type-fn :content_type})
                :key)
        (source/anomaly :cognitect.anomalies/not-found
                        {:type :alida.source.local/file-not-found
                         :source-id (:id source-cfg)
                         :canonical-url (:canonical_url discovered-item)
                         :path (:path discovered-item)})))))
