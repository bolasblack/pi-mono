(ns pi.kernel.provider-test
  (:require [cljs.test :refer [deftest async is testing]]
            [cljs.core.async :refer [go <! timeout]]
            [pi.kernel.provider :as provider]
            [pi.kernel.provider.mock-anthropic :as mock-anthropic]
            [pi.kernel.streaming :as streaming]))

;; --- run-mock-stream tests (data-driven mock helper) ---

(deftest run-mock-stream-emits-all-events
  (async done
    (let [events [{:type :text :content "a"}
                  {:type :text :content "b"}
                  {:type :stop :reason :end-turn}]
          received (atom [])
          bus (provider/run-mock-stream events 5)]
      (streaming/subscribe bus
        (fn [event]
          (swap! received conj event)))
      (go
        (<! (timeout 200))
        (let [non-closed (remove #(= :closed (:type %)) @received)]
          (is (= 3 (count non-closed)))
          (is (= [{:type :text :content "a"}
                   {:type :text :content "b"}
                   {:type :stop :reason :end-turn}]
                 (vec non-closed))))
        (done)))))

(deftest run-mock-stream-closes-bus-after-events
  (async done
    (let [bus (provider/run-mock-stream [{:type :text :content "x"}] 5)
          received (atom [])]
      (streaming/subscribe bus
        (fn [event]
          (swap! received conj event)))
      (go
        (<! (timeout 200))
        (is (some #(= :closed (:type %)) @received))
        (done)))))

(deftest run-mock-stream-empty-events
  (async done
    (let [received (atom [])
          bus (provider/run-mock-stream [] 5)]
      (streaming/subscribe bus
        (fn [event]
          (swap! received conj event)))
      (go
        (<! (timeout 200))
        (let [non-closed (remove #(= :closed (:type %)) @received)]
          (is (zero? (count non-closed))))
        (done)))))

;; --- Multimethod dispatch tests ---

(deftest mock-provider-returns-event-bus
  (async done
    (let [model {:api "mock" :provider :default}
          bus (provider/stream-provider model {} {})]
      (is (some? (:channel bus)))
      (is (some? (:mult bus)))
      (go
        (<! (timeout 200))
        (done)))))

(deftest mock-provider-emits-text-events
  (async done
    (let [model {:api "mock" :provider :default}
          received (atom [])
          bus (provider/stream-provider model {} {:tokens ["a" "b"] :delay-ms 5})]
      (streaming/subscribe bus
        (fn [event]
          (swap! received conj event)))
      (go
        (<! (timeout 200))
        (let [texts (filter #(= :text (:type %)) @received)]
          (is (= 2 (count texts)))
          (is (= "a" (:content (first texts))))
          (is (= "b" (:content (second texts)))))
        (done)))))

(deftest mock-tools-provider-emits-tool-call
  (async done
    (let [model {:api "mock-tools" :provider :default}
          received (atom [])
          bus (provider/stream-provider model {} {:delay-ms 5})]
      (streaming/subscribe bus
        (fn [event]
          (swap! received conj event)))
      (go
        (<! (timeout 200))
        (let [tool-calls (filter #(= :tool-call (:type %)) @received)]
          (is (= 1 (count tool-calls)))
          (is (= "read_file" (:name (first tool-calls))))
          (is (= "tc-1" (:id (first tool-calls)))))
        (done)))))

(deftest mock-provider-emits-stop-at-end
  (async done
    (let [model {:api "mock" :provider :default}
          received (atom [])
          bus (provider/stream-provider model {} {:tokens ["x"] :delay-ms 5})]
      (streaming/subscribe bus
        (fn [event]
          (swap! received conj event)))
      (go
        (<! (timeout 200))
        (let [stops (filter #(= :stop (:type %)) @received)]
          (is (= 1 (count stops)))
          (is (= :end-turn (:reason (first stops)))))
        (done)))))

(deftest different-provider-dispatch
  (async done
    (let [model-a {:api "mock" :provider :default}
          model-b {:api "mock-tools" :provider :default}
          received-a (atom [])
          received-b (atom [])
          bus-a (provider/stream-provider model-a {} {:tokens ["hi"] :delay-ms 5})
          bus-b (provider/stream-provider model-b {} {:delay-ms 5})]
      (streaming/subscribe bus-a
        (fn [event]
          (swap! received-a conj event)))
      (streaming/subscribe bus-b
        (fn [event]
          (swap! received-b conj event)))
      (go
        (<! (timeout 200))
        (let [a-types (set (map :type @received-a))]
          (is (contains? a-types :text))
          (is (contains? a-types :stop)))
        (let [b-types (set (map :type @received-b))]
          (is (contains? b-types :tool-call))
          (is (contains? b-types :stop)))
        (done)))))

(deftest unknown-provider-throws
  (async done
    (let [model {:api "unknown-api" :provider :unknown}]
      (is (thrown-with-msg? js/Error #"Unknown provider"
            (provider/stream-provider model {} {})))
      (done))))

;; --- Provider context tests ---

(deftest configure-anthropic-provider
  (async done
    (let [ctx (provider/create-provider-context)
          cfg (provider/configure-provider ctx {:api "anthropic"
                                                :model "claude-sonnet"
                                                :api-key "test-key-123"})]
      (is (= "anthropic" (:api cfg)))
      (is (= "claude-sonnet" (:model cfg)))
      (is (= "test-key-123" (:api-key cfg)))
      (is (some? (:endpoint cfg)))
      (done))))

(deftest provider-has-capabilities
  (async done
    (let [caps (provider/get-provider-caps "anthropic")]
      (is (true? (:supports-tools? caps)))
      (is (true? (:supports-images? caps)))
      (is (pos? (:max-tokens caps)))
      (is (pos? (:context-window caps))))
    (done)))

(deftest model-resolution
  (async done
    (let [resolved (provider/resolve-model "claude-sonnet")]
      (is (= "anthropic" (:api resolved)))
      (is (= "claude-3-5-sonnet-20241022" (:model-id resolved)))
      (is (= 200000 (:context-window resolved)))
      (is (= 8192 (:max-tokens resolved))))
    (done)))

(deftest resolve-unknown-model-returns-nil
  (async done
    (is (nil? (provider/resolve-model "nonexistent-model")))
    (done)))

(deftest unknown-provider-caps-returns-nil
  (async done
    (is (nil? (provider/get-provider-caps "unknown-api")))
    (done)))

(deftest list-available-providers
  (async done
    (let [ctx (provider/create-provider-context)]
      (provider/configure-provider ctx {:api "anthropic" :model "claude-sonnet" :api-key "test"})
      (let [providers (provider/list-providers ctx)]
        (is (vector? providers))
        (is (some #(= "anthropic" (:api %)) providers)))
      (done))))

(deftest reset-registry-clears-providers
  (async done
    (let [ctx (provider/create-provider-context)]
      (provider/configure-provider ctx {:api "anthropic" :model "claude-sonnet" :api-key "test"})
      (is (pos? (count (provider/list-providers ctx))))
      (provider/reset-registry! ctx)
      (is (zero? (count (provider/list-providers ctx))))
      (done))))

(deftest isolated-contexts-dont-share-state
  (async done
    (let [ctx-a (provider/create-provider-context)
          ctx-b (provider/create-provider-context)]
      (provider/configure-provider ctx-a {:api "anthropic" :model "claude-sonnet" :api-key "key-a"})
      (is (= 1 (count (provider/list-providers ctx-a))))
      (is (= 0 (count (provider/list-providers ctx-b))))
      (done))))

;; --- Anthropic mock lifecycle tests ---

(deftest stream-anthropic-mock-lifecycle
  (async done
    (let [ctx (provider/create-provider-context)
          cfg (provider/configure-provider ctx {:api "anthropic" :model "claude-sonnet" :api-key "test"})
          received (atom [])
          bus (mock-anthropic/stream-anthropic-mock cfg {:messages [{:role "user" :content "Hi"}]} {})]
      (streaming/subscribe bus
        (fn [event]
          (swap! received conj event)))
      (go
        (<! (timeout 500))
        (let [types (mapv :type @received)]
          (is (= :message-start (first types)))
          (let [msg-start (first @received)
                msg (:message msg-start)]
            (is (some? msg))
            (is (string? (:id msg)))
            (is (= "claude-3-5-sonnet-20241022" (:model msg)))
            (is (= "assistant" (:role msg))))
          (is (some #(= :content-block-start %) types))
          (is (some #(= :content-block-delta %) types))
          (is (some #(= :content-block-stop %) types))
          (is (= :message-stop (last (remove #(= :closed %) types))))
          (let [deltas (filter #(= :content-block-delta (:type %)) @received)]
            (is (pos? (count deltas)))
            (let [d (first deltas)]
              (is (= 0 (:index d)))
              (is (= :text_delta (:type (:delta d))))
              (is (string? (:text (:delta d)))))))
        (done)))))

(deftest stream-anthropic-mock-tool-use
  (async done
    (let [ctx (provider/create-provider-context)
          cfg (provider/configure-provider ctx {:api "anthropic" :model "claude-sonnet" :api-key "test"})
          received (atom [])
          bus (mock-anthropic/stream-anthropic-mock cfg
                {:messages [{:role "user" :content "Read foo.txt"}]}
                {:emit-tool-use {:id "toolu_123" :name "read_file" :input {:path "foo.txt"}}})]
      (streaming/subscribe bus
        (fn [event]
          (swap! received conj event)))
      (go
        (<! (timeout 500))
        (let [tool-blocks (filter #(and (= :content-block-start (:type %))
                                        (= "tool_use" (get-in % [:content-block :type])))
                                  @received)]
          (is (= 1 (count tool-blocks)))
          (let [tb (first tool-blocks)]
            (is (= "read_file" (get-in tb [:content-block :name])))
            (is (= "toolu_123" (get-in tb [:content-block :id])))))
        (done)))))

(deftest provider-error-handling-invalid-key
  (async done
    (let [ctx (provider/create-provider-context)
          cfg (provider/configure-provider ctx {:api "anthropic" :model "claude-sonnet" :api-key ""})
          received (atom [])
          bus (mock-anthropic/stream-anthropic-mock cfg {:messages []} {:validate-key? true})]
      (streaming/subscribe bus
        (fn [event]
          (swap! received conj event)))
      (go
        (<! (timeout 200))
        (let [errors (filter #(= :error (:type %)) @received)]
          (is (= 1 (count errors)))
          (is (string? (:message (first errors)))))
        (done)))))
