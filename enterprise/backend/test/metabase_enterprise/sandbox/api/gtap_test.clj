(ns metabase-enterprise.sandbox.api.gtap-test
  (:require
   [clojure.test :refer :all]
   [metabase.driver.util :as driver.util]
   [metabase.permissions.models.data-permissions.graph :as data-perms.graph]
   [metabase.premium-features.core :as premium-features]
   [metabase.request.core :as request]
   [metabase.test :as mt]
   [metabase.test.http-client :as client]
   [toucan2.core :as t2]))

(deftest require-auth-test
  (testing "Must be authenticated to query for GTAPs"
    (mt/with-premium-features #{:sandboxes}
      (is (= (get request/response-unauthentic :body)
             (client/client :get 401 "mt/gtap")))

      (is (= "You don't have permissions to do that."
             (mt/user-http-request :rasta :get 403 "mt/gtap"))))))

(def ^:private default-gtap-results
  {:id                   true
   :card_id              true
   :table_id             true
   :group_id             true
   :attribute_remappings {:foo 1}})

(defmacro ^:private with-gtap-cleanup!
  "Invokes `body` ensuring any `GroupTableAccessPolicy` created will be removed afterward. Leaving behind a GTAP can
  case referential integrity failures for any related `Card` that would be cleaned up as part of a `with-temp*` call"
  [& body]
  `(mt/with-premium-features #{:sandboxes}
     (mt/with-model-cleanup [:model/GroupTableAccessPolicy]
       ~@body)))

(defn- gtap-post
  "`gtap-data` is a map to be POSTed to the GTAP endpoint"
  [gtap-data]
  (mt/user-http-request :crowberto :post 200 "mt/gtap" gtap-data))

(deftest validate-token-test
  (testing "POST /api/mt/gtap"
    (testing "Must have a valid token to use GTAPs"
      (with-redefs [premium-features/enable-sandboxes? (constantly false)]
        (mt/with-temporary-setting-values [premium-embedding-token nil]
          (mt/with-temp [:model/Table            {table-id :id} {}
                         :model/PermissionsGroup {group-id :id} {}
                         :model/Card             {card-id :id}  {}]
            (mt/assert-has-premium-feature-error "Sandboxes" (mt/user-http-request :crowberto :post 402 "mt/gtap"
                                                                                   {:table_id             table-id
                                                                                    :group_id             group-id
                                                                                    :card_id              card-id
                                                                                    :attribute_remappings {"foo" 1}}))))))))

(deftest fetch-gtap-test
  (testing "GET /api/mt/gtap/"
    (with-gtap-cleanup!
      (mt/with-temp [:model/Table                  {table-id-1 :id} {}
                     :model/Table                  {table-id-2 :id} {}
                     :model/PermissionsGroup       {group-id-1 :id} {}
                     :model/PermissionsGroup       {group-id-2 :id} {}
                     :model/Card                   {card-id :id} {}
                     :model/GroupTableAccessPolicy {gtap-id-1 :id} {:table_id table-id-1
                                                                    :group_id group-id-1
                                                                    :card_id  card-id}
                     :model/GroupTableAccessPolicy {gtap-id-2 :id} {:table_id table-id-2
                                                                    :group_id group-id-2
                                                                    :card_id  card-id}]
        (testing "Test that we can fetch the list of all GTAPs"
          (is (partial=
               [{:id gtap-id-1 :table_id table-id-1 :group_id group-id-1}
                {:id gtap-id-2 :table_id table-id-2 :group_id group-id-2}]
               (filter
                #(#{gtap-id-1 gtap-id-2} (:id %))
                (mt/user-http-request :crowberto :get 200 "mt/gtap/")))))

        (testing "Test that we can fetch the GTAP for a specific table and group"
          (is (partial=
               {:id gtap-id-1 :table_id table-id-1 :group_id group-id-1}
               (mt/user-http-request :crowberto :get 200 "mt/gtap/"
                                     :group_id group-id-1 :table_id table-id-1))))))))

(deftest create-gtap-test
  (testing "POST /api/mt/gtap"
    (mt/with-temp [:model/Table            {table-id :id} {}
                   :model/PermissionsGroup {group-id :id} {}]
      (testing "Test that we can create a new GTAP"
        (mt/with-temp [:model/Card {card-id :id}]
          (with-gtap-cleanup!
            (let [post-results (gtap-post {:table_id             table-id
                                           :group_id             group-id
                                           :card_id              card-id
                                           :attribute_remappings {"foo" 1}})]
              (is (= default-gtap-results
                     (mt/boolean-ids-and-timestamps post-results)))
              (is (= post-results
                     (mt/user-http-request :crowberto :get 200 (format "mt/gtap/%s" (:id post-results)))))))))

      (testing "Test that we can create a new GTAP without a card"
        (with-gtap-cleanup!
          (let [post-results (gtap-post {:table_id             table-id
                                         :group_id             group-id
                                         :card_id              nil
                                         :attribute_remappings {"foo" 1}})]
            (is (= (assoc default-gtap-results :card_id false)
                   (mt/boolean-ids-and-timestamps post-results)))
            (is (= post-results
                   (mt/user-http-request :crowberto :get 200 (format "mt/gtap/%s" (:id post-results))))))))

      (testing "Meaningful errors should be returned if you create an invalid GTAP"
        (mt/with-temp [:model/Field _ {:name "My field" :table_id table-id :base_type :type/Integer}
                       :model/Card  {card-id :id} {:dataset_query (mt/mbql-query venues
                                                                    {:fields      [[:expression "My field"]]
                                                                     :expressions {"My field" [:ltrim "wow"]}})}]
          (with-gtap-cleanup!
            (is (=? {:message  "Sandbox Questions can't return columns that have different types than the Table they are sandboxing."
                     :expected "type/Integer"
                     :actual   "type/Text"}
                    (mt/user-http-request :crowberto :post 400 "mt/gtap"
                                          {:table_id             table-id
                                           :group_id             group-id
                                           :card_id              card-id
                                           :attribute_remappings {"foo" 1}})))))))))

(deftest validate-sandbox-test
  (testing "POST /api/mt/gtap/validate"
    (mt/with-temp [:model/Table            {table-id :id} {}
                   :model/PermissionsGroup {group-id :id} {}]
      (testing "A valid sandbox passes validation and returns no error"
        (mt/with-temp [:model/Card {card-id :id}]
          (with-gtap-cleanup!
            (mt/user-http-request :crowberto :post 204 "mt/gtap/validate"
                                  {:table_id             table-id
                                   :group_id             group-id
                                   :card_id              card-id}))))

      (testing "A sandbox without a card-id passes validation, because the validation is not applicable in this case"
        (with-gtap-cleanup!
          (mt/user-http-request :crowberto :post 204 "mt/gtap/validate"
                                {:table_id             table-id
                                 :group_id             group-id
                                 :card_id              nil
                                 :attribute_remappings {"foo" 1}})))

      (testing "An invalid sandbox results in a 400 error being returned"
        (mt/with-temp [:model/Field _ {:name "My field", :table_id table-id, :base_type :type/Integer}
                       :model/Card  {card-id :id} {:dataset_query (mt/mbql-query venues
                                                                    {:fields      [[:expression "My field"]]
                                                                     :expressions {"My field" [:ltrim "wow"]}})}]
          (with-gtap-cleanup!
            (is (=? {:message  "Sandbox Questions can't return columns that have different types than the Table they are sandboxing."
                     :expected "type/Integer"
                     :actual   "type/Text"}
                    (mt/user-http-request :crowberto :post 400 "mt/gtap/validate"
                                          {:table_id             table-id
                                           :group_id             group-id
                                           :card_id              card-id
                                           :attribute_remappings {"foo" 1}}))))))
      (testing "A database without the saved question sandboxing features returns a 400 error"
        (with-redefs [driver.util/supports? (fn [_ feature _] (not= feature :saved-question-sandboxing))]
          (mt/with-temp [:model/Card {card-id :id}]
            (with-gtap-cleanup!
              (is (=? {:message  "Sandboxing with a saved question is not enabled for this database."}
                      (mt/user-http-request :crowberto :post 400 "mt/gtap/validate"
                                            {:table_id             table-id
                                             :group_id             group-id
                                             :card_id              card-id
                                             :attribute_remappings {"foo" 1}}))))))))))

(deftest delete-gtap-test
  (testing "DELETE /api/mt/gtap/:id"
    (testing "Test that we can delete a GTAP"
      (mt/with-temp [:model/Table            {table-id :id} {}
                     :model/PermissionsGroup {group-id :id} {}
                     :model/Card             {card-id :id} {}]
        (with-gtap-cleanup!
          (let [{:keys [id]} (gtap-post {:table_id             table-id
                                         :group_id             group-id
                                         :card_id              card-id
                                         :attribute_remappings {"foo" 1}})]
            (is (= default-gtap-results
                   (mt/boolean-ids-and-timestamps (mt/user-http-request :crowberto :get 200 (format "mt/gtap/%s" id)))))
            (is (= nil
                   (mt/user-http-request :crowberto :delete 204 (format "mt/gtap/%s" id))))
            (is (= "Not found."
                   (mt/user-http-request :crowberto :get 404 (format "mt/gtap/%s" id))))))))))

(deftest update-gtap-test
  (testing "PUT /api/mt/gtap/:id"
    (mt/with-temp [:model/Table            {table-id :id} {}
                   :model/PermissionsGroup {group-id :id} {}
                   :model/Card             {card-id :id}  {}]
      (mt/with-premium-features #{:sandboxes}
        (testing "Test that we can update only the attribute remappings for a GTAP"
          (mt/with-temp [:model/GroupTableAccessPolicy {gtap-id :id} {:table_id             table-id
                                                                      :group_id             group-id
                                                                      :card_id              card-id
                                                                      :attribute_remappings {"foo" 1}}]
            (is (= (assoc default-gtap-results :attribute_remappings {:bar 2})
                   (mt/boolean-ids-and-timestamps
                    (mt/user-http-request :crowberto :put 200 (format "mt/gtap/%s" gtap-id)
                                          {:attribute_remappings {:bar 2}}))))))

        (testing "Test that we can add a card_id via PUT"
          (mt/with-temp [:model/GroupTableAccessPolicy {gtap-id :id} {:table_id             table-id
                                                                      :group_id             group-id
                                                                      :card_id              nil
                                                                      :attribute_remappings {"foo" 1}}]
            (is (= default-gtap-results
                   (mt/boolean-ids-and-timestamps
                    (mt/user-http-request :crowberto :put 200 (format "mt/gtap/%s" gtap-id)
                                          {:card_id card-id}))))))

        (testing "Test that we can remove a card_id via PUT"
          (mt/with-temp [:model/GroupTableAccessPolicy {gtap-id :id} {:table_id             table-id
                                                                      :group_id             group-id
                                                                      :card_id              card-id
                                                                      :attribute_remappings {"foo" 1}}]
            (is (= (assoc default-gtap-results :card_id false)
                   (mt/boolean-ids-and-timestamps
                    (mt/user-http-request :crowberto :put 200 (format "mt/gtap/%s" gtap-id)
                                          {:card_id nil}))))))

        (testing "Test that we can remove a card_id and change attribute remappings via PUT"
          (mt/with-temp [:model/GroupTableAccessPolicy {gtap-id :id} {:table_id             table-id
                                                                      :group_id             group-id
                                                                      :card_id              card-id
                                                                      :attribute_remappings {"foo" 1}}]
            (is (= (assoc default-gtap-results :card_id false, :attribute_remappings {:bar 2})
                   (mt/boolean-ids-and-timestamps
                    (mt/user-http-request :crowberto :put 200 (format "mt/gtap/%s" gtap-id)
                                          {:card_id              nil
                                           :attribute_remappings {:bar 2}}))))))))))

(deftest bulk-upsert-sandboxes-test
  (testing "PUT /api/permissions/graph"
    (mt/with-temp [:model/Table            {table-id-1 :id} {:db_id (mt/id) :schema "PUBLIC"}
                   :model/Table            {table-id-2 :id} {:db_id (mt/id) :schema "PUBLIC"}
                   :model/PermissionsGroup {group-id :id}   {}
                   :model/Card             {card-id-1 :id}  {}
                   :model/Card             {card-id-2 :id}  {}]
      (mt/with-premium-features #{:sandboxes}
        (with-gtap-cleanup!
          (testing "Test that we can create a new sandbox using the permission graph API"
            (let [graph  (-> (data-perms.graph/api-graph)
                             (assoc-in [:groups group-id (mt/id) :view-data] {"PUBLIC" {table-id-1 :sandboxed}})
                             (assoc :sandboxes [{:table_id             table-id-1
                                                 :group_id             group-id
                                                 :card_id              card-id-1
                                                 :attribute_remappings {"foo" 1}}]))
                  result (mt/user-http-request :crowberto :put 200 "permissions/graph" graph)]
              (is (=? [{:id                   (mt/malli=? :int)
                        :table_id             table-id-1
                        :group_id             group-id
                        :card_id              card-id-1
                        :attribute_remappings {:foo 1}}]
                      (:sandboxes result)))
              (is (t2/exists? :model/GroupTableAccessPolicy :table_id table-id-1 :group_id group-id))))

          (testing "Test that we can update a sandbox using the permission graph API"
            (let [sandbox-id (t2/select-one-fn :id :model/GroupTableAccessPolicy
                                               :table_id table-id-1
                                               :group_id group-id)
                  graph      (-> (data-perms.graph/api-graph)
                                 (assoc :sandboxes [{:id                   sandbox-id
                                                     :card_id              card-id-2
                                                     :attribute_remappings {"foo" 2}}]))
                  result     (mt/user-http-request :crowberto :put 200 "permissions/graph" graph)]
              (is (partial= [{:table_id table-id-1 :group_id group-id}]
                            (:sandboxes result)))
              (is (partial= {:card_id              card-id-2
                             :attribute_remappings {"foo" 2}}
                            (t2/select-one :model/GroupTableAccessPolicy
                                           :table_id table-id-1
                                           :group_id group-id)))))

          (testing "Test that we can create and update multiple sandboxes at once using the permission graph API"
            (let [sandbox-id (t2/select-one-fn :id :model/GroupTableAccessPolicy
                                               :table_id table-id-1
                                               :group_id group-id)
                  graph       (-> (data-perms.graph/api-graph)
                                  (assoc-in [:groups group-id (mt/id) :view-data] {"PUBLIC" {table-id-2 :sandboxed}})
                                  (assoc :sandboxes [{:id                   sandbox-id
                                                      :card_id              card-id-1
                                                      :attribute_remappings {"foo" 3}}
                                                     {:table_id             table-id-2
                                                      :group_id             group-id
                                                      :card_id              card-id-2
                                                      :attribute_remappings {"foo" 10}}]))
                  result     (mt/user-http-request :crowberto :put 200 "permissions/graph" graph)]
              (is (partial= [{:table_id table-id-1 :group_id group-id}
                             {:table_id table-id-2 :group_id group-id}]
                            (:sandboxes result)))
              ;; Updated sandbox
              (is (partial= {:card_id              card-id-1
                             :attribute_remappings {"foo" 3}}
                            (t2/select-one :model/GroupTableAccessPolicy
                                           :table_id table-id-1
                                           :group_id group-id)))
              ;; Created sandbox
              (is (partial= {:card_id              card-id-2
                             :attribute_remappings {"foo" 10}}
                            (t2/select-one :model/GroupTableAccessPolicy
                                           :table_id table-id-2
                                           :group_id group-id))))))))))

(deftest bulk-upsert-sandboxes-error-test
  (testing "PUT /api/permissions/graph"
    (testing "make sure an error is thrown if the :sandboxes key is included in the request, but the :sandboxes feature
             is not enabled"
      (with-redefs [premium-features/enable-sandboxes? (constantly false)]
        (mt/with-temporary-setting-values [premium-embedding-token nil]
          (mt/assert-has-premium-feature-error "Sandboxes" (mt/user-http-request :crowberto :put 402 "permissions/graph"
                                                                                 (assoc (data-perms.graph/api-graph) :sandboxes [{:card_id 1}]))))))))
