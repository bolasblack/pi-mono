(ns pi.kernel.bridge.ai
  (:require [cljs.core.async :refer [chan put! close!]]
            [pi.kernel.promise :as p]
            ["@mariozechner/pi-ai" :as pi-ai-mod]))

(defn- convert-event
  "Convert a pi-ai AssistantMessageEvent (JS object) to a normalized CLJS event map."
  [^js js-event]
  (let [evt-type (.-type js-event)]
    (case evt-type
      "start"
      {:type :message-start}

      "text_delta"
      {:type :text :content (.-delta js-event)}

      "thinking_delta"
      {:type :thinking :content (.-delta js-event)}

      "toolcall_start"
      (let [^js partial (.-partial js-event)
            ^js content (aget (.-content partial) (.-contentIndex js-event))]
        {:type :tool-call-start
         :id (.-id content)
         :name (.-name content)})

      "toolcall_delta"
      {:type :tool-input :content (.-delta js-event)}

      "toolcall_end"
      (let [^js tc (.-toolCall js-event)]
        {:type :tool-call
         :id (.-id tc)
         :name (.-name tc)
         :arguments (js->clj (.-arguments tc) :keywordize-keys true)})

      "done"
      {:type :stop
       :reason (keyword (.-reason js-event))
       :message (js->clj (.-message js-event) :keywordize-keys true)}

      "error"
      {:type :error
       :message (or (some-> ^js (.-error js-event) .-errorMessage)
                    "Unknown error")}

      ;; Ignore text_start, text_end, thinking_start, thinking_end, etc.
      nil)))

(defn- consume-async-iterator
  "Consume a JS async iterator, putting converted events on ch. Closes ch when done."
  [^js async-iter ch]
  (-> (.next async-iter)
      (p/then-let [^js result]
        (if (.-done result)
          (close! ch)
          (do
            (when-let [converted (convert-event (.-value result))]
              (put! ch converted))
            (consume-async-iterator async-iter ch))))
      (p/catch-let [^js err]
        (put! ch {:type :error
                  :message (or (.-message err) (str err))})
        (close! ch))))

(defn- async-iterable->channel
  "Convert a JS AsyncIterable to a core.async channel of normalized events."
  [async-iterable]
  (let [ch (chan 32)
        iter-fn (aget async-iterable js/Symbol.asyncIterator)
        async-iter (.call iter-fn async-iterable)]
    (consume-async-iterator async-iter ch)
    ch))

(defn stream-simple-with-signal
  "Call pi-ai's streamSimple() and return a core.async channel of normalized events.
   Accepts an optional AbortSignal to cancel the stream.
   pi-ai-module: the resolved ESM module (from dynamic import)
   model: a pi-ai Model object (JS)
   context: {:system-prompt string?, :messages [{:role string :content string}], :tools [...]}
   opts: optional SimpleStreamOptions map
   abort-signal: optional JS AbortSignal to cancel the stream

   Returns a core.async channel that emits normalized event maps."
  [^js pi-ai-module model context opts abort-signal]
  (let [stream-simple-fn (.-streamSimple pi-ai-module)
        js-context (clj->js {:systemPrompt (:system-prompt context)
                              :messages (:messages context)
                              :tools (:tools context)})
        js-opts (clj->js (cond-> (or opts {})
                           abort-signal (assoc :signal abort-signal)))
        event-stream (stream-simple-fn model js-context js-opts)]
    (async-iterable->channel event-stream)))

(defn stream-simple
  "Call pi-ai's streamSimple() without abort support. Convenience wrapper."
  [^js pi-ai-module model context opts]
  (stream-simple-with-signal pi-ai-module model context opts nil))

(defn load-pi-ai
  "Return the @mariozechner/pi-ai module (imported at ns level).
   Returns the module object synchronously."
  []
  pi-ai-mod)

(defn get-model
  "Get a Model object from pi-ai by provider and model-id.
   pi-ai-module: the resolved ESM module
   provider: string like \"anthropic\"
   model-id: string like \"claude-haiku-4-5-20251001\"
   Returns a JS Model object or nil."
  [^js pi-ai-module provider model-id]
  (let [get-model-fn (.-getModel pi-ai-module)]
    (get-model-fn provider model-id)))
