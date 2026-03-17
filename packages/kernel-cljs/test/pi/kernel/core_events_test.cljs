(ns pi.kernel.core-events-test
  (:require [cljs.test :refer [deftest testing is async]]
            [cljs.core.async :refer [go <! timeout]]
            [pi.kernel.core :as core]
            [pi.kernel.interceptor :as interceptor]
            [pi.kernel.session :as session]
            [pi.kernel.streaming :as streaming]))

;; Helper: capture events emitted on an event bus
(defn collect-events
  "Subscribe to event-bus, collect all events into an atom. Returns the atom."
  [event-bus]
  (let [events (atom [])]
    (streaming/subscribe event-bus
      (fn [event]
        (swap! events conj event)))
    events))

;; Helper: capture stdout/stderr writes
(defn with-captured-stdout
  "Replace process.stdout.write temporarily. Returns {:calls atom, :restore fn}."
  []
  (let [calls (atom [])
        original-write (.-write js/process.stdout)]
    (set! (.-write js/process.stdout)
      (fn [data & _args]
        (swap! calls conj (str data))
        true))
    {:calls calls
     :restore (fn [] (set! (.-write js/process.stdout) original-write))}))

(defn with-captured-stderr
  "Replace process.stderr.write temporarily. Returns {:calls atom, :restore fn}."
  []
  (let [calls (atom [])
        original-write (.-write js/process.stderr)]
    (set! (.-write js/process.stderr)
      (fn [data & _args]
        (swap! calls conj (str data))
        true))
    {:calls calls
     :restore (fn [] (set! (.-write js/process.stderr) original-write))}))

