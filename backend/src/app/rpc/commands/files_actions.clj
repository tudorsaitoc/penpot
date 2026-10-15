;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2026 Tudor Saitoc

(ns app.rpc.commands.files-actions
  (:require
   [app.binfile.common :as bfc]
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.files.changes :as cpc]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.features.fdata :as fdata]
   [app.features.file-snapshots :as fsnap]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.files-update :as update]
   [app.rpc.commands.teams :as teams]
   [app.rpc.permissions :as perms]
   [app.util.blob :as blob]
   [app.util.services :as sv]
   [clojure.walk :as walk])
  (:import
   java.nio.charset.StandardCharsets
   java.security.MessageDigest
   java.util.HexFormat))

;; Engine capabilities are not product approvals. Publication, spend, rights and
;; evidence remain external policy. No boolean or plugin metadata can confer it.
(def ^:private schema:action
  [:map
   [:id ::sm/uuid]
   [:request-id ::sm/uuid]
   [:session-id ::sm/uuid]
   [:revn [::sm/int {:min 0}]]
   [:vern [::sm/int {:min 0}]]
   [:features {:optional true} ::cfeat/features]
   [:changes [:vector {:min 1} cpc/schema:change]]])

(def ^:private schema:commit
  [:merge schema:action
   [:map [:grant-id ::sm/uuid] [:args-sha256 [:string {:min 64 :max 64}]]]])

(def ^:private protected-fields
  #{:permissions :is-shared :project-id :team-id :profile-id :revn :vern
    :approval :approved :grant-id :provenance :rights :billing :publication
    :evidence :skip-validate})

(defn- deny! [code]
  (ex/raise :type :validation :code code))

