(ns jotis.viz.dashboard.trading-views
  (:require [reagent.core :as r]
            [jotis.viz.dashboard.config :as config]))

(def ^:private api config/api-base)

;; ---- Shared styles ----

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

(defn- field [label child]
  [:div {:style {:display "flex" :flex-direction "column"}}
   [:label {:style s-label} label]
   child])

(defn- text-in [form k label & [{:keys [upper?]}]]
  [field label
   [:input {:type      "text"
            :value     (get @form k "")
            :style     (cond-> s-input upper? (assoc :text-transform "uppercase"))
            :on-change #(swap! form assoc k (.. % -target -value))}]])

(defn- num-in [form k label & [attrs]]
  [field label
   [:input (merge {:type "number" :value (get @form k "") :style s-input
                   :on-change #(swap! form assoc k (.. % -target -value))}
                  attrs)]])

(defn- date-in [form k label]
  [field label
   [:input {:type "date" :value (get @form k "") :style s-input
            :on-change #(swap! form assoc k (.. % -target -value))}]])

(defn- select-in [form k label opts]
  [field label
   [:select {:value     (get @form k (ffirst opts))
             :style     (assoc s-input :cursor "pointer")
             :on-change #(swap! form assoc k (.. % -target -value))}
    (for [[v t] opts]
      ^{:key v} [:option {:value v} t])]])

(defn- grid [cols & children]
  (into [:div {:style {:display               "grid"
                       :grid-template-columns (str "repeat(" cols ", 1fr)")
                       :gap                   "14px"}}]
        (remove nil? children)))

;; ---- API helpers ----

(defn- post! [path body on-ok on-err]
  (-> (js/fetch (str api path)
                #js {:method  "POST"
                     :headers #js {"Content-Type" "application/json"}
                     :body    (.stringify js/JSON (clj->js body))})
      (.then (fn [r] (.json r)))
      (.then (fn [d]
               (let [m (js->clj d :keywordize-keys true)]
                 (if (:error m) (on-err (:error m)) (on-ok m)))))
      (.catch #(on-err (.-message %)))))

(defn- get! [path on-ok on-err]
  (-> (js/fetch (str api path))
      (.then (fn [r] (.json r)))
      (.then (fn [d]
               (let [m (js->clj d :keywordize-keys true)]
                 (if (:error m) (on-err (:error m)) (on-ok m)))))
      (.catch #(on-err (.-message %)))))

(defn- delete! [path on-ok on-err]
  (-> (js/fetch (str api path) #js {:method "DELETE"})
      (.then (fn [r] (.json r)))
      (.then (fn [d]
               (let [m (js->clj d :keywordize-keys true)]
                 (if (:error m) (on-err (:error m)) (on-ok m)))))
      (.catch #(on-err (.-message %)))))

;; ---- Account summary banner ----

(defn account-banner []
  (let [acct (r/atom nil)]
    (r/create-class
      {:component-did-mount
       (fn [_]
         (get! "/api/account"
               #(reset! acct %)
               #(js/console.warn "Account fetch failed:" %)))
       :reagent-render
       (fn []
         (if-let [a @acct]
           (let [fmt #(str "$" (.toLocaleString %))]
             [:div {:style {:display "flex" :gap "32px" :padding "12px 0"
                            :border-bottom "1px solid #1e293b" :margin-bottom "20px"}}
              (for [[label val] [["Equity"       (fmt (:equity a))]
                                 ["Cash"         (fmt (:cash a))]
                                 ["Buying Power" (fmt (:buying-power a))]
                                 ["Status"       (:status a)]]]
                ^{:key label}
                [:div
                 [:div {:style {:color "#64748b" :font-size "11px"}} label]
                 [:div {:style {:color "white" :font-size "15px" :font-weight "600"}} val]])])
           [:div {:style {:color "#64748b" :font-size "13px" :padding "8px 0"}}
            "Loading account…"]))})))

;; ---- Submit button + status ----

(defn- submit-btn [label loading? on-click]
  [:button {:style    {:background    (if @loading? "#374151" "#16a34a")
                       :color         "white"
                       :border        "none"
                       :border-radius "6px"
                       :padding       "9px 28px"
                       :font-size     "14px"
                       :font-weight   "600"
                       :cursor        (if @loading? "not-allowed" "pointer")}
            :disabled @loading?
            :on-click on-click}
   (if @loading? "Submitting…" label)])

(defn- status-msg [status]
  (when-let [s @status]
    [:div {:style {:margin-top "10px"
                   :color      (if (:error s) "#f87171" "#4ade80")
                   :font-size  "13px"}}
     (if (:error s)
       (str "Error: " (:error s))
       (str "Order submitted · Trade ID: " (or (:trade-id s) "ok")))]))

;; ---- Instrument forms ----

(defn- vertical-form []
  (let [form    (r/atom {:option-type "put" :qty "1"})
        loading (r/atom false)
        status  (r/atom nil)]
    (fn []
      [:<>
       [grid 4
        [text-in   form :ticker       "Ticker" {:upper? true}]
        [select-in form :option-type  "Type"
         [["put" "Put Spread"] ["call" "Call Spread"]]]
        [num-in    form :short-strike "Short Strike ($)" {:step "0.5" :min "0"}]
        [num-in    form :long-strike  "Long Strike ($)"  {:step "0.5" :min "0"}]
        [date-in   form :expiry       "Expiration Date"]
        [num-in    form :limit-price  "Net Credit (+) / Debit (−)" {:step "0.01"}]
        [num-in    form :qty          "Contracts" {:step "1" :min "1"}]]
       [status-msg status]
       [:div {:style {:margin-top "14px"}}
        [submit-btn "Place Vertical Spread" loading
         (fn []
           (reset! loading true)
           (reset! status nil)
           (post! "/api/orders/vertical"
                  (-> @form
                      (update :short-strike js/parseFloat)
                      (update :long-strike  js/parseFloat)
                      (update :limit-price  js/parseFloat)
                      (update :qty          js/parseInt))
                  (fn [r] (reset! status r)         (reset! loading false))
                  (fn [e] (reset! status {:error e}) (reset! loading false))))]]])))

(defn- calendar-form []
  (let [form    (r/atom {:option-type "call" :qty "1"})
        loading (r/atom false)
        status  (r/atom nil)]
    (fn []
      [:<>
       [:p {:style {:color "#64748b" :font-size "12px" :margin "0 0 12px"}}
        "Same strike, two expirations — sell near-term leg, buy far-term leg. "
        "Typically a net debit; enter a positive limit-price for a net credit."]
       [grid 4
        [text-in   form :ticker      "Ticker" {:upper? true}]
        [select-in form :option-type "Option Type"
         [["call" "Call Calendar"] ["put" "Put Calendar"]]]
        [num-in  form :strike      "Strike ($)"               {:step "0.5" :min "0"}]
        [date-in form :near-expiry "Short Leg Expiry (near)"]
        [date-in form :far-expiry  "Long Leg Expiry (far)"]
        [num-in  form :limit-price "Net Debit (−) / Credit (+)" {:step "0.01"}]
        [num-in  form :qty         "Contracts" {:step "1" :min "1"}]]
       [status-msg status]
       [:div {:style {:margin-top "14px"}}
        [submit-btn "Place Calendar Spread" loading
         (fn []
           (reset! loading true)
           (reset! status nil)
           (post! "/api/orders/calendar"
                  (-> @form
                      (update :strike      js/parseFloat)
                      (update :limit-price js/parseFloat)
                      (update :qty         js/parseInt))
                  (fn [r] (reset! status r)         (reset! loading false))
                  (fn [e] (reset! status {:error e}) (reset! loading false))))]]]))  )

(defn- diagonal-form []
  (let [form    (r/atom {:option-type "put" :qty "1"})
        loading (r/atom false)
        status  (r/atom nil)]
    (fn []
      [:<>
       [grid 4
        [text-in  form :ticker      "Ticker" {:upper? true}]
        [select-in form :option-type "Type"
         [["put" "Put Diagonal"] ["call" "Call Diagonal"]]]
        [num-in  form :near-strike "Short Leg Strike ($)" {:step "0.5" :min "0"}]
        [date-in form :near-expiry "Short Leg Expiry"]
        [num-in  form :far-strike  "Long Leg Strike ($)"  {:step "0.5" :min "0"}]
        [date-in form :far-expiry  "Long Leg Expiry"]
        [num-in  form :limit-price "Net Debit / Credit"   {:step "0.01"}]
        [num-in  form :qty         "Contracts" {:step "1" :min "1"}]]
       [status-msg status]
       [:div {:style {:margin-top "14px"}}
        [submit-btn "Place Diagonal" loading
         (fn []
           (reset! loading true)
           (reset! status nil)
           (post! "/api/orders/diagonal"
                  (-> @form
                      (update :near-strike js/parseFloat)
                      (update :far-strike  js/parseFloat)
                      (update :limit-price js/parseFloat)
                      (update :qty         js/parseInt))
                  (fn [r] (reset! status r)         (reset! loading false))
                  (fn [e] (reset! status {:error e}) (reset! loading false))))]]])))

(defn- single-option-form []
  (let [form    (r/atom {:option-type "put" :side "long" :qty "1"})
        loading (r/atom false)
        status  (r/atom nil)]
    (fn []
      [:<>
       [grid 4
        [text-in   form :ticker      "Ticker" {:upper? true}]
        [select-in form :option-type "Option Type"
         [["put" "Put"] ["call" "Call"]]]
        [select-in form :side        "Direction"
         [["long" "Long (Buy)"] ["short" "Short (Sell)"]]]
        [num-in  form :strike      "Strike ($)"         {:step "0.5" :min "0"}]
        [date-in form :expiry      "Expiration Date"]
        [num-in  form :limit-price "Limit Price (blank = market)" {:step "0.01"}]
        [num-in  form :qty         "Contracts" {:step "1" :min "1"}]]
       [status-msg status]
       [:div {:style {:margin-top "14px"}}
        [submit-btn "Place Option Order" loading
         (fn []
           (reset! loading true)
           (reset! status nil)
           (post! "/api/orders/single-option"
                  (-> @form
                      (update :strike      js/parseFloat)
                      (update :limit-price #(when (seq (str %)) (js/parseFloat %)))
                      (update :qty         js/parseInt))
                  (fn [r] (reset! status r)         (reset! loading false))
                  (fn [e] (reset! status {:error e}) (reset! loading false))))]]])))

(defn- stock-form []
  (let [form    (r/atom {:side "long" :order-type "market" :qty "1"})
        loading (r/atom false)
        status  (r/atom nil)]
    (fn []
      [:<>
       [grid 4
        [text-in   form :ticker     "Ticker" {:upper? true}]
        [select-in form :side       "Direction"
         [["long" "Long (Buy)"] ["short" "Short (Sell)"]]]
        [select-in form :order-type "Order Type"
         [["market" "Market"] ["limit" "Limit"]]]
        [num-in form :qty "Shares" {:step "1" :min "1"}]
        (when (= (:order-type @form) "limit")
          [num-in form :limit-price "Limit Price ($)" {:step "0.01"}])]
       [status-msg status]
       [:div {:style {:margin-top "14px"}}
        [submit-btn "Place Stock Order" loading
         (fn []
           (reset! loading true)
           (reset! status nil)
           (post! "/api/orders/stock"
                  (-> @form
                      (update :qty         js/parseInt)
                      (update :limit-price #(when (seq (str %)) (js/parseFloat %))))
                  (fn [r] (reset! status r)         (reset! loading false))
                  (fn [e] (reset! status {:error e}) (reset! loading false))))]]])))

(defn- butterfly-form []
  (let [form    (r/atom {:option-type "call" :direction "long" :qty "1"})
        loading (r/atom false)
        status  (r/atom nil)]
    (fn []
      (let [dir (:direction @form)]
        [:<>
         [:p {:style {:color "#64748b" :font-size "12px" :margin "0 0 12px"}}
          (if (= dir "long")
            "Long (debit): buy lower wing · sell 2× body · buy upper wing"
            "Short (credit): sell lower wing · buy 2× body · sell upper wing")
          " — for a broken-wing butterfly, set unequal wing widths."]
         [grid 4
          [text-in   form :ticker        "Ticker" {:upper? true}]
          [select-in form :option-type   "Option Type"
           [["call" "Call Butterfly"] ["put" "Put Butterfly"]]]
          [select-in form :direction     "Direction"
           [["long" "Long (Debit)"] ["short" "Short (Credit)"]]]
          [num-in  form :lower-strike  "Lower Wing Strike ($)"  {:step "0.5" :min "0"}]
          [num-in  form :middle-strike "Body Strike ($)"        {:step "0.5" :min "0"}]
          [num-in  form :upper-strike  "Upper Wing Strike ($)"  {:step "0.5" :min "0"}]
          [date-in form :expiry        "Expiration Date"]
          [num-in  form :limit-price   "Net Debit (−) / Credit (+)" {:step "0.01"}]
          [num-in  form :qty           "Contracts" {:step "1" :min "1"}]]
         [status-msg status]
         [:div {:style {:margin-top "14px"}}
          [submit-btn "Place Butterfly" loading
           (fn []
             (reset! loading true)
             (reset! status nil)
             (post! "/api/orders/butterfly"
                    (-> @form
                        (update :lower-strike  js/parseFloat)
                        (update :middle-strike js/parseFloat)
                        (update :upper-strike  js/parseFloat)
                        (update :limit-price   js/parseFloat)
                        (update :qty           js/parseInt))
                    (fn [r] (reset! status r)         (reset! loading false))
                    (fn [e] (reset! status {:error e}) (reset! loading false))))]]]))  ))

(defn- iron-condor-form []
  (let [form    (r/atom {:direction "short" :qty "1"})
        loading (r/atom false)
        status  (r/atom nil)]
    (fn []
      (let [short? (= (:direction @form) "short")]
        [:<>
         [:p {:style {:color "#64748b" :font-size "12px" :margin "0 0 12px"}}
          (if short?
            "Short IC (credit): sell OTM put spread + sell OTM call spread. All 4 legs, same expiry."
            "Long IC (debit):   buy OTM put spread + buy OTM call spread. All 4 legs, same expiry.")
          " Strike order: Put Buy < Put Sell < spot < Call Sell < Call Buy"]
         [grid 4
          [text-in   form :ticker          "Ticker" {:upper? true}]
          [select-in form :direction       "Direction"
           [["short" "Short IC (Credit)"] ["long" "Long IC (Debit)"]]]
          [date-in   form :expiry          "Expiration Date"]
          [num-in  form :put-buy-strike  "Put Buy Strike ($)"   {:step "0.5" :min "0"}]
          [num-in  form :put-sell-strike "Put Sell Strike ($)"  {:step "0.5" :min "0"}]
          [num-in  form :call-sell-strike "Call Sell Strike ($)" {:step "0.5" :min "0"}]
          [num-in  form :call-buy-strike "Call Buy Strike ($)"  {:step "0.5" :min "0"}]
          [num-in  form :limit-price     "Net Credit (+) / Debit (−)" {:step "0.01"}]
          [num-in  form :qty             "Contracts" {:step "1" :min "1"}]]
         [status-msg status]
         [:div {:style {:margin-top "14px"}}
          [submit-btn "Place Iron Condor" loading
           (fn []
             (reset! loading true)
             (reset! status nil)
             (post! "/api/orders/iron-condor"
                    (-> @form
                        (update :put-buy-strike   js/parseFloat)
                        (update :put-sell-strike  js/parseFloat)
                        (update :call-sell-strike js/parseFloat)
                        (update :call-buy-strike  js/parseFloat)
                        (update :limit-price      js/parseFloat)
                        (update :qty              js/parseInt))
                    (fn [r] (reset! status r)         (reset! loading false))
                    (fn [e] (reset! status {:error e}) (reset! loading false))))]]]))  ))

(defn- back-ratio-form []
  (let [form    (r/atom {:option-type "call" :qty "1"})
        loading (r/atom false)
        status  (r/atom nil)]
    (fn []
      (let [opt (:option-type @form)]
        [:<>
         [:p {:style {:color "#64748b" :font-size "12px" :margin "0 0 12px"}}
          (if (= opt "call")
            "Call back ratio: sell 1 call at short-strike · buy 2 calls at higher long-strike."
            "Put back ratio: sell 1 put at short-strike · buy 2 puts at lower long-strike.")
          " Profits from a large directional move beyond the long strike."]
         [grid 4
          [text-in   form :ticker       "Ticker" {:upper? true}]
          [select-in form :option-type  "Option Type"
           [["call" "Call Back Ratio"] ["put" "Put Back Ratio"]]]
          [num-in  form :short-strike "Short Strike — Sell 1 ($)" {:step "0.5" :min "0"}]
          [num-in  form :long-strike  "Long Strike — Buy 2 ($)"  {:step "0.5" :min "0"}]
          [date-in form :expiry       "Expiration Date"]
          [num-in  form :limit-price  "Net Debit (−) / Credit (+)" {:step "0.01"}]
          [num-in  form :qty          "Spreads" {:step "1" :min "1"}]]
         [status-msg status]
         [:div {:style {:margin-top "14px"}}
          [submit-btn "Place Back Ratio" loading
           (fn []
             (reset! loading true)
             (reset! status nil)
             (post! "/api/orders/back-ratio"
                    (-> @form
                        (update :short-strike js/parseFloat)
                        (update :long-strike  js/parseFloat)
                        (update :limit-price  js/parseFloat)
                        (update :qty          js/parseInt))
                    (fn [r] (reset! status r)          (reset! loading false))
                    (fn [e] (reset! status {:error e}) (reset! loading false))))]]]))))

;; ---- Instrument selector ----

(def ^:private instrument-opts
  [["vertical"      "Vertical Spread (Credit/Debit)"]
   ["calendar"      "Calendar Spread (same strike, two expiries)"]
   ["diagonal"      "Diagonal Spread (different strikes + expiries)"]
   ["butterfly"     "Butterfly (Symmetric / Broken Wing)"]
   ["iron-condor"   "Iron Condor (4 legs)"]
   ["back-ratio"    "Back Ratio Spread (sell 1, buy 2)"]
   ["single-option" "Single Option (Put / Call)"]
   ["stock"         "Stock (Long / Short)"]])

(defn trade-panel []
  (let [instrument (r/atom "vertical")]
    (fn []
      [:div
       [account-banner]
       [:div {:style s-card}
        [:div {:style {:margin-bottom "16px"}}
         [:label {:style s-label} "Instrument Type"]
         [:select {:value     @instrument
                   :style     (assoc s-input :max-width "340px" :cursor "pointer")
                   :on-change #(reset! instrument (.. % -target -value))}
          (for [[v t] instrument-opts]
            ^{:key v} [:option {:value v} t])]]
        (case @instrument
          "vertical"      [vertical-form]
          "calendar"      [calendar-form]
          "diagonal"      [diagonal-form]
          "butterfly"     [butterfly-form]
          "iron-condor"   [iron-condor-form]
          "back-ratio"    [back-ratio-form]
          "single-option" [single-option-form]
          "stock"         [stock-form])]])))

;; ---- History panel ----

(defn- pnl-color [pnl]
  (cond (nil? pnl) "#64748b"
        (pos? pnl) "#4ade80"
        (neg? pnl) "#f87171"
        :else      "#94a3b8"))

(defn- fmt-date [inst]
  (when inst (subs (str inst) 0 10)))

(defn- s-btn [color]
  {:background "transparent" :color color :border (str "1px solid " color)
   :border-radius "4px" :padding "3px 10px" :font-size "12px" :cursor "pointer"})

(defn- trade-row [_ _ _ _]
  (let [close-val (r/atom "")]
    (fn [t closing on-close on-cancel]
      (let [tid      (str (:trade/id t))
            open?    (= (keyword (str (:trade/status t))) :open)
            active?  (= @closing tid)
            cell     {:style {:padding "8px 10px"}}
            hint     (if (= (keyword (str (:trade/instrument-type t))) :stock)
                       "price/share" "credit + / debit −")]
        [:tr {:style {:border-bottom "1px solid #1e293b"}}
         [:td cell (fmt-date (:trade/opened-at t))]
         [:td {:style {:padding "8px 10px" :font-weight "600"}} (:trade/ticker t)]
         [:td cell (some-> (:trade/instrument-type t) name)]
         [:td cell (some-> (:trade/option-type t) name)]
         [:td cell (:trade/qty t)]
         [:td cell (str "$" (:trade/open-value t))]
         [:td {:style {:padding "8px 10px" :color (pnl-color (:trade/pnl t))}}
          (if-let [pnl (:trade/pnl t)]
            (str "$" (.toFixed pnl 2))
            "—")]
         [:td {:style {:padding "8px 10px"
                       :color   (case (keyword (str (:trade/status t)))
                                  :open      "#60a5fa"
                                  :closed    "#4ade80"
                                  :cancelled "#f87171"
                                  "#94a3b8")}}
          (some-> (:trade/status t) name)]
         [:td {:style {:padding "4px 8px" :white-space "nowrap"}}
          (cond
            (not open?) nil

            active?
            [:span {:style {:display "flex" :gap "6px" :align-items "center"}}
             [:input {:type        "number"
                      :placeholder hint
                      :step        "0.01"
                      :value       @close-val
                      :style       (assoc s-input :width "130px" :padding "4px 8px"
                                                  :font-size "12px")
                      :on-change   #(reset! close-val (.. % -target -value))}]
             [:button {:style    {:background    "#16a34a"
                                  :color         "white"
                                  :border        "none"
                                  :border-radius "4px"
                                  :padding       "4px 12px"
                                  :font-size     "12px"
                                  :cursor        "pointer"}
                       :on-click #(on-close tid (js/parseFloat @close-val))}
              "Confirm"]
             [:button {:style    {:background  "transparent"
                                  :color       "#64748b"
                                  :border      "none"
                                  :font-size   "16px"
                                  :cursor      "pointer"
                                  :line-height "1"}
                       :on-click #(reset! closing nil)}
              "✕"]]

            :else
            [:span {:style {:display "flex" :gap "6px"}}
             [:button {:style    (s-btn "#f87171")
                       :on-click #(do (reset! close-val "")
                                      (reset! closing tid))}
              "Close"]
             [:button {:style    (s-btn "#475569")
                       :on-click #(when (js/confirm "Cancel this trade record?")
                                    (on-cancel tid))}
              "Cancel"]])]]))))

(defn history-panel []
  (let [trades  (r/atom [])
        perf    (r/atom nil)
        closing (r/atom nil)]
    (letfn [(refresh! []
              (get! "/api/trades"
                    #(reset! trades (:trades %))
                    #(js/console.warn "trades fetch failed:" %))
              (get! "/api/trades/performance"
                    #(reset! perf %)
                    #(js/console.warn "perf fetch failed:" %)))
            (on-close [trade-id close-value]
              (post! (str "/api/trades/" trade-id "/close")
                     {:close-value close-value}
                     (fn [_] (reset! closing nil) (refresh!))
                     #(js/console.error "Close trade failed:" %)))
            (on-cancel [trade-id]
              (post! (str "/api/trades/" trade-id "/cancel")
                     {}
                     (fn [_] (reset! closing nil) (refresh!))
                     #(js/console.error "Cancel trade failed:" %)))]
      (r/create-class
        {:component-did-mount (fn [_] (refresh!))
         :reagent-render
         (fn []
           [:div
            [account-banner]
            (when-let [p @perf]
              [:div {:style (assoc s-card :display "flex" :gap "40px"
                                         :align-items "center")}
               (for [[label val]
                     [["Total Trades" (:total-trades p)]
                      ["Open"         (:open-trades p)]
                      ["Winners"      (:winners p)]
                      ["Losers"       (:losers p)]
                      ["Win Rate"     (str (.toFixed (* 100 (or (:win-rate p) 0)) 1) "%")]
                      ["Total P&L"    (str "$" (.toFixed (or (:total-pnl p) 0) 2))]]]
                 ^{:key label}
                 [:div
                  [:div {:style {:color "#64748b" :font-size "11px"}} label]
                  [:div {:style {:color "white" :font-size "18px" :font-weight "700"}} val]])
               [:div {:style {:margin-left "auto"}}
                [:button {:style    {:background "transparent" :color "#60a5fa"
                                     :border "1px solid #334155" :border-radius "6px"
                                     :padding "6px 16px" :font-size "12px" :cursor "pointer"}
                          :on-click refresh!}
                 "Refresh"]]])
            [:div {:style s-card}
             (if (empty? @trades)
               [:p {:style {:color "#64748b" :font-size "14px"}} "No trades recorded yet."]
               [:table {:style {:width "100%" :border-collapse "collapse"
                                :color "white" :font-size "13px"}}
                [:thead
                 [:tr {:style {:color         "#64748b"
                               :text-align    "left"
                               :border-bottom "1px solid #334155"}}
                  (for [h ["Opened" "Ticker" "Type" "Put/Call" "Qty"
                            "Open Value" "P&L" "Status" ""]]
                    ^{:key h} [:th {:style {:padding "8px 10px"}} h])]]
                [:tbody
                 (for [t (sort-by #(str (:trade/opened-at %)) #(compare %2 %1) @trades)]
                   ^{:key (str (:trade/id t))}
                   [trade-row t closing on-close on-cancel])]])]])}))))

;; ---- Positions panel ----

(defn- money [v]
  (when (some? v) (str "$" v)))

(defn- th-row [headers]
  [:tr {:style {:color "#64748b" :text-align "left" :border-bottom "1px solid #334155"}}
   (for [h headers]
     ^{:key h} [:th {:style {:padding "8px 10px"}} h])])

(defn positions-panel []
  (let [positions (r/atom [])
        orders    (r/atom [])
        err       (r/atom nil)]
    (letfn [(refresh! []
              (reset! err nil)
              (get! "/api/positions"
                    #(reset! positions (:positions %))
                    #(reset! err %))
              (get! "/api/orders?status=open"
                    #(reset! orders (:orders %))
                    #(reset! err %)))
            (cancel-order! [order-id]
              (delete! (str "/api/orders/" order-id)
                       (fn [_] (refresh!))
                       #(reset! err %)))]
      (r/create-class
        {:component-did-mount (fn [_] (refresh!))
         :reagent-render
         (fn []
           [:div
            [account-banner]
            (when-let [e @err]
              [:div {:style {:color "#f87171" :font-size "13px" :margin-bottom "12px"}}
               (str "Error: " e)])

            ;; Open positions
            [:div {:style s-card}
             [:div {:style {:display "flex" :justify-content "space-between"
                            :align-items "center" :margin-bottom "14px"}}
              [:h3 {:style {:color "white" :margin 0 :font-size "15px"}} "Open Positions"]
              [:button {:style    {:background "transparent" :color "#60a5fa"
                                   :border "1px solid #334155" :border-radius "6px"
                                   :padding "5px 14px" :font-size "12px" :cursor "pointer"}
                        :on-click refresh!}
               "Refresh"]]
             (if (empty? @positions)
               [:p {:style {:color "#64748b" :font-size "14px"}} "No open positions."]
               [:table {:style {:width "100%" :border-collapse "collapse"
                                :color "white" :font-size "13px"}}
                [:thead [th-row ["Symbol" "Qty" "Side" "Avg Price"
                                 "Market Value" "Unrealized P&L"]]]
                [:tbody
                 (for [p @positions]
                   ^{:key (:symbol p)}
                   (let [upnl (js/parseFloat (str (:unrealized-pnl p)))]
                     [:tr {:style {:border-bottom "1px solid #1e293b"}}
                      [:td {:style {:padding "8px 10px" :font-weight "600"}} (:symbol p)]
                      [:td {:style {:padding "8px 10px"}} (:qty p)]
                      [:td {:style {:padding "8px 10px"}} (:side p)]
                      [:td {:style {:padding "8px 10px"}} (money (:avg-price p))]
                      [:td {:style {:padding "8px 10px"}} (money (:market-value p))]
                      [:td {:style {:padding "8px 10px"
                                    :color   (if (neg? upnl) "#f87171" "#4ade80")}}
                       (money (:unrealized-pnl p))]]))]])]

            ;; Open orders
            [:div {:style s-card}
             [:h3 {:style {:color "white" :margin "0 0 14px" :font-size "15px"}}
              "Open Orders"]
             (if (empty? @orders)
               [:p {:style {:color "#64748b" :font-size "14px"}} "No open orders."]
               [:table {:style {:width "100%" :border-collapse "collapse"
                                :color "white" :font-size "13px"}}
                [:thead [th-row ["Order ID" "Symbol" "Qty" "Side" "Status" ""]]]
                [:tbody
                 (for [o @orders]
                   ^{:key (:id o)}
                   [:tr {:style {:border-bottom "1px solid #1e293b"}}
                    [:td {:style {:padding "8px 10px" :font-size "11px" :color "#64748b"}}
                     (str (subs (str (:id o)) 0 8) "…")]
                    [:td {:style {:padding "8px 10px" :font-weight "600"}} (:symbol o)]
                    [:td {:style {:padding "8px 10px"}} (:qty o)]
                    [:td {:style {:padding "8px 10px"}} (:side o)]
                    [:td {:style {:padding "8px 10px" :color "#60a5fa"}} (:status o)]
                    [:td {:style {:padding "4px 8px"}}
                     [:button {:style    (s-btn "#f87171")
                               :on-click #(when (js/confirm (str "Cancel order " (:id o) "?"))
                                            (cancel-order! (:id o)))}
                      "Cancel"]]])]])]
            ])}))))
