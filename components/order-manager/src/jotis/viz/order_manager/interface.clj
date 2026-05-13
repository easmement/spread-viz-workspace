(ns jotis.viz.order-manager.interface
  (:require [org.httpkit.client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def ^:private base-url "https://paper-api.alpaca.markets")

(defn- headers []
  {"APCA-API-KEY-ID"     (System/getenv "APCA_API_KEY_ID")
   "APCA-API-SECRET-KEY" (System/getenv "APCA_API_SECRET_KEY")
   "Accept"              "application/json"
   "Content-Type"        "application/json"})

(defn occ-symbol
  "Builds OCC option symbol, e.g. SPY250530P00580000"
  [ticker expiry option-type strike]
  (let [date-part  (str/replace (subs expiry 2) "-" "")
        type-char  (if (= option-type :put) "P" "C")
        strike-str (format "%08d" (long (* (double strike) 1000)))]
    (str (str/upper-case ticker) date-part type-char strike-str)))

(defn- post! [path body]
  (let [{:keys [body error status]} @(http/post (str base-url path)
                                                {:headers (headers)
                                                 :body    (json/generate-string body)})]
    (if error
      (throw (ex-info "Alpaca request failed" {:path path :error error}))
      (let [parsed (json/parse-string body true)]
        (if (>= status 400)
          (throw (ex-info "Alpaca rejected order"
                          {:status status :message (:message parsed) :body parsed}))
          parsed)))))

(defn- delete! [path]
  (let [{:keys [error status]} @(http/delete (str base-url path) {:headers (headers)})]
    (when error (throw (ex-info "Alpaca request failed" {:path path :error error})))
    status))

(defn- get! [path]
  (let [{:keys [body error]} @(http/get (str base-url path) {:headers (headers)})]
    (if error
      (throw (ex-info "Alpaca request failed" {:path path :error error}))
      (json/parse-string body true))))

;; ---- Order placement ----

(defn place-stock-order
  "Long or short stock. side: :long or :short, order-type: :market or :limit."
  [{:keys [ticker qty side order-type limit-price]
    :or   {order-type :market}}]
  (post! "/v2/orders"
         (cond-> {:symbol        (str/upper-case ticker)
                  :qty           (str qty)
                  :side          (if (= side :long) "buy" "sell")
                  :type          (name order-type)
                  :time_in_force "day"}
           (= order-type :limit) (assoc :limit_price (str limit-price)))))

(defn place-single-option
  "Long or short a single put or call.
   Accepts :ticker :expiry :option-type :strike :side :qty :limit-price"
  [{:keys [ticker expiry option-type strike side qty limit-price]}]
  (post! "/v2/orders"
         (cond-> {:symbol        (occ-symbol ticker expiry option-type strike)
                  :qty           (str qty)
                  :side          (if (= side :long) "buy" "sell")
                  :type          (if limit-price "limit" "market")
                  :time_in_force "day"}
           limit-price (assoc :limit_price (str limit-price)))))

(defn place-vertical-spread
  "Credit or debit vertical spread (same expiry, same option type).
   :ticker :option-type :expiry :short-strike :long-strike :qty :limit-price
   limit-price is the net credit (positive) or net debit (negative)."
  [{:keys [ticker option-type expiry short-strike long-strike qty limit-price]}]
  (post! "/v2/orders"
         {:order_class   "mleg"
          :qty           (str qty)
          :type          (if limit-price "limit" "market")
          :time_in_force "day"
          :limit_price   (str limit-price)
          :legs          [{:symbol          (occ-symbol ticker expiry option-type short-strike)
                           :side            "sell"
                           :ratio_qty       1
                           :position_effect "open"}
                          {:symbol          (occ-symbol ticker expiry option-type long-strike)
                           :side            "buy"
                           :ratio_qty       1
                           :position_effect "open"}]}))

(defn place-calendar
  "Calendar spread: sell near-term leg, buy far-term leg at the same strike.
   Same option type (put or call), same strike, two expirations.
   Typically a net debit for long calendars; positive limit-price = net credit.
   :ticker :option-type :strike :near-expiry :far-expiry :qty :limit-price"
  [{:keys [ticker option-type strike near-expiry far-expiry qty limit-price]}]
  (post! "/v2/orders"
         {:order_class   "mleg"
          :qty           (str qty)
          :type          (if limit-price "limit" "market")
          :time_in_force "day"
          :limit_price   (str limit-price)
          :legs          [{:symbol          (occ-symbol ticker near-expiry option-type strike)
                           :side            "sell"
                           :ratio_qty       1
                           :position_effect "open"}
                          {:symbol          (occ-symbol ticker far-expiry option-type strike)
                           :side            "buy"
                           :ratio_qty       1
                           :position_effect "open"}]}))

(defn place-diagonal
  "Diagonal: sell near-term leg, buy far-term leg (same option type).
   :ticker :option-type :near-expiry :near-strike :far-expiry :far-strike
   :qty :limit-price"
  [{:keys [ticker option-type near-expiry near-strike far-expiry far-strike qty limit-price]}]
  (post! "/v2/orders"
         {:order_class   "mleg"
          :qty           (str qty)
          :type          (if limit-price "limit" "market")
          :time_in_force "day"
          :limit_price   (str limit-price)
          :legs          [{:symbol          (occ-symbol ticker near-expiry option-type near-strike)
                           :side            "sell"
                           :ratio_qty       1
                           :position_effect "open"}
                          {:symbol          (occ-symbol ticker far-expiry option-type far-strike)
                           :side            "buy"
                           :ratio_qty       1
                           :position_effect "open"}]}))

(defn place-butterfly
  "Butterfly spread (put or call, symmetric or broken-wing).
   Long butterfly (debit):  buy lower wing, sell 2x body, buy upper wing.
   Short butterfly (credit): sell lower wing, buy 2x body, sell upper wing.
   :ticker :expiry :option-type :lower-strike :middle-strike :upper-strike
   :direction (:long|:short) :qty :limit-price"
  [{:keys [ticker expiry option-type lower-strike middle-strike upper-strike
           direction qty limit-price]
    :or   {direction :long}}]
  (let [outer-side  (if (= direction :long) "buy" "sell")
        middle-side (if (= direction :long) "sell" "buy")]
    (post! "/v2/orders"
           {:order_class   "mleg"
            :qty           (str qty)
            :type          (if limit-price "limit" "market")
            :time_in_force "day"
            :limit_price   (str limit-price)
            :legs          [{:symbol          (occ-symbol ticker expiry option-type lower-strike)
                             :side            outer-side
                             :ratio_qty       1
                             :position_effect "open"}
                            {:symbol          (occ-symbol ticker expiry option-type middle-strike)
                             :side            middle-side
                             :ratio_qty       2
                             :position_effect "open"}
                            {:symbol          (occ-symbol ticker expiry option-type upper-strike)
                             :side            outer-side
                             :ratio_qty       1
                             :position_effect "open"}]})))

(defn place-iron-condor
  "Iron condor: OTM put spread + OTM call spread, all same expiry.
   Short IC (credit):  sell put-sell, buy put-buy, sell call-sell, buy call-buy.
   Long IC  (debit):   buy put-sell, sell put-buy, buy call-sell, sell call-buy.
   Strike order: put-buy < put-sell < [spot] < call-sell < call-buy
   :ticker :expiry :put-buy-strike :put-sell-strike :call-sell-strike :call-buy-strike
   :direction (:short|:long) :qty :limit-price"
  [{:keys [ticker expiry put-buy-strike put-sell-strike call-sell-strike call-buy-strike
           direction qty limit-price]
    :or   {direction :short}}]
  (let [short? (= direction :short)]
    (post! "/v2/orders"
           {:order_class   "mleg"
            :qty           (str qty)
            :type          (if limit-price "limit" "market")
            :time_in_force "day"
            :limit_price   (str limit-price)
            :legs          [{:symbol          (occ-symbol ticker expiry :put put-buy-strike)
                             :side            (if short? "buy"  "sell")
                             :ratio_qty       1
                             :position_effect "open"}
                            {:symbol          (occ-symbol ticker expiry :put put-sell-strike)
                             :side            (if short? "sell" "buy")
                             :ratio_qty       1
                             :position_effect "open"}
                            {:symbol          (occ-symbol ticker expiry :call call-sell-strike)
                             :side            (if short? "sell" "buy")
                             :ratio_qty       1
                             :position_effect "open"}
                            {:symbol          (occ-symbol ticker expiry :call call-buy-strike)
                             :side            (if short? "buy"  "sell")
                             :ratio_qty       1
                             :position_effect "open"}]})))

(defn place-back-ratio
  "Back ratio spread: sell 1 at short-strike, buy 2 at long-strike (same expiry, same option type).
   Profits from a large directional move beyond long-strike.
   Call back ratio: short-strike < long-strike (both OTM calls, long strike further OTM).
   Put back ratio:  long-strike < short-strike (both OTM puts, long strike further OTM).
   :ticker :expiry :option-type :short-strike :long-strike :qty :limit-price
   limit-price: net debit (positive) or small credit (negative)."
  [{:keys [ticker expiry option-type short-strike long-strike qty limit-price]}]
  (post! "/v2/orders"
         {:order_class   "mleg"
          :qty           (str qty)
          :type          (if limit-price "limit" "market")
          :time_in_force "day"
          :limit_price   (str limit-price)
          :legs          [{:symbol          (occ-symbol ticker expiry option-type short-strike)
                           :side            "sell"
                           :ratio_qty       1
                           :position_effect "open"}
                          {:symbol          (occ-symbol ticker expiry option-type long-strike)
                           :side            "buy"
                           :ratio_qty       2
                           :position_effect "open"}]}))

(defn cancel-order [order-id]
  (delete! (str "/v2/orders/" order-id)))

(defn get-order [order-id]
  (get! (str "/v2/orders/" order-id)))
