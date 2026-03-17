(ns pi.kernel.core-pure-test
  "Tests for pure functions extracted from core.cljs agent loop."
  (:require [cljs.test :refer [deftest testing is]]
            [pi.kernel.core :as core]
            [pi.kernel.agent :as k-agent]
            [pi.kernel.session :as session]
            [pi.kernel.state :as state]
            [pi.kernel.streaming :as streaming]))

;; ---------------------------------------------------------------------------
;; should-continue-turn? tests
;; ---------------------------------------------------------------------------

(deftest should-continue-turn?-has-results-and-room-test
  (testing "continues when tool results present, not stopped, under max turns"
    (is (true? (core/should-continue-turn?
                 {:tool-results [{:content "ok"}]
                  :stop? false
                  :tool-turn 0
                  :max-turns 20
                  :aborted? false})))))

(deftest should-continue-turn?-no-results-test
  (testing "stops when no tool results"
    (is (false? (core/should-continue-turn?
                  {:tool-results []
                   :stop? false
                   :tool-turn 0
                   :max-turns 20
                   :aborted? false})))))

(deftest should-continue-turn?-stopped-test
  (testing "stops when stop? is true"
    (is (false? (core/should-continue-turn?
                  {:tool-results [{:content "ok"}]
                   :stop? true
                   :tool-turn 0
                   :max-turns 20
                   :aborted? false})))))

(deftest should-continue-turn?-at-max-turns-test
  (testing "stops when at max turns"
    (is (false? (core/should-continue-turn?
                  {:tool-results [{:content "ok"}]
                   :stop? false
                   :tool-turn 20
                   :max-turns 20
                   :aborted? false})))))

(deftest should-continue-turn?-above-max-turns-test
  (testing "stops when above max turns"
    (is (false? (core/should-continue-turn?
                  {:tool-results [{:content "ok"}]
                   :stop? false
                   :tool-turn 21
                   :max-turns 20
                   :aborted? false})))))

(deftest should-continue-turn?-aborted-test
  (testing "stops when aborted"
    (is (false? (core/should-continue-turn?
                  {:tool-results [{:content "ok"}]
                   :stop? false
                   :tool-turn 0
                   :max-turns 20
                   :aborted? true})))))

(deftest should-continue-turn?-nil-results-test
  (testing "nil tool-results treated as empty"
    (is (false? (core/should-continue-turn?
                  {:tool-results nil
                   :stop? false
                   :tool-turn 0
                   :max-turns 20
                   :aborted? false})))))

;; ---------------------------------------------------------------------------
;; build-turn-context tests
;; ---------------------------------------------------------------------------

(deftest build-turn-context-minimal-test
  (testing "builds context with required fields from agent and session"
    (let [agent (k-agent/create-agent {:system-prompt "test prompt"})
          sess (session/create-session)
          agent-state (state/create-state core/default-agent-state-shape)
          event-bus (streaming/create-event-bus)
          ctx (core/build-turn-context agent sess
                {:tool-turn 0
                 :agent-state agent-state
                 :event-bus event-bus
                 :turn-id "test-turn-1"
                 :start-time 1000})]
      (is (= sess (:session ctx)))
      (is (= [] (:messages ctx)))
      (is (= "test prompt" (:system-prompt ctx)))
      (is (some? (:model-config ctx)))
      (is (some? (:tool-registry ctx)))
      (is (= agent-state (:agent-state ctx)))
      (is (= event-bus (:event-bus ctx)))
      (is (nil? (:response ctx)))
      (is (= [] (:tool-results ctx)))
      (is (false? (:skip-provider? ctx)))
      (is (false? (:stop? ctx)))
      (is (= 0 (:tool-turn ctx)))
      (is (= "test-turn-1" (:turn-id ctx)))
      (is (= 1000 (:start-time ctx)))
      (is (true? (:root-turn? ctx)))
      (streaming/close-bus! event-bus))))

(deftest build-turn-context-non-root-turn-test
  (testing "tool-turn > 0 means not root turn by default"
    (let [agent (k-agent/create-agent {})
          sess (session/create-session)
          ctx (core/build-turn-context agent sess
                {:tool-turn 3
                 :agent-state (state/create-state {})
                 :event-bus (streaming/create-event-bus)
                 :turn-id "t"
                 :start-time 0})]
      (is (false? (:root-turn? ctx)))
      (is (= 3 (:tool-turn ctx)))
      (streaming/close-bus! (:event-bus ctx)))))

(deftest build-turn-context-explicit-root-turn-test
  (testing "explicit :root-turn? overrides default"
    (let [agent (k-agent/create-agent {})
          sess (session/create-session)
          ctx (core/build-turn-context agent sess
                {:tool-turn 5
                 :root-turn? true
                 :agent-state (state/create-state {})
                 :event-bus (streaming/create-event-bus)
                 :turn-id "t"
                 :start-time 0})]
      (is (true? (:root-turn? ctx)))
      (streaming/close-bus! (:event-bus ctx)))))

(deftest build-turn-context-with-abort-signal-test
  (testing "abort-signal is included when provided"
    (let [agent (k-agent/create-agent {})
          sess (session/create-session)
          ac (js/AbortController.)
          ctx (core/build-turn-context agent sess
                {:tool-turn 0
                 :agent-state (state/create-state {})
                 :event-bus (streaming/create-event-bus)
                 :turn-id "t"
                 :start-time 0
                 :abort-signal (.-signal ac)})]
      (is (some? (:abort-signal ctx)))
      (streaming/close-bus! (:event-bus ctx)))))

(deftest build-turn-context-without-abort-signal-test
  (testing "no abort-signal key when not provided"
    (let [agent (k-agent/create-agent {})
          sess (session/create-session)
          ctx (core/build-turn-context agent sess
                {:tool-turn 0
                 :agent-state (state/create-state {})
                 :event-bus (streaming/create-event-bus)
                 :turn-id "t"
                 :start-time 0})]
      (is (not (contains? ctx :abort-signal)))
      (streaming/close-bus! (:event-bus ctx)))))

(deftest build-turn-context-with-pi-ai-model-test
  (testing "pi-ai-model is included when provided"
    (let [agent (k-agent/create-agent {})
          sess (session/create-session)
          model #js {:provider "anthropic" :id "claude-3"}
          ctx (core/build-turn-context agent sess
                {:tool-turn 0
                 :agent-state (state/create-state {})
                 :event-bus (streaming/create-event-bus)
                 :turn-id "t"
                 :start-time 0
                 :pi-ai-model model})]
      (is (= model (:pi-ai-model ctx)))
      (streaming/close-bus! (:event-bus ctx)))))

(deftest build-turn-context-max-tool-turns-from-agent-test
  (testing "max-tool-turns comes from agent config"
    (let [agent (k-agent/create-agent {:max-tool-turns 5})
          sess (session/create-session)
          ctx (core/build-turn-context agent sess
                {:tool-turn 0
                 :agent-state (state/create-state {})
                 :event-bus (streaming/create-event-bus)
                 :turn-id "t"
                 :start-time 0})]
      (is (= 5 (:max-tool-turns ctx)))
      (streaming/close-bus! (:event-bus ctx)))))
