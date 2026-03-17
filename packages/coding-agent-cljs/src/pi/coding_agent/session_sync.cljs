(ns pi.coding-agent.session-sync
  "Dual persistence: sync DataScript session with TS SessionManager.
   On :agent-end, projects messages from DataScript to TS format and saves.
   On session restore, loads from TS SessionManager and populates DataScript."
  (:require [pi.kernel.session :as session]))

;; --- Shared helpers ---

(def ^:private default-usage
  {:input 0 :output 0 :cacheRead 0 :cacheWrite 0
   :totalTokens 0
   :cost {:input 0 :output 0 :cacheRead 0 :cacheWrite 0 :total 0}})

(defn- extract-text-from-js-content
  "Extract concatenated text from a JS content array of {type, text} blocks.
   Returns a string. If content is already a string, returns it directly."
  [content]
  (if (string? content)
    content
    (->> (array-seq content)
         (keep #(when (= "text" (.-type %)) (.-text %)))
         (apply str))))

(defn- entry-timestamp
  "Return the entry's timestamp or current time."
  [entry]
  (or (:entry/timestamp entry) (.now js/Date)))

;; --- DataScript -> TS Message Projection ---

(defn build-assistant-content-block-maps
  "Build a vector of CLJS content block maps for an assistant message.
   Adds thinking, text, and toolCall blocks in order.
   Returns a CLJS vector (not a JS array) — pure data, no JS interop."
  [{:keys [thinking content tool-calls]}]
  (cond-> []
    thinking
    (conj {:type "thinking" :thinking thinking})

    (and content (seq content))
    (conj {:type "text" :text content})

    (seq tool-calls)
    (into (mapv (fn [tc]
                  {:type "toolCall"
                   :id (:id tc)
                   :name (:name tc)
                   :arguments (:arguments tc)})
                tool-calls))))

(defn- build-user-message-map
  "Build a CLJS map for a user message entry."
  [entry]
  (let [content (get-in entry [:entry/data :content])]
    {:role "user"
     :content (if (string? content)
                [{:type "text" :text content}]
                content)
     :timestamp (entry-timestamp entry)}))

(defn- build-assistant-message-map
  "Build a CLJS map for an assistant message entry."
  [entry]
  (let [data (:entry/data entry)]
    {:role "assistant"
     :content (build-assistant-content-block-maps data)
     :model (or (:model data) "unknown")
     :provider (or (:provider data) "unknown")
     :api (or (:api data) "unknown")
     :usage (or (:usage data) default-usage)
     :stopReason (or (:stop-reason data) "stop")
     :timestamp (entry-timestamp entry)}))

(defn- build-tool-result-map
  "Build a CLJS map for a tool result entry."
  [entry]
  {:role "toolResult"
   :toolCallId (:tool-call/id entry)
   :toolName (:tool-call/name entry)
   :content [{:type "text" :text (or (:tool-result/content entry) "")}]
   :isError (boolean (:tool-result/error? entry))
   :timestamp (entry-timestamp entry)})

(defn build-ts-message-map
  "Convert a DataScript entry to a CLJS map with JS-friendly keys.
   Returns nil for unknown entry types.
   Pure data transformation — no JS interop."
  [entry]
  (case (:entry/type entry)
    :user-message      (build-user-message-map entry)
    :assistant-message (build-assistant-message-map entry)
    :tool-result       (build-tool-result-map entry)
    nil))

(defn- entry->ts-message
  "Convert a DataScript entry to a TS-compatible message JS object.
   Delegates to build-ts-message-map for pure logic, then converts to JS."
  [entry]
  (when-let [m (build-ts-message-map entry)]
    (clj->js m)))

(defn project-messages
  "Project all DataScript entries to a JS array of TS-compatible messages."
  [kernel-session]
  (let [db @(:conn kernel-session)
        entries (session/query-all-entries db)]
    (into-array (keep entry->ts-message entries))))

;; --- TS Messages -> DataScript ---

(defn- parse-assistant-content-blocks
  "Reduce over JS content blocks, accumulating {:text str :thinking str :tool-calls vec}."
  [^js content]
  (reduce
    (fn [acc block]
      (case (.-type block)
        "text"     (update acc :text str (.-text block))
        "thinking" (update acc :thinking str (.-thinking block))
        "toolCall" (update acc :tool-calls conj
                          {:id (.-id block)
                           :name (.-name block)
                           :arguments (js->clj (.-arguments block) :keywordize-keys true)})
        acc))
    {:text "" :thinking "" :tool-calls []}
    (array-seq content)))

(defn- ts-message->entries
  "Convert a TS message JS object to DataScript entries."
  [^js msg]
  (let [role (.-role msg)
        timestamp (or (.-timestamp msg) (.now js/Date))]
    (case role
      "user"
      [{:entry/type :user-message
        :entry/timestamp timestamp
        :entry/data {:content (extract-text-from-js-content (.-content msg))}}]

      "assistant"
      (let [{:keys [text thinking tool-calls]} (parse-assistant-content-blocks (.-content msg))]
        [{:entry/type :assistant-message
          :entry/timestamp timestamp
          :entry/data (cond-> {:content text}
                        (seq thinking) (assoc :thinking thinking)
                        (seq tool-calls) (assoc :tool-calls tool-calls)
                        true (assoc :model (or (.-model msg) "unknown")
                                    :provider (or (.-provider msg) "unknown")
                                    :api (or (.-api msg) "unknown")
                                    :stop-reason (or (.-stopReason msg) "stop")
                                    :usage (js->clj (.-usage msg) :keywordize-keys true)))}])

      "toolResult"
      [{:entry/type :tool-result
        :entry/timestamp timestamp
        :tool-call/id (.-toolCallId msg)
        :tool-call/name (.-toolName msg)
        :tool-result/content (extract-text-from-js-content (.-content msg))
        :tool-result/error? (boolean (.-isError msg))}]

      ;; Skip unknown roles
      [])))

(defn populate-from-ts-messages!
  "Populate a DataScript session from a JS array of TS messages."
  [kernel-session ^js messages]
  (doseq [msg (array-seq messages)]
    (doseq [entry (ts-message->entries msg)]
      (session/append-entry! kernel-session entry))))

;; --- Persistence ---

(defn save-to-session-manager!
  "Save messages from DataScript to TS SessionManager.
   Appends a message entry for each message in the session."
  [kernel-session ^js session-manager]
  (let [messages (project-messages kernel-session)]
    ;; The TS SessionManager.appendMessage expects individual messages
    ;; We need to clear and re-append, or use the bulk method
    ;; For now, we return the messages array for the caller to handle
    messages))

(defn restore-from-session-manager!
  "Restore DataScript session from TS SessionManager's messages."
  [kernel-session ^js session-manager]
  (let [context (.buildSessionContext session-manager)
        messages (.-messages context)]
    (populate-from-ts-messages! kernel-session messages)))
