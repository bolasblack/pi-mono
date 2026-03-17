(ns pi.coding-agent.mode-dispatch
  "Mode dispatch: routing to interactive TUI, RPC, or print mode.
   Extracted from core.cljs for cohesion — all mode-selection and
   mode-launching logic lives here."
  (:require ["@mariozechner/pi-coding-agent/dist/core/sdk.js" :as sdk-mod]
            ["@mariozechner/pi-coding-agent/dist/modes/interactive/interactive-mode.js" :as im-mod]
            ["@mariozechner/pi-coding-agent/dist/modes/rpc/rpc-mode.js" :as rpc-mode-mod]
            ["@mariozechner/pi-coding-agent/dist/modes/print-mode.js" :as print-mode-mod]
            ["@mariozechner/pi-coding-agent/dist/modes/interactive/theme/theme.js" :as theme-mod]
            ["chalk" :default chalk]
            [pi.kernel.promise :as p]
            [pi.coding-agent.session-facade :as facade]
            [pi.coding-agent.session-resolution :as session-res]))

;; ---------------------------------------------------------------------------
;; Pure classification
;; ---------------------------------------------------------------------------

(defn classify-agent-mode
  "Classify which agent mode to run. Pure function.
   Parameters (map):
     :mode    - CLI --mode value (\"rpc\", \"text\", \"json\", etc.) or nil
     :print?  - true if --print flag is set
     :stdin?  - true if stdin content was detected
   Returns :rpc, :interactive, or :print."
  [{:keys [mode print? stdin?]}]
  (cond
    (= mode "rpc")                        :rpc
    (or print? stdin? (some? mode))       :print
    :else                                 :interactive))

;; ---------------------------------------------------------------------------
;; Mode launchers
;; ---------------------------------------------------------------------------

(defn start-tui
  "Interactive TUI mode using the TS InteractiveMode class.
   Creates an AgentSession via the TS SDK's createAgentSession, then wraps
   it in a Session Facade that routes the agent loop through kernel-cljs.
   Passes the facade to the TS InteractiveMode."
  [session-options im-options]
  (-> (let [create-agent-session (.-createAgentSession sdk-mod)]
        (create-agent-session session-options))
      (p/then-let [result]
        (let [ts-session (.-session result)
              model-fallback-message (.-modelFallbackMessage result)
              session (facade/create-session-facade ts-session)
              InteractiveMode (.-InteractiveMode im-mod)
              opts (clj->js (cond-> (or im-options {})
                              model-fallback-message
                              (assoc :modelFallbackMessage model-fallback-message)))
              im (InteractiveMode. session opts)]
          (.run im)))
      (p/catch-let [err]
        (.error js/console "Failed to start interactive mode:" err)
        (.exit js/process 1))))

(defn- run-rpc-mode
  "Start RPC mode, delegating to the TS implementation."
  [session-opts]
  (-> (let [create-agent-session (.-createAgentSession sdk-mod)]
        (create-agent-session session-opts))
      (p/then-let [result]
        (let [runRpcMode (.-runRpcMode rpc-mode-mod)]
          (runRpcMode (.-session result))))
      (p/catch-let [err]
        (.error js/console "RPC mode failed:" err)
        (.exit js/process 1))))

(defn- run-print-mode
  "Start print mode, delegating to the TS implementation."
  [session-opts parsed initial-message initial-images]
  (let [create-agent-session (.-createAgentSession sdk-mod)
        mode-str (or (.-mode parsed) "text")]
    (-> (create-agent-session session-opts)
        (p/then-let [result]
          (let [ts-session (.-session result)]
            (when (not (.-model ts-session))
              (.error js/console (.red chalk "No models available."))
              (.error js/console (.yellow chalk "\nSet an API key environment variable:"))
              (.error js/console "  ANTHROPIC_API_KEY, OPENAI_API_KEY, GEMINI_API_KEY, etc.")
              (.exit js/process 1))
            (let [runPrintMode (.-runPrintMode print-mode-mod)
                  stopThemeWatcher (.-stopThemeWatcher theme-mod)]
              (-> (runPrintMode ts-session
                               #js {:mode mode-str
                                    :messages (.-messages parsed)
                                    :initialMessage initial-message
                                    :initialImages initial-images})
                  (p/then-let [_]
                    (stopThemeWatcher)
                    (.exit js/process 0))))))
        (p/catch-let [err]
          (.error js/console "Print mode failed:" err)
          (.exit js/process 1)))))

;; ---------------------------------------------------------------------------
;; Dispatch
;; ---------------------------------------------------------------------------

(defn dispatch-agent-mode
  "Dispatch to the appropriate mode (rpc, interactive, print) after all setup is complete."
  [{:keys [parsed session-opts is-rpc is-interactive
           initial-message initial-images scoped-models
           migrated-providers settings-manager]}]
  (cond
    is-rpc
    (run-rpc-mode session-opts)

    is-interactive
    (let [im-options (cond-> {}
                       (seq (js->clj migrated-providers))
                       (assoc :migratedProviders (js->clj migrated-providers))

                       initial-message
                       (assoc :initialMessage initial-message)

                       initial-images
                       (assoc :initialImages initial-images)

                       (> (.-length (.-messages parsed)) 0)
                       (assoc :initialMessages (js->clj (.-messages parsed)))

                       (.-verbose parsed)
                       (assoc :verbose true))]
      (session-res/log-scoped-models scoped-models parsed settings-manager)
      (start-tui session-opts im-options))

    :else
    (run-print-mode session-opts parsed initial-message initial-images)))
