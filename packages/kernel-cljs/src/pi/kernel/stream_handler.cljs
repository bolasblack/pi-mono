(ns pi.kernel.stream-handler
  "Stream event accumulator for provider responses.
   Handles accumulating text, thinking, and tool-call events from
   a provider stream, emitting lifecycle events (message-start,
   message-update, message-end, stop) to an event bus, and producing
   a finalization result on the result channel.

   State is held in a single atom containing an immutable map.
   All state transitions are pure functions (apply-*) passed to swap!.
   Extracted from interceptors.defaults for single-responsibility."
  (:require [cljs.core.async :refer [chan put! close!]]
            [pi.kernel.streaming :as streaming]))

;; ---------------------------------------------------------------------------
;; Pure state transitions — no atoms, no side effects
;; ---------------------------------------------------------------------------

(def init-state
  "Initial accumulator state. All stream contexts start from this value."
  {:text             ""
   :tools            []
   :thinking         ""
   :cur-tool         nil
   :finished?        false
   :message-started? false})

(defn apply-text
  "Append text content to the accumulator."
  [state content]
  (update state :text str content))

(defn apply-thinking
  "Append thinking content to the accumulator."
  [state content]
  (update state :thinking str content))

(defn apply-tool-call
  "Append a complete tool call to the tools vector."
  [state {:keys [id name arguments]}]
  (update state :tools conj {:id id :name name :arguments arguments}))

(defn apply-tool-call-start
  "Set the current in-progress tool call."
  [state {:keys [id name]}]
  (assoc state :cur-tool {:id id :name name :input ""}))

(defn apply-tool-input
  "Append content to the current in-progress tool's input buffer.
   No-op when no tool is in progress."
  [state content]
  (if (:cur-tool state)
    (update-in state [:cur-tool :input] str content)
    state))

(defn apply-block-stop
  "Finalize the current in-progress tool: parse its JSON input,
   move it to the tools vector, and clear cur-tool.
   No-op when no tool is in progress or tool has no :id."
  [state]
  (if-let [tool (:cur-tool state)]
    (if (:id tool)
      (let [args (try
                   (js->clj (js/JSON.parse (:input tool)) :keywordize-keys true)
                   (catch :default _ {}))]
        (-> state
            (update :tools conj {:id (:id tool) :name (:name tool) :arguments args})
            (assoc :cur-tool nil)))
      (assoc state :cur-tool nil))
    state))

(defn mark-started
  "Mark message as started."
  [state]
  (assoc state :message-started? true))

(defn mark-finished
  "Mark stream as finished."
  [state]
  (assoc state :finished? true))

;; ---------------------------------------------------------------------------
;; Pure helpers for message/result building
;; ---------------------------------------------------------------------------

(defn build-accumulated-message
  "Build the accumulated assistant message from current values.
   Pure function — takes plain values, not atoms."
  [text tools thinking model-config stop-reason usage]
  (let [content (cond-> []
                  (seq thinking)
                  (conj {:type :thinking :thinking thinking})

                  (seq text)
                  (conj {:type :text :text text})

                  (seq tools)
                  (into (mapv (fn [tc]
                                {:type :tool-call
                                 :id (:id tc)
                                 :name (:name tc)
                                 :arguments (:arguments tc)})
                              tools)))]
    {:role "assistant"
     :content content
     :stop-reason (or stop-reason "stop")
     :usage (or usage {})
     :model (or (:model-id model-config) "unknown")
     :provider (or (:api model-config) "unknown")
     :api (or (:api model-config) "unknown")
     :timestamp (.now js/Date)}))

(defn build-finalization-result
  "Build the finalization result map for the interceptor context.
   Pure function. reason is nil for normal stop, :aborted for abort."
  [text tool-calls usage reason]
  (let [aborted? (= reason :aborted)]
    (cond-> {:response {:text (if aborted?
                                (str text "\n\n*[Operation aborted]*")
                                text)
                        :tool-calls (if aborted? [] tool-calls)
                        :usage (or usage {})}}
      aborted? (assoc :stop? true))))

