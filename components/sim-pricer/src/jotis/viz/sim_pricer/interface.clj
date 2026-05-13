(ns jotis.viz.sim-pricer.interface
  (:require [org.httpkit.client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [jotis.viz.math-engine.interface :as math])
  (:import [java.time LocalDate]
           [java.util UUID]
           [com.zaxxer.hikari HikariConfig HikariDataSource]))

;; ---- Constants ----

(def ^:private risk-free-rate  0.05)
(def ^:private ewma-lambda     0.94)
(def ^:private spread-pct      0.03)   ; ±3% of mid for bid/ask
(def ^:private spread-min      0.05)   ; minimum half-spread
(def ^:private expiry-offsets  [30 60 90 180])

;; ---- Schema ----

(def ^:private ddl-stmts
  ["CREATE SCHEMA IF NOT EXISTS sim"

   "CREATE TABLE IF NOT EXISTS sim.underlying_prices (
      ticker     VARCHAR(20)   NOT NULL,
      price_date DATE          NOT NULL,
      close      NUMERIC(12,4) NOT NULL,
      PRIMARY KEY (ticker, price_date)
    )"

   "CREATE TABLE IF NOT EXISTS sim.option_prices (
      ticker           VARCHAR(20)   NOT NULL,
      price_date       DATE          NOT NULL,
      expiry           DATE          NOT NULL,
      strike           NUMERIC(12,4) NOT NULL,
      option_type      VARCHAR(4)    NOT NULL,
      mid              NUMERIC(14,6) NOT NULL,
      bid              NUMERIC(14,6) NOT NULL,
      ask              NUMERIC(14,6) NOT NULL,
      underlying_close NUMERIC(12,4) NOT NULL,
      iv               NUMERIC(10,6) NOT NULL,
      delta            NUMERIC(10,6),
      theta            NUMERIC(10,6),
      PRIMARY KEY (ticker, price_date, expiry, strike, option_type)
    )"

   "CREATE TABLE IF NOT EXISTS sim.trades (
      id              UUID          PRIMARY KEY,
      ticker          VARCHAR(20)   NOT NULL,
      instrument_type VARCHAR(30)   NOT NULL,
      option_type     VARCHAR(10),
      status          VARCHAR(20)   NOT NULL DEFAULT 'open',
      qty             INTEGER       NOT NULL,
      open_value      NUMERIC(14,6) NOT NULL,
      sim_open_date   DATE          NOT NULL,
      sim_close_date  DATE,
      close_value     NUMERIC(14,6),
      pnl             NUMERIC(14,6),
      notes           TEXT,
      created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW()
    )"

   "CREATE TABLE IF NOT EXISTS sim.trade_legs (
      id          SERIAL        PRIMARY KEY,
      trade_id    UUID          NOT NULL REFERENCES sim.trades(id) ON DELETE CASCADE,
      symbol      VARCHAR(50)   NOT NULL,
      option_type VARCHAR(10)   NOT NULL,
      side        VARCHAR(10)   NOT NULL,
      ratio_qty   INTEGER       NOT NULL DEFAULT 1,
      strike      NUMERIC(12,4),
      expiry      DATE,
      open_price  NUMERIC(14,6) NOT NULL DEFAULT 0,
      close_price NUMERIC(14,6)
    )"])

;; ---- Datasource ----

(def ^:private jdbc-url
  (or (System/getenv "TRADER_DB_URL")
      "jdbc:postgresql://localhost:5432/traderdb"))

(defonce ^:private ds (atom nil))

(defn init!
  "Creates sim schema tables and opens connection pool."
  []
  (let [cfg (doto (HikariConfig.)
              (.setJdbcUrl jdbc-url)
              (.setMaximumPoolSize 5))]
    (when-let [u (System/getenv "TRADER_DB_USER")]     (.setUsername cfg u))
    (when-let [p (System/getenv "TRADER_DB_PASSWORD")] (.setPassword cfg p))
    (let [datasource (HikariDataSource. cfg)]
      (reset! ds datasource)
      (doseq [stmt ddl-stmts]
        (jdbc/execute! datasource [stmt]))
      (println "Sim pricer store connected"))))

;; ---- Alpaca historical bars ----

