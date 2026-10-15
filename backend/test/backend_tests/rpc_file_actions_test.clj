;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2026 Tudor Saitoc

(ns backend-tests.rpc-file-actions-test
  (:require
   [app.common.features :as cfeat]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.http.errors :as errors]
   [app.msgbus :as mbus]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files-actions :as actions]
   [app.rpc.commands.files-update :as update]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [yetti.response :as yres]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- call [command actor params]
  (th/command! (assoc params ::th/type command ::rpc/profile-id (:id actor))))

(defn- ok [out]
  (t/is (nil? (:error out)) (some-> (:error out) str))
  (:result out))

(defn- denied [out]
  (t/is (some? (:error out))))

(defn- code [expected out]
  (t/is (= expected (th/ex-code (:error out)))))

(defn- fixture []
  (let [owner (th/create-profile* 1 {:is-active true})
        editor (th/create-profile* 2 {:is-active true})
        viewer (th/create-profile* 3 {:is-active true})
        outsider (th/create-profile* 4 {:is-active true})
        team-id (:default-team-id owner)
        file (th/create-file* 1 {:profile-id (:id owner)
                                 :project-id (:default-project-id owner)})
        page-id (get-in file [:data :pages 0])
        shape-id (uuid/next)]
    (th/create-team-role* {:team-id team-id :profile-id (:id editor) :role :editor})
    (th/create-team-role* {:team-id team-id :profile-id (:id viewer) :role :viewer})
    (ok (call :update-file owner
              {:id (:id file) :session-id (uuid/next) :revn 0 :vern 0
               :features cfeat/supported-features
               :changes [{:type :add-obj :page-id page-id :id shape-id
                          :parent-id uuid/zero :frame-id uuid/zero
                          :obj (cts/setup-shape {:id shape-id :type :text :name "Title"})}]}))
    {:owner owner :editor editor :viewer viewer :outsider outsider
     :file file :team-id team-id :page-id page-id :shape-id shape-id}))

(defn- content [text]
  {:type "root" :children [{:type "paragraph-set"
                            :children [{:type "paragraph" :children [{:text text}]}]}]})

(defn- proposal [{:keys [file page-id shape-id]}]
  {:id (:id file) :request-id (uuid/next) :session-id (uuid/next)
   :revn 1 :vern 0 :features cfeat/supported-features
   :changes [{:type :mod-obj :page-id page-id :id shape-id
              :operations [{:type :set :attr :content :val (content "Approved title")}]}]})

(defn- issue [ctx params]
  (ok (call :issue-file-action-grant (:owner ctx) params)))

(defn- commit-params [params grant]
  (merge params (select-keys grant [:grant-id :args-sha256])))

(defn- saved [ctx]
  (ok (call :get-file (:owner ctx) {:id (get-in ctx [:file :id])})))

(t/deftest approved-title-commits-once-and-replays-after-another-edit
  (let [ctx (fixture) params (proposal ctx) grant (issue ctx params)
        commit (commit-params params grant)
        receipt (ok (call :commit-file-action (:owner ctx) commit))]
    (t/is (= (actions/changes-sha256 (:changes params)) (:args-sha256 grant)))
    (t/is (= 2 (:revn receipt)))
    (t/is (= 0 (:vern receipt)))
    (t/is (= (:id (:owner ctx)) (:actor-id receipt)))
    (t/is (= (content "Approved title")
             (get-in (saved ctx) [:data :pages-index (:page-id ctx) :objects (:shape-id ctx) :content])))
    (t/is (= receipt (ok (call :commit-file-action (:owner ctx) commit))))
    (t/is (= 2 (:revn (saved ctx))))
    (t/is (= 1 (count (th/db-query :file-action-grant {:id (:grant-id grant)}))))
    (ok (call :update-file (:editor ctx)
              {:id (:id (:file ctx)) :session-id (uuid/next) :revn 2 :vern 0
               :features cfeat/supported-features
               :changes [{:type :mod-page :id (:page-id ctx) :name "Collaborator"}]}))
    (t/is (= receipt (ok (call :commit-file-action (:owner ctx) commit))))
    (t/is (= 3 (:revn (saved ctx))))))

