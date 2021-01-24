(ns nr.stats
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.core.async :refer [chan put! <!] :as async]
            [clojure.string :refer [capitalize]]
            [jinteki.cards :refer [all-cards]]
            [nr.ajax :refer [GET DELETE]]
            [nr.appstate :refer [app-state]]
            [nr.auth :refer [authenticated] :as auth]
            [nr.avatar :refer [avatar]]
            [nr.end-of-game-stats :refer [build-game-stats]]
            [nr.player-view :refer [player-view]]
            [nr.translations :refer [tr tr-side tr-format tr-lobby]]
            [nr.utils :refer [faction-icon render-message notnum->zero num->percent set-scroll-top store-scroll-top]]
            [nr.ws :as ws]
            [reagent.core :as r]))

(def state (r/atom {:games nil}))

(defn- fetch-game-history []
  (go (let [{:keys [status json]} (<! (GET "/profile/history"))
            games (mapv #(assoc % :start-date (js/Date. (:start-date %))
                               :end-date (js/Date. (:end-date %))) json)]
        (when (= 200 status)
          (swap! state assoc :games games)))))

(defn update-deck-stats
  "Update the local app-state with a new version of deck stats"
  [deck-id stats]
  (let [deck (first (filter #(= (:_id %) deck-id) (:decks @app-state)))
        deck (assoc deck :stats stats)
        others (remove #(= (:_id %) deck-id) (:decks @app-state))]
    (swap! app-state assoc :decks (conj others deck))))

(ws/register-ws-handler!
  :stats/update
  #(do (swap! app-state assoc :stats (-> % :userstats))
       (update-deck-stats (-> % :deck-id) (-> % :deckstats))
       (fetch-game-history)))

(defn share-replay [state gameid]
  (go (let [{:keys [status json]} (<! (GET (str "/profile/history/share/" gameid)))]
        (when (= 200 status)
          (swap! state assoc :view-game
                 (assoc (:view-game @state) :replay-shared true))))))

(defn game-details [state]
  (let [game (:view-game @state)]
    [:div.games.panel
     [:h4 (:title game) (when (:replay-shared game) " ⭐")]
     [:div
      [:div (str (tr [:stats.lobby "Lobby"]) ": " (capitalize (tr-lobby (:room game))))]
      [:div (str (tr [:stats.format "Format"]) ": " (capitalize (tr-format (:format game))))]
      [:div (str (tr [:stats.winner "Winner"]) ": " (capitalize (tr-side (:winner game))))]
      [:div (str (tr [:stats.win-method "Win method"]) ": " (:reason game))]
      [:div (str (tr [:stats.started "Started"]) ": " (:start-date game))]
      [:div (str (tr [:stats.ended "Ended"]) ": " (:end-date game))]
      (when (:stats game)
        [build-game-stats (get-in game [:stats :corp]) (get-in game [:stats :runner])])
      [:p [:button {:on-click #(swap! state dissoc :view-game)} (tr [:stats.view-games "View games"])]
       (when (and (:replay game)
                  (not (:replay-shared game)))
         [:button {:on-click #(share-replay state (:gameid game))} "Share replay"])
       (if (:replay game)
         [:a.button {:href (str "/profile/history/full/" (:gameid game)) :download (str (:title game) ".json")} "Download replay"]
         "Replay unavailable")]
      (when (:replay-shared game)
        [:p [:input.share-link {:type "text" :read-only true :value (str (.-origin (.-location js/window)) "/play?" (:gameid game))}]])]]))

(defn clear-user-stats []
  (authenticated
    (fn [user]
      (let [id (get-in @app-state [:user :_id])]
        (try (js/ga "send" "event" "user" "clearuserstats") (catch js/Error e))
        (go (let [result (<! (DELETE "/profile/stats/user"))]
              (swap! app-state assoc :stats result)))))))

(defn stat-view [{:keys [start-key complete-key win-key lose-key stats]}]
  (r/with-let [started (notnum->zero (start-key stats))
               completed (notnum->zero (complete-key stats))
               pc (notnum->zero (num->percent completed started))
               win (notnum->zero (win-key stats))
               lose (notnum->zero (lose-key stats))
               pw (notnum->zero (num->percent win (+ win lose)))
               pl (notnum->zero (num->percent lose (+ win lose)))
               incomplete (notnum->zero (- started completed))
               pi (notnum->zero (num->percent incomplete started))]
    [:section
     [:div (tr [:stats.started "Started"]) ": " started]
     [:div (tr [:stats.completed "Completed"]) ": " completed " (" pc "%)"]
     [:div (tr [:stats.not-completed "Not completed"]) ": " incomplete  " (" pi "%)"]
     (when-not (= "none" (get-in @app-state [:options :gamestats]))
       [:div [:div (tr [:stats.won "Won"]) ": " win  " (" pw "%)"]
        [:div (tr [:stats.lost "Lost"]) ": " lose  " (" pl "%)"]])]))

(defn stats-panel [stats]
  [:div.games.panel
   [:div.games
    [:div
     [:h3 (tr [:stats.game-stats "Game Stats"])]
     [stat-view {:stats @stats
                 :start-key :games-started :complete-key :games-completed
                 :win-key :wins :lose-key :loses}]]
    [:div
     [:h3 (tr [:stats.corp-stats "Corp Stats"])]
     [stat-view {:stats @stats
                 :start-key :games-started-corp :complete-key :games-completed-corp
                 :win-key :wins-corp :lose-key :loses-corp}]]
    [:div
     [:h3 (tr [:stats.runner-stats "Runner Stats"])]
     [stat-view {:stats @stats
                 :start-key :games-started-runner :complete-key :games-completed-runner
                 :win-key :wins-runner :lose-key :loses-runner}]]]
   [:p [:button {:on-click #(clear-user-stats)} (tr [:stats.clear-stats "Clear Stats"])]]] )

(defn left-panel [state stats]
  (if (:view-game @state)
    [game-details state]
    [stats-panel stats]))

(defn game-log [state log-scroll-top]
  (r/create-class
    {
     :display-name "stats-game-log"
     :component-did-mount #(set-scroll-top % @log-scroll-top)
     :component-will-unmount #(store-scroll-top % log-scroll-top)
     :reagent-render
     (fn [state log-scroll-top]
       (let [game (:view-game @state)]
         [:div {:style {:overflow "auto"}}
          [:div.panel.messages
           (if (seq (:log game))
             (doall (map-indexed
                      (fn [i msg]
                        (when-not (and (= (:user msg) "__system__") (= (:text msg) "typing"))
                          (if (= (:user msg) "__system__")
                            [:div.system {:key i} (render-message (:text msg))]
                            [:div.message {:key i}
                             [avatar (:user msg) {:opts {:size 38}}]
                             [:div.content
                              [:div.username (get-in msg [:user :username])]
                              [:div (render-message (:text msg))]]])))
                      (:log game)))
             [:h4 (tr [:stats.no-log "No log available"])])]]))}))

(def faction-icon-memo (memoize faction-icon))

(defn fetch-log [state game]
  (go (let [{:keys [status json]} (<! (GET (str "/profile/history/" (:gameid game))))]
        (when (= 200 status)
          (swap! state assoc :view-game (assoc game :log json))))))

(defn game-row
  [state {:keys [title corp runner turn winner reason replay-shared] :as game} log-scroll-top]
  (let [corp-id (first (filter #(= (:title %) (:identity corp)) @all-cards))
        runner-id (first (filter #(= (:title %) (:identity runner)) @all-cards))]
    [:div.gameline {:style {:min-height "auto"}}
     [:button.float-right
      {:on-click #(do
                    (fetch-log state game)
                    (reset! log-scroll-top 0))}
      (tr [:stats.view-log "View log"])]
     [:h4
      {:title (when replay-shared "Replay shared")}
      title " (" (or turn 0) " turn" (if (not= 1 turn) "s") ")" (when replay-shared " ⭐")]
;; =======
;;       (tr [:stats.view-log "View log"])]
;;      [:h4 title " (" (tr [:stats.turn-count] (or turn 0)) ")"]
;; >>>>>>> b82d19022 (Stats page translations)

     [:div
      [:span.player
       [avatar (:player corp) {:opts {:size 24}}]
       (get-in corp [:player :username]) " - "
       (faction-icon-memo (:faction corp-id) (:title corp-id)) " " (:title corp-id)]]

     [:div
      [:span.player
       [avatar (:player runner) {:opts {:size 24}}]
       (get-in runner [:player :username]) " - "
       (faction-icon-memo (:faction runner-id) (:title runner-id)) " " (:title runner-id)]]

     (when winner
       [:h4 (tr [:stats.winner "Winner"]) ": " (tr-side winner)])]))

(defn history [state list-scroll-top log-scroll-top]
  (r/create-class
    {:display-name "stats-history"
     :component-did-mount #(set-scroll-top % @list-scroll-top)
     :component-will-unmount #(store-scroll-top % list-scroll-top)
     :reagent-render
     (fn [state list-scroll-top log-scroll-top]
       (let [games (reverse (:games @state))]
         [:div.game-list
           [:div.controls
             [:button {:on-click #(swap! state update :filter-replays not)}
             (if (:filter-replays @state)
               "Show all games"
               "Only show shared")]]
           (if (empty? games)
             [:h4 "No games"]
             (doall
               (for [game games]
                 (when (or (not (:filter-replays @state))
                           (:replay-shared game))
                   ^{:key (:gameid game)}
                   [game-row state game log-scroll-top]))))]))}))

(defn right-panel [state list-scroll-top log-scroll-top]
  (if (:view-game @state)
    [game-log state log-scroll-top]
    [:div.game-panel
     [history state list-scroll-top log-scroll-top]]))

(defn stats []
  (let [stats (r/cursor app-state [:stats])
        active (r/cursor app-state [:active-page])
        list-scroll-top (atom 0)
        log-scroll-top (atom 0)]

    (fetch-game-history)

    (fn []
      (when (= "/stats" (first @active))
        [:div.page-container
         [:div.lobby.panel.blue-shade
          [left-panel state stats]
          [right-panel state list-scroll-top log-scroll-top]]]))))