;; Test 1: provider interceptor emits :text events on event bus (no stdout)
(deftest provider-emits-text-events-test
  (async done
    (go
      (let [sess (session/create-session)
            event-bus (streaming/create-event-bus)
            config {:api "mock" :provider :default}
            _ (session/append-entry! sess
                {:entry/type :user-message
                 :entry/data {:content "hello"}})
            events (collect-events event-bus)
            stdout-capture (with-captured-stdout)
            ctx {:session       sess
                 :messages      (core/build-messages sess)
                 :system-prompt core/system-prompt
                 :model-config  config
                 :tool-registry core/tool-registry
                 :event-bus     event-bus
                 :tool-turn     0
                 :max-tool-turns 20
                 :skip-provider? false
                 :stop?         false
                 :turn-id       (str (random-uuid))
                 :start-time    (js/Date.now)}
            chain (interceptor/create-chain core/default-interceptors)
            result (<! (interceptor/execute-async chain ctx))]
        ;; Wait for events to propagate
        (<! (timeout 50))
        ;; Should have :text events on the bus
        (let [text-events (filter #(= :text (:type %)) @events)]
          (is (pos? (count text-events)) "Expected :text events on event bus"))
        ;; Should NOT have written to stdout from interceptors
        (let [stdout-writes @(:calls stdout-capture)]
          (is (empty? stdout-writes)
            (str "Expected no stdout writes from interceptors, got: " stdout-writes)))
        ((:restore stdout-capture))
        (streaming/close-bus! event-bus))
      (done))))

;; Test 2: tool-execution emits :tool-result events on event bus
(deftest tool-execution-emits-events-test
  (async done
    (go
      (let [sess (session/create-session)
            event-bus (streaming/create-event-bus)
            config {:api "mock-tools" :provider :default}
            _ (session/append-entry! sess
                {:entry/type :user-message
                 :entry/data {:content "read a file"}})
            events (collect-events event-bus)
            stdout-capture (with-captured-stdout)]
        (<! (core/run-agent-turn sess config {:event-bus event-bus}))
        (<! (timeout 50))
        ;; Should have :tool-result events on the bus
        (let [tool-events (filter #(= :tool-result (:type %)) @events)]
          (is (pos? (count tool-events)) "Expected :tool-result events on event bus")
          (when (seq tool-events)
            (is (string? (:name (first tool-events))) "Tool event should have :name")
            (is (some? (:content (first tool-events))) "Tool event should have :content")))
        ;; No direct stdout from tool execution
        (let [stdout-writes @(:calls stdout-capture)]
          (is (empty? stdout-writes)
            (str "Expected no stdout writes, got: " stdout-writes)))
        ((:restore stdout-capture))
        (streaming/close-bus! event-bus))
      (done))))

;; Test 3: max-tool-turns emits :warning event (not stderr)
(deftest max-tool-turns-emits-warning-test
  (async done
    (go
      (let [sess (session/create-session)
            event-bus (streaming/create-event-bus)
            config {:api "mock-tools" :provider :default}
            _ (session/append-entry! sess
                {:entry/type :user-message
                 :entry/data {:content "read a file"}})
            events (collect-events event-bus)
            stderr-capture (with-captured-stderr)]
        (binding [core/*max-tool-turns* 0]
          (<! (core/run-agent-turn sess config {:event-bus event-bus})))
        (<! (timeout 50))
        ;; Should have :warning event on the bus
        (let [warning-events (filter #(= :warning (:type %)) @events)]
          (is (pos? (count warning-events)) "Expected :warning events on event bus"))
        ;; Should NOT have written to stderr
        (let [stderr-writes @(:calls stderr-capture)]
          (is (empty? stderr-writes)
            (str "Expected no stderr writes, got: " stderr-writes)))
        ((:restore stderr-capture))
        (streaming/close-bus! event-bus))
      (done))))

;; Test 4: provider error emits :error event on bus
(deftest provider-error-emits-error-event-test
  (async done
    (go
      (let [sess (session/create-session)
            event-bus (streaming/create-event-bus)
            config {:api "mock-error" :provider :default}
            _ (session/append-entry! sess
                {:entry/type :user-message
                 :entry/data {:content "trigger error"}})
            events (collect-events event-bus)
            stderr-capture (with-captured-stderr)
            ctx {:session       sess
                 :messages      (core/build-messages sess)
                 :system-prompt core/system-prompt
                 :model-config  config
                 :tool-registry core/tool-registry
                 :event-bus     event-bus
                 :tool-turn     0
                 :max-tool-turns 20
                 :skip-provider? false
                 :stop?         false
                 :turn-id       (str (random-uuid))
                 :start-time    (js/Date.now)}
            chain (interceptor/create-chain core/default-interceptors)
            result (<! (interceptor/execute-async chain ctx))]
        (<! (timeout 50))
        ;; Should have :error event on the bus
        (let [error-events (filter #(= :error (:type %)) @events)]
          (is (pos? (count error-events)) "Expected :error events on event bus"))
        ;; Should NOT have written to stderr
        (let [stderr-writes @(:calls stderr-capture)]
          (is (empty? stderr-writes)
            (str "Expected no stderr writes from interceptor, got: " stderr-writes)))
        ((:restore stderr-capture))
        (streaming/close-bus! event-bus))
      (done))))

;; Test 5: agent loop is silent when no adapter connected
(deftest agent-loop-silent-without-subscriber-test
  (async done
    (go
      (let [sess (session/create-session)
            config {:api "mock" :provider :default}
            _ (session/append-entry! sess
                {:entry/type :user-message
                 :entry/data {:content "hello"}})
            stdout-capture (with-captured-stdout)
            stderr-capture (with-captured-stderr)]
        ;; Run without providing an event bus — should create one internally
        ;; but with no subscribers, nothing should go to stdout/stderr
        (<! (core/run-agent-turn sess config))
        (<! (timeout 50))
        (let [stdout-writes @(:calls stdout-capture)
              stderr-writes @(:calls stderr-capture)]
          (is (empty? stdout-writes)
            (str "Expected no stdout writes without subscriber, got: " stdout-writes))
          (is (empty? stderr-writes)
            (str "Expected no stderr writes without subscriber, got: " stderr-writes)))
        ((:restore stdout-capture))
        ((:restore stderr-capture)))
      (done))))
