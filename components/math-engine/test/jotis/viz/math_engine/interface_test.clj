(ns jotis.viz.math-engine.interface-test
  (:require [clojure.test :refer [deftest is testing]]
            [jotis.viz.math-engine.interface :as math]))

(defn- close? [a b tol] (< (Math/abs (- (double a) (double b))) tol))

(deftest black-scholes-reference-values
  (testing "textbook case: S=100 K=100 T=1 r=5% sigma=20%"
    (is (close? 10.4506 (math/black-scholes :call 100 100 1.0 0.05 0.20) 1e-3))
    (is (close? 5.5735 (math/black-scholes :put 100 100 1.0 0.05 0.20) 1e-3)))
  (testing "put-call parity: C - P = S - K e^(-rT)"
    (let [s 180 k 175 t (/ 30 365.0) r 0.05 iv 0.35]
      (is (close? (- (math/black-scholes :call s k t r iv) (math/black-scholes :put s k t r iv))
                  (- s (* k (Math/exp (- (* r t)))))
                  1e-9))))
  (testing "at expiry, intrinsic value"
    (is (== 5.0 (math/black-scholes :put 175 180 0 0.05 0.35)))))

(deftest spread-pnl-default-put-credit-spread
  ;; The dashboard's default: short 180 put, long 175 put, $1.50 credit, spot 180, 30 days,
  ;; IV 35%, rate 5%. With IV and rate swapped (the old bug) this came out at +1.48.
  (let [legs [{:type :put :strike 180 :side :short} {:type :put :strike 175 :side :long}]
        pnl  (math/spread-pnl legs 180.0 (/ 30 365.0) 0.05 0.35 1.50)]
    (is (close? -0.73 pnl 0.01))
    (testing "at expiry far above both strikes, the credit is kept"
      (is (close? 1.50 (math/spread-pnl legs 250.0 0 0.05 0.35 1.50) 1e-9)))
    (testing "at expiry below both strikes, max loss = width - credit"
      (is (close? -3.50 (math/spread-pnl legs 150.0 0 0.05 0.35 1.50) 1e-9)))))