(t/deftest grant-issuance-requires-real-current-editor
  (let [ctx (fixture) params (proposal ctx)]
    (doseq [actor [(:viewer ctx) (:outsider ctx)]]
      (denied (call :issue-file-action-grant actor params)))
    (ok (call :issue-file-action-grant (:editor ctx) params))
    (code :revn-conflict (call :issue-file-action-grant (:owner ctx) (assoc params :revn 0)))
    (code :vern-conflict (call :issue-file-action-grant (:owner ctx) (assoc params :vern 1)))
    (th/db-delete! :team-profile-rel {:team-id (:team-id ctx) :profile-id (:id (:editor ctx))})
    (denied (call :issue-file-action-grant (:editor ctx) (assoc params :request-id (uuid/next))))))

(t/deftest full-binding-and-single-use
  (let [ctx (fixture) params (proposal ctx) grant (issue ctx params)
        commit (commit-params params grant)
        other-file (th/create-file* 2 {:profile-id (:id (:owner ctx))
                                       :project-id (:default-project-id (:owner ctx))})
        changed (assoc-in commit [:changes 0 :operations 0 :val] (content "Not approved"))]
    (doseq [bad [(assoc commit :id (:id other-file))
                 (assoc commit :request-id (uuid/next))
                 (assoc commit :session-id (uuid/next))
                 (assoc commit :revn 0)
                 (assoc commit :vern 1)
                 (assoc commit :args-sha256 (apply str (repeat 64 "0")))
                 changed
                 (assoc changed :args-sha256 (actions/changes-sha256 (:changes changed)))
                 (dissoc commit :features)]]
      (code :grant-binding-mismatch (call :commit-file-action (:owner ctx) bad)))
    ;; Another editor has file authority, but does not own this capability.
    (code :grant-binding-mismatch (call :commit-file-action (:editor ctx) commit))
    (t/is (= 1 (:revn (saved ctx))))
    (ok (call :commit-file-action (:owner ctx) commit))
    (code :grant-binding-mismatch
          (call :commit-file-action (:owner ctx) (assoc commit :request-id (uuid/next))))
    (code :grant-binding-mismatch (call :commit-file-action (:owner ctx) changed))
    ;; A second grant cannot reuse the same actor/request identity.
    (denied (call :issue-file-action-grant (:owner ctx) (assoc params :revn 2)))
    (t/is (= 2 (:revn (saved ctx))))))

(t/deftest collaborator-edit-rejects-approval-but-ordinary-collaboration-still-rebases
  (let [ctx (fixture) params (proposal ctx) grant (issue ctx params)
        update-params {:id (:id (:file ctx)) :session-id (uuid/next) :revn 0 :vern 0
                       :features cfeat/supported-features
                       :changes [{:type :mod-page :id (:page-id ctx) :name "Collaborator"}]}]
    ;; Deliberately old revn is valid for the ordinary collaborative endpoint.
    (ok (call :update-file (:editor ctx) update-params))
    (let [out (call :commit-file-action (:owner ctx) (commit-params params grant))]
      (code :revn-conflict out)
      (t/is (= 409 (::yres/status (errors/handle-error (:error out) {} nil)))))
    (t/is (= 2 (:revn (saved ctx))))
    (t/is (nil? (:receipt (th/db-get :file-action-grant {:id (:grant-id grant)}))))))

(t/deftest restored-version-rejects-approved-commit
  (let [ctx (fixture) params (proposal ctx) grant (issue ctx params)
        snapshot (ok (call :create-file-snapshot (:owner ctx) {:file-id (:id (:file ctx))}))]
    (ok (call :restore-file-snapshot (:owner ctx) {:file-id (:id (:file ctx)) :id (:id snapshot)}))
    (code :vern-conflict (call :commit-file-action (:owner ctx) (commit-params params grant)))
    (t/is (nil? (:receipt (th/db-get :file-action-grant {:id (:grant-id grant)}))))))

