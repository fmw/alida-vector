(ns alida.main
  (:gen-class)
  (:require [alida.cli :as cli]
            [alida.embed.azure-openai]
            [alida.embed.openai]
            [alida.embed.vertex-ai]
            [alida.source.local]
            [alida.source.website]
            [alida.verify.azure-openai]
            [alida.verify.openai]
            [alida.verify.vertex-ai]))

(defn -main
  [& argv]
  (let [{:keys [exit-code message]} (cli/run argv)]
    (when (seq message)
      (binding [*out* (if (zero? exit-code) *out* *err*)]
        (println message)))
    (shutdown-agents)
    (System/exit exit-code)))
