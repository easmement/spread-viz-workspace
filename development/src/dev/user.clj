(ns dev.user
  (:require [jotis.viz.dashboard.server :as server]))

(defn start  [] (server/start!))
(defn stop   [] (server/stop!))
(defn restart [] (server/stop!) (server/start!))