(defn- alpaca-headers []
  {"APCA-API-KEY-ID"     (System/getenv "APCA_API_KEY_ID")
   "APCA-API-SECRET-KEY" (System/getenv "APCA_API_SECRET_KEY")
   "Accept"              "application/json"})

(defn- fetch-bars
  "Fetches all daily closing prices for ticker between start and end (YYYY-MM-DD).
   Handles Alpaca pagination automatically."
  [ticker start end]
  (loop [all-bars [] token nil]
    (let [params  (cond-> {:timeframe "1Day" :start start :end end :sort "asc" :limit 1000}
                    token (assoc :page_token token))
          url     (str "https://data.alpaca.markets/v2/stocks/" ticker "/bars")
          {:keys [body error]} @(http/get url {:headers      (alpaca-headers)
                                               :query-params params})]
      (if error
        (throw (ex-info "Alpaca bars fetch failed" {:ticker ticker :error error}))
        (let [parsed   (json/parse-string body true)
              bars     (:bars parsed)
              next-tok (:next_page_token parsed)
              rows     (mapv (fn [b] {:date (subs (str (:t b)) 0 10) :close (double (:c b))})
                             bars)]
          (if (and next-tok (seq next-tok))
            (recur (into all-bars rows) next-tok)
            (into all-bars rows)))))))

;; ---- EWMA volatility ----

