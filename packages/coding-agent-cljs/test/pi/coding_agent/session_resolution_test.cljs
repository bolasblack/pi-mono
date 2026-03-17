(ns pi.coding-agent.session-resolution-test
  "Tests for session resolution logic extracted from core."
  (:require [cljs.test :refer [deftest testing is]]
            [pi.coding-agent.session-resolution :as sr]))

;; --- validate-fork tests ---

(deftest validate-fork-no-fork-test
  (testing "returns nil when fork is not set"
    (is (nil? (sr/validate-fork-flags
                {:fork false :session nil :continue false
                 :resume false :no-session false})))))

(deftest validate-fork-no-conflicts-test
  (testing "returns nil when fork is set with no conflicts"
    (is (nil? (sr/validate-fork-flags
                {:fork true :session nil :continue false
                 :resume false :no-session false})))))

(deftest validate-fork-session-conflict-test
  (testing "returns error when fork conflicts with --session"
    (let [result (sr/validate-fork-flags
                   {:fork true :session "my-session"
                    :continue false :resume false :no-session false})]
      (is (some? result))
      (is (re-find #"--session" result)))))

(deftest validate-fork-continue-conflict-test
  (testing "returns error when fork conflicts with --continue"
    (let [result (sr/validate-fork-flags
                   {:fork true :session nil :continue true
                    :resume false :no-session false})]
      (is (some? result))
      (is (re-find #"--continue" result)))))

(deftest validate-fork-resume-conflict-test
  (testing "returns error when fork conflicts with --resume"
    (let [result (sr/validate-fork-flags
                   {:fork true :session nil :continue false
                    :resume true :no-session false})]
      (is (some? result))
      (is (re-find #"--resume" result)))))

(deftest validate-fork-no-session-conflict-test
  (testing "returns error when fork conflicts with --no-session"
    (let [result (sr/validate-fork-flags
                   {:fork true :session nil :continue false
                    :resume false :no-session true})]
      (is (some? result))
      (is (re-find #"--no-session" result)))))

(deftest validate-fork-multiple-conflicts-test
  (testing "returns error listing all conflicting flags"
    (let [result (sr/validate-fork-flags
                   {:fork true :session "x" :continue true
                    :resume false :no-session true})]
      (is (some? result))
      (is (re-find #"--session" result))
      (is (re-find #"--continue" result))
      (is (re-find #"--no-session" result)))))

;; --- resolve-session-type tests ---

(deftest resolve-session-type-path-like-slash-test
  (testing "path with / is classified as :path"
    (is (= :path (sr/resolve-session-type "./sessions/test.jsonl")))))

(deftest resolve-session-type-path-like-backslash-test
  (testing "path with \\ is classified as :path"
    (is (= :path (sr/resolve-session-type "sessions\\test")))))

(deftest resolve-session-type-path-like-jsonl-test
  (testing "path ending in .jsonl is classified as :path"
    (is (= :path (sr/resolve-session-type "my-session.jsonl")))))

(deftest resolve-session-type-id-test
  (testing "plain string is classified as :id"
    (is (= :id (sr/resolve-session-type "my-session")))))

;; --- session-mode-dispatch tests ---

(deftest session-mode-dispatch-no-session-test
  (testing "no-session flag returns :in-memory"
    (is (= :in-memory
           (sr/session-mode-dispatch
             {:no-session true :session nil
              :session-mode nil :continue false})))))

(deftest session-mode-dispatch-session-with-create-test
  (testing "session + create mode returns :create"
    (is (= :create
           (sr/session-mode-dispatch
             {:no-session false :session "foo"
              :session-mode "create" :continue false})))))

(deftest session-mode-dispatch-session-with-continue-mode-test
  (testing "session + continue mode returns :open"
    (is (= :open
           (sr/session-mode-dispatch
             {:no-session false :session "foo"
              :session-mode "continue" :continue false})))))

(deftest session-mode-dispatch-session-with-auto-mode-test
  (testing "session + auto mode returns :auto"
    (is (= :auto
           (sr/session-mode-dispatch
             {:no-session false :session "foo"
              :session-mode "auto" :continue false})))))

(deftest session-mode-dispatch-session-without-mode-test
  (testing "session without mode returns :lookup"
    (is (= :lookup
           (sr/session-mode-dispatch
             {:no-session false :session "foo"
              :session-mode nil :continue false})))))

(deftest session-mode-dispatch-continue-test
  (testing "continue flag returns :continue-recent"
    (is (= :continue-recent
           (sr/session-mode-dispatch
             {:no-session false :session nil
              :session-mode nil :continue true})))))

(deftest session-mode-dispatch-default-test
  (testing "no flags returns :default"
    (is (= :default
           (sr/session-mode-dispatch
             {:no-session false :session nil
              :session-mode nil :continue false})))))

;; --- format-scoped-model-name tests ---

(deftest format-scoped-model-name-basic-test
  (testing "formats model id without thinking level"
    (is (= "claude-sonnet-4"
           (sr/format-scoped-model-name
             #js {:model #js {:id "claude-sonnet-4"}
                  :thinkingLevel nil})))))

(deftest format-scoped-model-name-with-thinking-test
  (testing "appends thinking level suffix when present"
    (is (= "claude-sonnet-4:high"
           (sr/format-scoped-model-name
             #js {:model #js {:id "claude-sonnet-4"}
                  :thinkingLevel "high"})))))

(deftest format-scoped-model-name-empty-thinking-test
  (testing "appends colon+empty when thinkingLevel is empty string"
    (is (= "gpt-4o:"
           (sr/format-scoped-model-name
             #js {:model #js {:id "gpt-4o"}
                  :thinkingLevel ""})))))