(t/deftest revoked-and-downgraded-membership-denies-even-replay
  (let [ctx (fixture) params (proposal ctx)
        grant (ok (call :issue-file-action-grant (:editor ctx) params))
        commit (commit-params params grant)]
    ;; Direct file grants must not survive loss of actual team membership.
    (th/create-file-role* {:file-id (:id (:file ctx)) :profile-id (:id (:editor ctx)) :role :editor})
    (th/db-delete! :team-profile-rel {:team-id (:team-id ctx) :profile-id (:id (:editor ctx))})
    (denied (call :commit-file-action (:editor ctx) commit))
    (th/db-delete! :file-profile-rel {:file-id (:id (:file ctx)) :profile-id (:id (:editor ctx))})
    (th/create-team-role* {:team-id (:team-id ctx) :profile-id (:id (:editor ctx)) :role :viewer})
    (denied (call :commit-file-action (:editor ctx) commit))
    (th/db-update! :team-profile-rel {:can-edit true} {:team-id (:team-id ctx) :profile-id (:id (:editor ctx))})
    (ok (call :commit-file-action (:editor ctx) commit))
    (th/db-delete! :team-profile-rel {:team-id (:team-id ctx) :profile-id (:id (:editor ctx))})
    (denied (call :commit-file-action (:editor ctx) commit))))

(t/deftest explicit-revoke-and-expiry
  (let [ctx (fixture) params (proposal ctx) grant (issue ctx params)
        revoke {:id (:id (:file ctx)) :grant-id (:grant-id grant)}]
    (denied (call :revoke-file-action-grant (:viewer ctx) revoke))
    (code :grant-binding-mismatch (call :revoke-file-action-grant (:editor ctx) revoke))
    (ok (call :revoke-file-action-grant (:owner ctx) revoke))
    (code :grant-revoked (call :commit-file-action (:owner ctx) (commit-params params grant)))
    (let [params (assoc params :request-id (uuid/next))
          grant (issue ctx params)]
      (th/db-exec! ["UPDATE file_action_grant SET expires_at = now() - interval '1 second' WHERE id = ?" (:grant-id grant)])
      (code :grant-expired (call :commit-file-action (:owner ctx) (commit-params params grant))))
    (t/is (= 1 (:revn (saved ctx))))))

(t/deftest no-client-approval-or-protected-field-bypass
  (let [ctx (fixture) params (proposal ctx)]
    (doseq [bad [(assoc params :approved true)
                 (assoc params :skip-validate true)
                 (assoc params :actor-id (:id (:owner ctx)))
                 (assoc-in params [:changes 0 :operations 0 :attr] :permissions)
                 (assoc-in params [:changes 0 :operations 0 :attr] :plugin-data)
                 (assoc params :changes [{:type :set-plugin-data :object-type :file
                                          :namespace :shared/decks :key "approval" :value "true"}])]]
      (code :protected-action-field (call :issue-file-action-grant (:owner ctx) bad)))
    (denied (call :issue-file-action-grant (:owner ctx) (assoc params :changes [{:type :execute-code :code "anything"}])))
    (let [grant (issue ctx params)]
      (code :protected-action-field
            (call :commit-file-action (:owner ctx) (assoc (commit-params params grant) :approved true))))
    (t/is (= 1 (:revn (saved ctx))))))

(t/deftest failed-mutation-rolls-back-grant-and-document
  (let [ctx (fixture) params (proposal ctx) grant (issue ctx params)
        commit (commit-params params grant)
        original update/sm$update-file]
    (with-redefs [update/sm$update-file (fn [cfg params]
                                       (original cfg params)
                                       (throw (ex-info "Failure after mutation, before receipt" {})))]
      (denied (call :commit-file-action (:owner ctx) commit)))
    (t/is (= 1 (:revn (saved ctx))))
    (t/is (nil? (:receipt (th/db-get :file-action-grant {:id (:grant-id grant)}))))
    (ok (call :commit-file-action (:owner ctx) commit))
    (t/is (= 2 (:revn (saved ctx))))))

(t/deftest concurrent-exact-replay-mutates-only-once
  (let [ctx (fixture) params (proposal ctx) grant (issue ctx params)
        commit (commit-params params grant)
        gate (promise)
        run #(do @gate (call :commit-file-action (:owner ctx) commit))
        a (future (run)) b (future (run))]
    (deliver gate true)
    (let [ra (ok @a) rb (ok @b)] (t/is (= ra rb)))
    (t/is (= 2 (:revn (saved ctx))))
    (t/is (= 1 (count (th/db-exec! ["SELECT id FROM file_change WHERE file_id = ? AND revn = 2 AND changes IS NOT NULL" (:id (:file ctx))]))))))

