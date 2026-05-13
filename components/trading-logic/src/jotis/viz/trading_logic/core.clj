(ns jotis.viz.trading-logic.core
  (:require [jotis.viz.market-data.interface :as data]
            [jotis.viz.math-engine.interface :as math]))

(defn calculate-spread-pnl
  "Calculates P&L for a directional spread.
   legs: collection of {:type :call/:put :strike 180 :side :long/:short}
   initial-cost: net credit (positive) or debit (negative)"
  [legs spot time iv rate initial-cost]
  (let [current-value (reduce (fn [acc {:keys [type strike side]}]
                                (let [price (math/black-scholes type spot strike time iv rate)]
                                  (if (= side :long) (+ acc price) (- acc price))))
                              0.0
                              legs)]
    ;; For credit spreads, initial-cost is the credit received. 
    ;; P&L = (Current Spread Value) - (Entry Spread Value)
    (+ current-value initial-cost)))


(defn get-spread-context
  "Aggregates spot price and IV for a spread analysis."
  [ticker short-option-symbol]
  (let [spot (data/get-spot-price ticker)
        {:keys [iv price]} (data/get-option-snapshot short-option-symbol)]
    {:spot spot
     :iv iv
     :current-spread-price price}))
