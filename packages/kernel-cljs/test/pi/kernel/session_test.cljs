(ns pi.kernel.session-test
  (:require [cljs.test :refer [deftest testing is]]
            [datascript.core :as d]
            [pi.kernel.session :as session]))

(deftest create-and-query-user-message-test
  (testing "Create session, append a user message, query it back"
    (let [sess (session/create-session)]
      (session/append-entry! sess {:entry/type :user-message
                                   :entry/timestamp 1000
                                   :entry/data "Hello agent"})
      (let [results (d/q '[:find ?data
                            :where
                            [?e :entry/type :user-message]
                            [?e :entry/data ?data]]
                         @(:conn sess))]
        (is (= #{["Hello agent"]} results))))))

(deftest query-tool-errors-test
  (testing "Append 3 tool-calls and 3 tool-results (1 error), query errors"
    (let [sess (session/create-session)
          now 10000]
      ;; 3 tool calls
      (session/append-entry! sess {:entry/type :tool-call
                                   :entry/timestamp now
                                   :tool-call/name "read"
                                   :tool-call/args "{\"path\": \"foo.txt\"}"})
      (session/append-entry! sess {:entry/type :tool-call
                                   :entry/timestamp (+ now 1)
                                   :tool-call/name "write"
                                   :tool-call/args "{\"path\": \"bar.txt\"}"})
      (session/append-entry! sess {:entry/type :tool-call
                                   :entry/timestamp (+ now 2)
                                   :tool-call/name "edit"
                                   :tool-call/args "{\"path\": \"baz.txt\"}"})
      ;; 3 tool results, 1 with error
      (session/append-entry! sess {:entry/type :tool-result
                                   :entry/timestamp (+ now 10)
                                   :tool-call/name "read"
                                   :tool-result/error? false
                                   :tool-result/content "file contents"})
      (session/append-entry! sess {:entry/type :tool-result
                                   :entry/timestamp (+ now 11)
                                   :tool-call/name "write"
                                   :tool-result/error? true
                                   :tool-result/content "permission denied"})
      (session/append-entry! sess {:entry/type :tool-result
                                   :entry/timestamp (+ now 12)
                                   :tool-call/name "edit"
                                   :tool-result/error? false
                                   :tool-result/content "ok"})
      (let [errors (session/query-errors @(:conn sess) now)]
        (is (= #{"write"} errors))))))

(deftest query-tool-error-details-test
  (testing "Returns error details with name and timestamp"
    (let [sess (session/create-session)
          now 10000]
      (session/append-entry! sess {:entry/type :tool-result
                                   :entry/timestamp (+ now 10)
                                   :tool-call/name "read"
                                   :tool-result/error? false
                                   :tool-result/content "ok"})
      (session/append-entry! sess {:entry/type :tool-result
                                   :entry/timestamp (+ now 20)
                                   :tool-call/name "write"
                                   :tool-result/error? true
                                   :tool-result/content "permission denied"})
      (session/append-entry! sess {:entry/type :tool-result
                                   :entry/timestamp (+ now 30)
                                   :tool-call/name "exec"
                                   :tool-result/error? true
                                   :tool-result/content "timeout"})
      (let [details (session/query-tool-error-details @(:conn sess) now)]
        (is (= 2 (count details)))
        (is (= #{"write" "exec"} (set (map :tool-call/name details))))
        (is (every? :entry/timestamp details))))))

(deftest query-entries-since-test
  (testing "Append entries with timestamps, query recent entries"
    (let [sess (session/create-session)]
      ;; Old entries
      (session/append-entry! sess {:entry/type :user-message
                                   :entry/timestamp 1000
                                   :entry/data "old message"})
      ;; Recent entries (within "last 5 minutes" = since 5000)
      (session/append-entry! sess {:entry/type :user-message
                                   :entry/timestamp 6000
                                   :entry/data "recent 1"})
      (session/append-entry! sess {:entry/type :tool-call
                                   :entry/timestamp 7000
                                   :tool-call/name "read"})
      (session/append-entry! sess {:entry/type :user-message
                                   :entry/timestamp 8000
                                   :entry/data "recent 2"})
      (let [recent (session/query-entries-since @(:conn sess) 5000)]
        (is (= 3 (count recent)))
        ;; Old entry excluded
        (is (every? #(>= (:entry/timestamp %) 5000) recent))))))

(deftest query-turn-count-test
  (testing "Count distinct user messages as turns"
    (let [sess (session/create-session)]
      (session/append-entry! sess {:entry/type :user-message
                                   :entry/timestamp 1000
                                   :entry/data "msg 1"})
      (session/append-entry! sess {:entry/type :tool-call
                                   :entry/timestamp 2000
                                   :tool-call/name "read"})
      (session/append-entry! sess {:entry/type :user-message
                                   :entry/timestamp 3000
                                   :entry/data "msg 2"})
      (session/append-entry! sess {:entry/type :user-message
                                   :entry/timestamp 4000
                                   :entry/data "msg 3"})
      (is (= 3 (session/query-turn-count @(:conn sess)))))))

(deftest query-edited-files-test
  (testing "Query which files were edited"
    (let [sess (session/create-session)]
      (session/append-entry! sess {:entry/type :tool-call
                                   :entry/timestamp 1000
                                   :tool-call/name "edit"
                                   :tool-call/path "src/core.cljs"})
      (session/append-entry! sess {:entry/type :tool-call
                                   :entry/timestamp 2000
                                   :tool-call/name "read"
                                   :tool-call/path "README.md"})
      (session/append-entry! sess {:entry/type :tool-call
                                   :entry/timestamp 3000
                                   :tool-call/name "edit"
                                   :tool-call/path "src/utils.cljs"})
      (session/append-entry! sess {:entry/type :tool-call
                                   :entry/timestamp 4000
                                   :tool-call/name "edit"
                                   :tool-call/path "src/core.cljs"})
      (let [files (session/query-edited-files @(:conn sess))]
        (is (= #{"src/core.cljs" "src/utils.cljs"} files))))))

(deftest injectable-clock-test
  (testing "Session uses injected clock-fn for default timestamps"
    (let [counter (atom 100)
          clock-fn #(swap! counter + 10)
          sess (session/create-session {:clock-fn clock-fn})]
      ;; Append without explicit timestamp — should use clock-fn
      (session/append-entry! sess {:entry/type :user-message
                                   :entry/data "msg 1"})
      (session/append-entry! sess {:entry/type :user-message
                                   :entry/data "msg 2"})
      (let [entries (session/query-entries-since @(:conn sess) 0)]
        (is (= 2 (count entries)))
        ;; Timestamps should be 110 and 120 (atom starts at 100, increments by 10)
        (is (= #{110 120} (set (map :entry/timestamp entries))))))))

(deftest query-all-entries-test
  (testing "Returns all entries sorted by timestamp"
    (let [sess (session/create-session)]
      (session/append-entry! sess {:entry/type :user-message
                                   :entry/timestamp 3000
                                   :entry/data {:content "third"}})
      (session/append-entry! sess {:entry/type :assistant-message
                                   :entry/timestamp 1000
                                   :entry/data {:content "first"}})
      (session/append-entry! sess {:entry/type :tool-result
                                   :entry/timestamp 2000
                                   :tool-call/id "t1"
                                   :tool-call/name "bash"
                                   :tool-result/content "second"
                                   :tool-result/error? false})
      (let [entries (session/query-all-entries @(:conn sess))]
        (is (= 3 (count entries)))
        ;; Sorted by timestamp ascending
        (is (= [1000 2000 3000] (mapv :entry/timestamp entries)))
        ;; All entries have full data pulled
        (is (= :assistant-message (:entry/type (first entries))))
        (is (= :tool-result (:entry/type (second entries))))
        (is (= :user-message (:entry/type (nth entries 2)))))))

  (testing "Returns empty vec for empty session"
    (let [sess (session/create-session)]
      (is (= [] (vec (session/query-all-entries @(:conn sess))))))))