;; ---------------------------------------------------------------------------
;; Stream context — single atom holding immutable state map
;; ---------------------------------------------------------------------------

(defn make-stream-ctx
  "Create the streaming state bundle for a provider call.
   State is held in a single atom containing an immutable map.
   All mutations go through swap! with pure transition functions."
  [model-config event-bus result-ch interceptor-ctx]
  {:state           (atom init-state)
   :model-config    model-config
   :event-bus       event-bus
   :result-ch       result-ch
   :interceptor-ctx interceptor-ctx})

(defn snapshot
  "Return the current state as a plain immutable map.
   Useful for testing and debugging."
  [sctx]
  @(:state sctx))

(defn- build-msg-from-state
  "Build accumulated message from a state snapshot."
  [state model-config stop-reason usage]
  (build-accumulated-message (:text state) (:tools state) (:thinking state)
                              model-config stop-reason usage))

;; ---------------------------------------------------------------------------
;; Event bus emission helpers
;; ---------------------------------------------------------------------------

(defn- ensure-message-started!
  "Emit :message-start exactly once when first content arrives.
   Uses check-then-set which is safe in JS's single-threaded model."
  [sctx]
  (when (and (:event-bus sctx) (not (:message-started? @(:state sctx))))
    (swap! (:state sctx) mark-started)
    (let [msg (build-msg-from-state (snapshot sctx) (:model-config sctx) nil nil)]
      (streaming/emit! (:event-bus sctx)
                       {:type :message-start :message msg :role "assistant"}))))

(defn- emit-message-update!
  "Emit :message-update with the current accumulated message."
  [sctx]
  (when (and (:event-bus sctx) (:message-started? (snapshot sctx)))
    (let [msg (build-msg-from-state (snapshot sctx) (:model-config sctx) nil nil)]
      (streaming/emit! (:event-bus sctx) {:type :message-update :message msg}))))

(defn- emit-final-message!
  "Emit :message-end (and optionally :message-start if not yet sent)."
  [sctx msg]
  (when-let [bus (:event-bus sctx)]
    (when-not (:message-started? (snapshot sctx))
      (streaming/emit! bus {:type :message-start :message msg :role "assistant"})
      (swap! (:state sctx) mark-started))
    (streaming/emit! bus {:type :message-end :message msg})))

;; ---------------------------------------------------------------------------
;; Finalization
;; ---------------------------------------------------------------------------

(defn- finalize-stream!
  "Shared finalization for stop, closed, and abort paths.
   Emits final message events, puts result into channel, closes channel.
   reason: nil for normal, :aborted for abort."
  [sctx stop-reason-str usage reason]
  (let [aborted? (= reason :aborted)
        state    (snapshot sctx)
        msg      (build-accumulated-message (:text state) (:tools state) (:thinking state)
                                             (:model-config sctx)
                                             (if aborted? "aborted" stop-reason-str)
                                             usage)
        result   (build-finalization-result (:text state) (:tools state) usage reason)]
    (emit-final-message! sctx msg)
    (when-let [bus (:event-bus sctx)]
      (streaming/emit! bus {:type            :stop
                            :usage           (when-not aborted? usage)
                            :has-tool-calls? (and (not aborted?) (boolean (seq (:tools state))))}))
    (put! (:result-ch sctx) (merge (:interceptor-ctx sctx) result))
    (close! (:result-ch sctx))))

;; ---------------------------------------------------------------------------
;; Individual stream event handlers
;; ---------------------------------------------------------------------------

(defn- handle-content-event!
  "Common handler for content events that follow the pattern:
   ensure-message-started → apply state transition → emit-message-update → emit bus event.
   transition-fn: (fn [state arg]) applied via swap!
   transition-arg: argument passed to transition-fn
   bus-event: event map emitted to the event bus (or nil to skip)."
  [sctx transition-fn transition-arg bus-event]
  (ensure-message-started! sctx)
  (swap! (:state sctx) transition-fn transition-arg)
  (emit-message-update! sctx)
  (when-let [bus (:event-bus sctx)]
    (streaming/emit! bus bus-event)))

(defn- on-stream-text! [sctx event]
  (handle-content-event! sctx apply-text (:content event)
    {:type :text :content (:content event)}))

