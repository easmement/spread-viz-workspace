(ns jotis.viz.trade-store.interface-test
  (:require [clojure.test :refer [deftest is]]
            [jotis.viz.trade-store.interface :as store]))

(defn- close? [a b] (< (Math/abs (- (double a) (double b))) 1e-9))

(deftest credit-spread-pnl
  ;; Sold for +1.50 credit, bought back for 0.40 (a debit, so -0.40): keep 1.10 x 100 x 2.
  (is (close? 220.0 (store/trade-pnl {:instrument-type "vertical-spread" :open-value 1.50
                                 :close-value -0.40 :qty 2}))))

(deftest debit-spread-pnl
  ;; Bought for 2.00 (debit, -2.00), sold for 3.00 (credit, +3.00): +1.00 x 100.
  (is (close? 100.0 (store/trade-pnl {:instrument-type "butterfly" :open-value -2.0
                                 :close-value 3.0 :qty 1}))))

(deftest stock-pnl-respects-side
  (is (close? 50.0 (store/trade-pnl {:instrument-type "stock" :side "long" :open-value 100.0
                                :close-value 105.0 :qty 10})))
  (is (close? 50.0 (store/trade-pnl {:instrument-type "stock" :side "short" :open-value 100.0
                                :close-value 95.0 :qty 10}))))
