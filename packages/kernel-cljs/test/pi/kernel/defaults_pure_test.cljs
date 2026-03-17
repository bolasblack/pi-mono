(ns pi.kernel.defaults-pure-test
  "Tests for pure functions in interceptors/defaults.
   Note: build-accumulated-message and build-finalization-result tests
   have moved to pi.kernel.stream-handler-test."
  (:require [cljs.test :refer [deftest testing is]]
            [pi.kernel.interceptors.defaults :as defaults]))

;; --- entry->message tests ---

(deftest entry->message-user-test
  (testing "user-message entry converts to user message"
    (let [entry {:entry/type :user-message
                 :entry/data {:content "hello"}}]
      (is (= {:role "user" :content "hello"}
             (defaults/entry->message entry))))))

(deftest entry->message-assistant-text-only-test
  (testing "assistant-message without tool calls"
    (let [entry {:entry/type :assistant-message
                 :entry/data {:content "response" :tool-calls []}}]
      (is (= {:role "assistant" :content "response"}
             (defaults/entry->message entry))))))

(deftest entry->message-assistant-with-tools-test
  (testing "assistant-message with tool calls includes them"
    (let [tc [{:id "t1" :name "bash" :arguments {:command "ls"}}]
          entry {:entry/type :assistant-message
                 :entry/data {:content "let me check" :tool-calls tc}}]
      (is (= {:role "assistant" :content "let me check" :tool-calls tc}
             (defaults/entry->message entry))))))

(deftest entry->message-tool-result-test
  (testing "tool-result entry converts correctly"
    (let [entry {:entry/type :tool-result
                 :tool-call/id "t1"
                 :tool-call/name "bash"
                 :tool-result/content "output"
                 :tool-result/error? false}]
      (is (= {:role "tool-result"
              :tool-call-id "t1"
              :tool-name "bash"
              :content "output"
              :is-error false}
             (defaults/entry->message entry))))))

(deftest entry->message-unknown-type-test
  (testing "unknown entry type returns nil"
    (is (nil? (defaults/entry->message {:entry/type :something-else})))))

;; --- tool-call->result-entry tests ---

(deftest tool-call->result-entry-success-test
  (testing "successful tool result builds correct entry"
    (let [tc {:id "t1" :name "read_file" :arguments {:path "x.txt"}}
          result {:content "file contents"}]
      (is (= {:entry/type :tool-result
              :tool-call/id "t1"
              :tool-call/name "read_file"
              :tool-result/content "file contents"
              :tool-result/error? false}
             (defaults/tool-call->result-entry tc result))))))

(deftest tool-call->result-entry-error-test
  (testing "error tool result sets error flag and uses error as content"
    (let [tc {:id "t2" :name "bash" :arguments {:command "fail"}}
          result {:error "command failed"}]
      (is (= {:entry/type :tool-result
              :tool-call/id "t2"
              :tool-call/name "bash"
              :tool-result/content "command failed"
              :tool-result/error? true}
             (defaults/tool-call->result-entry tc result))))))

(deftest tool-call->result-entry-empty-result-test
  (testing "empty result defaults to empty string"
    (let [tc {:id "t3" :name "write_file" :arguments {}}
          result {}]
      (is (= {:entry/type :tool-result
              :tool-call/id "t3"
              :tool-call/name "write_file"
              :tool-result/content ""
              :tool-result/error? false}
             (defaults/tool-call->result-entry tc result))))))

;; --- max-turns-exceeded? tests ---

(deftest max-turns-exceeded-test
  (testing "returns true when at or above max"
    (is (true? (defaults/max-turns-exceeded? 5 5)))
    (is (true? (defaults/max-turns-exceeded? 6 5))))
  (testing "returns false when below max"
    (is (false? (defaults/max-turns-exceeded? 4 5)))
    (is (false? (defaults/max-turns-exceeded? 0 20)))))

;; --- backward-compat re-exports from stream-handler ---

(deftest build-accumulated-message-reexport-test
  (testing "defaults/build-accumulated-message still works via re-export"
    (let [msg (defaults/build-accumulated-message
                "hi" [] "" {:model-id "m" :api "a"} "stop" {})]
      (is (= "assistant" (:role msg)))
      (is (= 1 (count (:content msg)))))))

(deftest build-finalization-result-reexport-test
  (testing "defaults/build-finalization-result still works via re-export"
    (let [result (defaults/build-finalization-result "text" [] {} nil)]
      (is (= "text" (get-in result [:response :text]))))))
