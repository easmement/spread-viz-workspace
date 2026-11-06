(ns jotis.viz.trade-store.interface
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [jotis.viz.trade-store.schema :as schema])
  (:import [java.util UUID]
           [com.zaxxer.hikari HikariConfig HikariDataSource]))

(def ^:private jdbc-url
  (or (System/getenv "TRADER_DB_URL")
      "jdbc:postgresql://localhost:5432/traderdb"))

(defonce ^:private ds (atom nil))

(defn- make-ds []
  (let [cfg (doto (HikariConfig.)
              (.setJdbcUrl jdbc-url)
              (.setMaximumPoolSize 5))]
    (when-let [u (System/getenv "TRADER_DB_USER")]     (.setUsername cfg u))
    (when-let [p (System/getenv "TRADER_DB_PASSWORD")] (.setPassword cfg p))
    (HikariDataSource. cfg)))

(defn init!
  "Creates the trader schema and tables. Safe to call multiple times."
  []
  (let [datasource (make-ds)]
    (doseq [stmt schema/ddl-stmts]
      (jdbc/execute! datasource [stmt]))
    (reset! ds datasource)
    (println (str "Trade store connected: " jdbc-url))))

;; ---- Row transformations ----

(defn- to-date [ts]
  (when ts
    (cond
      (instance? java.time.OffsetDateTime ts) (java.util.Date/from (.toInstant ts))
      (instance? java.sql.Timestamp ts)       (java.util.Date. (.getTime ts))
      :else ts)))

(defn- row->leg [row]
  (when (:leg_id row)
    (cond-> {:leg/symbol      (:leg_symbol row)
             :leg/option-type (keyword (:leg_option_type row))
             :leg/side        (keyword (:leg_side row))
             :leg/open-price  (double (:leg_open_price row))}
      (:leg_strike row)      (assoc :leg/strike (double (:leg_strike row)))
      (:leg_expiry row)      (assoc :leg/expiry (str (:leg_expiry row)))
      (:leg_close_price row) (assoc :leg/close-price (double (:leg_close_price row))))))

(defn- row->trade [row]
  (cond-> {:trade/id              (:id row)
           :trade/ticker          (:ticker row)
           :trade/instrument-type (keyword (:instrument_type row))
           :trade/status          (keyword (:status row))
           :trade/qty             (:qty row)
           :trade/open-value      (double (:open_value row))
           :trade/account-pct     (double (:account_pct row))
           :trade/alpaca-order-id (:alpaca_order_id row)
           :trade/opened-at       (to-date (:opened_at row))}
    (:option_type row)   (assoc :trade/option-type (keyword (:option_type row)))
    (:close_value row)   (assoc :trade/close-value (double (:close_value row)))
    (:pnl row)           (assoc :trade/pnl (double (:pnl row)))
    (:notes row)         (assoc :trade/notes (:notes row))
    (:closed_at row)     (assoc :trade/closed-at (to-date (:closed_at row)))))

(defn- rows->trades [rows]
  (->> rows
       (group-by :id)
       vals
       (mapv (fn [group]
               (assoc (row->trade (first group))
                      :trade/legs (vec (keep row->leg group)))))))

