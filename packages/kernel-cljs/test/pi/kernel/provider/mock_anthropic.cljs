(ns pi.kernel.provider.mock-anthropic
  "Anthropic-shaped mock provider for testing. Simulates SSE event streams
   with configurable tokens, tool-use blocks, thinking blocks, and usage events."
  (:require [cljs.core.async :refer [go <! timeout]]
            [pi.kernel.streaming :as streaming]))

(def ^:private default-delay-ms 10)

(def ^:private default-tokens ["Hello" " world"])

(defn- gen-msg-id []
  (str "msg_" (random-uuid)))

(defn- emit-thinking-block!
  "Emit a thinking content block onto the bus."
  [bus thinking delay-ms]
  (go
    (streaming/emit! bus {:type :content-block-start
                          :index 0
                          :content-block {:type "thinking"}})
    (<! (timeout delay-ms))
    (streaming/emit! bus {:type :content-block-delta
                          :index 0
                          :delta {:type :thinking_delta
                                  :thinking thinking}})
    (<! (timeout delay-ms))
    (streaming/emit! bus {:type :content-block-stop :index 0})
    (<! (timeout delay-ms))))

(defn- emit-text-block!
  "Emit a text content block with token deltas onto the bus."
  [bus tokens text-idx delay-ms]
  (go
    (streaming/emit! bus {:type :content-block-start
                          :index text-idx
                          :content-block {:type "text"}})
    (<! (timeout delay-ms))
    (doseq [token tokens]
      (<! (timeout delay-ms))
      (streaming/emit! bus {:type :content-block-delta
                            :index text-idx
                            :delta {:type :text_delta
                                    :text token}}))
    (streaming/emit! bus {:type :content-block-stop :index text-idx})
    (<! (timeout delay-ms))))

(defn- emit-tool-block!
  "Emit a tool_use content block onto the bus."
  [bus tool tool-idx delay-ms]
  (go
    (streaming/emit! bus {:type :content-block-start
                          :index tool-idx
                          :content-block {:type "tool_use"
                                          :id (:id tool)
                                          :name (:name tool)}})
    (<! (timeout delay-ms))
    (streaming/emit! bus {:type :content-block-delta
                          :index tool-idx
                          :delta {:type :input_json_delta
                                  :partial-json (js/JSON.stringify (clj->js (:input tool)))}})
    (<! (timeout delay-ms))
    (streaming/emit! bus {:type :content-block-stop :index tool-idx})
    (<! (timeout delay-ms))))

(defn stream-anthropic-mock
  "Stream events in Anthropic SSE shape. Mock provider for testing.
   Options:
     :tokens - vector of text tokens (default [\"Hello\" \" world\"])
     :delay-ms - delay between events (default 10)
     :emit-tool-use - {:id :name :input} to emit a tool_use block
     :emit-usage - {:input-tokens N :output-tokens N}
     :emit-thinking - string of thinking content
     :validate-key? - if true, check api-key is non-empty"
  [provider-cfg _messages opts]
  (let [bus (streaming/create-event-bus)
        api-key (:api-key provider-cfg)
        model-name (or (:model-id provider-cfg) "claude-3-5-sonnet-20241022")
        tokens (or (:tokens opts) default-tokens)
        delay-ms (or (:delay-ms opts) default-delay-ms)
        has-thinking? (:emit-thinking opts)]
    (go
      (if (and (:validate-key? opts) (or (nil? api-key) (= "" api-key)))
        (do
          (streaming/emit! bus {:type :error
                                :message "Invalid API key: key is empty"})
          (streaming/close-bus! bus))
        (do
          (streaming/emit! bus {:type :message-start
                                :message {:id (gen-msg-id)
                                          :model model-name
                                          :role "assistant"}})
          (<! (timeout delay-ms))
          (when has-thinking?
            (<! (emit-thinking-block! bus (:emit-thinking opts) delay-ms)))
          (let [text-idx (if has-thinking? 1 0)]
            (<! (emit-text-block! bus tokens text-idx delay-ms)))
          (when-let [tool (:emit-tool-use opts)]
            (let [tool-idx (cond-> 1 has-thinking? inc)]
              (<! (emit-tool-block! bus tool tool-idx delay-ms))))
          (when-let [usage (:emit-usage opts)]
            (streaming/emit! bus {:type :usage
                                  :input-tokens (:input-tokens usage)
                                  :output-tokens (:output-tokens usage)})
            (<! (timeout delay-ms)))
          (streaming/emit! bus {:type :message-stop})
          (streaming/close-bus! bus))))
    bus))
