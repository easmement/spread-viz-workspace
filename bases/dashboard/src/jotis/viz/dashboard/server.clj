(ns jotis.viz.dashboard.server
  (:require [org.httpkit.server :as srv]
            [compojure.core :refer [GET POST DELETE defroutes]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.json :refer [wrap-json-body]]
            [jotis.viz.market-data.interface :as market]
            [jotis.viz.account.interface :as acct]
            [jotis.viz.order-manager.interface :as orders]
            [jotis.viz.trade-store.interface :as store]
            [cheshire.core :as json]))

(def ^:private port 3001)

(defn- ok [data]
  {:status  200
   :headers {"Content-Type"                "application/json; charset=utf-8"
             "Access-Control-Allow-Origin" "*"}
   :body    (json/generate-string data {:date-format "yyyy-MM-dd'T'HH:mm:ss'Z'"})})

(defn- err [msg status]
  {:status  status
   :headers {"Content-Type"                "application/json; charset=utf-8"
             "Access-Control-Allow-Origin" "*"}
   :body    (json/generate-string {:error msg})})

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
      (let [b     (get req :json-body)
            order (orders/place-stock-order b)
            id    (store/record-trade!
                    (assoc b
                           :instrument-type :stock
                           :alpaca-order-id (:id order)
                           :legs [{:symbol      (:ticker b)
                                   :option-type :stock
                                   :side        (:side b)
                                   :open-price  (or (:limit-price b) 0.0)}]))]
      (ok {:order order :trade-id (str id)}))))

  (POST "/api/orders/single-option" req
    (guarded
      (let [b     (get req :json-body)
            order (orders/place-single-option b)
            id    (store/record-trade!
                    (assoc b
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
      (let [b           (get req :json-body)
            opt-type    (keyword (:option-type b))
            order       (orders/place-vertical-spread (assoc b :option-type opt-type))
            short-sym   (orders/occ-symbol (:ticker b) (:expiry b) opt-type (:short-strike b))
            long-sym    (orders/occ-symbol (:ticker b) (:expiry b) opt-type (:long-strike b))
            id          (store/record-trade!
                          (assoc b
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
      (let [b        (get req :json-body)
            opt-type (keyword (:option-type b))
            order    (orders/place-calendar (assoc b :option-type opt-type))
            near-sym (orders/occ-symbol (:ticker b) (:near-expiry b) opt-type (:strike b))
            far-sym  (orders/occ-symbol (:ticker b) (:far-expiry b)  opt-type (:strike b))
            id       (store/record-trade!
                       (assoc b
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
      (let [b        (get req :json-body)
            opt-type (keyword (:option-type b))
            order    (orders/place-diagonal (assoc b :option-type opt-type))
            near-sym (orders/occ-symbol (:ticker b) (:near-expiry b) opt-type (:near-strike b))
            far-sym  (orders/occ-symbol (:ticker b) (:far-expiry b)  opt-type (:far-strike b))
            id       (store/record-trade!
                       (assoc b
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
      (let [b        (get req :json-body)
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
                       (assoc b
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
      (let [b     (get req :json-body)
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
                    (assoc b
                           :instrument-type :iron-condor
                           :alpaca-order-id (:id order)
                           :legs [(mk-leg :put  (if short? :long  :short) (:put-buy-strike b))
                                  (mk-leg :put  (if short? :short :long)  (:put-sell-strike b))
                                  (mk-leg :call (if short? :short :long)  (:call-sell-strike b))
                                  (mk-leg :call (if short? :long  :short) (:call-buy-strike b))]))]
        (ok {:order order :trade-id (str id)}))))

  (POST "/api/orders/back-ratio" req
    (guarded
      (let [b        (get req :json-body)
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
                       (assoc b
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
            close-val  (get (get req :json-body) :close-value)
            pnl        (store/close-trade! trade-id close-val)]
      (ok {:pnl pnl}))))

  (POST "/api/trades/:trade-id/cancel" req
    (guarded
      (let [trade-id (java.util.UUID/fromString (get-in req [:route-params :trade-id]))]
        (store/cancel-trade! trade-id)
        (ok {:cancelled (str trade-id)})))))

;; ---- App ----

(defroutes all-routes
  market-routes
  account-routes
  order-routes
  trade-routes)

(def app
  (-> all-routes
      (wrap-json-body {:keywords? true})
      wrap-params))

(defonce ^:private server (atom nil))

(defn start! []
  (store/init!)
  (when @server (@server :timeout 100))
  (reset! server (srv/run-server app {:port port}))
  (println (str "API server on http://localhost:" port)))

(defn stop! []
  (when @server
    (@server :timeout 100)
    (reset! server nil)
    (println "API server stopped")))

(defn -main [& _]
  (start!)
  @(promise))