(def ^:private join-sql
  "SELECT t.id, t.ticker, t.instrument_type, t.option_type, t.status, t.qty,
          t.open_value, t.close_value, t.pnl, t.account_pct, t.alpaca_order_id,
          t.notes, t.opened_at, t.closed_at,
          l.id AS leg_id, l.symbol AS leg_symbol, l.option_type AS leg_option_type,
          l.side AS leg_side, l.strike AS leg_strike, l.expiry AS leg_expiry,
          l.open_price AS leg_open_price, l.close_price AS leg_close_price
   FROM trader.trades t
   LEFT JOIN trader.legs l ON l.trade_id = t.id")

(defn- query-trades [where-clause & params]
  (rows->trades
    (jdbc/execute! @ds
                   (into [(str join-sql where-clause)] params)
                   {:builder-fn rs/as-unqualified-maps})))

;; ---- Write ----

(defn record-trade!
  "Persists a new open trade. Returns the UUID assigned to the trade.
   Required keys: :ticker :instrument-type :qty :open-value :account-pct
                  :alpaca-order-id :legs
   Each leg map: :symbol :option-type :side :strike :expiry :open-price"
  [{:keys [ticker instrument-type option-type qty open-value account-pct
           alpaca-order-id notes legs]}]
  (let [trade-id (UUID/randomUUID)]
    (jdbc/with-transaction [tx @ds]
      (jdbc/execute-one! tx
        ["INSERT INTO trader.trades
          (id, ticker, instrument_type, option_type, qty, open_value,
           account_pct, alpaca_order_id, notes)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
         trade-id
         (str ticker)
         (name instrument-type)
         (when option-type (name option-type))
         (long qty)
         (double open-value)
         (double account-pct)
         (str alpaca-order-id)
         (when notes (str notes))])
      (doseq [leg legs]
        (jdbc/execute-one! tx
          ["INSERT INTO trader.legs
            (trade_id, symbol, option_type, side, strike, expiry, open_price)
            VALUES (?, ?, ?, ?, ?, ?, ?)"
           trade-id
           (str (:symbol leg))
           (name (or (:option-type leg) :stock))
           (name (:side leg))
           (when (:strike leg) (double (:strike leg)))
           (:expiry leg)
           (double (or (:open-price leg) 0.0))])))
    trade-id))

(defn trade-pnl
  "P&L in dollars for a closed trade.
   Options: open-value and close-value are signed net premiums per share, the same convention as
   order entry: credit received (+), debit paid (-). P&L = (open + close) * qty * 100. Sell a
   spread for +1.50 and buy it back for -0.40: P&L = +110.
   Stock: open and close are prices per share; a short position gains when the price falls."
  [{:keys [instrument-type side open-value close-value qty]}]
  (if (= "stock" (name instrument-type))
    (* (- (double close-value) (double open-value)) qty (if (= "short" (some-> side name)) -1.0 1.0))
    (* (+ (double open-value) (double close-value)) qty 100.0)))

(defn close-trade!
  "Marks a trade closed, recording close-value and computing P&L (see trade-pnl for the sign
   convention close-value must follow)."
  [trade-id close-value]
  (let [trade    (jdbc/execute-one! @ds
                   ["SELECT t.open_value, t.qty, t.instrument_type,
                            (SELECT l.side FROM trader.legs l WHERE l.trade_id = t.id LIMIT 1) AS side
                     FROM trader.trades t WHERE t.id = ?"
                    trade-id]
                   {:builder-fn rs/as-unqualified-maps})
        pnl      (trade-pnl {:instrument-type (:instrument_type trade)
                             :side            (:side trade)
                             :open-value      (:open_value trade)
                             :close-value     close-value
                             :qty             (:qty trade)})]
    (jdbc/execute-one! @ds
      ["UPDATE trader.trades
        SET status = 'closed', closed_at = NOW(), close_value = ?, pnl = ?
        WHERE id = ?"
       (double close-value) pnl trade-id])
    pnl))

(defn cancel-trade! [trade-id]
  (jdbc/execute-one! @ds
    ["UPDATE trader.trades SET status = 'cancelled' WHERE id = ?"
     trade-id]))

;; ---- Read ----

(defn all-trades []
  (query-trades " ORDER BY t.opened_at DESC"))

(defn open-trades []
  (query-trades " WHERE t.status = 'open' ORDER BY t.opened_at DESC"))

(defn closed-trades []
  (query-trades " WHERE t.status = 'closed' ORDER BY t.opened_at DESC"))

(defn trade-by-id [trade-id]
  (first (query-trades " WHERE t.id = ?" trade-id)))

(defn performance-summary []
  (let [closed    (closed-trades)
        winners   (filter #(pos? (or (:trade/pnl %) 0.0)) closed)
        losers    (filter #(neg? (or (:trade/pnl %) 0.0)) closed)
        total-pnl (reduce + 0.0 (map #(or (:trade/pnl %) 0.0) closed))]
    {:total-trades (count closed)
     :open-trades  (count (open-trades))
     :winners      (count winners)
     :losers       (count losers)
     :win-rate     (if (pos? (count closed))
                     (/ (count winners) (double (count closed)))
                     0.0)
     :total-pnl    total-pnl}))
