(ns pi.kernel.interceptors.defaults
  "Default interceptor implementations for the agent turn pipeline.
   Extracted from core.cljs for single-responsibility: each interceptor
   stage is defined here, and the default chain is assembled as data."
  (:require [cljs.core.async :refer [go <! chan put! close!]]
            [cljs.core.async.impl.protocols]
            [clojure.string :as str]
            [datascript.core :as d]
            [pi.kernel.interceptors.self-mod-analysis :as self-mod-analysis]
            [pi.kernel.provider :as provider]
            [pi.kernel.session :as session]
            [pi.kernel.stream-handler :as stream-handler]
            [pi.kernel.streaming :as streaming]
            [pi.kernel.deftool :as deftool]))

;; ---------------------------------------------------------------------------
;; Pure helpers
;; ---------------------------------------------------------------------------

(defn normalize-tool-result
  "Ensure a tool result map has :content.
   - nil → {:content \"No result\"}
   - has :error but no :content → copies :error to :content
   - otherwise → passes through unchanged."
  [result]
  (cond
    (nil? result)
    {:content "No result"}

    (and (:error result) (not (:content result)))
    (assoc result :content (:error result))

    :else result))

(defn merge-tool-opts
  "Merge execution options (:on-update, :abort-signal) into tool arguments.
   Returns args unchanged when opts is nil or empty."
  [args opts]
  (cond-> args
    (:on-update opts)    (assoc :on-update (:on-update opts))
    (:abort-signal opts) (assoc :abort-signal (:abort-signal opts))))

;; ---------------------------------------------------------------------------
;; Tool execution
;; ---------------------------------------------------------------------------

(defn execute-tool
  "Execute a tool call against a tool registry.
   Returns a core.async channel yielding {:content string}.
   Handles both sync tools (deftool) and async tools (returning channel)."
  ([tool-registry tool-call] (execute-tool tool-registry tool-call nil))
  ([tool-registry tool-call opts]
   (if-let [tool (deftool/get-tool tool-registry (:name tool-call))]
     (try
       (let [result ((:execute tool) (merge-tool-opts (:arguments tool-call) opts))]
         (if (satisfies? cljs.core.async.impl.protocols/ReadPort result)
           (go (normalize-tool-result (<! result)))
           (go (normalize-tool-result result))))
       (catch :default e
         (go {:content (str "Error: " (.-message e))})))
     (go {:content (str "Unknown tool: " (:name tool-call))}))))

;; ---------------------------------------------------------------------------
;; Session / message helpers — pure transformations
;; ---------------------------------------------------------------------------

(defn entry->message
  "Convert a single session entry to a provider message map.
   Returns nil for unknown entry types. Pure function."
  [entry]
  (case (:entry/type entry)
    :user-message
    {:role "user" :content (get-in entry [:entry/data :content])}

    :assistant-message
    (cond-> {:role "assistant" :content (get-in entry [:entry/data :content])}
      (seq (get-in entry [:entry/data :tool-calls]))
      (assoc :tool-calls (get-in entry [:entry/data :tool-calls])))

    :tool-result
    {:role "tool-result"
     :tool-call-id (:tool-call/id entry)
     :tool-name (:tool-call/name entry)
     :content (:tool-result/content entry)
     :is-error (:tool-result/error? entry)}
    nil))

(def query-all-entries
  "Pull all session entries ordered by timestamp.
   Delegates to pi.kernel.session/query-all-entries."
  session/query-all-entries)

(defn build-messages
  "Build a messages vector from session history for the provider."
  [session]
  (->> @(:conn session)
       query-all-entries
       (keep entry->message)
       vec))

;; ---------------------------------------------------------------------------
;; Tool result helpers — pure transformations
;; ---------------------------------------------------------------------------

(defn tool-call->result-entry
  "Build a session entry map from a tool call and its execution result.
   Pure function — no side effects."
  [tool-call result]
  {:entry/type          :tool-result
   :tool-call/id        (:id tool-call)
   :tool-call/name      (:name tool-call)
   :tool-result/content (or (:content result) (:error result) "")
   :tool-result/error?  (boolean (:error result))})

(defn max-turns-exceeded?
  "Check whether the current tool turn has reached the maximum.
   Pure predicate."
  [tool-turn max-turns]
  (>= tool-turn max-turns))

;; ---------------------------------------------------------------------------
;; Interceptor implementations
;; ---------------------------------------------------------------------------

(defn logging-enter
  "Record start time in context."
  [ctx]
  (assoc ctx :start-time (js/Date.now)))

(defn logging-leave
  "Log duration (placeholder for future session storage)."
  [ctx]
  ctx)

(defn context-management-enter
  "Build messages from session history and assoc into context."
  [ctx]
  (assoc ctx :messages (build-messages (:session ctx))))

;; ---------------------------------------------------------------------------
;; Re-exports from stream-handler (backward compatibility)
;; ---------------------------------------------------------------------------

(def build-accumulated-message
  "Build the accumulated assistant message. See stream-handler."
  stream-handler/build-accumulated-message)

(def build-finalization-result
  "Build the finalization result map. See stream-handler."
  stream-handler/build-finalization-result)

;; ---------------------------------------------------------------------------
;; Provider interceptor
;; ---------------------------------------------------------------------------

(defn provider-enter
  "Stream provider response, accumulate text and tool calls.
   Returns a core.async channel yielding the updated context.
   Skips if :skip-provider? is true.
   Emits lifecycle events: :message-start, :message-update, :message-end.

   Event handling is delegated to pi.kernel.stream-handler."
  [ctx]
  (if (:skip-provider? ctx)
    ctx
    (let [model-config (:model-config ctx)
          event-bus    (:event-bus ctx)
          stream-opts  {:system        (:system-prompt ctx)
                        :tool-registry (:tool-registry ctx)
                        :abort-signal  (:abort-signal ctx)}
          raw-bus      (if-let [pi-ai (:pi-ai-model ctx)]
                         (provider/stream-via-pi-ai pi-ai (:messages ctx) stream-opts)
                         (provider/stream-provider model-config (:messages ctx) stream-opts))]
      (stream-handler/subscribe-to-provider!
        raw-bus model-config event-bus ctx (:abort-signal ctx)))))

(defn store-assistant-response-leave
  "Store the assistant response in the session before tool execution.
   This ensures correct message ordering: assistant message before tool results."
  [ctx]
  (when-let [response (:response ctx)]
    (session/append-entry! (:session ctx)
      {:entry/type :assistant-message
       :entry/data {:content (:text response)
                    :tool-calls (:tool-calls response)}}))
  (assoc ctx :assistant-stored? true))

(defn token-counting-leave
  "Record usage in session if available."
  [ctx]
  ctx)

(def ^:private default-max-tool-turns
  "Fallback when :max-tool-turns is not in the interceptor context."
  20)

(defn- emit-tool-lifecycle!
  "Emit tool execution start/end events to the event bus."
  [event-bus tc content is-error]
  (when event-bus
    (streaming/emit! event-bus
      {:type :tool-execution-end
       :tool-call-id (:id tc)
       :tool-name (:name tc)
       :content content
       :is-error is-error})
    (streaming/emit! event-bus
      {:type :tool-result
       :name (:name tc)
       :content content})))

(defn- make-bash-update-handler
  "Create an :on-update callback for streaming bash output to the event bus."
  [event-bus tc]
  (fn [chunk]
    (streaming/emit! event-bus
      {:type :tool-execution-update
       :tool-call-id (:id tc)
       :tool-name (:name tc)
       :args (:arguments tc)
       :partial-result {:content chunk}})))

(defn- build-exec-opts
  "Build tool execution options for bash tools with streaming and abort support."
  [event-bus abort-signal tc]
  (cond-> {}
    event-bus    (assoc :on-update (make-bash-update-handler event-bus tc))
    abort-signal (assoc :abort-signal abort-signal)))

(defn- emit-max-turns-warning!
  "Emit a warning event when the maximum tool turns has been exceeded."
  [event-bus max-turns]
  (when event-bus
    (streaming/emit! event-bus
      {:type :warning
       :message (str "Max tool turns (" max-turns ") reached, stopping.")})))

(defn- execute-and-record-tool!
  "Execute a single tool call, emit events, store result in session.
   Returns a channel yielding the tool result map."
  [ctx tc]
  (let [{:keys [event-bus abort-signal tool-registry session]} ctx
        is-bash? (= "bash" (:name tc))]
    (go
      (when event-bus
        (streaming/emit! event-bus
          {:type :tool-execution-start
           :tool-call-id (:id tc)
           :tool-name (:name tc)
           :args (:arguments tc)}))
      (let [exec-opts (when is-bash?
                        (build-exec-opts event-bus abort-signal tc))
            result    (<! (execute-tool tool-registry tc exec-opts))
            entry     (tool-call->result-entry tc result)]
        (emit-tool-lifecycle! event-bus tc
          (:tool-result/content entry)
          (:tool-result/error? entry))
        (session/append-entry! session entry)
        result))))

(defn- execute-tools-sequentially!
  "Execute tool calls one at a time in order, returning a go channel
   that yields a vector of result maps."
  [ctx tool-calls]
  (go
    (loop [remaining tool-calls
           results   []]
      (if (empty? remaining)
        results
        (let [result (<! (execute-and-record-tool! ctx (first remaining)))]
          (recur (rest remaining) (conj results result)))))))

(defn tool-execution-leave
  "Execute tool calls from the response, store results in context and session.
   Emits :tool-execution-start, :tool-execution-update (bash streaming),
   :tool-execution-end, :tool-result and :warning events.
   Returns a core.async channel (async leave) since tools may be async."
  [ctx]
  (let [tool-calls (get-in ctx [:response :tool-calls])
        tool-turn  (:tool-turn ctx 0)
        max-turns  (:max-tool-turns ctx default-max-tool-turns)]
    (cond
      (not (seq tool-calls))
      (assoc ctx :tool-results [])

      (max-turns-exceeded? tool-turn max-turns)
      (do (emit-max-turns-warning! (:event-bus ctx) max-turns)
          (assoc ctx :stop? true :tool-results []))

      :else
      (go
        (let [results (<! (execute-tools-sequentially! ctx tool-calls))]
          (assoc ctx :tool-results results))))))

;; ---------------------------------------------------------------------------
;; Default chain
;; ---------------------------------------------------------------------------

(def default-interceptors
  "The default interceptor chain for an agent turn."
  [{:name  :logging
    :enter logging-enter
    :leave logging-leave}
   {:name  :context-management
    :enter context-management-enter}
   self-mod-analysis/interceptor
   {:name  :provider
    :enter provider-enter}
   {:name  :token-counting
    :leave token-counting-leave}
   {:name  :tool-execution
    :leave tool-execution-leave}
   {:name  :store-assistant-response
    :leave store-assistant-response-leave}])
