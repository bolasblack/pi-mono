(ns pi.kernel.persistence-data-test
  (:require [cljs.test :refer [deftest is testing use-fixtures]]
            [pi.kernel.persistence :as persist]
            [pi.kernel.session :as session]
            [pi.kernel.state :as state]
            [datascript.core :as d]))

(def ^:dynamic *session* nil)

(use-fixtures :each
  {:before (fn []
             (set! *session* (session/create-session {:clock-fn (let [counter (atom 0)]
                                                                  #(swap! counter inc))})))
   :after  (fn [] (set! *session* nil))})

;; ============================================================================
;; session->entities (pure data, no EDN strings)
;; ============================================================================

(deftest session->entities-returns-vector-test
  (testing "session->entities returns a vector of entity maps"
    (session/append-entry! *session* {:entry/type :user-message :entry/data "hello"})
    (let [entities (persist/session->entities @(:conn *session*))]
      (is (vector? entities))
      (is (pos? (count entities)))
      (is (every? map? entities)))))

(deftest session->entities-preserves-data-test
  (testing "session->entities preserves entry types and data"
    (session/append-entry! *session* {:entry/type :user-message :entry/data "msg1"})
    (session/append-entry! *session* {:entry/type :tool-call :tool-call/name "edit" :tool-call/path "/a.txt"})
    (let [entities (persist/session->entities @(:conn *session*))
          types (set (map :entry/type entities))]
      (is (contains? types :user-message))
      (is (contains? types :tool-call)))))

;; ============================================================================
;; entities->conn (pure data, no EDN strings)
;; ============================================================================

(deftest entities->conn-roundtrip-test
  (testing "entities->conn hydrates entities back to a queryable DataScript conn"
    (session/append-entry! *session* {:entry/type :user-message :entry/data "hi"})
    (session/append-entry! *session* {:entry/type :tool-call :tool-call/name "edit" :tool-call/path "/b.txt"})
    (let [entities (persist/session->entities @(:conn *session*))
          restored-conn (persist/entities->conn entities)
          restored-db @restored-conn]
      (is (= 1 (session/query-turn-count restored-db)))
      (is (= #{"/b.txt"} (session/query-edited-files restored-db))))))

;; ============================================================================
;; full-session roundtrip uses data path (no double serialization)
;; ============================================================================

(deftest full-session-data-roundtrip-test
  (testing "serialize/deserialize full session avoids double EDN roundtrip"
    (session/append-entry! *session* {:entry/type :user-message :entry/data "test"})
    (session/append-entry! *session* {:entry/type :tool-call :tool-call/name "edit" :tool-call/path "/c.txt"})
    (let [agent-state (state/create-state {:tool-modifications {"edit" {:safe true}}
                                           :interceptor-config {:retries 2}
                                           :learned-patterns ["p1"]
                                           :turn-metrics [{:turn 1}]})
          db @(:conn *session*)
          edn-str (persist/serialize-full-session db agent-state)
          result (persist/deserialize-full-session edn-str)
          restored-db @(:conn result)
          restored-state (state/get-state (:agent-state result))]
      (is (= 1 (session/query-turn-count restored-db)))
      (is (= #{"/c.txt"} (session/query-edited-files restored-db)))
      (is (= {"edit" {:safe true}} (:tool-modifications restored-state)))
      (is (= ["p1"] (:learned-patterns restored-state))))))

(deftest session->entities-is-serializable-test
  (testing "session->entities output is pr-str-able and read-string-able"
    (session/append-entry! *session* {:entry/type :user-message :entry/data "check"})
    (let [entities (persist/session->entities @(:conn *session*))
          roundtripped (cljs.reader/read-string (pr-str entities))]
      (is (= entities roundtripped)))))
