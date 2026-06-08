(ns alida.system
  (:require [alida.config :as config]
            [com.brunobonacci.mulog :as u]
            [integrant.core :as ig]))

(defn system-config
  [config-path]
  {:alida/config {:path config-path}
   :alida/log {:type :console}})

(defmethod ig/init-key :alida/config
  [_ {:keys [path]}]
  (config/load-config path))

(defmethod ig/init-key :alida/log
  [_ _opts]
  (u/start-publisher! {:type :console}))

(defmethod ig/halt-key! :alida/log
  [_ stop-publisher!]
  (when stop-publisher!
    (stop-publisher!)))
