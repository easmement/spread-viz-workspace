(ns jotis.viz.dashboard.core
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [jotis.viz.dashboard.views :as views]
            [jotis.viz.math-engine.interface :as math]))

(defn- price-range
  "Auto-sizes grid to ±2.5σ around spot, step scaled to price magnitude."
  [spot iv dte]
  (let [em   (* spot iv (Math/sqrt (/ dte 365.0)))
        lo   (Math/round (- spot (* 2.5 em)))
        hi   (Math/round (+ spot (* 2.5 em)))
        step (max 1 (Math/round (/ spot 100.0)))]
    [lo hi step]))

(defn days-until [date-str]
  (let [now    (js/Date.)
        exp    (js/Date. date-str)
        ms     (- (.getTime exp) (.getTime now))]
    (max 0 (Math/round (/ ms 86400000)))))

(defn compute-heatmap [{:keys [legs spot iv rate initial-cost expiry]}]
  (let [dte          (days-until expiry)
        [lo hi step] (price-range spot iv dte)]
    (math/generate-heatmap-with-bounds
      [lo hi step]
      [0 dte 1]
      spot
      iv
      (fn [p t]
        (math/spread-pnl legs p t rate iv initial-cost)))))

(defn- date->str [^js d]
  (str (.getFullYear d) "-"
       (-> (.getMonth d) inc (.toString) (.padStart 2 "0")) "-"
       (-> (.getDate d) (.toString) (.padStart 2 "0"))))

(defn- default-expiry []
  (let [d (js/Date.)]
    (.setDate d (+ (.getDate d) 30))
    (date->str d)))

(def app-state
  (r/atom {:ticker       "SPY"
           :option-type  :put
           :legs         [{:type :put :strike 180 :side :short}
                          {:type :put :strike 175 :side :long}]
           :spot         180.0
           :iv           0.35
           :rate         0.05
           :initial-cost 1.50
           :expiry       (default-expiry)}))

(defn mount-root []
  (let [el (.getElementById js/document "app")]
    (rdom/unmount-component-at-node el)
    (rdom/render [views/main-panel app-state compute-heatmap] el)))

(defn init []
  (mount-root))