(defn- ewma-vols
  "Returns annualised EWMA volatility aligned to closes[1:] (one per return)."
  [closes]
  (let [returns  (map (fn [[a b]] (Math/log (/ b a))) (partition 2 1 closes))
        init-var (let [rs (take (min 20 (count returns)) returns)]
                   (/ (reduce + (map #(* % %) rs)) (max 1 (count rs))))
        vars     (reductions (fn [v r] (+ (* (- 1 ewma-lambda) r r)
                                          (* ewma-lambda v)))
                             init-var
                             returns)]
    (mapv #(Math/sqrt (* % 252.0)) vars)))

;; ---- Strike ladder ----

(defn- strike-ladder [spot]
  (let [step (cond (< spot 20)  0.5
                   (< spot 100) 1.0
                   (< spot 300) 2.5
                   :else        5.0)
        lo   (* step (Math/ceil  (/ (* 0.75 spot) step)))
        hi   (* step (Math/floor (/ (* 1.25 spot) step)))]
    (range lo (+ hi step) step)))

;; ---- Greeks ----

(defn- norm-pdf [x]
  (/ (Math/exp (* -0.5 x x)) (Math/sqrt (* 2.0 Math/PI))))

(defn- norm-cdf [x]
  (let [t (/ 1.0 (+ 1.0 (* 0.3275911 (Math/abs x))))
        p (* t (+ (* t (+ (* t (+ (* t (+ (* t  1.061405429)
                                             -1.453152027))
                                      1.421413741))
                              -0.284496736))
                    0.254829592))
        v (- 1.0 (* p (Math/exp (* (- x) x))))]
    (if (>= x 0) v (- v))))

(defn- calc-d1 [s k t iv r]
  (/ (+ (Math/log (/ s k)) (* (+ r (/ (* iv iv) 2.0)) t))
     (* iv (Math/sqrt t))))

(defn- calc-delta [otype s k t iv r]
  (if (<= t 0.0)
    (if (= otype "call") (if (> s k) 1.0 0.0) (if (< s k) -1.0 0.0))
    (let [d1 (calc-d1 s k t iv r)]
      (if (= otype "call") (norm-cdf d1) (- (norm-cdf d1) 1.0)))))

(defn- calc-theta [otype s k t iv r]
  ;; Daily theta (per calendar day)
  (if (<= t 0.0)
    0.0
    (let [d1    (calc-d1 s k t iv r)
          d2    (- d1 (* iv (Math/sqrt t)))
          term1 (/ (* (- s) (norm-pdf d1) iv) (* 2.0 (Math/sqrt t)))
          term2 (if (= otype "call")
                  (* (- r) k (Math/exp (* (- r) t)) (norm-cdf d2))
                  (* r k (Math/exp (* (- r) t)) (norm-cdf (- d2))))]
      (/ (+ term1 term2) 365.0))))

;; ---- Bid-ask spread model ----

(defn- mid->bid-ask [mid]
  (let [half (max spread-min (* spread-pct mid))]
    {:bid (max 0.01 (- mid half))
     :ask (+ mid half)}))

;; ---- Main generation function ----

(def ^:private option-price-upsert-sql
  (str "INSERT INTO sim.option_prices "
       "(ticker, price_date, expiry, strike, option_type, "
       " mid, bid, ask, underlying_close, iv, delta, theta) "
       "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
       "ON CONFLICT (ticker, price_date, expiry, strike, option_type) "
       "DO UPDATE SET mid=EXCLUDED.mid, bid=EXCLUDED.bid, ask=EXCLUDED.ask, "
       "iv=EXCLUDED.iv, delta=EXCLUDED.delta, theta=EXCLUDED.theta"))

(defn generate-ticker!
  "Fetches daily bars for ticker, computes EWMA vol, and inserts B-S option
   prices for each date × expiry-offset × strike into sim.option_prices.
   Returns {:rows-inserted N :dates M}."
  [ticker start end]
  (let [ticker (str/upper-case ticker)
        bars   (fetch-bars ticker start end)
        _      (when (< (count bars) 2)
                 (throw (ex-info "Too few bars returned" {:ticker ticker :count (count bars)})))
        closes (mapv :close bars)
        dates  (mapv :date bars)
        vols   (ewma-vols closes)
        r      risk-free-rate]

    ;; Upsert underlying prices
    (jdbc/with-transaction [tx @ds]
      (doseq [{:keys [date close]} bars]
        (jdbc/execute-one! tx
          ["INSERT INTO sim.underlying_prices (ticker, price_date, close)
            VALUES (?, ?::date, ?)
            ON CONFLICT (ticker, price_date) DO UPDATE SET close = EXCLUDED.close"
           ticker date (double close)])))

    ;; Build all option price rows (start at index 1; vol aligns to dates[1:])
    (let [rows (for [[date spot vol] (map vector (drop 1 dates) (drop 1 closes) vols)
                     :let [price-date (LocalDate/parse date)]
                     offset expiry-offsets
                     :let [expiry (.plusDays price-date offset)
                           t-yrs (/ offset 365.0)]
                     strike (strike-ladder spot)
                     otype  ["call" "put"]
                     :let  [mid (math/black-scholes (keyword otype) spot (double strike) t-yrs r vol)]
                     :when (>= mid 0.01)
                     :let  [{:keys [bid ask]} (mid->bid-ask mid)
                             delta (calc-delta otype spot (double strike) t-yrs vol r)
                             theta (calc-theta otype spot (double strike) t-yrs vol r)]]
               [ticker
                (java.sql.Date/valueOf date)
                (java.sql.Date/valueOf (str expiry))
                (double strike) otype
                mid bid ask (double spot) vol delta theta])]

      ;; Batch insert in 500-row chunks
      (let [all-rows (vec rows)]
        (jdbc/with-transaction [tx @ds]
          (with-open [ps (jdbc/prepare tx [option-price-upsert-sql])]
            (doseq [chunk (partition-all 500 all-rows)]
              (jdbc/execute-batch! ps chunk {}))))
        {:rows-inserted (count all-rows)
         :dates         (count dates)}))))

;; ---- Query helpers ----

(defn available-tickers []
  (->> (jdbc/execute! @ds
         ["SELECT ticker, COUNT(DISTINCT price_date) AS dates
           FROM sim.underlying_prices GROUP BY ticker ORDER BY ticker"]
         {:builder-fn rs/as-unqualified-maps})
       (mapv #(update % :dates long))))

(defn available-dates [ticker]
  (mapv #(str (:price_date %))
        (jdbc/execute! @ds
          ["SELECT price_date FROM sim.underlying_prices
            WHERE ticker = ? ORDER BY price_date"
           (str/upper-case ticker)]
          {:builder-fn rs/as-unqualified-maps})))

(defn available-expiries [ticker price-date]
  (mapv #(str (:expiry %))
        (jdbc/execute! @ds
          ["SELECT DISTINCT expiry FROM sim.option_prices
            WHERE ticker = ? AND price_date = ?::date ORDER BY expiry"
           (str/upper-case ticker) price-date]
          {:builder-fn rs/as-unqualified-maps})))

(defn option-chain
  "Returns all strikes for ticker on price-date × expiry, pivoted by call/put."
  [ticker price-date expiry]
  (let [rows (jdbc/execute! @ds
               ["SELECT strike, option_type, mid, bid, ask, iv, delta, theta
                 FROM sim.option_prices
                 WHERE ticker = ? AND price_date = ?::date AND expiry = ?::date
                 ORDER BY strike"
                (str/upper-case ticker) price-date expiry]
               {:builder-fn rs/as-unqualified-maps})
        by-strike (group-by #(double (:strike %)) rows)]
    (for [[strike legs] (sort-by key by-strike)
          :let [call (first (filter #(= "call" (:option_type %)) legs))
                put  (first (filter #(= "put"  (:option_type %)) legs))]]
      {:strike     strike
       :call-bid   (when call (double (:bid   call)))
       :call-ask   (when call (double (:ask   call)))
       :call-iv    (when call (double (:iv    call)))
       :call-delta (when call (double (:delta call)))
       :put-bid    (when put  (double (:bid   put)))
       :put-ask    (when put  (double (:ask   put)))
       :put-iv     (when put  (double (:iv    put)))
       :put-delta  (when put  (double (:delta put)))})))

(defn underlying-price [ticker price-date]
  (let [row (jdbc/execute-one! @ds
              ["SELECT close FROM sim.underlying_prices
                WHERE ticker = ? AND price_date = ?::date"
               (str/upper-case ticker) price-date]
              {:builder-fn rs/as-unqualified-maps})]
    (when row (double (:close row)))))

(defn contract-mid [ticker price-date expiry strike otype]
  (let [row (jdbc/execute-one! @ds
              ["SELECT mid, bid, ask FROM sim.option_prices
                WHERE ticker=? AND price_date=?::date AND expiry=?::date
                  AND strike=? AND option_type=?"
               ticker price-date expiry (double strike) otype]
              {:builder-fn rs/as-unqualified-maps})]
    (when row {:mid (double (:mid row))
               :bid (double (:bid row))
               :ask (double (:ask row))})))

;; ---- Sim trade management ----

(defn record-sim-trade!
  "Persists a simulated trade to sim.trades + sim.trade_legs.
   Required: :ticker :instrument-type :option-type :qty :open-value :sim-open-date :legs
   Each leg: :symbol :option-type :side :ratio-qty :strike :expiry :open-price"
  [{:keys [ticker instrument-type option-type qty open-value sim-open-date notes legs]}]
  (let [trade-id (UUID/randomUUID)]
    (jdbc/with-transaction [tx @ds]
      (jdbc/execute-one! tx
        ["INSERT INTO sim.trades
          (id, ticker, instrument_type, option_type, qty, open_value, sim_open_date, notes)
          VALUES (?, ?, ?, ?, ?, ?, ?::date, ?)"
         trade-id
         (str/upper-case ticker)
         (name instrument-type)
         (when option-type (name option-type))
         (long qty)
         (double open-value)
         sim-open-date
         (when notes (str notes))])
      (doseq [leg legs]
        (jdbc/execute-one! tx
          ["INSERT INTO sim.trade_legs
            (trade_id, symbol, option_type, side, ratio_qty, strike, expiry, open_price)
            VALUES (?, ?, ?, ?, ?, ?, ?::date, ?)"
           trade-id
           (str (:symbol leg))
           (name (or (:option-type leg) :unknown))
           (name (:side leg))
           (long (or (:ratio-qty leg) 1))
           (when (:strike leg) (double (:strike leg)))
           (when (:expiry leg) (str (:expiry leg)))
           (double (or (:open-price leg) 0.0))])))
    trade-id))

(defn- rows->sim-trades [rows]
  (->> rows
       (group-by :id)
       vals
       (mapv (fn [group]
               (let [t (first group)]
                 (cond-> {:trade/id              (:id t)
                          :trade/ticker          (:ticker t)
                          :trade/instrument-type (keyword (:instrument_type t))
                          :trade/status          (keyword (:status t))
                          :trade/qty             (:qty t)
                          :trade/open-value      (double (:open_value t))
                          :trade/sim-open-date   (str (:sim_open_date t))}
                   (:option_type t)  (assoc :trade/option-type (keyword (:option_type t)))
                   (:pnl t)          (assoc :trade/pnl (double (:pnl t)))
                   (:close_value t)  (assoc :trade/close-value (double (:close_value t)))
                   (:sim_close_date t) (assoc :trade/sim-close-date (str (:sim_close_date t)))
                   true (assoc :trade/legs
                               (vec (keep (fn [r]
                                            (when (:leg_id r)
                                              (cond-> {:leg/symbol     (:leg_symbol r)
                                                       :leg/option-type (keyword (:leg_option_type r))
                                                       :leg/side        (keyword (:leg_side r))
                                                       :leg/ratio-qty   (:leg_ratio_qty r)
                                                       :leg/open-price  (double (:leg_open_price r))}
                                                (:leg_strike r) (assoc :leg/strike (double (:leg_strike r)))
                                                (:leg_expiry r) (assoc :leg/expiry (str (:leg_expiry r))))))
                                          group)))))))))

(def ^:private sim-join-sql
  "SELECT t.id, t.ticker, t.instrument_type, t.option_type, t.status, t.qty,
          t.open_value, t.close_value, t.pnl, t.sim_open_date, t.sim_close_date, t.notes,
          l.id AS leg_id, l.symbol AS leg_symbol, l.option_type AS leg_option_type,
          l.side AS leg_side, l.ratio_qty AS leg_ratio_qty,
          l.strike AS leg_strike, l.expiry AS leg_expiry,
          l.open_price AS leg_open_price, l.close_price AS leg_close_price
   FROM sim.trades t
   LEFT JOIN sim.trade_legs l ON l.trade_id = t.id")

(defn open-sim-trades []
  (rows->sim-trades
    (jdbc/execute! @ds
      [(str sim-join-sql " WHERE t.status = 'open' ORDER BY t.created_at DESC")]
      {:builder-fn rs/as-unqualified-maps})))

(defn all-sim-trades []
  (rows->sim-trades
    (jdbc/execute! @ds
      [(str sim-join-sql " ORDER BY t.created_at DESC")]
      {:builder-fn rs/as-unqualified-maps})))

(defn sim-trade-pnl-at
  "Computes unrealised P&L for a sim trade at the given sim-date string.
   Returns nil if any leg price is unavailable for that date."
  [trade sim-date]
  (let [ticker (str (:trade/ticker trade))
        qty    (long (:trade/qty trade))
        legs   (:trade/legs trade)
        leg-pnls
        (for [leg legs
              :let [row (contract-mid ticker sim-date
                                      (str (:leg/expiry leg))
                                      (:leg/strike leg)
                                      (name (:leg/option-type leg)))]
              :when row
              :let [current  (:mid row)
                    open-p   (double (:leg/open-price leg))
                    ratio    (or (:leg/ratio-qty leg) 1)
                    sign     (if (= :long (:leg/side leg)) 1.0 -1.0)]]
          (* (- current open-p) sign ratio))]
    (when (= (count leg-pnls) (count legs))
      (* (reduce + 0.0 leg-pnls) qty 100.0))))

(defn close-sim-trade!
  "Marks sim trade as closed and records P&L. Uses mid prices at sim-date."
  [trade-id sim-date]
  (let [trade  (first (rows->sim-trades
                        (jdbc/execute! @ds
                          [(str sim-join-sql " WHERE t.id = ?") trade-id]
                          {:builder-fn rs/as-unqualified-maps})))
        pnl    (sim-trade-pnl-at trade sim-date)
        close-val (when pnl
                    (let [ov (double (:trade/open-value trade))]
                      (/ (+ pnl (* ov (:trade/qty trade) 100.0))
                         (* (:trade/qty trade) 100.0))))]
    (jdbc/execute-one! @ds
      ["UPDATE sim.trades
        SET status='closed', sim_close_date=?::date, close_value=?, pnl=?
        WHERE id=?"
       sim-date
       (when close-val (double close-val))
       (when pnl (double pnl))
       trade-id])
    pnl))
