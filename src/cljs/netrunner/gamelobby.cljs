(ns netrunner.gamelobby
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [om.core :as om :include-macros true]
            [sablono.core :as sab :include-macros true]
            [cljs.core.async :refer [chan put! <!] :as async]
            [clojure.string :refer [join]]
            [netrunner.auth :refer [authenticated avatar] :as auth]
            [netrunner.game :refer [init-game]]))

(def app-state (atom {:games [] :gameid nil :messages []}))
(def socket-channel (chan))
(def socket (.connect js/io (str js/iourl "/lobby")))
(.on socket "netrunner" #(put! socket-channel (js->clj % :keywordize-keys true)))

(defn launch-game []
  (let [gameid (:gameid @app-state)
        players (:players (some #(when (= (:id %) gameid) %) (:games @app-state)))
        corp (some #(when (= (:side %) "Corp") %) players)
        runner (some #(when (= (:side %) "Runner") %) players)
        username (get-in @auth/app-state [:user :username])
        side (if (= (get-in runner [:user :username]) username) "Runner" "Corp")]
    (init-game gameid side corp runner))
  (-> "#gamelobby" js/$ .fadeOut)
  (-> "#gameboard" js/$ .fadeIn))

(go (while true
      (let [msg (<! socket-channel)]
        (.log js/console (clj->js msg))
        (case (:type msg)
          "game" (swap! app-state assoc :gameid (:gameid msg))
          "games" (swap! app-state assoc :games (sort-by :date > (:games msg)))
          "say" (swap! app-state update-in [:messages] #(conj % {:user (:user msg) :text (:text msg)}))
          "start" (launch-game)
          nil))))

(defn send [msg]
  (.emit socket "netrunner" (clj->js msg)))

(defn new-game [cursor owner]
  (authenticated
   (fn [user]
     (om/set-state! owner :title (str (:username user) "'s game"))
     (om/set-state! owner :editing true)
     (-> ".game-title" js/$ .select))))

(defn create-game [cursor owner]
  (authenticated
   (fn [user]
     (if (empty? (om/get-state owner :title))
       (om/set-state! owner :flash-message "Please fill a game title.")
       (do
         (om/set-state! owner :editing false)
         (send {:action "create" :title (om/get-state owner :title)}))))))

(defn join-game [gameid owner]
  (authenticated
   (fn [user]
     (send {:action "join" :gameid gameid}))))

(defn leave-game [cursor owner]
  (send {:action "leave" :gameid (:gameid @app-state)})
  (om/set-state! owner :in-game false)
  (om/update! cursor :gameid nil)
  (om/update! cursor :message []))

(defn send-msg [event owner]
  (.preventDefault event)
  (let [input (om/get-node owner "msg-input")
        text (.-value input)]
    (when-not (empty? text)
      (send {:action "say" :gameid (:gameid @app-state) :text text})
      (aset input "value" "")
      (.focus input))))

(defn player-view [cursor]
  (om/component
   (sab/html
    [:span.player
     (om/build avatar (:user cursor) {:opts {:size 22}})
     (get-in cursor [:user :username])
     [:span.side (str "(" (:side cursor) ")")]])))

(defn chat-view [messages owner]
  (reify
    om/IDidUpdate
    (did-update [this prev-props prev-state]
      (let [div (om/get-node owner "msg-list")]
        (aset div "scrollTop" (.-scrollHeight div))))

    om/IRenderState
    (render-state [this state]
      (sab/html
       [:div
        [:h3 "Chat"]
        [:div.message-list {:ref "msg-list"}
         (for [msg messages]
           (if (= (:user msg) "__system__")
             [:div.system (:text msg)]
             [:div.message
              (om/build avatar (:user msg) {:opts {:size 38}})
              [:div.content
               [:div.username (get-in msg [:user :username])]
               [:div (:text msg)]]]))]
        [:form.msg-box {:on-submit #(send-msg % owner)}
         [:input {:ref "msg-input" :placeholder "Say something"}]
         [:button "Send"]]]))))

(defn game-lobby [{:keys [games gameid] :as cursor} owner]
  (reify
    om/IRenderState
    (render-state [this state]
      (sab/html
       [:div.lobby.panel.blue-shade
        [:div.games
         [:div.button-bar
          [:button {:class (if (:in-game state) "disabled" "")
                    :on-click #(new-game cursor owner)} "New game"]]
         [:div.game-list
          (if (empty? games)
            [:h4 "No game"]
            (for [game games]
              [:div.gameline {:class (when (= gameid (:id game)) "active")}
               (when-not (or gameid (= (count (:players game)) 2))
                 (let [id (:id game)]
                   [:button {:on-click #(join-game id owner)} "Join"]))
               [:h4 (:title game)]
               [:div
                (om/build-all player-view (:players game))]]))]]

        [:div.game-panel
         (if (:editing state)
           (do
             [:div
              [:div.button-bar
               [:button {:type "button" :on-click #(create-game cursor owner)} "Create"]
               [:button {:type "button" :on-click #(om/set-state! owner :editing false)} "Cancel"]]
              [:h4 "Title"]
              [:input.game-title {:on-change #(om/set-state! owner :title (.. % -target -value))
                                  :value (:title state) :placeholder "Title"}]
              [:p.flash-message (:flash-message state)]])
           (when-let [game (some #(when (= gameid (:id %)) %) games)]
             (let [username (get-in @auth/app-state [:user :username])
                   players (:players game)]
               [:div
                [:div.button-bar
                 (when (and (= (count players) 2) (= (-> players first :user :username) username))
                   [:button {:on-click #(send {:action "start" :gameid (:gameid @app-state)})} "Start"])
                 [:button {:on-click #(leave-game cursor owner)} "Leave"]]
                [:h2 (:title game)]
                [:h3.float-left "Players"]
                (when (= (-> players first :user :username) username)
                  [:span.fake-link.swap-link
                   {:on-click #(send {:action "swap" :gameid gameid})} "Change sides"])
                [:div.players
                 (for [player (:players game)]
                   [:div (om/build player-view player)])]
                (om/build chat-view (:messages cursor) {:state state})])))]]))))

(om/root game-lobby app-state {:target (. js/document (getElementById "gamelobby"))})