(defn changes-sha256
  "SHA256 of canonical EDN for the decoded typed change vector. Maps and sets
  are ordered by pr-str, vectors retain order. Issuance returns this digest;
  clients echo it at commit, where the actual changes are hashed again."
  [changes]
  (let [canonical (walk/postwalk
                   (fn [v]
                     (cond
                       (map? v) (into (sorted-map-by #(compare (pr-str %1) (pr-str %2))) v)
                       (set? v) (into (sorted-set-by #(compare (pr-str %1) (pr-str %2))) v)
                       :else v))
                   changes)]
    (.formatHex (HexFormat/of)
                (.digest (MessageDigest/getInstance "SHA-256")
                         (.getBytes (pr-str canonical) StandardCharsets/UTF_8)))))

(defn- check-action! [params allowed]
  (when (some #(and (not (namespace %)) (not (contains? allowed %))) (keys params))
    (deny! :protected-action-field))
  (walk/postwalk
   (fn [v]
     (when (and (map? v)
                (or (some protected-fields (keys v))
                    (contains? protected-fields (:attr v))
                    (and (= :set (:type v))
                         (contains? #{:id :type :parent-id :frame-id :shapes :plugin-data} (:attr v)))
                    (and (= :assign (:type v))
                         (some #{:id :type :parent-id :frame-id :shapes :plugin-data} (keys (:value v))))
                    (and (contains? v :plugin-data)
                         (some (fn [[ns values]]
                                 (or (not= ns :shared/decks)
                                     (not-every? #{"nodeId" "chart" "chartVisual" "notes" "slideOrder"} (keys values))))
                               (:plugin-data v)))
                    (and (= :set-plugin-data (:type v))
                         (not (and (= :shared/decks (:namespace v))
                                   (contains? #{"nodeId" "chart" "chartVisual" "notes" "slideOrder"} (:key v)))))))
       (deny! :protected-action-field))
     v)
   (:changes params)))

(def ^:private action-keys
  #{:id :request-id :session-id :revn :vern :features :changes})

(defn- lock-editor!
  "Lock the same authority rows used by get-file-permissions, so a concurrent
  membership revoke/downgrade cannot race the final permission check."
  [cfg actor id]
  (db/xact-lock! cfg id)
  (when-not (seq (db/exec! cfg ["SELECT id FROM profile WHERE id = ? AND is_active = true AND deleted_at IS NULL FOR SHARE" actor]))
    (deny! :object-not-found))
  (let [file (bfc/get-file cfg id :decode? false :migrate? false :lock-for-update? true)]
    (when-not (and (seq (db/exec! cfg ["SELECT p.id FROM project p WHERE p.id = ? AND p.deleted_at IS NULL FOR SHARE" (:project-id file)]))
                   (seq (db/exec! cfg ["SELECT t.id FROM team t WHERE t.id = ? AND t.deleted_at IS NULL FOR SHARE" (:team-id file)])))
      (deny! :object-not-found))
    (doseq [[sql scope] [["SELECT profile_id FROM team_profile_rel WHERE team_id = ? AND profile_id = ? FOR SHARE" (:team-id file)]
                         ["SELECT profile_id FROM project_profile_rel WHERE project_id = ? AND profile_id = ? FOR SHARE" (:project-id file)]
                         ["SELECT profile_id FROM file_profile_rel WHERE file_id = ? AND profile_id = ? FOR SHARE" id]]]
      (db/exec! cfg [sql scope actor]))
    (files/check-edition-permissions! cfg actor id)
    file))

(defn- check-revision! [file {:keys [revn vern]}]
  (when (not= vern (:vern file)) (deny! :vern-conflict))
  (when (not= revn (:revn file))
    (ex/raise :type :validation :code :revn-conflict :source :approved-action)))

(sv/defmethod ::issue-file-action-grant
  "Issue an engine-local capability as the authenticated editor, not as an
  arbitrary supplied actor. Product policy must authorize before calling."
  {::sm/params schema:action ::db/transaction true}
  [cfg {:keys [::rpc/profile-id id request-id session-id revn vern changes features] :as params}]
  (check-action! params action-keys)
  (let [file (lock-editor! cfg profile-id id)
        digest (changes-sha256 changes)
        grant-id (uuid/next)]
    (check-revision! file params)
    ;; Retention: unused grants expire after 15 minutes. Durable replay receipts
    ;; are retained 30 days, then removed on issuance; never replay after expiry.
    (db/exec! cfg ["DELETE FROM file_action_grant WHERE replay_until < now()"])
    (db/insert! cfg :file-action-grant
                {:id grant-id :file-id id :actor-id profile-id :request-id request-id
                 :session-id session-id :revn revn :vern vern :args-sha256 digest
                 :binding (blob/encode {:changes changes :features features})}
                {::db/return-keys false})
    {:grant-id grant-id :args-sha256 digest :id id :request-id request-id
     :session-id session-id :revn revn :vern vern}))

(sv/defmethod ::revoke-file-action-grant
  {::sm/params [:map [:id ::sm/uuid] [:grant-id ::sm/uuid]]
   ::db/transaction true}
  [cfg {:keys [::rpc/profile-id id grant-id]}]
  (lock-editor! cfg profile-id id)
  (let [grant (db/get-with-sql cfg ["SELECT * FROM file_action_grant WHERE id = ? FOR UPDATE" grant-id])]
    (when (or (not= id (:file-id grant)) (not= profile-id (:actor-id grant)))
      (deny! :grant-binding-mismatch))
    (db/update! cfg :file-action-grant {:revoked-at (ct/now)} {:id grant-id})
    {:grant-id grant-id :revoked true}))

(sv/defmethod ::commit-file-action
  "Mutation and receipt are committed in the RPC transaction. Exact replay
  rechecks current authority and the full binding, but not the old revision."
  {::sm/params schema:commit ::db/transaction true}
  [cfg {:keys [::rpc/profile-id id grant-id request-id session-id revn vern changes features args-sha256] :as params}]
  (check-action! params (conj action-keys :grant-id :args-sha256))
  (let [file (lock-editor! cfg profile-id id)
        grant (db/get-with-sql cfg ["SELECT *, expires_at > now() AS live, replay_until > now() AS replay_live FROM file_action_grant WHERE id = ? FOR UPDATE" grant-id])
        digest (changes-sha256 changes)]
    (when (or (not= id (:file-id grant))
              (not= profile-id (:actor-id grant))
              (not= request-id (:request-id grant))
              (not= session-id (:session-id grant))
              (not= revn (:revn grant))
              (not= vern (:vern grant))
              (not= digest args-sha256 (:args-sha256 grant))
              (not= {:changes changes :features features} (blob/decode (:binding grant))))
      (deny! :grant-binding-mismatch))
    (when (not= vern (:vern file)) (deny! :vern-conflict))
    (when (or (:revoked-at grant) (not (:replay-live grant))) (deny! :grant-revoked))
    (if-let [receipt (:receipt grant)]
      (blob/decode receipt)
      (do
        (when-not (:live grant) (deny! :grant-expired))
        (check-revision! file params)
        ;; Use the ordinary typed mutation engine and notifications in this same
        ;; connection/transaction; no second HTTP hop and no permissive rebase.
        (let [mutation (update/sm$update-file (assoc cfg ::update/defer-notifications? true)
                                              (select-keys params (conj action-keys ::rpc/profile-id)))
              receipt {:grant-id grant-id :request-id request-id :file-id id
                       :actor-id profile-id :session-id session-id :args-sha256 digest
                       :revn (inc revn) :vern vern}]
          (db/update! cfg :file-action-grant
                      {:receipt (blob/encode receipt) :consumed-at (ct/now)} {:id grant-id})
          (with-meta receipt (select-keys (meta mutation) [::rpc/before-complete-fns])))))))

(sv/defmethod ::get-file-revision
  "Read exactly a saved revision. Retention gaps are explicit, never latest.
  Historical incarnations require a snapshot/log tagged with their vern."
  {::sm/params [:map [:id ::sm/uuid] [:revn [::sm/int {:min 0}]] [:vern [::sm/int {:min 0}]]
                [:features {:optional true} ::cfeat/features]]
   ::db/transaction true}
  [cfg {:keys [::rpc/profile-id id revn vern features]}]
  (let [permissions (perms/get-file-read-permissions cfg profile-id id)]
    (files/check-read-permissions! permissions)
    (db/xact-lock! cfg id)
    (let [current (bfc/get-file cfg id :read-only? true :migrate? false :realize? true :lock-for-share? true)
          result
          (if (and (= revn (:revn current)) (= vern (:vern current)))
            current
            (let [row (db/exec-one! cfg
                                    ["SELECT id, revn FROM file_change WHERE file_id = ? AND vern = ? AND revn <= ? AND label IS NOT NULL AND (deleted_at IS NULL OR deleted_at > now()) ORDER BY revn DESC, created_at DESC LIMIT 1 FOR SHARE" id vern revn])]
              (when-not row (deny! :revision-unavailable))
              (let [snapshot (try
                               (->> (assoc (fsnap/get-snapshot cfg id (:id row)) :id id)
                                    (fdata/realize cfg))
                               (catch clojure.lang.ExceptionInfo cause
                                 (if (= :object-not-found (:code (ex-data cause)))
                                   (deny! :revision-unavailable)
                                   (throw cause))))
                    logs (db/exec! cfg
                                   ["SELECT revn, version, features, changes FROM file_change WHERE file_id = ? AND vern = ? AND revn > ? AND revn <= ? AND changes IS NOT NULL AND (deleted_at IS NULL OR deleted_at > now()) ORDER BY revn" id vern (:revn row) revn])]
                (when (or (not= (mapv :revn logs) (vec (range (inc (:revn row)) (inc revn))))
                          (some #(not= (:version snapshot) (:version %)) logs))
                  (deny! :revision-unavailable))
                (assoc current :revn revn :vern vern :version (:version snapshot)
                       :features (if-let [log (peek logs)]
                                   (db/decode-pgarray (:features log) #{})
                                   (:features snapshot))
                       :data (reduce (fn [data log] (cpc/process-changes data (blob/decode (:changes log))))
                                     (:data snapshot) logs)))))]
      (let [team (teams/get-team cfg :profile-id profile-id :file-id id)]
        (-> (cfeat/get-team-enabled-features cf/flags team)
            (cfeat/check-client-features! features)
            (cfeat/check-file-features! (:features result))))
      (-> result (assoc :permissions permissions) (files/check-version!)))))
