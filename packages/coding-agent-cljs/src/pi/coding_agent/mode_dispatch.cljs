(ns pi.coding-agent.mode-dispatch
  "Mode dispatch: routing to interactive TUI, RPC, or print mode.
   Extracted from core.cljs for cohesion — all mode-selection and
   mode-launching logic lives here."
  (:require ["@mariozechner/pi-coding-agent/dist/core/sdk.js" :as sdk-mod]
            ["@mariozechner/pi-coding-agent/dist/core/agent-session-runtime.js" :as runtime-mod]
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
;; Runtime factory
;; ---------------------------------------------------------------------------

(defn- build-runtime-factory
  "Build a CreateAgentSessionRuntimeFactory from session options.
   The factory creates new sessions using createAgentSession,
   merging factory-provided cwd/sessionManager with captured options.
   On first call, reuses the provided resourceLoader; subsequent calls
   let createAgentSession build a fresh one for the new cwd."
  [session-opts]
  (let [first-call? (atom true)
        createAgentSession (.-createAgentSession sdk-mod)]
    (fn [factory-opts]
      (let [is-initial? (compare-and-set! first-call? true false)
            create-opts #js {:cwd (.-cwd factory-opts)
                             :agentDir (.-agentDir factory-opts)
                             :sessionManager (.-sessionManager factory-opts)
                             :sessionStartEvent (.-sessionStartEvent factory-opts)
                             :settingsManager (.-settingsManager session-opts)
                             :authStorage (.-authStorage session-opts)
                             :modelRegistry (.-modelRegistry session-opts)
                             :model (.-model session-opts)
                             :thinkingLevel (.-thinkingLevel session-opts)
                             :scopedModels (.-scopedModels session-opts)
                             :tools (.-tools session-opts)
                             :customTools (.-customTools session-opts)}]
        (when is-initial?
          (aset create-opts "resourceLoader" (.-resourceLoader session-opts)))
        (-> (createAgentSession create-opts)
            (p/then-let [result]
              (let [session (.-session result)]
                #js {:session session
                     :extensionsResult (.-extensionsResult result)
                     :modelFallbackMessage (.-modelFallbackMessage result)
                     :services #js {:cwd (.-cwd factory-opts)
                                    :agentDir (.-agentDir factory-opts)
                                    :authStorage (.-authStorage session-opts)
                                    :settingsManager (.-settingsManager session)
                                    :modelRegistry (.-modelRegistry session)
                                    :resourceLoader (.-resourceLoader session)
                                    :diagnostics #js []}
                     :diagnostics #js []})))))))

(defn- create-runtime
  "Create an AgentSessionRuntime from session options.
   Returns a Promise<AgentSessionRuntime>."
  [session-opts cwd agent-dir]
  (let [factory (build-runtime-factory session-opts)
        createAgentSessionRuntime (.-createAgentSessionRuntime runtime-mod)]
    (createAgentSessionRuntime factory
      #js {:cwd cwd
           :agentDir agent-dir
           :sessionManager (.-sessionManager session-opts)})))

;; ---------------------------------------------------------------------------
;; Runtime facade (wraps runtime so .session returns a session facade)
;; ---------------------------------------------------------------------------

(defn- wrap-runtime-with-facade
  "Wrap AgentSessionRuntime so .session returns a session facade that
   routes prompts through kernel-cljs. Facade is invalidated on lifecycle
   operations (switchSession, newSession, fork) and lazily re-created."
  [^js runtime]
  (let [current-facade (atom nil)
        invalidate-facade!
        (fn []
          (when-let [f @current-facade]
            (when (aget f "__teardown")
              ((aget f "__teardown")))
            (reset! current-facade nil)))
        get-or-create-facade
        (fn []
          (when-not @current-facade
            (reset! current-facade
                    (facade/create-session-facade (.-session runtime))))
          @current-facade)
        proxy #js {}]

    ;; .session getter returns facade
    (js/Object.defineProperty proxy "session"
      #js {:get (fn [] (get-or-create-facade)) :enumerable true})

    ;; Forward other getters to runtime
    (doseq [prop ["services" "cwd" "diagnostics" "modelFallbackMessage"]]
      (js/Object.defineProperty proxy prop
        #js {:get (fn [] (aget runtime prop)) :enumerable true}))

    ;; Lifecycle methods: delegate then invalidate facade
    (doseq [method ["switchSession" "newSession" "fork" "importFromJsonl"]]
      (aset proxy method
        (fn [& args]
          (-> (.apply (aget runtime method) runtime (to-array args))
              (p/then-let [result]
                (invalidate-facade!)
                result)))))

    ;; Dispose: delegate then cleanup
    (aset proxy "dispose"
      (fn []
        (-> (.dispose runtime)
            (p/then-let [_]
              (invalidate-facade!)))))

    proxy))

;; ---------------------------------------------------------------------------
;; Mode launchers
;; ---------------------------------------------------------------------------

(defn start-tui
  "Interactive TUI mode using the TS InteractiveMode class.
   Creates an AgentSessionRuntime, wraps it with a session facade
   that routes prompts through kernel-cljs, and passes it to InteractiveMode."
  [session-opts im-options cwd agent-dir]
  (-> (create-runtime session-opts cwd agent-dir)
      (p/then-let [runtime]
        (let [wrapped (wrap-runtime-with-facade runtime)
              InteractiveMode (.-InteractiveMode im-mod)
              model-fallback-msg (.-modelFallbackMessage runtime)
              opts (clj->js (cond-> (or im-options {})
                              model-fallback-msg
                              (assoc :modelFallbackMessage model-fallback-msg)))
              im (InteractiveMode. wrapped opts)]
          (.run im)))
      (p/catch-let [err]
        (.error js/console "Failed to start interactive mode:" err)
        (.exit js/process 1))))

(defn- run-rpc-mode
  "Start RPC mode, delegating to the TS implementation."
  [session-opts cwd agent-dir]
  (-> (create-runtime session-opts cwd agent-dir)
      (p/then-let [runtime]
        (let [runRpcMode (.-runRpcMode rpc-mode-mod)]
          (runRpcMode runtime)))
      (p/catch-let [err]
        (.error js/console "RPC mode failed:" err)
        (.exit js/process 1))))

(defn- run-print-mode
  "Start print mode, delegating to the TS implementation."
  [session-opts parsed initial-message initial-images cwd agent-dir]
  (let [mode-str (or (.-mode parsed) "text")]
    (-> (create-runtime session-opts cwd agent-dir)
        (p/then-let [runtime]
          (let [session (.-session runtime)]
            (when (not (.-model session))
              (.error js/console (.red chalk "No models available."))
              (.error js/console (.yellow chalk "\nSet an API key environment variable:"))
              (.error js/console "  ANTHROPIC_API_KEY, OPENAI_API_KEY, GEMINI_API_KEY, etc.")
              (.exit js/process 1))
            (let [runPrintMode (.-runPrintMode print-mode-mod)
                  stopThemeWatcher (.-stopThemeWatcher theme-mod)]
              (-> (runPrintMode runtime
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
           migrated-providers settings-manager cwd agent-dir]}]
  (cond
    is-rpc
    (run-rpc-mode session-opts cwd agent-dir)

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
      (start-tui session-opts im-options cwd agent-dir))

    :else
    (run-print-mode session-opts parsed initial-message initial-images cwd agent-dir)))
