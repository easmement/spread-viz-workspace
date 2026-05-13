(ns jotis.viz.dashboard.sim-views
  (:require [reagent.core :as r]))

(def ^:private api "http://localhost:3001")

;; ---- Shared styles (mirrors trading-views) ----

(def ^:private s-input
  {:background "#0f172a" :color "white" :border "1px solid #334155"
   :border-radius "6px" :padding "7px 10px" :font-size "14px"
   :width "100%" :box-sizing "border-box"})

(def ^:private s-label
  {:color "#94a3b8" :font-size "12px" :font-weight "500"
   :margin-bottom "5px" :display "block" :letter-spacing "0.03em"})

(def ^:private s-card
  {:background "#0d1117" :border "1px solid #21262d" :border-radius "10px"
   :padding "20px 24px" :margin-bottom "20px"})

(def ^:private s-section-title
  {:color "white" :font-size "14px" :font-weight "600"
   :margin "0 0 16px" :letter-spacing "0.02em"})

(defn- field [label child]
  [:div {:style {:display "flex" :flex-direction "column"}}
   [:label {:style s-label} label]
   child])

(defn- text-in [form k label & [{:keys [upper?]}]]
  [field label
   [:input {:type "text" :value (get @form k "")
            :style (cond-> s-input upper? (assoc :text-transform "uppercase"))
            :on-change #(swap! form assoc k (.. % -target -value))}]])

(defn- num-in [form k label & [attrs]]
  [field label
   [:input (merge {:type "number" :value (get @form k "") :style s-input
                   :on-change #(swap! form assoc k (.. % -target -value))} attrs)]])

(defn- date-in [form k label]
  [field label
   [:input {:type "date" :value (get @form k "") :style s-input
            :on-change #(swap! form assoc k (.. % -target -value))}]])

(defn- select-in [form k label opts]
  [field label
   [:select {:value (get @form k (ffirst opts))
             :style (assoc s-input :cursor "pointer")
             :on-change #(swap! form assoc k (.. % -target -value))}
    (for [[v t] opts] ^{:key v} [:option {:value v} t])]])

(defn- grid [cols & children]
  (into [:div {:style {:display "grid"
                       :grid-template-columns (str "repeat(" cols ", 1fr)")
                       :gap "14px"}}]
        (remove nil? children)))

;; ---- HTTP helpers ----

(defn- post! [path body on-ok on-err]
  (-> (js/fetch (str api path)
                #js {:method "POST"
                     :headers #js {"Content-Type" "application/json"}
                     :body (.stringify js/JSON (clj->js body))})
      (.then #(.json %))
      (.then (fn [d]
               (let [m (js->clj d :keywordize-keys true)]
                 (if (:error m) (on-err (:error m)) (on-ok m)))))
      (.catch #(on-err (.-message %)))))

(defn- get! [path on-ok on-err]
  (-> (js/fetch (str api path))
      (.then #(.json %))
      (.then (fn [d]
               (let [m (js->clj d :keywordize-keys true)]
                 (if (:error m) (on-err (:error m)) (on-ok m)))))
      (.catch #(on-err (.-message %)))))

;; ---- Formatting helpers ----

(defn- fmt$ [v] (when (some? v) (str "$" (.toFixed v 2))))

(defn- pnl-color [v]
  (cond (nil? v) "#64748b" (pos? v) "#4ade80" (neg? v) "#f87171" :else "#94a3b8"))

(defn- s-btn [color]
  {:background "transparent" :color color :border (str "1px solid " color)
   :border-radius "4px" :padding "3px 10px" :font-size "12px" :cursor "pointer"})

(defn- action-btn [label color on-click & [{:keys [loading?]}]]
  [:button {:style    {:background    (if loading? "#374151" color)
                       :color         "white" :border "none"
                       :border-radius "6px" :padding "9px 24px"
                       :font-size "14px" :font-weight "600"
                       :cursor (if loading? "not-allowed" "pointer")}
            :disabled loading?
            :on-click on-click}
   (if loading? "Working…" label)])

;; ---- Section 1: Data Setup ----

(defn- setup-panel [on-tickers-loaded]
  (let [form    (r/atom {:ticker "NVDA" :start "2023-01-01" :end "2024-12-31"})
        loading (r/atom false)
        status  (r/atom nil)
        tickers (r/atom [])]
    (letfn [(load-tickers! []
              (get! "/api/sim/tickers"
                    (fn [r]
                      (reset! tickers (:tickers r))
                      (on-tickers-loaded (:tickers r)))
                    #(js/console.warn "tickers fetch failed:" %)))]
      (load-tickers!)
      (fn []
        [:div {:style s-card}
         [:h3 {:style s-section-title} "Data Setup"]
         [:p {:style {:color "#64748b" :font-size "12px" :margin "0 0 14px"}}
          "Fetches daily closing prices from Alpaca and computes B-S option prices "
          "for all strikes (±25% of spot) and four expiry offsets (30 / 60 / 90 / 180 days). "
          "Generation takes ~30–60 s per ticker."]
         [:div {:style {:display "flex" :gap "8px" :margin-bottom "10px" :align-items "center"}}
          [:span {:style {:color "#64748b" :font-size "11px"}} "QUICK PICK:"]
          (for [t ["NVDA" "MU" "TSLA"]]
            ^{:key t}
            [:button {:style    {:background (if (= (:ticker @form) t) "#1d4ed8" "#1e293b")
                                  :color "white" :border "1px solid #334155"
                                  :border-radius "4px" :padding "3px 10px"
                                  :font-size "12px" :cursor "pointer"}
                      :on-click #(swap! form assoc :ticker t)}
             t])]
        [grid 4
          [text-in form :ticker "Ticker" {:upper? true}]
          [date-in form :start  "History Start"]
          [date-in form :end    "History End"]]
         [:div {:style {:margin-top "14px" :display "flex" :gap "10px" :align-items "center"}}
          [action-btn "Generate" "#2563eb"
           (fn []
             (reset! loading true)
             (reset! status nil)
             (post! "/api/sim/generate"
                    {:ticker (:ticker @form)
                     :start  (:start @form)
                     :end    (:end @form)}
                    (fn [r]
                      (reset! status {:ok (str "Done — " (:rows-inserted r) " option rows for "
                                               (:dates r) " dates")})
                      (reset! loading false)
                      (load-tickers!))
                    (fn [e]
                      (reset! status {:err e})
                      (reset! loading false))))
           {:loading? @loading}]
          (when-let [s @status]
            [:span {:style {:font-size "13px"
                            :color     (if (:err s) "#f87171" "#4ade80")}}
             (or (:ok s) (str "Error: " (:err s)))])]

         ;; Ticker inventory
         (when (seq @tickers)
           [:div {:style {:margin-top "16px"}}
            [:div {:style {:color "#64748b" :font-size "11px" :margin-bottom "8px"}} "AVAILABLE DATA"]
            (into [:div {:style {:display "flex" :gap "10px" :flex-wrap "wrap"}}]
                  (for [{:keys [ticker dates]} @tickers]
                    [:div {:style {:background "#1e293b" :border-radius "6px"
                                   :padding "6px 12px" :font-size "13px"}}
                     [:span {:style {:color "white" :font-weight "600"}} ticker]
                     [:span {:style {:color "#64748b" :margin-left "6px"}} (str dates " days")]]))])]))))

;; ---- Section 2: Chain Explorer ----

(defn- chain-table [rows on-select-strike]
  (if (empty? rows)
    [:p {:style {:color "#64748b" :font-size "13px"}} "No chain data — load a chain above."]
    [:div {:style {:overflow-x "auto"}}
     [:table {:style {:width "100%" :border-collapse "collapse"
                      :color "white" :font-size "12px"}}
      [:thead
       [:tr {:style {:color "#64748b" :text-align "right"
                     :border-bottom "1px solid #334155"}}
        [:th {:style {:padding "6px 10px" :text-align "left"}} "Strike"]
        [:th {:style {:padding "6px 8px"}} "C-Bid"]
        [:th {:style {:padding "6px 8px"}} "C-Ask"]
        [:th {:style {:padding "6px 8px"}} "C-IV"]
        [:th {:style {:padding "6px 8px"}} "C-Δ"]
        [:th {:style {:padding "6px 8px" :border-left "1px solid #334155"}} "P-Bid"]
        [:th {:style {:padding "6px 8px"}} "P-Ask"]
        [:th {:style {:padding "6px 8px"}} "P-IV"]
        [:th {:style {:padding "6px 8px"}} "P-Δ"]
        [:th {:style {:padding "6px 8px"}} ""]]]
      [:tbody
       (for [row rows]
         ^{:key (:strike row)}
         [:tr {:style {:border-bottom "1px solid #1e293b"
                       :cursor "pointer"}
               :on-mouse-over #(set! (.. % -currentTarget -style -background) "#1e293b")
               :on-mouse-out  #(set! (.. % -currentTarget -style -background) "transparent")}
          [:td {:style {:padding "6px 10px" :font-weight "600" :text-align "left"}}
           (fmt$ (:strike row))]
          [:td {:style {:padding "6px 8px" :text-align "right" :color "#4ade80"}}
           (fmt$ (:call-bid row))]
          [:td {:style {:padding "6px 8px" :text-align "right"}}
           (fmt$ (:call-ask row))]
          [:td {:style {:padding "6px 8px" :text-align "right" :color "#94a3b8"}}
           (when (:call-iv row) (str (.toFixed (* 100 (:call-iv row)) 1) "%"))]
          [:td {:style {:padding "6px 8px" :text-align "right" :color "#60a5fa"}}
           (when (:call-delta row) (.toFixed (:call-delta row) 2))]
          [:td {:style {:padding "6px 8px" :text-align "right" :color "#4ade80"
                        :border-left "1px solid #334155"}}
           (fmt$ (:put-bid row))]
          [:td {:style {:padding "6px 8px" :text-align "right"}}
           (fmt$ (:put-ask row))]
          [:td {:style {:padding "6px 8px" :text-align "right" :color "#94a3b8"}}
           (when (:put-iv row) (str (.toFixed (* 100 (:put-iv row)) 1) "%"))]
          [:td {:style {:padding "6px 8px" :text-align "right" :color "#60a5fa"}}
           (when (:put-delta row) (.toFixed (:put-delta row) 2))]
          [:td {:style {:padding "6px 8px"}}
           [:button {:style (s-btn "#60a5fa")
                     :on-click #(on-select-strike (:strike row))}
            "Use"]]])]]]))

(defn- explorer-panel [_ on-chain-context]
  (let [form      (r/atom {:ticker "" :date "" :expiry ""})
        dates     (r/atom [])
        expiries  (r/atom [])
        chain     (r/atom [])
        loading   (r/atom false)
        init-done (atom false)]
    (letfn [(load-chain! []
              (let [{:keys [ticker date expiry]} @form]
                (when (and (seq ticker) (seq date) (seq expiry))
                  (reset! loading true)
                  (get! (str "/api/sim/chain/" ticker "?date=" date "&expiry=" expiry)
                        (fn [r]
                          (reset! chain (:chain r))
                          (on-chain-context {:ticker ticker :date date :expiry expiry})
                          (reset! loading false))
                        (fn [e]
                          (js/console.warn "chain fetch failed:" e)
                          (reset! loading false))))))
            (load-expiries! [ticker date then]
              (when (and (seq ticker) (seq date))
                (get! (str "/api/sim/expiries/" ticker "?date=" date)
                      (fn [r]
                        (reset! expiries (:expiries r))
                        (when-let [e (first (:expiries r))]
                          (swap! form assoc :expiry e))
                        (when then (then)))
                      #(js/console.warn "expiries fetch failed:" %))))
            (load-dates! [ticker then]
              (when (seq ticker)
                (get! (str "/api/sim/dates/" ticker)
                      (fn [r]
                        (reset! dates (:dates r))
                        (when-let [d (last (:dates r))]
                          (swap! form assoc :date d)
                          (load-expiries! ticker d then)))
                      #(js/console.warn "dates fetch failed:" %))))
            (set-ticker! [t]
              (swap! form assoc :ticker t :date "" :expiry "")
              (reset! dates [])
              (reset! expiries [])
              (load-dates! t nil))]
      (fn [tickers _]
        ;; Auto-select first available ticker once when tickers load
        (when (and (seq tickers) (empty? (:ticker @form)) (not @init-done))
          (reset! init-done true)
          (js/setTimeout #(set-ticker! (:ticker (first tickers))) 0))
        (let [ticker-opts (map (fn [{:keys [ticker]}] [ticker ticker]) tickers)]
          [:div {:style s-card}
           [:h3 {:style s-section-title} "Option Chain Explorer"]
           [grid 4
            (if (seq ticker-opts)
              [field "Ticker"
               [:select {:value     (:ticker @form "")
                         :style     (assoc s-input :cursor "pointer")
                         :on-change #(set-ticker! (.. % -target -value))}
                (for [[v t] ticker-opts] ^{:key v} [:option {:value v} t])]]
              [text-in form :ticker "Ticker" {:upper? true}])
            (if (seq @dates)
              [field "Sim Date"
               [:select {:value     (:date @form "")
                         :style     (assoc s-input :cursor "pointer")
                         :on-change (fn [e]
                                      (let [d (.. e -target -value)]
                                        (swap! form assoc :date d :expiry "")
                                        (reset! expiries [])
                                        (load-expiries! (:ticker @form) d nil)))}
                (for [d @dates] ^{:key d} [:option {:value d} d])]]
              [date-in form :date "Sim Date"])
            (if (seq @expiries)
              [field "Expiry"
               [:select {:value     (:expiry @form "")
                         :style     (assoc s-input :cursor "pointer")
                         :on-change #(swap! form assoc :expiry (.. % -target -value))}
                (for [e @expiries] ^{:key e} [:option {:value e} e])]]
              [date-in form :expiry "Expiry"])]
           [:div {:style {:margin-top "14px" :display "flex" :gap "10px"}}
            [action-btn "Load Chain" "#0f766e"
             (fn []
               (let [{:keys [ticker date expiry]} @form]
                 (cond
                   (and (seq date) (seq expiry)) (load-chain!)
                   (seq date)                    (load-expiries! ticker date load-chain!)
                   :else                         (load-dates! ticker load-chain!))))
             {:loading? @loading}]]
           [:div {:style {:margin-top "16px"}}
            [chain-table @chain (fn [strike]
                                  (swap! form assoc :selected-strike strike)
                                  (on-chain-context (assoc @form :selected-strike strike)))]]])))))

;; ---- Section 3: Sim Trade Forms ----

(defn- status-msg [status]
  (when-let [s @status]
    [:div {:style {:margin-top "10px" :font-size "13px"
                   :color (if (:error s) "#f87171" "#4ade80")}}
     (if (:error s)
       (str "Error: " (:error s))
       (str "Sim trade placed · ID: " (:trade-id s)))]))

(defn- sim-vertical-form [_ refresh-trades!]
  (let [form    (r/atom {:option-type "put" :qty "1"})
        loading (r/atom false)
        status  (r/atom nil)
        on-done refresh-trades!]
    (fn [props _]
      (let [sim-date (:date props)]
        [:<>
         [grid 4
          [text-in   form :ticker       "Ticker" {:upper? true}]
          [select-in form :option-type  "Type"
           [["put" "Put Spread"] ["call" "Call Spread"]]]
          [num-in    form :short-strike "Short Strike ($)" {:step "0.5" :min "0"}]
          [num-in    form :long-strike  "Long Strike ($)"  {:step "0.5" :min "0"}]
          [date-in   form :expiry       "Expiry"]
          [num-in    form :qty          "Contracts" {:step "1" :min "1"}]]
         [status-msg status]
         [:div {:style {:margin-top "12px"}}
          [action-btn "Place Vertical Spread" "#16a34a"
           (fn []
             (reset! loading true) (reset! status nil)
             (post! "/api/sim/orders/vertical"
                    (-> @form
                        (assoc :sim-date sim-date)
                        (update :short-strike js/parseFloat)
                        (update :long-strike  js/parseFloat)
                        (update :qty          js/parseInt))
                    (fn [r] (reset! status r) (reset! loading false) (on-done))
                    (fn [e] (reset! status {:error e}) (reset! loading false))))
           {:loading? @loading}]]]))))

(defn- sim-butterfly-form [_ refresh-trades!]
  (let [form    (r/atom {:option-type "call" :direction "long" :qty "1"})
        loading (r/atom false)
        status  (r/atom nil)
        on-done refresh-trades!]
    (fn [props _]
      (let [sim-date (:date props)]
        [:<>
         [grid 4
          [text-in   form :ticker        "Ticker" {:upper? true}]
          [select-in form :option-type   "Option Type"
           [["call" "Call Butterfly"] ["put" "Put Butterfly"]]]
          [select-in form :direction     "Direction"
           [["long" "Long (Debit)"] ["short" "Short (Credit)"]]]
          [num-in  form :lower-strike  "Lower Wing ($)"  {:step "0.5" :min "0"}]
          [num-in  form :middle-strike "Body Strike ($)" {:step "0.5" :min "0"}]
          [num-in  form :upper-strike  "Upper Wing ($)"  {:step "0.5" :min "0"}]
          [date-in form :expiry        "Expiry"]
          [num-in  form :qty           "Contracts" {:step "1" :min "1"}]]
         [status-msg status]
         [:div {:style {:margin-top "12px"}}
          [action-btn "Place Butterfly" "#16a34a"
           (fn []
             (reset! loading true) (reset! status nil)
             (post! "/api/sim/orders/butterfly"
                    (-> @form
                        (assoc :sim-date sim-date)
                        (update :lower-strike  js/parseFloat)
                        (update :middle-strike js/parseFloat)
                        (update :upper-strike  js/parseFloat)
                        (update :qty           js/parseInt))
                    (fn [r] (reset! status r) (reset! loading false) (on-done))
                    (fn [e] (reset! status {:error e}) (reset! loading false))))
           {:loading? @loading}]]]))))

(defn- sim-back-ratio-form [_ refresh-trades!]
  (let [form    (r/atom {:option-type "call" :qty "1"})
        loading (r/atom false)
        status  (r/atom nil)
        on-done refresh-trades!]
    (fn [props _]
      (let [sim-date (:date props)]
        [:<>
         [:p {:style {:color "#64748b" :font-size "12px" :margin "0 0 12px"}}
          "Sell 1 at short-strike · buy 2 at long-strike. Profits from a large move past the long strike."]
         [grid 4
          [text-in   form :ticker       "Ticker" {:upper? true}]
          [select-in form :option-type  "Option Type"
           [["call" "Call Back Ratio"] ["put" "Put Back Ratio"]]]
          [num-in  form :short-strike "Short Strike — Sell 1 ($)" {:step "0.5" :min "0"}]
          [num-in  form :long-strike  "Long Strike — Buy 2 ($)"  {:step "0.5" :min "0"}]
          [date-in form :expiry       "Expiry"]
          [num-in  form :qty          "Spreads" {:step "1" :min "1"}]]
         [status-msg status]
         [:div {:style {:margin-top "12px"}}
          [action-btn "Place Back Ratio" "#16a34a"
           (fn []
             (reset! loading true) (reset! status nil)
             (post! "/api/sim/orders/back-ratio"
                    (-> @form
                        (assoc :sim-date sim-date)
                        (update :short-strike js/parseFloat)
                        (update :long-strike  js/parseFloat)
                        (update :qty          js/parseInt))
                    (fn [r] (reset! status r) (reset! loading false) (on-done))
                    (fn [e] (reset! status {:error e}) (reset! loading false))))
           {:loading? @loading}]]]))))

(defn- trade-entry-panel [_ _]
  (let [strategy (r/atom "vertical")]
    (fn [ctx-atom on-refresh]
      [:div {:style s-card}
       [:h3 {:style s-section-title} "Place Sim Trade"]
       (when (:date @ctx-atom)
         [:p {:style {:color "#64748b" :font-size "12px" :margin "0 0 14px"}}
          "Trade date: " [:span {:style {:color "white"}} (:date @ctx-atom)]
          "  ·  Ticker: " [:span {:style {:color "white"}} (:ticker @ctx-atom)]])
       [:div {:style {:margin-bottom "16px"}}
        [:label {:style s-label} "Strategy"]
        [:select {:value     @strategy
                  :style     (assoc s-input :max-width "280px" :cursor "pointer")
                  :on-change #(reset! strategy (.. % -target -value))}
         [:option {:value "vertical"}   "Vertical Spread"]
         [:option {:value "butterfly"}  "Butterfly"]
         [:option {:value "back-ratio"} "Back Ratio Spread"]]]
       (case @strategy
         "vertical"   [sim-vertical-form   @ctx-atom on-refresh]
         "butterfly"  [sim-butterfly-form  @ctx-atom on-refresh]
         "back-ratio" [sim-back-ratio-form @ctx-atom on-refresh])])))

;; ---- Section 4: Sim Positions P&L ----

(defn- positions-panel []
  (let [trades   (r/atom [])
        pnl-date (r/atom "")
        pnl-map  (r/atom {})
        closing  (r/atom nil)]
    (letfn [(refresh! []
              (get! "/api/sim/trades"
                    #(reset! trades (:trades %))
                    #(js/console.warn "sim trades fetch failed:" %)))
            (load-pnl! []
              (let [d @pnl-date]
                (when (seq d)
                  (doseq [t @trades
                          :when (= "open" (name (or (:trade/status t) :open)))
                          :let [tid (str (:trade/id t))]]
                    (get! (str "/api/sim/trades/" tid "/pnl?date=" d)
                          (fn [r] (swap! pnl-map assoc tid (:pnl r)))
                          #(js/console.warn "pnl fetch failed:" %))))))
            (close-trade! [trade-id]
              (when (seq @pnl-date)
                (post! (str "/api/sim/trades/" trade-id "/close")
                       {:sim-date @pnl-date}
                       (fn [_] (reset! closing nil) (refresh!))
                       #(js/console.error "close failed:" %))))]
      (refresh!)
      (fn []
        [:div {:style s-card}
         [:h3 {:style s-section-title} "Sim Positions"]
         [:div {:style {:display "flex" :gap "12px" :align-items "flex-end" :margin-bottom "16px"}}
          [:div {:style {:flex 1}}
           [field "Evaluate P&L at Date"
            [:input {:type "date" :value @pnl-date :style s-input
                     :on-change #(reset! pnl-date (.. % -target -value))}]]]
          [:button {:style {:background "#0f766e" :color "white" :border "none"
                            :border-radius "6px" :padding "9px 18px" :font-size "13px"
                            :font-weight "600" :cursor "pointer" :white-space "nowrap"}
                    :on-click (fn [] (refresh!) (load-pnl!))}
           "Refresh P&L"]]

         (if (empty? @trades)
           [:p {:style {:color "#64748b" :font-size "14px"}} "No sim trades yet."]
           [:table {:style {:width "100%" :border-collapse "collapse"
                            :color "white" :font-size "13px"}}
            [:thead
             [:tr {:style {:color "#64748b" :text-align "left"
                           :border-bottom "1px solid #334155"}}
              (for [h ["Entry Date" "Ticker" "Strategy" "Qty" "Open Value"
                        "P&L at Date" "Status" ""]]
                ^{:key h} [:th {:style {:padding "8px 10px"}} h])]]
            [:tbody
             (for [t @trades]
               ^{:key (str (:trade/id t))}
               (let [tid     (str (:trade/id t))
                     pnl     (get @pnl-map tid)
                     open?   (= :open (keyword (name (or (:trade/status t) :open))))]
                 [:tr {:style {:border-bottom "1px solid #1e293b"}}
                  [:td {:style {:padding "8px 10px"}} (:trade/sim-open-date t)]
                  [:td {:style {:padding "8px 10px" :font-weight "600"}} (:trade/ticker t)]
                  [:td {:style {:padding "8px 10px"}}
                   (some-> (:trade/instrument-type t) name)]
                  [:td {:style {:padding "8px 10px"}} (:trade/qty t)]
                  [:td {:style {:padding "8px 10px"}}
                   (let [ov (:trade/open-value t)]
                     (if (and ov (pos? ov))
                       (str "$" (.toFixed ov 2) " cr")
                       (str "$" (.toFixed (js/Math.abs (or ov 0)) 2) " dr")))]
                  [:td {:style {:padding "8px 10px" :font-weight "600"
                                :color (pnl-color pnl)}}
                   (if pnl (str "$" (.toFixed pnl 2)) "—")]
                  [:td {:style {:padding "8px 10px"
                                :color (if open? "#60a5fa" "#4ade80")}}
                   (some-> (:trade/status t) name)]
                  [:td {:style {:padding "4px 8px"}}
                   (when open?
                     (if (= @closing tid)
                       [:span {:style {:display "flex" :gap "6px"}}
                        [:button {:style {:background "#16a34a" :color "white" :border "none"
                                          :border-radius "4px" :padding "4px 12px"
                                          :font-size "12px" :cursor "pointer"}
                                  :on-click #(close-trade! tid)}
                         (str "Close at " (or @pnl-date "?"))]
                        [:button {:style {:background "transparent" :color "#64748b"
                                          :border "none" :font-size "16px"
                                          :cursor "pointer" :line-height "1"}
                                  :on-click #(reset! closing nil)}
                         "✕"]]
                       [:button {:style (s-btn "#f87171")
                                 :on-click #(reset! closing tid)}
                        "Close"]))]]))]])]))))

;; ---- Main sim panel ----

(defn sim-panel []
  (let [tickers   (r/atom [])
        chain-ctx (r/atom {})
        trades-refresh (r/atom 0)]
    (fn []
      [:div
       [setup-panel (fn [t] (reset! tickers t))]
       [explorer-panel @tickers (fn [ctx] (reset! chain-ctx ctx))]
       [trade-entry-panel chain-ctx (fn [] (swap! trades-refresh inc))]
       ^{:key @trades-refresh}
       [positions-panel]])))
