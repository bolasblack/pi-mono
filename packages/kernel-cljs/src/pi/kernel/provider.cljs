(ns pi.kernel.provider
  (:require [cljs.core.async :refer [go go-loop <! timeout]]
            [pi.kernel.bridge.ai :as bridge-ai]
            [pi.kernel.provider.messages :as msgs]
            [pi.kernel.streaming :as streaming]))

;; --- Provider Context (instance-based, no global state) ---

(def model-catalog
  "Known model configurations keyed by short name."
  {"claude-sonnet" {:api "anthropic" :model-id "claude-3-5-sonnet-20241022"
                    :context-window 200000 :max-tokens 8192}
   "claude-haiku"  {:api "anthropic" :model-id "claude-3-5-haiku-20241022"
                    :context-window 200000 :max-tokens 8192}
   "claude-opus"   {:api "anthropic" :model-id "claude-3-opus-20240229"
                    :context-window 200000 :max-tokens 4096}
   "claude-haiku-4-5" {:api "anthropic" :model-id "claude-haiku-4-5-20251001"
                        :context-window 200000 :max-tokens 8192}})

;; NOTE: default-endpoints and fallback-endpoint are used by the provider-context
;; API (configure-provider), not the multimethod streaming path which uses
;; bridge-ai directly.
(def ^:private default-endpoints
  "Default API endpoints by provider API."
  {"anthropic" "https://api.anthropic.com/v1/messages"})

(def ^:private fallback-endpoint "https://api.example.com")

(def provider-capabilities
  "Static capability declarations per provider API."
  {"anthropic" {:supports-tools?  true
                :supports-images? true
                :max-tokens       8192
                :context-window   200000}})

(defn create-provider-context
  "Create an isolated provider context. Each agent instance should hold its own context.
   Returns a context map containing a provider registry atom."
  []
  {:registry (atom {})})

(defn configure-provider
  "Configure a provider within a context. Returns provider config map.
   Opts: {:api string :model string :api-key string :endpoint string?}
   When the model short-name exists in model-catalog, :model-id is resolved automatically."
  [ctx {:keys [api model api-key endpoint]}]
  (let [resolved (get model-catalog model)
        cfg (cond-> {:api      api
                     :model    model
                     :api-key  api-key
                     :endpoint (or endpoint
                                   (get default-endpoints api fallback-endpoint))}
              resolved (assoc :model-id (:model-id resolved)))]
    (swap! (:registry ctx) assoc api cfg)
    cfg))

(defn get-provider-caps
  "Get capabilities for a provider API."
  [api]
  (get provider-capabilities api))

(defn resolve-model
  "Resolve a short model name to full model config."
  [model-str]
  (get model-catalog model-str))

(defn list-providers
  "List all configured providers within a context."
  [ctx]
  (vec (vals @(:registry ctx))))

(defn reset-registry!
  "Clear the provider registry in a context. Useful for test isolation."
  [ctx]
  (reset! (:registry ctx) {}))

;; --- Mock stream helper ---

(def ^:private default-delay-ms
  "Default delay between mock events in milliseconds."
  10)

(defn run-mock-stream
  "Data-driven mock streaming. Takes a seq of events and a delay-ms,
   creates an event bus, emits each event with a delay, then closes.
   Returns the event bus immediately."
  [events delay-ms]
  (let [bus (streaming/create-event-bus)]
    (go
      (doseq [event events]
        (<! (timeout delay-ms))
        (streaming/emit! bus event))
      (streaming/close-bus! bus))
    bus))

;; --- Multimethod Dispatch ---

(defmulti stream-provider
  "Dispatch streaming based on [api provider] from the model map.
   Returns an event bus emitting stream events."
  (fn [model _messages _opts] [(:api model) (:provider model)]))

;; Mock provider for testing - simulates LLM token streaming.
(defmethod stream-provider ["mock" :default]
  [_model _messages opts]
  (let [tokens   (or (:tokens opts) ["Hello" " " "world" "!"])
        delay-ms (or (:delay-ms opts) default-delay-ms)
        events   (conj (mapv (fn [t] {:type :text :content t}) tokens)
                       {:type :stop :reason :end-turn})]
    (run-mock-stream events delay-ms)))

;; Mock provider variant with tool calls.
(defmethod stream-provider ["mock-tools" :default]
  [_model _messages opts]
  (let [delay-ms (or (:delay-ms opts) default-delay-ms)]
    (run-mock-stream
      [{:type :text :content "Let me help."}
       {:type :tool-call :id "tc-1" :name "read_file" :arguments {:path "foo.txt"}}
       {:type :stop :reason :end-turn}]
      delay-ms)))

;; Mock provider that emits an error event.
(defmethod stream-provider ["mock-error" :default]
  [_model _messages opts]
  (let [delay-ms (or (:delay-ms opts) default-delay-ms)]
    (run-mock-stream
      [{:type :error :message "Mock provider error"}
       {:type :stop :reason :error}]
      delay-ms)))

;; --- Anthropic provider via pi-ai bridge ---

(def ^:private pi-ai-module (atom nil))

(defn- ensure-pi-ai!
  "Load pi-ai module once and cache it. Returns the module."
  []
  (when-not @pi-ai-module
    (reset! pi-ai-module (bridge-ai/load-pi-ai)))
  @pi-ai-module)

(defn- override-model-base-url
  "Override model's baseUrl from ANTHROPIC_BASE_URL env var if set."
  [^js pi-ai-model]
  (let [base-url (aget (.-env js/process) "ANTHROPIC_BASE_URL")]
    (if (and base-url (not= base-url ""))
      (js/Object.assign #js {} pi-ai-model #js {:baseUrl base-url})
      pi-ai-model)))

(defn- stream-pi-ai-to-bus
  "Shared streaming core for any pi-ai model. Creates an event bus, converts
   kernel messages/tools to pi-ai format, starts the stream, and forwards
   events. Returns the event bus."
  [^js pi-ai-model messages opts]
  (let [bus        (streaming/create-event-bus)
        pi-ai      (ensure-pi-ai!)
        tools      (msgs/tools-for-provider (:tool-registry opts))
        pi-msgs    (msgs/convert-messages-for-pi-ai messages)
        context    {:system-prompt (:system opts)
                    :messages      pi-msgs
                    :tools         tools}
        event-ch   (bridge-ai/stream-simple-with-signal
                     pi-ai pi-ai-model context nil (:abort-signal opts))]
    (go-loop []
      (let [event (<! event-ch)]
        (if (nil? event)
          (streaming/close-bus! bus)
          (do
            (streaming/emit! bus event)
            (recur)))))
    bus))

(defmethod stream-provider ["anthropic" :default]
  [model messages opts]
  (let [pi-ai       (ensure-pi-ai!)
        model-id    (:model-id model)
        pi-ai-model (-> (bridge-ai/get-model pi-ai "anthropic" model-id)
                        override-model-base-url)]
    (stream-pi-ai-to-bus pi-ai-model messages opts)))

;; Default handler for unknown providers.
(defmethod stream-provider :default
  [model _messages _opts]
  (throw (ex-info "Unknown provider" {:model model})))

;; --- Direct pi-ai bridge (any provider) ---

(defn stream-via-pi-ai
  "Stream via pi-ai bridge using a TS Model JS object directly.
   This bypasses the multimethod dispatch and works with any provider
   that pi-ai supports. Used by Session Facade when a TS Model is available.
   Returns an event bus emitting normalized stream events."
  [^js pi-ai-model messages opts]
  (stream-pi-ai-to-bus pi-ai-model messages opts))