(t/deftest exact-historical-read-and-authority
  (let [ctx (fixture) id (:id (:file ctx)) page-id (:page-id ctx)
        read-params {:id id :revn 2 :vern 0}
        change-page (fn [revn name]
                      (ok (call :update-file (:owner ctx)
                                {:id id :revn revn :vern 0 :session-id (uuid/next)
                                 :features cfeat/supported-features
                                 :changes [{:type :mod-page :id page-id :name name}]})))]
    (change-page 1 "Historical")
    (change-page 2 "Latest")
    ;; Force reconstruction from the revn 1 snapshot and revn 2 change log,
    ;; rather than merely reading the exact revn 2 snapshot.
    (th/db-exec! ["DELETE FROM file_change WHERE file_id = ? AND revn = 2 AND label IS NOT NULL" id])
    (let [historical (ok (call :get-file-revision (:owner ctx) read-params))]
      (t/is (= 2 (:revn historical)))
      (t/is (= 0 (:vern historical)))
      (t/is (= "Historical" (get-in historical [:data :pages-index page-id :name])))
      (t/is (= "Latest" (get-in (saved ctx) [:data :pages-index page-id :name]))))
    (ok (call :get-file-revision (:viewer ctx) read-params))
    (denied (call :get-file-revision (:outsider ctx) read-params))
    (th/db-delete! :team-profile-rel {:team-id (:team-id ctx) :profile-id (:id (:viewer ctx))})
    (denied (call :get-file-revision (:viewer ctx) read-params))
    (code :revision-unavailable (call :get-file-revision (:owner ctx) (assoc read-params :revn 100)))
    (code :revision-unavailable (call :get-file-revision (:owner ctx) (assoc read-params :vern 100)))
    (th/db-exec! ["DELETE FROM file_change WHERE file_id = ? AND revn = 2 AND changes IS NOT NULL" id])
    (code :revision-unavailable (call :get-file-revision (:owner ctx) read-params))
    (let [current (ok (call :get-file-revision (:owner ctx) (assoc read-params :revn 3)))]
      (t/is (= "Latest" (get-in current [:data :pages-index page-id :name]))))))

(t/deftest commit-notifications-follow-transaction-and-not-replay
  (let [ctx (fixture) params (proposal ctx) grant (issue ctx params)
        commit (commit-params params grant) published (atom 0)
        original update/sm$update-file]
    (with-redefs [mbus/pub! (fn [& _] (swap! published inc))]
      (with-redefs [update/sm$update-file (fn [cfg params]
                                         (original cfg params)
                                         (throw (ex-info "Rollback" {})))]
        (denied (call :commit-file-action (:owner ctx) commit)))
      (t/is (zero? @published))
      (let [receipt (ok (call :commit-file-action (:owner ctx) commit))]
        (t/is (zero? @published))
        (t/is (= 2 (:revn (saved ctx))))
        ;; HTTP completion normally executes these after the RPC transaction.
        (doseq [notify (::rpc/before-complete-fns (meta receipt))] (notify))
        (t/is (pos? @published))
        (let [before @published replay (ok (call :commit-file-action (:owner ctx) commit))]
          (doseq [notify (::rpc/before-complete-fns (meta replay))] (notify))
          (t/is (= before @published)))))))

(t/deftest inactive-actor-cannot-issue-revoke-or-commit
  (let [ctx (fixture) params (proposal ctx) grant (issue ctx params)]
    (th/db-update! :profile {:is-active false} {:id (:id (:owner ctx))})
    (doseq [[command args] [[:issue-file-action-grant (assoc params :request-id (uuid/next))]
                            [:revoke-file-action-grant {:id (:id (:file ctx)) :grant-id (:grant-id grant)}]
                            [:commit-file-action (commit-params params grant)]]]
      (denied (call command (:owner ctx) args)))))

