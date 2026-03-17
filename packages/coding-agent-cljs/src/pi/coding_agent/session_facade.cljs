(ns pi.coding-agent.session-facade
  "Session Facade: wraps TS AgentSession, routing the agent loop through
   kernel-cljs run-agent-turn.

   .prompt() always uses the kernel-cljs agent loop.
   Auto-retry and auto-compaction are implemented here.
   All other operations delegate to the underlying TS AgentSession."
  (:require [cljs.core.async :refer [go <! timeout]]
            [pi.kernel.core :as kernel]
            [pi.kernel.agent :as k-agent]
            [pi.kernel.session :as k-session]
            [pi.kernel.state :as k-state]
            [pi.kernel.streaming :as streaming]
            [pi.coding-agent.event-convert :as event-convert]
            [pi.coding-agent.session-sync :as session-sync]
            [pi.kernel.promise :as p]))

;; --- Retryable error detection ---

(def ^:private retryable-patterns
  "Regex patterns for errors that should trigger auto-retry."
  [#"(?i)overloaded"
   #"(?i)rate.?limit"
   #"(?i)too many requests"
   #"(?i)529"
   #"(?i)503"
   #"(?i)500"
   #"(?i)server error"
   #"(?i)internal error"
   #"(?i)capacity"])

(defn retryable-error?
  "Check if an error message matches a retryable pattern."
  [error-message]
  (when (string? error-message)
    (some #(re-find % error-message) retryable-patterns)))

(defn retry-delay-ms
  "Calculate retry delay with exponential backoff. Base 5s, max 60s."
  [attempt]
  (min (* 5000 (js/Math.pow 2 (dec attempt))) 60000))

(defn should-retry?
  "Pure predicate: should a failed prompt be retried?
   Takes a map with :auto-retry-enabled?, :error-message, :attempt, :max-retries."
  [{:keys [auto-retry-enabled? error-message attempt max-retries]}]
  (boolean
    (and auto-retry-enabled?
         (retryable-error? error-message)
         (<= attempt max-retries))))

;; --- Error helpers ---

(defn error->message
  "Extract a human-readable message from an error value.
   JS Error -> .-message, string -> identity, other -> str, nil -> empty string."
  [err]
  (cond
    (instance? js/Error err) (.-message err)
    (string? err)            err
    (nil? err)               ""
    :else                    (str err)))

(defn compaction-aborted?
  "Pure predicate: was a compaction error an intentional abort?
   Takes a map with :error (JS Error or string).
   Returns true for 'Compaction cancelled' messages or AbortError names."
  [{:keys [error]}]
  (let [msg (error->message error)]
    (boolean
      (or (= "Compaction cancelled" msg)
          (and (instance? js/Error error) (= "AbortError" (.-name error)))))))

;; --- Kernel Session Reset ---

(defn reset-kernel-session!
  "Replace the kernel session in facade-state with a fresh one.
   When :restore-from is provided (a TS SessionManager), populates the new
   session from it.  Additional state keys (e.g. {:agent nil}) are merged
   into the atom."
  [facade-state {:keys [restore-from] :as extra-state}]
  (let [new-ks (k-session/create-session)
        extra  (dissoc extra-state :restore-from)]
    (when restore-from
      (session-sync/restore-from-session-manager! new-ks restore-from))
    (swap! facade-state merge (assoc extra :kernel-session new-ks))))

;; --- Facade State ---

(defn- create-facade-state
  "Create the mutable state backing a session facade."
  [^js ts-session]
  (let [kernel-session (k-session/create-session)
        event-bus (streaming/create-event-bus)]
    (atom {:ts-session ts-session
           :kernel-session kernel-session
           :agent-state (k-state/create-state kernel/default-agent-state-shape)
           :event-bus event-bus
           :agent nil              ;; kernel agent instance, created lazily
           :listeners []
           :is-streaming false
           :abort-controller nil
           :bash-abort-controller nil
           ;; Auto-retry state
           :retry-attempt 0
           :retry-abort-controller nil
           ;; Auto-compaction state
           :compaction-abort-controller nil})))

;; --- Event Bus -> TS Listener Bridge ---

(defn- emit-to-listeners!
  "Emit a CLJS event to all registered JS listeners via clj->js-event."
  [facade-state event]
  (when-let [js-event (event-convert/clj->js-event event)]
    (doseq [listener (:listeners @facade-state)]
      (try
        (listener js-event)
        (catch :default e
          (.error js/console "Event listener error:" e))))))

(defn- setup-event-bridge!
  "Subscribe to the kernel-cljs event bus and forward translated events."
  [facade-state]
  (let [event-bus (:event-bus @facade-state)]
    (streaming/subscribe event-bus
      (fn [event]
        (emit-to-listeners! facade-state event)))))

(def ^:private default-compaction-threshold
  "Context usage threshold above which auto-compaction triggers."
  0.8)

(defn should-compact?
  "Pure predicate: should auto-compaction trigger?
   Takes a map with :auto-compaction-enabled?, :percent-used, and optional :threshold."
  [{:keys [auto-compaction-enabled? percent-used threshold]
    :or {threshold default-compaction-threshold}}]
  (boolean
    (and auto-compaction-enabled?
         (some? percent-used)
         (> percent-used threshold))))

;; --- Auto-compaction ---

(defn- check-auto-compaction!
  "Check if auto-compaction should trigger after an agent turn."
  [facade-state]
  (let [{:keys [ts-session]} @facade-state
        usage (.getContextUsage ts-session)]
    (when (should-compact?
            {:auto-compaction-enabled? (.-autoCompactionEnabled ts-session)
             :percent-used (when usage (.-percentUsed usage))})
      ;; Emit compaction start
      (emit-to-listeners! facade-state
        {:type :auto-compaction-start :reason "threshold"})
      ;; Delegate compaction to TS session
      (let [ac (js/AbortController.)]
        (swap! facade-state assoc :compaction-abort-controller ac)
        (-> (.compact ts-session)
            (p/then-let [result]
              (swap! facade-state assoc :compaction-abort-controller nil)
              ;; Sync compacted messages back to DataScript
              (reset-kernel-session! facade-state
                {:restore-from (.-sessionManager ts-session) :agent nil})
              (emit-to-listeners! facade-state
                {:type :auto-compaction-end
                 :result {:summary (.-summary result)
                          :tokens-before (.-tokensBefore result)}
                 :aborted false
                 :will-retry false}))
            (p/catch-let [err]
              (swap! facade-state assoc :compaction-abort-controller nil)
              (let [aborted (compaction-aborted? {:error err})]
                (emit-to-listeners! facade-state
                  {:type :auto-compaction-end
                   :aborted aborted
                   :will-retry false
                   :error-message (when-not aborted (error->message err))}))))))))

;; --- Prompt via kernel-cljs ---

(defn- do-prompt!
  "Run a single prompt through kernel-cljs. Returns a Promise."
  [facade-state text opts]
  (p/create
    (fn [resolve reject]
      (let [{:keys [kernel-session event-bus agent-state]} @facade-state
            ts-session (:ts-session @facade-state)
            ts-model (.-model ts-session)
            abort-controller (js/AbortController.)
            kernel-agent (or (:agent @facade-state)
                             (k-agent/create-agent
                               {:default-tools true
                                :system-prompt (.-systemPrompt ts-session)
                                :model-config (kernel/make-provider-config
                                                (when ts-model
                                                  (str (.-provider ts-model) "/" (.-id ts-model))))}))]

        (swap! facade-state assoc
               :is-streaming true
               :abort-controller abort-controller
               :agent kernel-agent)

        ;; Append user message to DataScript
        (k-session/append-entry! kernel-session
          {:entry/type :user-message
           :entry/data {:content text}})

        (go
          (try
            (<! (kernel/run-agent-turn kernel-agent kernel-session
                  {:event-bus event-bus
                   :agent-state agent-state
                   :abort-signal (.-signal abort-controller)
                   :pi-ai-model ts-model}))

            ;; Check auto-compaction
            (check-auto-compaction! facade-state)

            (swap! facade-state assoc
                   :is-streaming false
                   :abort-controller nil
                   :retry-attempt 0)
            (resolve nil)
            (catch :default e
              (swap! facade-state assoc
                     :is-streaming false
                     :abort-controller nil)
              (reject e))))))))

(defn- run-prompt-with-retry!
  "Run a prompt with auto-retry on retryable errors."
  [facade-state text opts]
  (let [ts-session (:ts-session @facade-state)
        settings-manager (.-settingsManager ts-session)
        max-retries (or (try (.getMaxRetries settings-manager) (catch :default _ nil)) 3)]
    (-> (do-prompt! facade-state text opts)
        (p/catch-let [err]
          (let [msg (error->message err)
                attempt (inc (:retry-attempt @facade-state))]
            (if (should-retry?
                  {:auto-retry-enabled? (.-autoRetryEnabled ts-session)
                   :error-message msg
                   :attempt attempt
                   :max-retries max-retries})
              ;; Auto-retry with cancellable delay
              (let [delay (retry-delay-ms attempt)
                    ac (js/AbortController.)]
                (swap! facade-state assoc
                       :retry-attempt attempt
                       :retry-abort-controller ac)
                (emit-to-listeners! facade-state
                  {:type :auto-retry-start
                   :attempt attempt
                   :max-attempts max-retries
                   :delay-ms delay
                   :error-message msg})
                (-> (p/delay-with-abort delay ac)
                    (p/then-let [result]
                      (swap! facade-state assoc :retry-abort-controller nil)
                      (if (= result :aborted)
                        (do
                          (swap! facade-state assoc :retry-attempt 0)
                          (emit-to-listeners! facade-state
                            {:type :auto-retry-end
                             :success false
                             :attempt attempt
                             :final-error "Retry cancelled"})
                          nil)
                        (run-prompt-with-retry! facade-state text opts)))))
              ;; Not retryable or max retries reached
              (do
                (when (pos? (:retry-attempt @facade-state))
                  (emit-to-listeners! facade-state
                    {:type :auto-retry-end
                     :success false
                     :attempt (:retry-attempt @facade-state)
                     :final-error msg})
                  (swap! facade-state assoc :retry-attempt 0))
                (p/rejected err))))))))

;; ---------------------------------------------------------------------------
;; Data-driven delegation
;; ---------------------------------------------------------------------------

(def ^:private pass-through-methods
  "Method names that simply delegate all arguments to the TS session."
  ["steer" "followUp" "sendUserMessage" "sendCustomMessage" "clearQueue"
   "setModel" "cycleModel" "setThinkingLevel" "cycleThinkingLevel"
   "getAvailableThinkingLevels" "supportsXhighThinking" "supportsThinking"
   "setSteeringMode" "setFollowUpMode" "compact" "abortBranchSummary"
   "setAutoCompactionEnabled" "bindExtensions" "reload" "setAutoRetryEnabled"
   "executeBash" "recordBashResult" "setSessionName"
   "getActiveToolNames" "getAllTools" "setActiveToolsByName" "setScopedModels"
   "getSteeringMessages" "getFollowUpMessages" "getUserMessagesForForking"
   "getSessionStats" "getContextUsage" "exportToHtml" "getLastAssistantText"
   "hasExtensionHandlers"
   "exportToJsonl" "importFromJsonl"])

(def ^:private pass-through-getters
  "Property names that delegate to the TS session via simple getters."
  ["agent" "sessionManager" "settingsManager" "modelRegistry" "resourceLoader"
   "extensionRunner" "scopedModels" "promptTemplates" "model" "thinkingLevel"
   "isCompacting" "isBashRunning" "hasPendingBashMessages" "systemPrompt"
   "autoCompactionEnabled" "autoRetryEnabled" "steeringMode" "followUpMode"
   "sessionFile" "sessionId" "sessionName" "pendingMessageCount"])

(defn- install-pass-through-methods!
  "Install simple delegation methods on the facade object.
   Each method forwards all arguments to the same-named method on ts-session."
  [facade ^js ts-session]
  (doseq [method-name pass-through-methods]
    (aset facade method-name
      (fn [& args]
        (.apply (aget ts-session method-name) ts-session (to-array args))))))

(defn- install-pass-through-getters!
  "Install simple delegation getter properties on the facade object.
   Each getter reads the same-named property from ts-session."
  [facade ^js ts-session]
  (let [descriptors (reduce
                      (fn [acc prop-name]
                        (aset acc prop-name
                          #js {:get (fn [] (aget ts-session prop-name))
                               :enumerable true})
                        acc)
                      #js {}
                      pass-through-getters)]
    (js/Object.defineProperties facade descriptors)))

;; ---------------------------------------------------------------------------
;; Session-resync helpers (switchSession, fork, navigateTree)
;; ---------------------------------------------------------------------------

(defn- resync-kernel-session!
  "Reset the kernel session from the TS SessionManager after a session switch."
  [facade-state ^js ts-session]
  (reset-kernel-session! facade-state
    {:restore-from (.-sessionManager ts-session) :agent nil}))

(defn- wrap-with-resync
  "Wrap a TS session method that returns a Promise. After resolution,
   resync the kernel session if `should-resync?` returns true for the result."
  [facade-state ^js ts-session method-name should-resync?]
  (fn [& args]
    (-> (.apply (aget ts-session method-name) ts-session (to-array args))
        (p/then-let [result]
          (when (should-resync? result)
            (resync-kernel-session! facade-state ts-session))
          result))))

(defn- install-resync-methods!
  "Install methods that delegate to TS and resync kernel session on success."
  [facade facade-state ^js ts-session]
  (aset facade "switchSession"
    (wrap-with-resync facade-state ts-session "switchSession" some?))
  (aset facade "fork"
    (wrap-with-resync facade-state ts-session "fork" #(not (.-cancelled %))))
  (aset facade "navigateTree"
    (wrap-with-resync facade-state ts-session "navigateTree" #(not (.-cancelled %)))))

;; ---------------------------------------------------------------------------
;; Core facade methods
;; ---------------------------------------------------------------------------

(defn- create-facade-core-methods
  "Build the #js object with core facade methods: subscribe, dispose, prompt,
   abort, abortBash, abortRetry, abortCompaction, newSession, waitForIdle.
   Resync placeholders (switchSession, fork, navigateTree) are set to nil
   and installed separately."
  [facade-state ^js ts-session]
  #js {:subscribe
       (fn [listener]
         (swap! facade-state update :listeners conj listener)
         ;; Also subscribe to TS session for non-agent events (extension events etc.)
         (let [ts-unsub (.subscribe ts-session listener)]
           (fn []
             (swap! facade-state update :listeners
                    (fn [ls] (vec (remove #(= % listener) ls))))
             (ts-unsub))))

       :dispose
       (fn []
         (streaming/close-bus! (:event-bus @facade-state))
         (.dispose ts-session))

       :prompt
       (fn [text opts]
         (run-prompt-with-retry! facade-state text opts))

       :abort
       (fn []
         (when-let [ac (:abort-controller @facade-state)]
           (.abort ac))
         (p/resolved))

       :abortBash
       (fn []
         (if-let [ac (:bash-abort-controller @facade-state)]
           (.abort ac)
           (.abortBash ts-session)))

       :abortRetry
       (fn []
         (when-let [ac (:retry-abort-controller @facade-state)]
           (.abort ac)))

       :abortCompaction
       (fn []
         (when-let [ac (:compaction-abort-controller @facade-state)]
           (.abort ac))
         (.abortCompaction ts-session))

       :newSession
       (fn [options]
         (reset-kernel-session! facade-state {:agent nil :retry-attempt 0})
         (.newSession ts-session options))

       :waitForIdle
       (fn [] (.waitForIdle (.-agent ts-session)))

       ;; Placeholders for resync methods installed later
       :switchSession  nil
       :fork           nil
       :navigateTree   nil})

;; ---------------------------------------------------------------------------
;; Custom getters (facade-owned state)
;; ---------------------------------------------------------------------------

(defn- install-custom-getters!
  "Install getters for facade-owned state: isStreaming, retryAttempt, isRetrying,
   state, messages, and internal accessors (__facade_state, etc.)."
  [facade facade-state]
  (js/Object.defineProperties facade
    #js {:isStreaming
         #js {:get (fn [] (:is-streaming @facade-state)) :enumerable true}

         :retryAttempt
         #js {:get (fn [] (:retry-attempt @facade-state)) :enumerable true}

         :isRetrying
         #js {:get (fn [] (some? (:retry-abort-controller @facade-state))) :enumerable true}

         :state
         #js {:get (fn []
                     ;; Lazy projection: build messages from DataScript
                     (let [msgs (session-sync/project-messages (:kernel-session @facade-state))]
                       #js {:messages msgs}))
              :enumerable true}

         :messages
         #js {:get (fn []
                     (session-sync/project-messages (:kernel-session @facade-state)))
              :enumerable true}

         ;; Internal access for CLJS code
         :__facade_state  #js {:get (fn [] facade-state) :enumerable false}
         :__kernel_session #js {:get (fn [] (:kernel-session @facade-state)) :enumerable false}
         :__event_bus     #js {:get (fn [] (:event-bus @facade-state)) :enumerable false}}))

;; ---------------------------------------------------------------------------
;; Facade Object Creation
;; ---------------------------------------------------------------------------

(defn create-session-facade
  "Create a Session Facade wrapping a TS AgentSession.
   .prompt() always routes through kernel-cljs run-agent-turn.
   Returns a JS object with the same interface as AgentSession."
  [^js ts-session]
  (let [facade-state (create-facade-state ts-session)
        _unsub-bridge (setup-event-bridge! facade-state)
        ;; Populate DataScript from existing TS messages (session restore)
        _ (when (> (.-length (.. ts-session -state -messages)) 0)
            (session-sync/populate-from-ts-messages!
              (:kernel-session @facade-state)
              (.. ts-session -state -messages)))
        facade (create-facade-core-methods facade-state ts-session)]
    (install-pass-through-methods! facade ts-session)
    (install-resync-methods! facade facade-state ts-session)
    (install-pass-through-getters! facade ts-session)
    (install-custom-getters! facade facade-state)
    facade))
