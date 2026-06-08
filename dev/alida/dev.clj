(ns alida.dev
  (:require [alida.config :as config]
            [alida.system :as system]
            [clojure.pprint :as pprint]))

(def default-config-path "alida.yml")

(defn prep
  []
  (system/system-config default-config-path))

(defn load-config
  ([] (load-config default-config-path))
  ([path] (config/load-config path)))

(defn print-config
  ([] (print-config default-config-path))
  ([path]
   (pprint/pprint (config/load-config path))
   nil))

(defn open-portal
  []
  (require 'portal.api)
  (let [portal ((resolve 'portal.api/open))]
    (add-tap (resolve 'portal.api/submit))
    portal))
