;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2026 Tudor Saitoc

(ns frontend-tests.data.workspace-guided-test
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.workspace.guided :as guided]
   [app.main.ui.workspace.plugins :as plugins]
   [app.plugins.register :as register]
   [cljs.test :as t :include-macros true]))

(t/deftest controlled-manifest-only
  (let [manifest {:host "http://localhost:3041/"
                  :permissions #{"content:read" "library:read"}}]
    (t/is (plugins/guided-manifest-safe? manifest))
    (t/is (not (plugins/guided-manifest-safe? nil)))
    (t/is (not (plugins/guided-manifest-safe? (assoc manifest :host "https://untrusted.example/"))))
    (doseq [permission ["content:write" "library:write" "comment:write" "allow:localstorage"]]
      (t/is (not (plugins/guided-manifest-safe?
                  (update manifest :permissions conj permission)))))))

(t/deftest session-plugin-does-not-install-or-grant-write
  (let [id (str (uuid/next))
        installed (register/plugins-list)]
    (try
      (register/register-session-plugin! {:plugin-id id :permissions #{"content:read"}})
      (t/is (register/check-permission id "content:read"))
      (t/is (not (register/check-permission id "content:write")))
      (t/is (= installed (register/plugins-list)))
      (finally (register/unregister-session-plugin! id)))
    (t/is (nil? (register/get-plugin id)))))

(t/deftest title-target-follows-first-slide-not-map-order
  (let [board (uuid/next) second-board (uuid/next)
        body (uuid/next) title (uuid/next) other-title (uuid/next)
        objects {uuid/zero {:shapes [board second-board]}
                 board {:id board :type :frame :shapes [body title]}
                 second-board {:id second-board :type :frame :shapes [other-title]}
                 body {:id body :type :text :x 10 :y 200}
                 title {:id title :type :text :x 10 :y 20}
                 other-title {:id other-title :type :text :x 10 :y -500}}]
    (t/is (= title (guided/initial-title-id objects)))
    (t/is (nil? (guided/initial-title-id {})))))

(t/deftest title-change-binds-exact-page-shape-and-content
  (let [page-id (uuid/next) shape-id (uuid/next)
        content {:type "root" :children []}]
    (t/is (= {:type :mod-obj :page-id page-id :id shape-id
              :operations [{:type :set :attr :content :val content}]}
             (guided/title-change page-id shape-id content)))))

(t/deftest export-frame-is-a-controlled-file-map
  (t/is (= "/guided?fixture=goodles&state=export&dock=1"
           (plugins/guided-export-url "4e46db1e-3a60-80bf-8008-cb9131813611")))
  (t/is (nil? (plugins/guided-export-url "https://untrusted.example/"))))
