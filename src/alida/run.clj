(ns alida.run
  (:require [alida.config :as config]))

(defn selected-indexes
  [sys index-name]
  (config/selected-indexes (:alida/config sys) index-name))

(defn decide-action
  [{:keys [auto_activate]} {:keys [final_verdict first_run]}]
  (if (and auto_activate
           (not first_run)
           (= "pass" final_verdict))
    :activate
    :hold))
