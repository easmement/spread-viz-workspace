(ns jotis.viz.math-engine.interface
  #?(:clj (:import [org.apache.commons.math3.distribution NormalDistribution])))

#?(:clj (def ^:private std-nd (NormalDistribution. 0.0 1.0)))

;; Abramowitz & Stegun 7.1.26 approximation, max error 1.5e-7
#?(:cljs
   (defn- erf* [x]
     (let [ax (Math/abs x)
           t  (/ 1.0 (+ 1.0 (* 0.3275911 ax)))
           p  (* t (+ (* t (+ (* t (+ (* t (+ (* t  1.061405429)
                                                  -1.453152027))
                                          1.421413741))
                                 -0.284496736))
                    0.254829592))
           v  (- 1.0 (* p (Math/exp (- (* ax ax)))))]
       (if (>= x 0) v (- v)))))

(defn- cdf [x]
  #?(:clj  (.cumulativeProbability std-nd x)
     :cljs (* 0.5 (+ 1.0 (erf* (/ x (Math/sqrt 2.0)))))))

(defn- pdf [z]
  #?(:clj  (.density std-nd z)
     :cljs (/ (Math/exp (* -0.5 z z))
              (Math/sqrt (* 2 Math/PI)))))

(defn- d1 [s k t r iv]
  (/ (+ (Math/log (/ s k))
        (* (+ r (/ (Math/pow iv 2) 2)) t))
     (* iv (Math/sqrt t))))

(defn- d2 [d1-val iv t]
  (- d1-val (* iv (Math/sqrt t))))

(defn black-scholes
  "Calculates theoretical price.
   t: Time in years (e.g., 30 days = 30/365)
   iv: Implied Volatility as decimal (e.g., 0.25)"
  [type s k t r iv]
  (if (<= t 0)
    (if (= type :call) (max 0.0 (- s k)) (max 0.0 (- k s)))
    (let [d1-v (d1 s k t r iv)
          d2-v (d2 d1-v iv t)]
      (if (= type :call)
        (- (* s (cdf d1-v))
           (* k (Math/exp (* -1 r t)) (cdf d2-v)))
        (- (* k (Math/exp (* -1 r t)) (cdf (- d2-v)))
           (* s (cdf (- d1-v))))))))

(defn generate-pnl-matrix
  "Generates a 2D matrix for Plotly.
   price-range: [min-p max-p step]
   time-range:  [min-t max-t step] (in days)
   spread-fn:   a function that takes [price time-in-years] and returns P&L"
  [price-range time-range spread-fn]
  (let [[p-min p-max p-step] price-range
        [t-min t-max t-step] time-range
        prices (range p-min (+ p-max p-step) p-step)
        times  (range t-min (+ t-max t-step) t-step)]
    {:x prices
     :y times
     :z (for [t times]
          (for [p prices]
            (spread-fn p (/ t 365.0))))}))

(defn price-probability
  "Probability density of target-p given a normal distribution centered at spot.
   s: current spot, iv: implied vol, t: time in years, target-p: price to check"
  [s iv t target-p]
  (if (<= t 0)
    0.0
    (let [std-dev (* s iv (Math/sqrt t))
          z       (/ (- target-p s) std-dev)]
      (/ (pdf z) std-dev))))

(defn generate-full-data-set [price-range time-range spot iv spread-fn]
  (let [matrix (generate-pnl-matrix price-range time-range spread-fn)
        probs  (for [t (:y matrix)]
                 (for [p (:x matrix)]
                   (price-probability spot iv (/ t 365.0) p)))]
    (assoc matrix :prob-z probs)))

(defn expected-move
  "Returns [lower upper] ±1σ price bounds. s: spot, iv: IV, t: years."
  [s iv t]
  (if (<= t 0)
    [s s]
    (let [move (* s iv (Math/sqrt t))]
      [(- s move) (+ s move)])))

(defn generate-heatmap-with-bounds
  [price-range time-range spot iv spread-fn]
  (let [matrix (generate-pnl-matrix price-range time-range spread-fn)
        bounds (map (fn [t-days]
                      (expected-move spot iv (/ t-days 365.0)))
                    (:y matrix))]
    (assoc matrix
           :lower-sigma (map first bounds)
           :upper-sigma (map second bounds))))
