(ns alida.verify)

(defn- dispatch-provider
  [_sys provider-cfg & _]
  (keyword (:provider provider-cfg)))

(defmulti complete dispatch-provider)

(defmethod complete :default
  [_sys provider-cfg _prompt]
  (throw (ex-info (str "Unsupported verification provider: " (:provider provider-cfg))
                  {:type :alida.verify/unsupported-provider
                   :provider (:provider provider-cfg)})))
