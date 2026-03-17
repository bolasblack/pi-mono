(ns pi.kernel.persistence-test
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

(deftest test-serialize-deserialize-roundtrip
  (testing "serialize-session + deserialize-session roundtrip preserves data"
    (session/append-entry! *session* {:entry/type :user-message :entry/data "hello"})
    (session/append-entry! *session* {:entry/type :tool-call :tool-call/name "edit" :tool-call/path "/foo.txt"})
    (let [db @(:conn *session*)
          edn-str (persist/serialize-session db)
          restored-conn (persist/deserialize-session edn-str)
          restored-db @restored-conn]
      (is (string? edn-str))
      (is (= (session/query-turn-count db)
             (session/query-turn-count restored-db)))
      (is (= (session/query-edited-files db)
             (session/query-edited-files restored-db))))))

(deftest test-create-persistence-handler
  (testing "create-persistence-handler accepts injected IO fns"
    (let [store (atom {})
          handler (persist/create-persistence-handler
                   {:write-fn (fn [key data] (swap! store assoc key data))
                    :read-fn (fn [key] (get @store key))})]
      (is (some? handler))
      (is (fn? (:write-fn handler)))
      (is (fn? (:read-fn handler))))))

(deftest test-save-session-calls-write-fn
  (testing "save-session! calls write-fn with serialized data"
    (let [written (atom nil)
          handler (persist/create-persistence-handler
                   {:write-fn (fn [key data] (reset! written {:key key :data data}))
                    :read-fn (fn [_] nil)})]
      (session/append-entry! *session* {:entry/type :user-message :entry/data "test"})
      (persist/save-session! handler "sess-1" @(:conn *session*))
      (is (= "sess-1" (:key @written)))
      (is (string? (:data @written))))))

(deftest test-load-session-calls-read-fn
  (testing "load-session calls read-fn and returns hydrated session"
    (session/append-entry! *session* {:entry/type :user-message :entry/data "test"})
    (let [db @(:conn *session*)
          edn-str (persist/serialize-session db)
          handler (persist/create-persistence-handler
                   {:write-fn (fn [_ _] nil)
                    :read-fn (fn [_key] edn-str)})
          restored-conn (persist/load-session handler "sess-1")]
      (is (some? restored-conn))
      (is (= 1 (session/query-turn-count @restored-conn))))))

(deftest test-save-load-roundtrip-preserves-queryable-data
  (testing "Roundtrip through save + load preserves queryable session data"
    (let [store (atom {})
          handler (persist/create-persistence-handler
                   {:write-fn (fn [key data] (swap! store assoc key data))
                    :read-fn (fn [key] (get @store key))})]
      (session/append-entry! *session* {:entry/type :user-message :entry/data "hi"})
      (session/append-entry! *session* {:entry/type :tool-call
                                        :tool-call/name "edit"
                                        :tool-call/path "/a.txt"})
      (session/append-entry! *session* {:entry/type :tool-result
                                        :tool-call/name "read"
                                        :tool-result/error? true
                                        :entry/timestamp 500})
      (persist/save-session! handler "s1" @(:conn *session*))
      (let [restored-conn (persist/load-session handler "s1")
            restored-db @restored-conn]
        (is (= 1 (session/query-turn-count restored-db)))
        (is (= #{"read"} (session/query-errors restored-db 0)))
        (is (= #{"/a.txt"} (session/query-edited-files restored-db)))))))

;; --- Full session (DataScript + agent state) tests ---

(deftest test-serialize-full-session-includes-both
  (testing "serialize-full-session includes DataScript data and agent state"
    (session/append-entry! *session* {:entry/type :user-message :entry/data "hello"})
    (let [agent-state (state/create-state {:tool-modifications {"edit" {:enabled true}}
                                           :learned-patterns ["pattern-1"]})
          db @(:conn *session*)
          edn-str (persist/serialize-full-session db agent-state)
          parsed (cljs.reader/read-string edn-str)]
      (is (string? edn-str))
      (is (= 2 (:version parsed)))
      (is (some? (:session-data parsed)))
      (is (some? (:agent-state parsed)))
      (is (= {"edit" {:enabled true}} (get-in parsed [:agent-state :tool-modifications])))
      (is (= ["pattern-1"] (get-in parsed [:agent-state :learned-patterns]))))))

(deftest test-deserialize-full-session-restores-both
  (testing "deserialize-full-session restores DataScript and agent state"
    (session/append-entry! *session* {:entry/type :user-message :entry/data "hi"})
    (session/append-entry! *session* {:entry/type :tool-call :tool-call/name "edit" :tool-call/path "/b.txt"})
    (let [agent-state (state/create-state {:tool-modifications {"read" {:count 3}}
                                           :interceptor-config {}
                                           :learned-patterns []
                                           :turn-metrics []})
          db @(:conn *session*)
          edn-str (persist/serialize-full-session db agent-state)
          result (persist/deserialize-full-session edn-str)]
      (is (some? (:conn result)))
      (is (some? (:agent-state result)))
      (let [restored-db @(:conn result)]
        (is (= 1 (session/query-turn-count restored-db)))
        (is (= #{"/b.txt"} (session/query-edited-files restored-db))))
      (is (= {"read" {:count 3}} (:tool-modifications (state/get-state (:agent-state result))))))))

(deftest test-roundtrip-tool-modifications
  (testing "round-trip preserves tool modifications in state"
    (session/append-entry! *session* {:entry/type :user-message :entry/data "test"})
    (let [mods {"edit" {:wrapper-fn "safe-edit" :max-lines 100}
                "bash" {:blocked true}}
          agent-state (state/create-state {:tool-modifications mods
                                           :interceptor-config {}
                                           :learned-patterns []
                                           :turn-metrics []})
          db @(:conn *session*)
          edn-str (persist/serialize-full-session db agent-state)
          result (persist/deserialize-full-session edn-str)
          restored-state (state/get-state (:agent-state result))]
      (is (= mods (:tool-modifications restored-state))))))

(deftest test-roundtrip-learned-patterns
  (testing "round-trip preserves learned patterns in state"
    (session/append-entry! *session* {:entry/type :user-message :entry/data "test"})
    (let [patterns [{:pattern "prefer-edit" :confidence 0.9}
                    {:pattern "check-before-write" :confidence 0.7}]
          agent-state (state/create-state {:tool-modifications {}
                                           :interceptor-config {}
                                           :learned-patterns patterns
                                           :turn-metrics []})
          db @(:conn *session*)
          edn-str (persist/serialize-full-session db agent-state)
          result (persist/deserialize-full-session edn-str)
          restored-state (state/get-state (:agent-state result))]
      (is (= patterns (:learned-patterns restored-state))))))

(deftest test-save-load-full-session-roundtrip
  (testing "save-full-session! + load-full-session roundtrip"
    (let [store (atom {})
          handler (persist/create-persistence-handler
                   {:write-fn (fn [key data] (swap! store assoc key data))
                    :read-fn (fn [key] (get @store key))})
          agent-state (state/create-state {:tool-modifications {"edit" {:safe true}}
                                           :interceptor-config {:max-retries 3}
                                           :learned-patterns ["p1"]
                                           :turn-metrics [{:turn 1 :tokens 500}]})]
      (session/append-entry! *session* {:entry/type :user-message :entry/data "hi"})
      (session/append-entry! *session* {:entry/type :tool-call :tool-call/name "edit" :tool-call/path "/c.txt"})
      (persist/save-full-session! handler "full-1" @(:conn *session*) agent-state)
      (let [result (persist/load-full-session handler "full-1")]
        (is (some? result))
        (let [restored-db @(:conn result)
              restored-state (state/get-state (:agent-state result))]
          (is (= 1 (session/query-turn-count restored-db)))
          (is (= #{"/c.txt"} (session/query-edited-files restored-db)))
          (is (= {"edit" {:safe true}} (:tool-modifications restored-state)))
          (is (= {:max-retries 3} (:interceptor-config restored-state)))
          (is (= ["p1"] (:learned-patterns restored-state))))))))

(deftest test-backward-compat-v1-format
  (testing "deserialize-full-session handles old v1 DataScript-only format"
    (session/append-entry! *session* {:entry/type :user-message :entry/data "old"})
    (let [db @(:conn *session*)
          ;; v1 format: just the entity vector EDN, no wrapper
          v1-edn (persist/serialize-session db)
          result (persist/deserialize-full-session v1-edn)]
      (is (some? (:conn result)))
      (is (some? (:agent-state result)))
      (let [restored-db @(:conn result)
            restored-state (state/get-state (:agent-state result))]
        (is (= 1 (session/query-turn-count restored-db)))
        ;; Should have default empty state
        (is (= {} (:tool-modifications restored-state)))
        (is (= {} (:interceptor-config restored-state)))
        (is (= [] (:learned-patterns restored-state)))
        (is (= [] (:turn-metrics restored-state)))))))
