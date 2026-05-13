(ns jotis.viz.market-data.interface
  (:require [org.httpkit.client :as http]
            [cheshire.core :as json]))

(def ^:dynamic *auth-headers*
  {"APCA-API-KEY-ID"     (System/getenv "APCA_API_KEY_ID")
   "APCA-API-SECRET-KEY" (System/getenv "APCA_API_SECRET_KEY")})

(def ^:private base-data-url "https://data.alpaca.markets")

(defn- get-headers []
  {"APCA-API-KEY-ID"     (System/getenv "APCA_API_KEY_ID")
   "APCA-API-SECRET-KEY" (System/getenv "APCA_API_SECRET_KEY")
   "Accept"              "application/json"})

(defn get-last-quote [ticker]
  (let [url (str base-data-url "/v2/stocks/" ticker "/quotes/latest")]
    @(http/get url {:headers (get-headers)})))

(defn get-spot-price
  "Fetches the latest ask price for an underlying ticker."
  [ticker]
  (let [url (str base-data-url "/v2/stocks/" ticker "/quotes/latest")
        {:keys [body error]} @(http/get url {:headers (get-headers)})]
    (if error
      (throw (ex-info "Alpaca API Error" {:error error}))
      (-> (json/parse-string body true) :quote :ap))))

(defn get-option-snapshot
  "Fetches IV and Greeks for a specific option contract symbol."
  [option-symbol]
  (let [url         (str base-data-url "/v1beta1/options/snapshots")
        query-params {:symbols option-symbol :feed "opra"}
        {:keys [body error]} @(http/get url {:headers      (get-headers)
                                             :query-params query-params})]
    (if error
      (throw (ex-info "Alpaca API Error" {:error error}))
      (let [snapshot (get-in (json/parse-string body true) [:snapshots (keyword option-symbol)])]
        {:iv    (:implied_volatility snapshot)
         :delta (get-in snapshot [:greeks :delta])
         :price (get-in snapshot [:latest_quote :ap])}))))

(defn get-atm-iv
  "Returns implied volatility of the option closest to spot for the given ticker,
   expiry (YYYY-MM-DD), spot price, and option-type (\"put\" or \"call\")."
  [ticker expiry spot option-type]
  (let [url (str base-data-url "/v1beta1/options/snapshots/" ticker)
        {:keys [body error]} @(http/get url {:headers      (get-headers)
                                             :query-params {:expiration_date expiry
                                                            :type            option-type
                                                            :feed            "opra"
                                                            :limit           100}})]
    (if error
      (throw (ex-info "Alpaca API Error" {:error error}))
      (let [snapshots (seq (:snapshots (json/parse-string body true)))]
        (when (empty? snapshots)
          (throw (ex-info "No options found" {:ticker ticker :expiry expiry :type option-type})))
        (let [atm (apply min-key
                         (fn [[_ snap]]
                           (Math/abs (- (double (get-in snap [:details :strike_price]))
                                        (double spot))))
                         snapshots)]
          (-> atm second :implied_volatility))))))