(t/deftest historical-read-survives-restore-and-refuses-unknown-incarnation
  (let [ctx (fixture) id (:id (:file ctx))
        params (proposal ctx) grant (issue ctx params)]
    (ok (call :commit-file-action (:owner ctx) (commit-params params grant)))
    (let [snapshot (ok (call :create-file-snapshot (:owner ctx) {:file-id id}))]
      (ok (call :restore-file-snapshot (:owner ctx) {:file-id id :id (:id snapshot)})))
    (let [historical (ok (call :get-file-revision (:owner ctx) {:id id :revn 2 :vern 0}))]
      (t/is (= 2 (:revn historical)))
      (t/is (= 0 (:vern historical)))
      (t/is (= (content "Approved title")
               (get-in historical [:data :pages-index (:page-id ctx) :objects (:shape-id ctx) :content]))))
    ;; Legacy rows have no provable incarnation. Never relabel them as current.
    (th/db-exec! ["UPDATE file_change SET vern = NULL WHERE file_id = ?" id])
    (code :revision-unavailable (call :get-file-revision (:owner ctx) {:id id :revn 2 :vern 0}))))

(t/deftest consumed-grant-can-be-revoked-without-changing-receipt-or-file
  (let [ctx (fixture) params (proposal ctx) grant (issue ctx params)
        commit (commit-params params grant)
        receipt (ok (call :commit-file-action (:owner ctx) commit))
        before (:receipt (th/db-get :file-action-grant {:id (:grant-id grant)}))]
    (ok (call :revoke-file-action-grant (:owner ctx)
              {:id (:id (:file ctx)) :grant-id (:grant-id grant)}))
    (code :grant-revoked (call :commit-file-action (:owner ctx) commit))
    (t/is (= (:revn receipt) (:revn (saved ctx))))
    (t/is (java.util.Arrays/equals ^bytes before
                                  ^bytes (:receipt (th/db-get :file-action-grant {:id (:grant-id grant)}))))))

(t/deftest approved-shared-deck-metadata-remains-editable-and-historical
  (let [ctx (fixture)
        params (assoc (proposal ctx) :changes
                      [{:type :set-plugin-data :object-type :shape
                        :object-id (:shape-id ctx) :page-id (:page-id ctx)
                        :namespace :shared/decks :key "chart"
                        :value "{\"type\":\"bar\",\"data\":[1,2]}"}
                       {:type :set-plugin-data :object-type :shape
                        :object-id (:shape-id ctx) :page-id (:page-id ctx)
                        :namespace :shared/decks :key "chartVisual"
                        :value "approved-native-child-fingerprint"}
                       {:type :set-plugin-data :object-type :page
                        :object-id (:page-id ctx) :namespace :shared/decks
                        :key "slideOrder" :value "[]"}])
        grant (issue ctx params)]
    (ok (call :commit-file-action (:owner ctx) (commit-params params grant)))
    (code :grant-binding-mismatch
          (call :commit-file-action (:owner ctx)
                (assoc-in (commit-params params grant) [:changes 1 :value] "different-fingerprint")))
    (let [file (ok (call :get-file-revision (:owner ctx)
                        {:id (:id (:file ctx)) :revn 2 :vern 0
                         :features cfeat/supported-features}))]
      (t/is (= "{\"type\":\"bar\",\"data\":[1,2]}"
               (get-in file [:data :pages-index (:page-id ctx) :objects (:shape-id ctx)
                             :plugin-data :shared/decks "chart"])))
      (t/is (= "approved-native-child-fingerprint"
               (get-in file [:data :pages-index (:page-id ctx) :objects (:shape-id ctx)
                             :plugin-data :shared/decks "chartVisual"])))
      (t/is (= "[]" (get-in file [:data :pages-index (:page-id ctx) :plugin-data :shared/decks "slideOrder"]))))))

(t/deftest revision-read-negotiates-features-like-get-file
  (let [ctx (fixture) id (:id (:file ctx))
        normal (call :get-file (:owner ctx) {:id id :features #{}})
        exact (call :get-file-revision (:owner ctx) {:id id :revn 1 :vern 0 :features #{}})]
    (code :feature-not-supported normal)
    (code :feature-not-supported exact)))

(t/deftest exact-replay-denies-restored-incarnation
  (let [ctx (fixture) params (proposal ctx) grant (issue ctx params)
        commit (commit-params params grant)]
    (ok (call :commit-file-action (:owner ctx) commit))
    (let [snapshot (ok (call :create-file-snapshot (:owner ctx) {:file-id (:id (:file ctx))}))]
      (ok (call :restore-file-snapshot (:owner ctx) {:file-id (:id (:file ctx)) :id (:id snapshot)})))
    (code :vern-conflict (call :commit-file-action (:owner ctx) commit))))
