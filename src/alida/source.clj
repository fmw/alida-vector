(ns alida.source)

(defn- dispatch-type
  [_sys source-cfg & _]
  (keyword (:type source-cfg)))

(defmulti discover dispatch-type)

(defmulti fetch dispatch-type)

(defmethod discover :default
  [_sys source-cfg]
  (throw (ex-info (str "Unsupported source type: " (:type source-cfg))
                  {:type :alida.source/unsupported
                   :source-type (:type source-cfg)})))

(defmethod fetch :default
  [_sys source-cfg _discovered-item]
  (throw (ex-info (str "Unsupported source type: " (:type source-cfg))
                  {:type :alida.source/unsupported
                   :source-type (:type source-cfg)})))
