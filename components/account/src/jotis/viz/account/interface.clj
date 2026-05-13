(ns jotis.viz.account.interface
  (:require [org.httpkit.client :as http]
            [cheshire.core :as json]))

(def ^:private base-url "https://paper-api.alpaca.markets")

(defn- headers []
  {"APCA-API-KEY-ID"     (System/getenv "APCA_API_KEY_ID")
   "APCA-API-SECRET-KEY" (System/getenv "APCA_API_SECRET_KEY")
   "Accept"              "application/json"})

(defn- get! [path & [params]]
  (let [{:keys [body error status]} @(http/get (str base-url path)
                                               {:headers      (headers)
                                                :query-params params})]
    (if error
      (throw (ex-info "Alpaca request failed" {:path path :error error}))
      (if (>= status 400)
        (throw (ex-info "Alpaca API error" {:status status :body body}))
        (json/parse-string body true)))))

(defn get-account
  "Returns account equity, cash, buying-power, and status."
  []
  (let [a (get! "/v2/account")]
    {:equity       (Double/parseDouble (:equity a))
     :cash         (Double/parseDouble (:cash a))
     :buying-power (Double/parseDouble (:buying_power a))
     :status       (:status a)
     :day-trades   (:daytrade_count a)}))

(defn get-positions
  "Returns all current open positions."
  []
  (mapv (fn [p]
          {:symbol    (:symbol p)
           :qty       (Long/parseLong (:qty p))
           :side      (:side p)
           :avg-price (Double/parseDouble (:avg_entry_price p))
           :market-value (Double/parseDouble (:market_value p))
           :unrealized-pnl (Double/parseDouble (:unrealized_pl p))})
        (get! "/v2/positions")))

(defn get-orders
  "Returns recent orders. status: \"open\" \"closed\" \"all\""
  ([] (get-orders "open"))
  ([status]
   (mapv (fn [o]
           {:id          (:id o)
            :symbol      (:symbol o)
            :qty         (:qty o)
            :side        (:side o)
            :type        (:type o)
            :status      (:status o)
            :submitted-at (:submitted_at o)
            :filled-at   (:filled_at o)
            :filled-avg-price (:filled_avg_price o)})
         (get! "/v2/orders" {:status status :limit 50}))))
