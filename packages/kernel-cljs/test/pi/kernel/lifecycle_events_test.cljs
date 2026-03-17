(ns pi.kernel.lifecycle-events-test
  "Tests for Phase 1 lifecycle events.
   Tests the interceptor-level event protocol WITHOUT importing core.cljs
   (which transitively requires pi-ai via provider.cljs).
   Instead, we construct interceptor chains directly with mock enter/leave fns."
  (:require [cljs.test :refer [deftest testing is async]]
            [cljs.core.async :refer [go <! chan put! close! timeout]]
            [pi.kernel.interceptor :as interceptor]
            [pi.kernel.session :as session]
            [pi.kernel.streaming :as streaming]))

;; --- Helpers ---

(defn collect-events
  "Subscribe to event-bus, collect all events into an atom."
  [event-bus]
  (let [events (atom [])]
    (streaming/subscribe event-bus
      (fn [event]
        (swap! events conj event)))
    events))

(defn events-of-type [events type]
  (filterv #(= type (:type %)) @events))

;; --- Mock interceptors that simulate provider-enter + tool-execution-leave ---

(defn mock-provider-enter
  "Simulates provider-enter: emits :message-start, :message-update, :message-end.
   Returns ctx with :response containing text and optional tool-calls."
  [response-text tool-calls]
  (fn [ctx]
    (let [result-ch (chan 1)
          event-bus (:event-bus ctx)
          msg {:role "assistant"
               :content [{:type :text :text response-text}]
               :stop-reason "stop"
               :usage {}
               :model "mock" :provider "mock" :api "mock"
               :timestamp (.now js/Date)}]
      (go
        ;; message-start
        (when event-bus
          (streaming/emit! event-bus {:type :message-start :message msg :role "assistant"}))
        (<! (timeout 5))
        ;; message-update (simulate streaming)
        (when event-bus
          (streaming/emit! event-bus {:type :message-update :message msg}))
        (<! (timeout 5))
        ;; message-end
        (when event-bus
          (streaming/emit! event-bus {:type :message-end :message msg}))
        (put! result-ch
          (assoc ctx :response {:text response-text
                                :tool-calls (or tool-calls [])
                                :usage {}}))
        (close! result-ch))
      result-ch)))

(defn mock-tool-execution-leave
  "Simulates tool-execution-leave: emits :tool-execution-start, :tool-execution-end."
  [ctx]
  (let [tool-calls (get-in ctx [:response :tool-calls])
        event-bus (:event-bus ctx)]
    (if (seq tool-calls)
      (go
        (loop [remaining tool-calls
               results []]
          (if (empty? remaining)
            (assoc ctx :tool-results results)
            (let [tc (first remaining)]
              ;; tool-execution-start
              (when event-bus
                (streaming/emit! event-bus
                  {:type :tool-execution-start
                   :tool-call-id (:id tc)
                   :tool-name (:name tc)
                   :args (:arguments tc)}))
              (<! (timeout 5))
              ;; tool-execution-end
              (when event-bus
                (streaming/emit! event-bus
                  {:type :tool-execution-end
                   :tool-call-id (:id tc)
                   :tool-name (:name tc)
                   :content "mock result"
                   :is-error false}))
              (recur (rest remaining)
                     (conj results {:content "mock result"}))))))
      (assoc ctx :tool-results []))))

;; --- Tests ---

;; RED: message lifecycle events
(deftest message-lifecycle-events-test
  (async done
    (go
      (let [event-bus (streaming/create-event-bus)
            events (collect-events event-bus)
            chain (interceptor/create-chain
                    [{:name :provider
                      :enter (mock-provider-enter "Hello world" nil)}])]
        (<! (interceptor/execute-async chain
              {:event-bus event-bus :session (session/create-session)}))
        (<! (timeout 50))

        (testing ":message-start emitted"
          (let [starts (events-of-type events :message-start)]
            (is (= 1 (count starts)) "Expected exactly 1 :message-start")
            (when (seq starts)
              (is (= "assistant" (:role (first starts))))
              (is (some? (:message (first starts)))))))

        (testing ":message-update emitted"
          (is (pos? (count (events-of-type events :message-update)))
              "Expected at least 1 :message-update"))

        (testing ":message-end emitted"
          (let [ends (events-of-type events :message-end)]
            (is (= 1 (count ends)) "Expected exactly 1 :message-end")
            (when (seq ends)
              (is (some? (:message (first ends)))))))

        (testing "ordering: start before update before end"
          (let [types (mapv :type @events)
                start-idx (.indexOf types :message-start)
                update-idx (.indexOf types :message-update)
                end-idx (.lastIndexOf types :message-end)]
            (is (< start-idx update-idx))
            (is (< update-idx end-idx))))

        (streaming/close-bus! event-bus))
      (done))))

;; RED: tool execution lifecycle events
(deftest tool-execution-lifecycle-events-test
  (async done
    (go
      (let [event-bus (streaming/create-event-bus)
            events (collect-events event-bus)
            tool-calls [{:id "tc-1" :name "read_file" :arguments {:path "foo.txt"}}
                        {:id "tc-2" :name "bash" :arguments {:command "ls"}}]
            chain (interceptor/create-chain
                    [{:name :provider
                      :enter (mock-provider-enter "Let me help." tool-calls)}
                     {:name :tool-execution
                      :leave mock-tool-execution-leave}])]
        (<! (interceptor/execute-async chain
              {:event-bus event-bus :session (session/create-session)}))
        (<! (timeout 100))

        (testing ":tool-execution-start emitted for each tool"
          (let [starts (events-of-type events :tool-execution-start)]
            (is (= 2 (count starts)))
            (when (= 2 (count starts))
              (is (= "tc-1" (:tool-call-id (first starts))))
              (is (= "read_file" (:tool-name (first starts))))
              (is (= "tc-2" (:tool-call-id (second starts))))
              (is (= "bash" (:tool-name (second starts)))))))

        (testing ":tool-execution-end emitted for each tool"
          (let [ends (events-of-type events :tool-execution-end)]
            (is (= 2 (count ends)))
            (when (= 2 (count ends))
              (is (= "tc-1" (:tool-call-id (first ends))))
              (is (string? (:content (first ends))))
              (is (= false (:is-error (first ends)))))))

        (testing "start before end for same tool"
          (let [types (mapv :type @events)
                s1 (.indexOf types :tool-execution-start)
                e1 (.indexOf types :tool-execution-end)]
            (is (< s1 e1))))

        (streaming/close-bus! event-bus))
      (done))))

;; RED: tool-execution-update for streaming tools (bash)
(deftest tool-execution-update-events-test
  (async done
    (go
      (let [event-bus (streaming/create-event-bus)
            events (collect-events event-bus)
            ;; Simulate a bash tool that emits updates
            tool-leave (fn [ctx]
                         (let [event-bus (:event-bus ctx)
                               tc {:id "tc-1" :name "bash" :arguments {:command "echo hi"}}]
                           (go
                             (when event-bus
                               (streaming/emit! event-bus
                                 {:type :tool-execution-start
                                  :tool-call-id "tc-1" :tool-name "bash"
                                  :args {:command "echo hi"}}))
                             (<! (timeout 5))
                             ;; streaming update
                             (when event-bus
                               (streaming/emit! event-bus
                                 {:type :tool-execution-update
                                  :tool-call-id "tc-1" :tool-name "bash"
                                  :args {:command "echo hi"}
                                  :partial-result {:content "hi\n"}}))
                             (<! (timeout 5))
                             (when event-bus
                               (streaming/emit! event-bus
                                 {:type :tool-execution-end
                                  :tool-call-id "tc-1" :tool-name "bash"
                                  :content "hi\n" :is-error false}))
                             (assoc ctx :tool-results [{:content "hi\n"}]))))
            chain (interceptor/create-chain
                    [{:name :provider
                      :enter (mock-provider-enter "Running..." [{:id "tc-1" :name "bash" :arguments {:command "echo hi"}}])}
                     {:name :tool-execution
                      :leave tool-leave}])]
        (<! (interceptor/execute-async chain
              {:event-bus event-bus :session (session/create-session)}))
        (<! (timeout 100))

        (testing ":tool-execution-update emitted"
          (let [updates (events-of-type events :tool-execution-update)]
            (is (= 1 (count updates)))
            (when (seq updates)
              (is (= "tc-1" (:tool-call-id (first updates))))
              (is (= "bash" (:tool-name (first updates))))
              (is (some? (:partial-result (first updates)))))))

        (testing "ordering: start -> update -> end"
          (let [types (mapv :type @events)
                s (.indexOf types :tool-execution-start)
                u (.indexOf types :tool-execution-update)
                e (.lastIndexOf types :tool-execution-end)]
            (is (< s u))
            (is (< u e))))

        (streaming/close-bus! event-bus))
      (done))))

;; RED: message contains accumulated content
(deftest message-accumulation-test
  (async done
    (go
      (let [event-bus (streaming/create-event-bus)
            events (collect-events event-bus)
            chain (interceptor/create-chain
                    [{:name :provider
                      :enter (mock-provider-enter "Accumulated text" nil)}])]
        (<! (interceptor/execute-async chain
              {:event-bus event-bus :session (session/create-session)}))
        (<! (timeout 50))

        (testing "message-end carries full accumulated message"
          (let [ends (events-of-type events :message-end)
                msg (:message (first ends))]
            (is (some? msg))
            (when msg
              (is (= "assistant" (:role msg)))
              (is (seq (:content msg)))
              (let [text-block (first (filter #(= :text (:type %)) (:content msg)))]
                (is (= "Accumulated text" (:text text-block)))))))

        (streaming/close-bus! event-bus))
      (done))))