(defn- on-stream-thinking! [sctx event]
  (handle-content-event! sctx apply-thinking (:content event)
    {:type :thinking :content (:content event)}))

(defn- on-stream-tool-call! [sctx event]
  (handle-content-event! sctx apply-tool-call event
    {:type :tool-call :name (:name event) :arguments (:arguments event)}))

(defn- on-stream-tool-call-start! [sctx event]
  (ensure-message-started! sctx)
  (swap! (:state sctx) apply-tool-call-start event)
  (when-let [bus (:event-bus sctx)]
    (streaming/emit! bus {:type :tool-call-start
                          :name (:name event)})))

(defn- on-stream-tool-input! [sctx event]
  (swap! (:state sctx) apply-tool-input (:content event)))

(defn- on-stream-block-stop! [sctx _event]
  (let [state-before (snapshot sctx)]
    (when-let [tool (:cur-tool state-before)]
      (when (:id tool)
        (swap! (:state sctx) apply-block-stop)
        (emit-message-update! sctx)
        (let [completed-tool (peek (:tools (snapshot sctx)))]
          (when-let [bus (:event-bus sctx)]
            (streaming/emit! bus {:type :tool-call
                                  :name (:name completed-tool)
                                  :arguments (:arguments completed-tool)})))))))

(defn- on-stream-stop! [sctx event]
  (swap! (:state sctx) mark-finished)
  (let [usage (get-in event [:message :usage])]
    (finalize-stream! sctx "stop" usage nil)))

(defn- on-stream-closed! [sctx _event]
  (when-not (:finished? (snapshot sctx))
    (swap! (:state sctx) mark-finished)
    (finalize-stream! sctx "stop" {} nil)))

(defn- on-stream-error! [sctx event]
  (when-let [bus (:event-bus sctx)]
    (streaming/emit! bus {:type :error :message (:message event)})))

;; ---------------------------------------------------------------------------
;; Data-driven dispatch
;; ---------------------------------------------------------------------------

(def ^:private stream-event-handlers
  "Map from event type keyword to handler fn.
   Each handler receives (sctx event) and performs side effects."
  {:text            on-stream-text!
   :thinking        on-stream-thinking!
   :tool-call       on-stream-tool-call!
   :tool-call-start on-stream-tool-call-start!
   :tool-input      on-stream-tool-input!
   :block-stop      on-stream-block-stop!
   :stop            on-stream-stop!
   :closed          on-stream-closed!
   :error           on-stream-error!})

(defn handle-stream-event!
  "Dispatch a streaming event to the appropriate handler via the dispatch map.
   No-ops when the stream is already finished or the event type is unrecognized."
  [sctx event]
  (when-not (:finished? (snapshot sctx))
    (when-let [handler (get stream-event-handlers (:type event))]
      (handler sctx event))))

;; ---------------------------------------------------------------------------
;; Abort handling
;; ---------------------------------------------------------------------------

(defn setup-abort-handler!
  "Install an AbortSignal listener that finalizes the stream on abort.
   Checks finished? to ensure at-most-once finalization."
  [sctx abort-signal]
  (when abort-signal
    (.addEventListener abort-signal "abort"
      (fn []
        (when-not (:finished? (snapshot sctx))
          (swap! (:state sctx) mark-finished)
          (finalize-stream! sctx "aborted" {} :aborted)))
      #js {:once true})))

;; ---------------------------------------------------------------------------
;; Public: wire up a provider stream to accumulation
;; ---------------------------------------------------------------------------

(defn subscribe-to-provider!
  "Wire a provider event bus to a stream accumulator.
   Creates the stream context, sets up abort handling, subscribes to events.
   Returns the result channel that will yield the finalized interceptor context."
  [raw-bus model-config event-bus interceptor-ctx abort-signal]
  (let [result-ch (chan 1)
        sctx (make-stream-ctx model-config event-bus result-ch interceptor-ctx)]
    (setup-abort-handler! sctx abort-signal)
    (streaming/subscribe raw-bus (partial handle-stream-event! sctx))
    result-ch))
