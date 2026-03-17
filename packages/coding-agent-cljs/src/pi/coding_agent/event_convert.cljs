(ns pi.coding-agent.event-convert
  "Convert kernel-cljs CLJS events (keyword maps) to JS objects
   matching the TS AgentSessionEvent format consumed by interactive-mode.

   Uses a data-driven dispatch table (`event-converters`) so new event
   types can be added without touching any branching logic.

   Internal helpers build pure CLJS maps; JS conversion happens once
   at the boundary via `clj->js`.")

;; ---------------------------------------------------------------------------
;; Pure map builders (no JS interop, fully testable)
;; ---------------------------------------------------------------------------

(defn build-content-block-map
  "Convert a CLJS content block to a plain CLJS map with JS-friendly keys.
   Returns a CLJS map (not a JS object)."
  [block]
  (case (:type block)
    :text      {:type "text" :text (:text block)}
    :thinking  {:type "thinking" :thinking (:thinking block)}
    :tool-call {:type "toolCall"
                :id (:id block)
                :name (:name block)
                :arguments (:arguments block)}
    block))

(defn- build-usage-map
  "Convert a CLJS usage map to a map with camelCase keys."
  [usage]
  {:input       (or (:input usage) 0)
   :output      (or (:output usage) 0)
   :cacheRead   (or (:cache-read usage) 0)
   :cacheWrite  (or (:cache-write usage) 0)
   :totalTokens (or (:total-tokens usage) 0)
   :cost        {:input 0 :output 0 :cacheRead 0 :cacheWrite 0 :total 0}})

(defn build-message-map
  "Convert a CLJS message map to a plain CLJS map with JS-friendly keys.
   Returns nil for nil input. No JS interop — pure data transformation."
  [msg]
  (when msg
    (cond-> {:role      (or (:role msg) "assistant")
             :content   (mapv build-content-block-map (or (:content msg) []))
             :model     (or (:model msg) "unknown")
             :provider  (or (:provider msg) "unknown")
             :api       (or (:api msg) "unknown")
             :timestamp (or (:timestamp msg) (.now js/Date))
             :stopReason (or (:stop-reason msg) "stop")}
      (:usage msg)         (assoc :usage (build-usage-map (:usage msg)))
      (:error-message msg) (assoc :errorMessage (:error-message msg))
      (:retry-attempt msg) (assoc :retryAttempt (:retry-attempt msg)))))

(defn- normalize-tool-result-content
  "Normalize tool result content to a vector of content blocks.
   Wraps a bare string in a text content block."
  [content]
  (if (string? content)
    [{:type "text" :text content}]
    content))

;; ---------------------------------------------------------------------------
;; Per-type converter functions (pure CLJS maps, no JS interop)
;; ---------------------------------------------------------------------------

(defn- convert-empty-event
  "Converter for events that carry no payload beyond the type string."
  [_event js-type]
  {:type js-type})

(defn- convert-turn-end [event js-type]
  (cond-> {:type js-type}
    (:message event)      (assoc :message (build-message-map (:message event)))
    (:tool-results event) (assoc :toolResults (:tool-results event))))

(defn- convert-message-start [event js-type]
  {:type js-type
   :message (build-message-map (:message event))
   :role (or (:role event) (get-in event [:message :role]) "assistant")})

(defn- convert-message-update [event js-type]
  {:type js-type
   :message (build-message-map (:message event))})

(defn- convert-message-end [event js-type]
  {:type js-type
   :message (build-message-map (:message event))})

(defn- convert-tool-execution-start [event js-type]
  {:type js-type
   :toolCallId (:tool-call-id event)
   :toolName (:tool-name event)
   :args (or (:args event) {})})

(defn- convert-tool-execution-update [event js-type]
  {:type js-type
   :toolCallId (:tool-call-id event)
   :toolName (:tool-name event)
   :partialResult (:partial-result event)})

(defn- convert-tool-execution-end [event js-type]
  {:type js-type
   :toolCallId (:tool-call-id event)
   :toolName (:tool-name event)
   :result {:content (normalize-tool-result-content (:content event))
            :isError (boolean (:is-error event))}
   :isError (boolean (:is-error event))})

(defn- convert-auto-compaction-start [event js-type]
  {:type js-type
   :reason (or (:reason event) "threshold")})

(defn- convert-auto-compaction-end [event js-type]
  (cond-> {:type js-type
           :aborted (boolean (:aborted event))
           :willRetry (boolean (:will-retry event))}
    (:result event)        (assoc :result (:result event))
    (:error-message event) (assoc :errorMessage (:error-message event))))

(defn- convert-auto-retry-start [event js-type]
  {:type js-type
   :attempt (:attempt event)
   :maxAttempts (:max-attempts event)
   :delayMs (:delay-ms event)
   :errorMessage (or (:error-message event) "")})

(defn- convert-auto-retry-end [event js-type]
  {:type js-type
   :success (boolean (:success event))
   :attempt (:attempt event)
   :finalError (:final-error event)})

;; ---------------------------------------------------------------------------
;; Dispatch table
;; ---------------------------------------------------------------------------

(def ^:private event-type-mapping
  "Keyword -> JS string mapping for event type names."
  {:agent-start            "agent_start"
   :agent-end              "agent_end"
   :turn-start             "turn_start"
   :turn-end               "turn_end"
   :message-start          "message_start"
   :message-update         "message_update"
   :message-end            "message_end"
   :tool-execution-start   "tool_execution_start"
   :tool-execution-update  "tool_execution_update"
   :tool-execution-end     "tool_execution_end"
   :auto-compaction-start  "auto_compaction_start"
   :auto-compaction-end    "auto_compaction_end"
   :auto-retry-start       "auto_retry_start"
   :auto-retry-end         "auto_retry_end"})

(def ^:private event-converters
  "Dispatch table: event keyword -> (fn [event js-type] cljs-map).
   Each converter receives the original CLJS event map and the JS type string.
   Returns a plain CLJS map — JS conversion happens at the boundary."
  {:agent-start            convert-empty-event
   :agent-end              convert-empty-event
   :turn-start             convert-empty-event
   :turn-end               convert-turn-end
   :message-start          convert-message-start
   :message-update         convert-message-update
   :message-end            convert-message-end
   :tool-execution-start   convert-tool-execution-start
   :tool-execution-update  convert-tool-execution-update
   :tool-execution-end     convert-tool-execution-end
   :auto-compaction-start  convert-auto-compaction-start
   :auto-compaction-end    convert-auto-compaction-end
   :auto-retry-start       convert-auto-retry-start
   :auto-retry-end         convert-auto-retry-end})

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn clj->js-event
  "Convert a kernel-cljs event map to a JS AgentSessionEvent object.
   Returns a JS object or nil if the event type is unknown.
   This is the single JS conversion boundary — all converters return
   pure CLJS maps that are converted here via `clj->js`."
  [event]
  (let [event-type (:type event)]
    (when-let [js-type (get event-type-mapping event-type)]
      (if-let [converter (get event-converters event-type)]
        (clj->js (converter event js-type))
        (clj->js (assoc event :type js-type))))))
