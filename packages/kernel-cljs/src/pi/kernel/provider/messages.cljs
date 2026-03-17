(ns pi.kernel.provider.messages
  "Pure functions for converting kernel messages/tools to pi-ai format.
   No side effects, no state — just data transformation."
  (:require [malli.json-schema :as json-schema]))

;; --- Tool schema ---

(defn tool-json-schema
  "Get JSON Schema for a tool. Uses :json-schema if present, otherwise
   generates from :params-spec via malli."
  [tool]
  (or (:json-schema tool)
      (when-let [spec (:params-spec tool)]
        (json-schema/transform spec))))

(defn tools-for-provider
  "Convert kernel tool registry entries to pi-ai Tool format (as CLJS data).
   Each tool needs :name, :description, :parameters (JSON Schema map).
   Will be converted to JS by bridge/ai stream-simple's clj->js."
  [tool-registry]
  (when tool-registry
    (let [tools (vals @tool-registry)]
      (->> tools
           (keep (fn [tool]
                   (when-let [schema (tool-json-schema tool)]
                     {"name" (:name tool)
                      "description" (or (:doc tool) "")
                      "parameters" schema})))
           vec))))

;; --- Message conversion ---

(def default-assistant-stub
  "Default placeholder fields for converted assistant messages.
   These are required by the pi-ai Message schema but not available
   from kernel messages. Override via the optional `defaults` parameter
   in convert-assistant-msg."
  {"api"        "anthropic-messages"
   "provider"   "anthropic"
   "model"      "unknown"
   "usage"      {"input" 0 "output" 0 "cacheRead" 0 "cacheWrite" 0
                 "totalTokens" 0
                 "cost" {"input" 0 "output" 0 "cacheRead" 0 "cacheWrite" 0 "total" 0}}
   "stopReason" "stop"})

(defn- convert-tool-call
  "Convert a kernel tool-call map to a pi-ai toolCall content block."
  [tc]
  {"type" "toolCall"
   "id" (:id tc)
   "name" (:name tc)
   "arguments" (:arguments tc)})

(defn build-assistant-content
  "Build the content array for a converted assistant message.
   Includes text block (when non-empty) and tool-call blocks."
  [text tool-calls]
  (let [content (cond-> []
                  (and text (seq text))
                  (conj {"type" "text" "text" text})
                  (seq tool-calls)
                  (into (mapv convert-tool-call tool-calls)))]
    (if (empty? content)
      [{"type" "text" "text" ""}]
      content)))

(defn convert-user-msg
  "Convert a kernel user message to pi-ai format."
  [msg now]
  {"role" "user"
   "content" (:content msg)
   "timestamp" now})

(defn convert-tool-result-msg
  "Convert a kernel tool-result message to pi-ai format."
  [msg now]
  {"role" "toolResult"
   "toolCallId" (:tool-call-id msg)
   "toolName" (:tool-name msg)
   "content" [{"type" "text" "text" (or (:content msg) "")}]
   "isError" (boolean (:is-error msg))
   "timestamp" now})

(defn convert-assistant-msg
  "Convert a kernel assistant message to pi-ai format.
   Optional `defaults` map overrides fields from `default-assistant-stub`."
  ([msg now]
   (convert-assistant-msg msg now nil))
  ([msg now defaults]
   (let [stub (if defaults
                (merge default-assistant-stub defaults)
                default-assistant-stub)]
     (merge stub
            {"role" "assistant"
             "content" (build-assistant-content (:content msg) (:tool-calls msg))
             "timestamp" now}))))

(defn convert-messages-for-pi-ai
  "Convert kernel message format to pi-ai Message format.
   Kernel format: [{:role 'user' :content 'text'} {:role 'assistant' :content 'text'} ...]
   pi-ai format: UserMessage has content string, AssistantMessage has content array,
   ToolResultMessage has role 'toolResult'.
   Optional `assistant-defaults` map overrides assistant stub fields."
  ([messages]
   (convert-messages-for-pi-ai messages nil))
  ([messages assistant-defaults]
   (let [now (js/Date.now)]
     (mapv (fn [msg]
             (case (:role msg)
               "user"        (convert-user-msg msg now)
               "tool-result" (convert-tool-result-msg msg now)
               "assistant"   (convert-assistant-msg msg now assistant-defaults)
               msg))
           messages))))
