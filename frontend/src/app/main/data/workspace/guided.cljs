;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2026 Tudor Saitoc

(ns app.main.data.workspace.guided
  (:require
   [app.common.types.text :as txt]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as changes]
   [app.main.features :as features]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [beicon.v2.core :as rx]
   [promesa.core :as p]))

(defn initial-title-id
  [objects]
  (let [root (get objects uuid/zero)
        board (some #(when (= :frame (:type (get objects %))) (get objects %))
                    (:shapes root))]
    (->> (tree-seq #(seq (:shapes %)) #(map objects (:shapes %)) board)
         (filter #(= :text (:type %)))
         (sort-by (juxt :y :x))
         first
         :id)))

(defn title-change
  [page-id shape-id content]
  {:type :mod-obj :id shape-id :page-id page-id
   :operations [{:type :set :attr :content :val content}]})

(defn- command
  [name params]
  (p/create
   (fn [resolve reject]
     (->> (rp/cmd! name params)
          (rx/subs! resolve
                    (fn [error]
                      (reject (js/Error. (str "The deck action was rejected: "
                                               (or (:code (ex-data error))
                                                   (.-message error)
                                                   error))))))))))

(defn create-extension
  "A bounded, tab-local proposal presentation. Only the backend grant commits."
  [file-id]
  (let [proposal* (atom nil)
        busy? (atom false)
        ensure-current!
        (fn []
          (when (not= file-id (:current-file-id @st/state))
            (throw (js/Error. "This deck is no longer open."))))
        lookup!
        (fn [proposal-id]
          (ensure-current!)
          (let [proposal @proposal*]
            (when (not= proposal-id (:proposal-id proposal))
              (throw (js/Error. "This review expired. Propose again.")))
            proposal))
        commit!
        (fn [proposal]
          (let [params (:params proposal)
                grant (:grant proposal)]
            (command :commit-file-action
                     (assoc params :grant-id (:grant-id grant)
                                   :args-sha256 (:args-sha256 grant)))))]
    #js
    {:prepareTitle
     (fn [text]
       (p/let [_ (do
                   (ensure-current!)
                   (when @busy? (throw (js/Error. "Wait for the current action.")))
                   (when (or (not (string? text)) (empty? text) (> (count text) 10000))
                     (throw (js/Error. "Enter replacement text, up to 10000 characters."))))
               result
               (let [state @st/state
                     page-id (:current-page-id state)
                     selected (get-in state [:workspace-local :selected])
                     shape-id (first selected)
                     file (get-in state [:files file-id])
                     shape (get-in file [:data :pages-index page-id :objects shape-id])
                     persistence (:persistence state)]
                 (when (or (not= 1 (count selected)) (not= :text (:type shape)))
                   (throw (js/Error. "Select one title or text on the deck.")))
                 (when (or (get-in state [:workspace-local :edition])
                           (seq (:queue persistence))
                           (#{:pending :saving :error} (:status persistence)))
                   (throw (js/Error. "Finish editing and wait for the deck to save.")))
                 (let [id (uuid/next)
                       before (:content shape)
                       after (txt/change-text before text)
                       params {:id file-id :revn (:revn file) :vern (:vern file)
                               :request-id id :session-id (:session-id state)
                               :features (features/get-enabled-features state (:current-team-id state))
                               :changes [(title-change page-id shape-id after)]}
                       proposal {:proposal-id (str id) :params params
                                 :undo [(title-change page-id shape-id before)]}]
                   (reset! proposal* proposal)
                   #js {:proposalId (str id) :before (txt/content->text before)
                        :after text :fileId (str file-id) :revn (:revn file) :vern (:vern file)}))]
         result))
     :approveTitle
     (fn [proposal-id]
       (let [proposal (lookup! proposal-id)]
         (when (or @busy? (:receipt proposal))
           (throw (js/Error. "This review was already applied or is applying.")))
         (reset! busy? true)
         (-> (p/let [grant (command :issue-file-action-grant (:params proposal))
                     bound (assoc proposal :grant grant)
                     _ (reset! proposal* bound)
                     receipt (commit! bound)
                     _ (ensure-current!)]
               (reset! proposal* (assoc bound :receipt receipt))
               (st/emit! (changes/commit
                          {:file-id file-id :file-revn (:revn receipt) :file-vern (:vern receipt)
                           :source :remote :redo-changes (get-in proposal [:params :changes])
                           :undo-changes (:undo proposal) :save-undo? true :stack-undo? false
                           :undo-group (get-in proposal [:params :request-id]) :tags #{}}))
               (clj->js receipt))
             (p/finally #(reset! busy? false)))))
     :replayTitle
     (fn [proposal-id]
       (p/let [proposal (lookup! proposal-id)
               _ (when-not (:receipt proposal)
                   (throw (js/Error. "Apply the reviewed change before replaying its receipt.")))
               receipt (commit! proposal)]
         (clj->js receipt)))}))
