(ns jotis.viz.dashboard.server-test
  (:require [clojure.test :refer [deftest is testing]]
            [jotis.viz.dashboard.server :as server]))

(def ^:private handler
  (-> (constantly {:status 200 :headers {} :body "ok"})
      server/wrap-cors
      server/wrap-localhost-only))

(defn- req [method headers] {:request-method method :uri "/api/account" :headers headers})

(deftest localhost-requests-allowed
  (doseq [host ["localhost:3001" "127.0.0.1:3001" "[::1]:3001"]]
    (is (= 200 (:status (handler (req :get {"host" host})))) host)))

(deftest dns-rebinding-rejected
  (testing "Host naming another domain (DNS rebinding)"
    (is (= 403 (:status (handler (req :get {"host" "evil.example:3001"})))))
    (is (= 403 (:status (handler (req :post {"host" "localhost.evil.example:3001"}))))))
  (testing "a disallowed browser origin, even addressed to localhost"
    (is (= 403 (:status (handler (req :post {"host" "localhost:3001" "origin" "http://evil.example"})))))))

(deftest cors-only-for-the-dashboard
  (let [allowed "http://localhost:8080"
        resp    (handler (req :get {"host" "localhost:3001" "origin" allowed}))]
    (is (= allowed (get-in resp [:headers "Access-Control-Allow-Origin"]))))
  (testing "no wildcard, and no CORS headers without an Origin"
    (is (nil? (get-in (handler (req :get {"host" "localhost:3001"})) [:headers "Access-Control-Allow-Origin"]))))
  (testing "preflight: allowed origin gets 204, others are rejected before the CORS layer"
    (is (= 204 (:status (handler (req :options {"host" "localhost:3001" "origin" "http://localhost:8080"})))))
    (is (= 403 (:status (handler (req :options {"host" "localhost:3001" "origin" "http://evil.example"})))))))
