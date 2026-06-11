(ns alida.run
  (:require [alida.config :as config]))

(defn selected-indexes
  [sys index-name]
  (config/selected-indexes (:alida/config sys) index-name))

(defn decide-action
  [{:keys [auto_activate embedding]} {:keys [final_verdict first_run]}]
  (if (and auto_activate
           (not first_run)
           (not= "noop" (:provider embedding))
           (= "pass" final_verdict))
    :activate
    :hold))
