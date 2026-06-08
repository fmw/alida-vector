(ns user
  (:require [alida.dev :as dev]
            [integrant.repl :as ig-repl]))

(ig-repl/set-prep! dev/prep)

(def go ig-repl/go)
(def halt ig-repl/halt)
(def reset ig-repl/reset)
(def reset-all ig-repl/reset-all)
