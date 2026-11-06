(ns jotis.viz.dashboard.views
  (:require [reagent.core :as r]
            [clojure.string :as str]
            ["plotly.js-dist-min" :as Plotly]
            [jotis.viz.dashboard.config :as config]
            [jotis.viz.dashboard.trading-views :as tv]
            [jotis.viz.dashboard.sim-views :as sv]))

;; ---- Styles ----

(def ^:private s-input
  {:background "#0f172a" :color "white" :border "1px solid #334155"
   :border-radius "6px" :padding "7px 10px" :font-size "14px"
   :width "100%" :box-sizing "border-box"})

(def ^:private s-label
  {:color "#94a3b8" :font-size "12px" :font-weight "500"
   :margin-bottom "5px" :display "block" :letter-spacing "0.03em"})

;; ---- Form field components ----

(defn- field [label child]
  [:div {:style {:display "flex" :flex-direction "column"}}
   [:label {:style s-label} label]
   child])

(defn- text-in [form k label]
  [field label
   [:input {:type      "text"
            :value     (get @form k)
            :style     (assoc s-input :text-transform "uppercase")
            :on-change #(swap! form assoc k (.. % -target -value))}]])

(defn- num-in [form k label & [attrs]]
  [field label
   [:input (merge {:type      "number"
                   :value     (get @form k)
                   :style     s-input
                   :on-change #(swap! form assoc k (.. % -target -value))}
                  attrs)]])

(defn- date-in [form k label]
  [field label
   [:input {:type      "date"
            :value     (get @form k)
            :style     s-input
            :on-change #(swap! form assoc k (.. % -target -value))}]])

(defn- select-in [form k label opts]
  [field label
   [:select {:value     (get @form k)
             :style     (assoc s-input :cursor "pointer")
             :on-change #(swap! form assoc k (.. % -target -value))}
    (for [[v t] opts]
      ^{:key v} [:option {:value v} t])]])

;; ---- State <-> form conversion ----

(defn- state->form [{:keys [ticker option-type legs spot iv initial-cost expiry]}]
  {:ticker       ticker
   :option-type  (name option-type)
   :spot         spot
   :short-strike (-> legs first :strike)
   :long-strike  (-> legs second :strike)
   :iv-pct       (* 100 iv)
   :initial-cost initial-cost
   :expiry       expiry})

(defn- form->state [{:keys [ticker option-type spot short-strike long-strike
                            iv-pct initial-cost expiry]}]
  (let [type-kw (keyword option-type)]
    {:ticker       (str/upper-case (str/trim ticker))
     :option-type  type-kw
     :legs         [{:type type-kw :strike (js/parseFloat short-strike) :side :short}
                    {:type type-kw :strike (js/parseFloat long-strike)  :side :long}]
     :spot         (js/parseFloat spot)
     :iv           (/ (js/parseFloat iv-pct) 100.0)
     :rate         0.05
     :initial-cost (js/parseFloat initial-cost)
     :expiry       expiry}))

;; ---- Live data fetch ----

(def ^:private api-base config/api-base)

(defn- fetch-live! [form loading err]
  (let [{:keys [ticker expiry spot option-type]} @form]
    (reset! loading true)
    (reset! err nil)
    (-> (js/Promise.all
          #js [(-> (js/fetch (str api-base "/api/quote/" ticker))
                   (.then #(.json %)))
               (-> (js/fetch (str api-base "/api/iv/" ticker
                                  "?expiry=" expiry
                                  "&spot=" spot
                                  "&type=" option-type))
                   (.then #(.json %)))])
        (.then (fn [[q-data iv-data]]
                 (let [price (aget q-data "price")
                       iv    (aget iv-data "iv")
                       qerr  (aget q-data "error")
                       iverr (aget iv-data "error")]
                   (cond
                     qerr  (reset! err (str "Quote: " qerr))
                     iverr (reset! err (str "IV: " iverr))
                     :else (swap! form assoc
                                  :spot    (.toFixed price 2)
                                  :iv-pct  (.toFixed (* iv 100) 1))))))
        (.catch (fn [e]
                  (reset! err (str "Cannot reach API server — is it running? " (.-message e)))))
        (.finally (fn [] (reset! loading false))))))

;; ---- Form panel ----

(defn form-panel [app-state]
  (let [form    (r/atom (state->form @app-state))
        loading (r/atom false)
        err     (r/atom nil)]
    (fn [app-state]
      [:div {:style {:background    "#0d1117"
                     :border        "1px solid #21262d"
                     :border-radius "10px"
                     :padding       "18px 20px"
                     :margin-bottom "24px"}}
       [:div {:style {:display               "grid"
                      :grid-template-columns "repeat(4, 1fr)"
                      :gap                   "14px"}}
        [text-in   form :ticker       "Ticker"]
        [select-in form :option-type  "Spread Type"
         [["put" "Put Spread"] ["call" "Call Spread"]]]
        [num-in    form :spot         "Spot Price ($)" {:step "0.01" :min "0"}]
        [num-in    form :iv-pct       "Implied Vol (%)" {:step "0.1" :min "0" :max "500"}]
        [num-in    form :short-strike "Short Leg Strike ($)" {:step "0.5" :min "0"}]
        [num-in    form :long-strike  "Long Leg Strike ($)"  {:step "0.5" :min "0"}]
        [num-in    form :initial-cost "Net Credit (+) / Debit (−)" {:step "0.01"}]
        [date-in   form :expiry       "Expiration Date"]]
       (when @err
         [:div {:style {:margin-top "10px" :color "#f87171" :font-size "12px"}} @err])
       [:div {:style {:margin-top "14px" :display "flex" :justify-content "flex-end" :gap "10px"}}
        [:button {:style    {:background    (if @loading "#374151" "#0f766e")
                             :color         "white"
                             :border        "none"
                             :border-radius "6px"
                             :padding       "8px 20px"
                             :font-size     "14px"
                             :font-weight   "500"
                             :cursor        (if @loading "not-allowed" "pointer")
                             :letter-spacing "0.03em"}
                  :disabled @loading
                  :on-click #(fetch-live! form loading err)}
         (if @loading "Fetching…" "Fetch Live")]
        [:button {:style    {:background    "#2563eb"
                             :color         "white"
                             :border        "none"
                             :border-radius "6px"
                             :padding       "8px 24px"
                             :font-size     "14px"
                             :font-weight   "500"
                             :cursor        "pointer"
                             :letter-spacing "0.03em"}
                  :on-click #(reset! app-state (form->state @form))}
         "Update Chart"]]])))

;; ---- Plotly chart ----

(defn- ->traces [data]
  (clj->js
    [{:x          (vec (:x data))
      :y          (vec (:y data))
      :z          (mapv vec (:z data))
      :type       "heatmap"
      :colorscale "RdBu"
      :zmid       0
      :colorbar   {:title "P&L ($)"}}
     {:x    (vec (:upper-sigma data))
      :y    (vec (:y data))
      :type "scatter"
      :mode "lines"
      :name "+1σ"
      :line {:color "rgba(255,255,255,0.85)" :dash "dash" :width 2}}
     {:x    (vec (:lower-sigma data))
      :y    (vec (:y data))
      :type "scatter"
      :mode "lines"
      :name "-1σ"
      :line {:color "rgba(255,255,255,0.85)" :dash "dash" :width 2}}]))

(defn- ->layout [ticker option-type expiry dte]
  (let [spread-label (if (= option-type :put) "Put Spread" "Call Spread")]
    (clj->js
      {:title         {:text (str ticker " · " spread-label " P&L"
                               "  |  Exp: " expiry "  (" dte " DTE)")
                       :font {:color "white" :size 16}}
       :xaxis         {:title "Stock Price" :color "white" :gridcolor "#1e293b"}
       :yaxis         {:title "Days to Expiration" :color "white"
                       :gridcolor "#1e293b" :autorange "reversed"}
       :height        560
       :paper_bgcolor "#0f0f23"
       :plot_bgcolor  "#0f172a"
       :font          {:color "white"}
       :legend        {:font {:color "white"}}})))

(defn plotly-chart [ticker option-type expiry dte data]
  (let [el (atom nil)]
    (r/create-class
      {:component-did-mount
       (fn [_]
         (when @el
           (.newPlot Plotly @el (->traces data) (->layout ticker option-type expiry dte))))
       :component-did-update
       (fn [this]
         (let [[_ tk ot ex dt d] (r/argv this)]
           (when @el
             (.react Plotly @el (->traces d) (->layout tk ot ex dt)))))
       :reagent-render
       (fn [_ _ _ _ _]
         [:div {:ref #(reset! el %) :style {:width "100%"}}])})))

;; ---- Helpers ----

(defn- days-until [date-str]
  (let [ms (- (.getTime (js/Date. date-str)) (.getTime (js/Date.)))]
    (max 0 (Math/round (/ ms 86400000)))))

;; ---- Tab bar ----

(def ^:private tabs
  [[:viz       "P&L Visualizer"]
   [:trade     "Trade"]
   [:positions "Positions"]
   [:history   "History"]
   [:sim       "Simulator"]])

(defn- tab-bar [current-tab on-change]
  (into [:div {:style {:display "flex" :gap "4px" :margin-bottom "24px"
                       :border-bottom "1px solid #21262d" :padding-bottom "0"}}]
        (map (fn [[k label]]
               ^{:key k}
               [:button {:style    {:background    (if (= current-tab k) "#1e293b" "transparent")
                                    :color         (if (= current-tab k) "white" "#64748b")
                                    :border        "none"
                                    :border-bottom (if (= current-tab k) "2px solid #3b82f6" "2px solid transparent")
                                    :padding       "8px 18px"
                                    :font-size     "14px"
                                    :font-weight   "500"
                                    :cursor        "pointer"
                                    :margin-bottom "-1px"}
                         :on-click #(on-change k)}
                label])
             tabs)))

;; ---- Main panel ----

(defn main-panel [_ _]
  (let [active-tab (r/atom :viz)]
    (fn [app-state compute-heatmap]
      (let [{:keys [ticker option-type legs iv initial-cost expiry] :as params} @app-state
            [short-leg long-leg] legs
            dte  (days-until expiry)
            data (compute-heatmap params)]
        [:div {:style {:background "#0f0f23" :min-height "100vh"
                       :padding "24px 32px" :font-family "system-ui, sans-serif"}}
         [:h1 {:style {:color "white" :margin-bottom "4px" :font-size "22px"}}
          "Options Spread Visualizer"]
         [tab-bar @active-tab #(reset! active-tab %)]
         (case @active-tab
           :viz
           [:<>
            [:p {:style {:color "#64748b" :margin-top 0 :margin-bottom "20px" :font-size "14px"}}
             (str ticker
                  " · " (str/capitalize (name option-type)) " Spread"
                  " · Short " (:strike short-leg)
                  " / Long " (:strike long-leg)
                  " · IV " (int (* iv 100)) "%"
                  " · " (if (pos? initial-cost)
                          (str "$" initial-cost " credit")
                          (str "$" (- initial-cost) " debit"))
                  " · Exp " expiry)]
            [form-panel app-state]
            [plotly-chart ticker option-type expiry dte data]]

           :trade
           [tv/trade-panel]

           :positions
           [tv/positions-panel]

           :history
           [tv/history-panel]

           :sim
           [sv/sim-panel])]))))
