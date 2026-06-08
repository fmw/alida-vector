(ns alida.embed)

(defn- dispatch-provider
  [_sys provider-cfg & _]
  (keyword (:provider provider-cfg)))

(defmulti embed-batch dispatch-provider)

(defmethod embed-batch :default
  [_sys provider-cfg _texts]
  (throw (ex-info (str "Unsupported embedding provider: " (:provider provider-cfg))
                  {:type :alida.embed/unsupported-provider
                   :provider (:provider provider-cfg)})))
