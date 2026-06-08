(ns alida.main
  (:gen-class)
  (:require [alida.cli :as cli]))

(defn -main
  [& argv]
  (let [{:keys [exit-code message]} (cli/run argv)]
    (when (seq message)
      (binding [*out* (if (zero? exit-code) *out* *err*)]
        (println message)))
    (shutdown-agents)
    (System/exit exit-code)))
