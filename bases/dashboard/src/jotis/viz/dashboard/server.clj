(ns jotis.viz.dashboard.server
  (:require [org.httpkit.server :as srv]
            [compojure.core :refer [GET POST DELETE defroutes]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.json :refer [wrap-json-body]]
            [clojure.string :as str]
            [jotis.viz.market-data.interface :as market]
            [jotis.viz.account.interface :as acct]
            [jotis.viz.order-manager.interface :as orders]
            [jotis.viz.trade-store.interface :as store]
            [jotis.viz.sim-pricer.interface :as sim]
            [cheshire.core :as json]))

;; ---- Configuration (environment variables, with safe defaults) ----

(def ^:private host
  "Interface to listen on. 127.0.0.1 keeps the API off the network: anyone who can reach it can
   place orders, and it has no login."
  (or (System/getenv "SPREAD_VIZ_HOST") "127.0.0.1"))

(def ^:private port (Long/parseLong (or (System/getenv "SPREAD_VIZ_PORT") "3001")))

(def allowed-origins
  "Browser origins allowed to call the API (the dashboard's own dev server by default)."
  (set (map str/trim (str/split (or (System/getenv "SPREAD_VIZ_ALLOWED_ORIGINS")
                                    "http://localhost:8080,http://127.0.0.1:8080")
                                #","))))

(defn- ok [data]
  {:status  200
   :headers {"Content-Type" "application/json; charset=utf-8"}
   :body    (json/generate-string data {:date-format "yyyy-MM-dd'T'HH:mm:ss'Z'"})})

(defn- err [msg status]
  {:status  status
   :headers {"Content-Type" "application/json; charset=utf-8"}
   :body    (json/generate-string {:error msg})})

;; ---- Trade journal entries ----

(defn- with-opening
  "Adds what the journal needs to an order request: :open-value, the signed net premium per share
   (credit +, debit -; price per share for stock), and :account-pct, the position's size as a
   percentage of account equity. Market orders have no limit price: stock uses the current quote
   as an estimate, options record 0 and say so in :notes."
  [b instrument-type]
  (let [stock?   (= instrument-type :stock)
        limit    (some-> (:limit-price b) double)
        open     (double (or limit (when stock? (market/get-spot-price (:ticker b))) 0.0))
        qty      (long (or (:qty b) 1))
        equity   (try (:equity (acct/get-account)) (catch Exception _ nil))
        notional (* (Math/abs open) qty (if stock? 1 100))]
    (assoc b
           :open-value  open
           :account-pct (if (and equity (pos? equity)) (* 100.0 (/ notional equity)) 0.0)
           :notes       (or (:notes b) (when-not limit "Market order: open value is an estimate")))))

(defmacro guarded [& body]
  `(try ~@body
        (catch clojure.lang.ExceptionInfo e#
          (err (str (:message (ex-data e#)) " " (.getMessage e#)) 500))
        (catch Exception e#
          (err (.getMessage e#) 500))))

;; ---- Market data ----

(defroutes market-routes
  (GET "/api/quote/:ticker" [ticker]
    (guarded
      (let [price (market/get-spot-price ticker)]
        (if price (ok {:price price}) (err (str "No quote for " ticker) 404)))))

  (GET "/api/iv/:ticker" [ticker expiry spot type]
    (guarded
      (let [iv (market/get-atm-iv ticker expiry (Double/parseDouble spot) type)]
        (if iv (ok {:iv iv}) (err (str "No IV data for " ticker) 404))))))

;; ---- Account ----

(defroutes account-routes
  (GET "/api/account" []
    (guarded (ok (acct/get-account))))

  (GET "/api/positions" []
    (guarded (ok {:positions (acct/get-positions)})))

  (GET "/api/orders" [status]
    (guarded (ok {:orders (acct/get-orders (or status "open"))}))))

;; ---- Order placement ----

(defroutes order-routes
  (POST "/api/orders/stock" req
    (guarded
      (let [b     (:body req)
            order (orders/place-stock-order b)
            id    (store/record-trade!
                    (assoc (with-opening b :stock)
                           :instrument-type :stock
                           :alpaca-order-id (:id order)
                           :legs [{:symbol      (:ticker b)
                                   :option-type :stock
                                   :side        (:side b)
                                   :open-price  (or (:limit-price b) 0.0)}]))]
      (ok {:order order :trade-id (str id)}))))

  (POST "/api/orders/single-option" req
    (guarded
      (let [b     (:body req)
            order (orders/place-single-option b)
            id    (store/record-trade!
                    (assoc (with-opening b :single-option)
                           :instrument-type :single-option
                           :alpaca-order-id (:id order)
                           :legs [{:symbol      (orders/occ-symbol
                                                  (:ticker b) (:expiry b)
                                                  (keyword (:option-type b)) (:strike b))
                                   :option-type (keyword (:option-type b))
                                   :side        (keyword (:side b))
                                   :strike      (:strike b)
                                   :expiry      (:expiry b)
                                   :open-price  (or (:limit-price b) 0.0)}]))]
      (ok {:order order :trade-id (str id)}))))

  (POST "/api/orders/vertical" req
    (guarded
      (let [b           (:body req)
            opt-type    (keyword (:option-type b))
            order       (orders/place-vertical-spread (assoc b :option-type opt-type))
            short-sym   (orders/occ-symbol (:ticker b) (:expiry b) opt-type (:short-strike b))
            long-sym    (orders/occ-symbol (:ticker b) (:expiry b) opt-type (:long-strike b))
            id          (store/record-trade!
                          (assoc (with-opening b :vertical-spread)
                                 :instrument-type :vertical-spread
                                 :option-type     opt-type
                                 :alpaca-order-id (:id order)
                                 :legs [{:symbol      short-sym
                                         :option-type opt-type
                                         :side        :short
                                         :strike      (:short-strike b)
                                         :expiry      (:expiry b)
                                         :open-price  (or (:limit-price b) 0.0)}
                                        {:symbol      long-sym
                                         :option-type opt-type
                                         :side        :long
                                         :strike      (:long-strike b)
                                         :expiry      (:expiry b)
                                         :open-price  0.0}]))]
      (ok {:order order :trade-id (str id)}))))

  (POST "/api/orders/calendar" req
    (guarded
      (let [b        (:body req)
            opt-type (keyword (:option-type b))
            order    (orders/place-calendar (assoc b :option-type opt-type))
            near-sym (orders/occ-symbol (:ticker b) (:near-expiry b) opt-type (:strike b))
            far-sym  (orders/occ-symbol (:ticker b) (:far-expiry b)  opt-type (:strike b))
            id       (store/record-trade!
                       (assoc (with-opening b :calendar)
                              :instrument-type :calendar
                              :option-type     opt-type
                              :alpaca-order-id (:id order)
                              :legs [{:symbol      near-sym
                                      :option-type opt-type
                                      :side        :short
                                      :strike      (:strike b)
                                      :expiry      (:near-expiry b)
                                      :open-price  (or (:limit-price b) 0.0)}
                                     {:symbol      far-sym
                                      :option-type opt-type
                                      :side        :long
                                      :strike      (:strike b)
                                      :expiry      (:far-expiry b)
                                      :open-price  0.0}]))]
        (ok {:order order :trade-id (str id)}))))

  (POST "/api/orders/diagonal" req
    (guarded
      (let [b        (:body req)
            opt-type (keyword (:option-type b))
            order    (orders/place-diagonal (assoc b :option-type opt-type))
            near-sym (orders/occ-symbol (:ticker b) (:near-expiry b) opt-type (:near-strike b))
            far-sym  (orders/occ-symbol (:ticker b) (:far-expiry b)  opt-type (:far-strike b))
            id       (store/record-trade!
                       (assoc (with-opening b :diagonal)
                              :instrument-type :diagonal
                              :option-type     opt-type
                              :alpaca-order-id (:id order)
                              :legs [{:symbol      near-sym
                                      :option-type opt-type
                                      :side        :short
                                      :strike      (:near-strike b)
                                      :expiry      (:near-expiry b)
                                      :open-price  (or (:limit-price b) 0.0)}
                                     {:symbol      far-sym
                                      :option-type opt-type
                                      :side        :long
                                      :strike      (:far-strike b)
                                      :expiry      (:far-expiry b)
                                      :open-price  0.0}]))]
      (ok {:order order :trade-id (str id)}))))

  (POST "/api/orders/butterfly" req
    (guarded
      (let [b        (:body req)
            opt-type (keyword (:option-type b))
            dir      (keyword (or (:direction b) "long"))
            order    (orders/place-butterfly (assoc b :option-type opt-type :direction dir))
            mk-leg   (fn [side strike]
                       {:symbol      (orders/occ-symbol (:ticker b) (:expiry b) opt-type strike)
                        :option-type opt-type
                        :side        side
                        :strike      strike
                        :expiry      (:expiry b)
                        :open-price  (or (:limit-price b) 0.0)})
            id       (store/record-trade!
                       (assoc (with-opening b :butterfly)
                              :instrument-type :butterfly
                              :option-type     opt-type
                              :alpaca-order-id (:id order)
                              :legs [(mk-leg (if (= dir :long) :long :short) (:lower-strike b))
                                     (mk-leg (if (= dir :long) :short :long) (:middle-strike b))
                                     (mk-leg (if (= dir :long) :short :long) (:middle-strike b))
                                     (mk-leg (if (= dir :long) :long :short) (:upper-strike b))]))]
        (ok {:order order :trade-id (str id)}))))

  (POST "/api/orders/iron-condor" req
    (guarded
      (let [b     (:body req)
            dir   (keyword (or (:direction b) "short"))
            order (orders/place-iron-condor (assoc b :direction dir))
            short? (= dir :short)
            mk-leg (fn [opt-type side strike]
                     {:symbol      (orders/occ-symbol (:ticker b) (:expiry b) opt-type strike)
                      :option-type opt-type
                      :side        side
                      :strike      strike
                      :expiry      (:expiry b)
                      :open-price  (or (:limit-price b) 0.0)})
            id    (store/record-trade!
                    (assoc (with-opening b :iron-condor)
                           :instrument-type :iron-condor
                           :alpaca-order-id (:id order)
                           :legs [(mk-leg :put  (if short? :long  :short) (:put-buy-strike b))
                                  (mk-leg :put  (if short? :short :long)  (:put-sell-strike b))
                                  (mk-leg :call (if short? :short :long)  (:call-sell-strike b))
                                  (mk-leg :call (if short? :long  :short) (:call-buy-strike b))]))]
        (ok {:order order :trade-id (str id)}))))

  (POST "/api/orders/back-ratio" req
    (guarded
      (let [b        (:body req)
            opt-type (keyword (:option-type b))
            order    (orders/place-back-ratio (assoc b :option-type opt-type))
            short-sym (orders/occ-symbol (:ticker b) (:expiry b) opt-type (:short-strike b))
            long-sym  (orders/occ-symbol (:ticker b) (:expiry b) opt-type (:long-strike b))
            mk-leg   (fn [side sym strike]
                       {:symbol      sym
                        :option-type opt-type
                        :side        side
                        :strike      strike
                        :expiry      (:expiry b)
                        :open-price  (or (:limit-price b) 0.0)})
            id       (store/record-trade!
                       (assoc (with-opening b :back-ratio)
                              :instrument-type :back-ratio
                              :option-type     opt-type
                              :alpaca-order-id (:id order)
                              :legs [(mk-leg :short short-sym (:short-strike b))
                                     (mk-leg :long  long-sym  (:long-strike b))
                                     (mk-leg :long  long-sym  (:long-strike b))]))]
        (ok {:order order :trade-id (str id)}))))

  (DELETE "/api/orders/:order-id" [order-id]
    (guarded
      (orders/cancel-order order-id)
      (ok {:cancelled order-id}))))

;; ---- Trade history ----

(defroutes trade-routes
  (GET "/api/trades" []
    (guarded (ok {:trades (store/all-trades)})))

  (GET "/api/trades/open" []
    (guarded (ok {:trades (store/open-trades)})))

  (GET "/api/trades/performance" []
    (guarded (ok (store/performance-summary))))

  (POST "/api/trades/:trade-id/close" req
    (guarded
      (let [trade-id   (java.util.UUID/fromString (get-in req [:route-params :trade-id]))
            close-val  (get (:body req) :close-value)
            pnl        (store/close-trade! trade-id close-val)]
      (ok {:pnl pnl}))))

  (POST "/api/trades/:trade-id/cancel" req
    (guarded
      (let [trade-id (java.util.UUID/fromString (get-in req [:route-params :trade-id]))]
        (store/cancel-trade! trade-id)
        (ok {:cancelled (str trade-id)})))))

;; ---- Sim routes ----

(defroutes sim-routes
  (POST "/api/sim/generate" req
    (guarded
      (let [{:keys [ticker start end]} (:body req)
            result (sim/generate-ticker! ticker start end)]
        (ok result))))

  (GET "/api/sim/tickers" []
    (guarded (ok {:tickers (sim/available-tickers)})))

  (GET "/api/sim/price/:ticker" [ticker date]
    (guarded
      (let [price (sim/underlying-price ticker date)]
        (if price (ok {:price price}) (err (str "No price data for " ticker " on " date) 404)))))

  (GET "/api/sim/dates/:ticker" [ticker]
    (guarded (ok {:dates (sim/available-dates ticker)})))

  (GET "/api/sim/expiries/:ticker" [ticker date]
    (guarded (ok {:expiries (sim/available-expiries ticker date)})))

  (GET "/api/sim/chain/:ticker" [ticker date expiry]
    (guarded (ok {:chain (vec (sim/option-chain ticker date expiry))})))

  (POST "/api/sim/orders/vertical" req
    (guarded
      (let [b         (:body req)
            otype     (str (:option-type b))
            sim-date  (str (:sim-date b))
            ticker    (str/upper-case (:ticker b))
            expiry    (str (:expiry b))
            ss        (double (:short-strike b))
            ls        (double (:long-strike b))
            qty       (long   (:qty b))
            short-px  (:bid (sim/contract-mid ticker sim-date expiry ss otype))
            long-px   (:ask (sim/contract-mid ticker sim-date expiry ls otype))
            net-credit (- (or short-px 0.0) (or long-px 0.0))
            id        (sim/record-sim-trade!
                        {:ticker          ticker
                         :instrument-type :vertical-spread
                         :option-type     (keyword otype)
                         :qty             qty
                         :open-value      net-credit
                         :sim-open-date   sim-date
                         :legs [{:symbol      (str ticker "-" otype "-" ss "-short")
                                 :option-type (keyword otype)
                                 :side        :short :ratio-qty 1
                                 :strike      ss :expiry expiry :open-price (or short-px 0.0)}
                                {:symbol      (str ticker "-" otype "-" ls "-long")
                                 :option-type (keyword otype)
                                 :side        :long  :ratio-qty 1
                                 :strike      ls :expiry expiry :open-price (or long-px 0.0)}]})]
        (ok {:trade-id (str id) :net-credit net-credit}))))

  (POST "/api/sim/orders/butterfly" req
    (guarded
      (let [b        (:body req)
            otype    (str (:option-type b))
            sim-date (str (:sim-date b))
            ticker   (str/upper-case (:ticker b))
            expiry   (str (:expiry b))
            ls       (double (:lower-strike b))
            ms       (double (:middle-strike b))
            us       (double (:upper-strike b))
            qty      (long (:qty b))
            dir      (str (or (:direction b) "long"))
            mk-px    (fn [side strike]
                       (let [px (sim/contract-mid ticker sim-date expiry strike otype)]
                         (if (= "long" side) (:ask px) (:bid px))))
            lo-px  (mk-px dir ls)
            mid-px (mk-px (if (= "long" dir) "short" "long") ms)
            up-px  (mk-px dir us)
            net    (if (= "long" dir)
                     (- (* 2 (or mid-px 0.0)) (or lo-px 0.0) (or up-px 0.0))
                     (- (or lo-px 0.0) (* 2 (or mid-px 0.0)) (+ (or up-px 0.0))))
            id     (sim/record-sim-trade!
                     {:ticker          ticker
                      :instrument-type :butterfly
                      :option-type     (keyword otype)
                      :qty             qty
                      :open-value      net
                      :sim-open-date   sim-date
                      :legs [{:symbol (str ticker "-" otype "-" ls) :option-type (keyword otype)
                               :side (keyword dir) :ratio-qty 1 :strike ls :expiry expiry :open-price (or lo-px 0.0)}
                              {:symbol (str ticker "-" otype "-" ms "-1") :option-type (keyword otype)
                               :side (keyword (if (= "long" dir) "short" "long")) :ratio-qty 1 :strike ms :expiry expiry :open-price (or mid-px 0.0)}
                              {:symbol (str ticker "-" otype "-" ms "-2") :option-type (keyword otype)
                               :side (keyword (if (= "long" dir) "short" "long")) :ratio-qty 1 :strike ms :expiry expiry :open-price (or mid-px 0.0)}
                              {:symbol (str ticker "-" otype "-" us) :option-type (keyword otype)
                               :side (keyword dir) :ratio-qty 1 :strike us :expiry expiry :open-price (or up-px 0.0)}]})]
        (ok {:trade-id (str id) :net net}))))

  (POST "/api/sim/orders/back-ratio" req
    (guarded
      (let [b        (:body req)
            otype    (str (:option-type b))
            sim-date (str (:sim-date b))
            ticker   (str/upper-case (:ticker b))
            expiry   (str (:expiry b))
            ss       (double (:short-strike b))
            ls       (double (:long-strike b))
            qty      (long (:qty b))
            short-px (:bid (sim/contract-mid ticker sim-date expiry ss otype))
            long-px  (:ask (sim/contract-mid ticker sim-date expiry ls otype))
            net      (- (or short-px 0.0) (* 2.0 (or long-px 0.0)))
            id       (sim/record-sim-trade!
                       {:ticker          ticker
                        :instrument-type :back-ratio
                        :option-type     (keyword otype)
                        :qty             qty
                        :open-value      net
                        :sim-open-date   sim-date
                        :legs [{:symbol (str ticker "-" otype "-" ss "-short") :option-type (keyword otype)
                                 :side :short :ratio-qty 1 :strike ss :expiry expiry :open-price (or short-px 0.0)}
                                {:symbol (str ticker "-" otype "-" ls "-long-1") :option-type (keyword otype)
                                 :side :long :ratio-qty 1 :strike ls :expiry expiry :open-price (or long-px 0.0)}
                                {:symbol (str ticker "-" otype "-" ls "-long-2") :option-type (keyword otype)
                                 :side :long :ratio-qty 1 :strike ls :expiry expiry :open-price (or long-px 0.0)}]})]
        (ok {:trade-id (str id) :net net}))))

  (GET "/api/sim/trades" []
    (guarded (ok {:trades (sim/all-sim-trades)})))

  (POST "/api/sim/trades/:trade-id/close" req
    (guarded
      (let [trade-id (java.util.UUID/fromString (get-in req [:route-params :trade-id]))
            sim-date (get (:body req) :sim-date)
            pnl      (sim/close-sim-trade! trade-id sim-date)]
        (ok {:pnl pnl}))))

  (GET "/api/sim/trades/:trade-id/pnl" req
    (guarded
      (let [trade-id (java.util.UUID/fromString (get-in req [:route-params :trade-id]))
            sim-date (get-in req [:params "date"])
            trade    (first (filter #(= trade-id (:trade/id %)) (sim/all-sim-trades)))
            pnl      (when trade (sim/sim-trade-pnl-at trade sim-date))]
        (ok {:pnl pnl})))))

;; ---- App ----

(defroutes all-routes
  market-routes
  account-routes
  order-routes
  trade-routes
  sim-routes)

(defn- cors-headers [origin]
  {"Access-Control-Allow-Origin"  origin
   "Access-Control-Allow-Methods" "GET, POST, DELETE, OPTIONS"
   "Access-Control-Allow-Headers" "Content-Type"
   "Vary"                         "Origin"})

(defn wrap-cors
  "CORS for the dashboard's own origin(s) only. Other sites' pages can't read responses, and
   can't send the JSON POST/DELETE requests that place and cancel orders: those need a preflight,
   which is refused here."
  [handler]
  (fn [req]
    (let [origin  (get-in req [:headers "origin"])
          allowed (contains? allowed-origins origin)]
      (if (= :options (:request-method req))
        (if allowed
          {:status 204 :headers (assoc (cors-headers origin) "Access-Control-Max-Age" "86400") :body ""}
          {:status 403 :headers {} :body ""})
        (cond-> (handler req)
          allowed (update :headers merge (cors-headers origin)))))))

(def ^:private local-hostnames #{"localhost" "127.0.0.1" "[::1]" "::1"})

(defn- hostname [host-header]
  (let [h (str/lower-case (or host-header ""))]
    (cond
      (str/starts-with? h "[") (subs h 0 (inc (max 0 (str/index-of h "]"))))
      :else (first (str/split h #":")))))

(defn wrap-localhost-only
  "Defence against DNS rebinding: a page on evil.example can re-point its own domain at 127.0.0.1
   and call this API as if it were same-origin, so CORS doesn't help. Browsers never let scripts
   change Host, though, so reject any request whose Host isn't localhost, and any request from a
   browser origin that isn't allowed."
  [handler]
  (fn [req]
    (let [origin (get-in req [:headers "origin"])]
      (cond
        (not (contains? (conj local-hostnames host) (hostname (get-in req [:headers "host"]))))
        (err "Requests must be addressed to localhost" 403)

        (and origin (not= origin "null") (not (contains? allowed-origins origin)))
        (err (str "Origin not allowed: " origin) 403)

        :else (handler req)))))

(def app
  (-> all-routes
      (wrap-json-body {:keywords? true})
      wrap-params
      wrap-cors
      wrap-localhost-only))

(defonce ^:private server (atom nil))

(defn start! []
  (store/init!)
  (sim/init!)
  (when @server (@server :timeout 100))
  (reset! server (srv/run-server app {:ip host :port port}))
  (println (str "API server on http://" host ":" port)))

(defn stop! []
  (when @server
    (@server :timeout 100)
    (reset! server nil)
    (println "API server stopped")))

(defn -main [& _]
  (start!)
  @(promise))
