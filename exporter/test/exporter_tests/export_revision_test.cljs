;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2026 Saitoc

(ns exporter-tests.export-revision-test
  (:require
   [app.handlers.export-shapes :as exports]
   [cljs.test :refer [deftest is]]))

(defn- entry [file-id page-id object-id revn vern]
  {:file-id file-id :page-id page-id :object-id object-id
   :revn revn :vern vern :name "Slide" :suffix "" :scale 1 :type :png})

(deftest preserves-requested-revision-and-incarnation
  (let [file-id (random-uuid)
        page-id (random-uuid)
        first-id (random-uuid)
        second-id (random-uuid)
        prepared (exports/prepare-exports
                  [(entry file-id page-id first-id 3 0)
                   (entry file-id page-id second-id 4 0)]
                  "fixture-token")]
    (is (= 2 (count prepared)))
    (is (= #{[3 0] [4 0]} (set (map (juxt :revn :vern) prepared))))
    (is (= #{first-id second-id} (set (mapcat #(map :id (:objects %)) prepared))))))

(deftest partitions-exports-by-file-page-and-incarnation
  (let [file-id (random-uuid)
        page-id (random-uuid)
        prepared (exports/prepare-exports
                  [(entry file-id page-id (random-uuid) 3 0)
                   (entry file-id page-id (random-uuid) 3 1)
                   (entry (random-uuid) page-id (random-uuid) 3 0)
                   (entry file-id (random-uuid) (random-uuid) 3 0)]
                  "fixture-token")]
    (is (= 4 (count prepared)))
    (is (every? #(= 1 (count (:objects %))) prepared))))

(deftest batches-only-the-same-saved-revision
  (let [file-id (random-uuid)
        page-id (random-uuid)
        prepared (exports/prepare-exports
                  [(entry file-id page-id (random-uuid) 5 2)
                   (entry file-id page-id (random-uuid) 5 2)]
                  "fixture-token")]
    (is (= 1 (count prepared)))
    (is (= 2 (count (:objects (first prepared)))))
    (is (= [5 2] ((juxt :revn :vern) (first prepared))))))
