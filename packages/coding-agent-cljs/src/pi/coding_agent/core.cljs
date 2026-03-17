(ns pi.coding-agent.core
  "Entry point for the pi coding agent.
   Thin layer on kernel-cljs that adds coding-agent-specific configuration:
   system prompt, tool set, CLI entry point.
   Interactive TUI mode delegates to the TS InteractiveMode class."
  (:require ["@mariozechner/pi-coding-agent/dist/cli/args.js" :as args-mod]
            ["@mariozechner/pi-coding-agent/dist/cli/initial-message.js" :as initial-msg-mod]
            ["@mariozechner/pi-coding-agent/dist/cli/file-processor.js" :as file-proc-mod]
            ["@mariozechner/pi-coding-agent/dist/cli/list-models.js" :as list-models-mod]
            ["@mariozechner/pi-coding-agent/dist/config.js" :as config-mod]
            ["@mariozechner/pi-coding-agent/dist/core/model-resolver.js" :as model-resolver-mod]
            ["@mariozechner/pi-coding-agent/dist/core/model-registry.js" :as model-registry-mod]
            ["@mariozechner/pi-coding-agent/dist/core/session-manager.js" :as session-mgr-mod]
            ["@mariozechner/pi-coding-agent/dist/core/settings-manager.js" :as settings-mgr-mod]
            ["@mariozechner/pi-coding-agent/dist/core/auth-storage.js" :as auth-storage-mod]
            ["@mariozechner/pi-coding-agent/dist/core/resource-loader.js" :as resource-loader-mod]
            ["@mariozechner/pi-coding-agent/dist/core/export-html/index.js" :as export-mod]
            ["@mariozechner/pi-coding-agent/dist/core/keybindings.js" :as keybindings-mod]
            ["@mariozechner/pi-coding-agent/dist/migrations.js" :as migrations-mod]
            ["@mariozechner/pi-coding-agent/dist/main.js" :as main-mod]
            ["@mariozechner/pi-coding-agent/dist/modes/interactive/theme/theme.js" :as theme-mod]
            ["@mariozechner/pi-coding-agent/dist/core/tools/index.js" :as tools-mod]

            ["@mariozechner/pi-ai" :as pi-ai-mod]
            ["fs" :as fs-mod]
            ["chalk" :default chalk]
            [clojure.string :as str]
            [pi.kernel.promise :as p]
            [pi.coding-agent.session-resolution :as session-res]
            [pi.coding-agent.session-options :as session-opts]
            [pi.coding-agent.mode-dispatch :as mode-dispatch]))

;; --- Helpers ---

(defn- truthy-env-flag? [value]
  (when value
    (or (= value "1")
        (= (str/lower-case value) "true")
        (= (str/lower-case value) "yes"))))

(defn- package-command?
  "Check if the first arg is a package subcommand."
  [args]
  (when (seq args)
    (let [cmd (first args)]
      (or (= cmd "install") (= cmd "remove") (= cmd "uninstall")
          (= cmd "update") (= cmd "list") (= cmd "config")))))

(defn- read-piped-stdin
  "Read all content from piped stdin. Returns a Promise<string|undefined>."
  []
  (p/create
   (fn [resolve _reject]
     (if (.-isTTY js/process.stdin)
       (resolve js/undefined)
       (let [data (atom "")]
         (.setEncoding js/process.stdin "utf8")
         (.on js/process.stdin "data"
              (fn [chunk] (swap! data str chunk)))
         (.on js/process.stdin "end"
              (fn []
                (let [trimmed (str/trim @data)]
                  (resolve (if (= trimmed "") js/undefined trimmed)))))
         (.resume js/process.stdin))))))

;; --- Session Options ---

(defn- find-saved-model-idx
  "Find the index of the saved default model within scoped-models.
   Returns the index or nil if not found."
  [^js scoped-models ^js settings-manager ^js model-registry]
  (let [saved-provider (.getDefaultProvider settings-manager)
        saved-model-id (.getDefaultModel settings-manager)
        saved-model (when (and saved-provider saved-model-id)
                      (.find model-registry saved-provider saved-model-id))
        modelsAreEqual (.-modelsAreEqual pi-ai-mod)]
    (when saved-model
      (let [idx (.findIndex scoped-models
                            (fn [sm] (modelsAreEqual (.-model sm) saved-model)))]
        (when (>= idx 0) idx)))))

(defn- resolve-cli-model
  "Resolve model from CLI flags. Returns {:model M :thinking-level TL} or nil.
   Side effect: logs warnings, exits on error."
  [^js parsed ^js model-registry]
  (when (.-model parsed)
    (let [result (.resolveCliModel model-resolver-mod
                                   #js {:cliProvider (.-provider parsed)
                                        :cliModel (.-model parsed)
                                        :modelRegistry model-registry})]
      (when (.-warning result)
        (.warn js/console (.yellow chalk (str "Warning: " (.-warning result)))))
      (when (.-error result)
        (.error js/console (.red chalk (.-error result)))
        (.exit js/process 1))
      (when (.-model result)
        {:model (.-model result)
         :thinking-level (when (not (.-thinking parsed)) (.-thinkingLevel result))}))))

(defn- resolve-tools
  "Resolve tool selection to a JS array or nil (nil means use all defaults)."
  [^js parsed]
  (let [all-tools (.-allTools tools-mod)
        sel (session-opts/resolve-tools-option
              {:no-tools?  (.-noTools parsed)
               :tool-names (when (.-tools parsed)
                             (vec (array-seq (.-tools parsed))))})]
    (case sel
      :all  nil
      :none #js []
      (into-array (map #(aget all-tools %) (:specific sel))))))

(defn- build-scoped-models-array
  "Build a JS array of scoped model descriptors for Ctrl+P cycling, or nil."
  [^js scoped-models]
  (when (> (.-length scoped-models) 0)
    (.map scoped-models (fn [^js sm] #js {:model (.-model sm)
                                           :thinkingLevel (.-thinkingLevel sm)}))))

(defn- build-session-options
  "Build CreateAgentSessionOptions from parsed args, mirroring TS main.ts logic.
   Returns a JS options object built from a pure Clojure map — no set! mutations."
  [^js parsed ^js scoped-models session-manager ^js model-registry ^js settings-manager
   {:keys [auth-storage resource-loader]}]
  (let [cli-result (resolve-cli-model parsed model-registry)
        scoped-vec (vec (array-seq scoped-models))
        scoped-clj (mapv (fn [^js sm] {:model (.-model sm)
                                        :thinking-level (.-thinkingLevel sm)})
                         scoped-vec)
        saved-idx (when (and (not (:model cli-result))
                             (pos? (.-length scoped-models)))
                    (find-saved-model-idx scoped-models settings-manager model-registry))
        scoped-selection (session-opts/select-scoped-model
                           {:model-already-set? (some? (:model cli-result))
                            :scoped-models scoped-clj
                            :is-continue? (boolean (.-continue parsed))
                            :is-resume? (boolean (.-resume parsed))
                            :saved-model-idx saved-idx
                            :cli-thinking? (boolean (.-thinking parsed))})
        {:keys [model thinking-level]} (session-opts/resolve-model-and-thinking
                                         {:cli-model (:model cli-result)
                                          :cli-thinking-level (:thinking-level cli-result)
                                          :scoped-selection scoped-selection
                                          :explicit-cli-thinking (.-thinking parsed)})
        tools (resolve-tools parsed)
        opts-map (session-opts/build-options-map
                   {:session-manager session-manager
                    :model model
                    :thinking-level thinking-level
                    :scoped-models (build-scoped-models-array scoped-models)
                    :tools tools
                    :auth-storage auth-storage
                    :model-registry model-registry
                    :resource-loader resource-loader})]
    (session-opts/options-map->js opts-map)))

(defn- prepare-initial-message
  "Prepare the initial message from CLI args, @files, and stdin. Returns a Promise."
  [parsed stdin-content]
  (if (= 0 (.-length (.-fileArgs parsed)))
    (p/resolved (.buildInitialMessage initial-msg-mod
                                              #js {:parsed parsed :stdinContent stdin-content}))
    (-> (.processFileArguments file-proc-mod (.-fileArgs parsed) #js {})
        (p/then-let [result]
          (.buildInitialMessage initial-msg-mod
                                #js {:parsed parsed
                                     :fileText (.-text result)
                                     :fileImages (.-images result)
                                     :stdinContent stdin-content})))))

;; --- Mode Dispatch (delegated to mode-dispatch namespace) ---

(defn- run-normal-operation
  "Run the normal agent operation after early exits have been handled.
   Reads stdin, resolves models + session in parallel, then dispatches to mode."
  [{:keys [parsed extensions-result cwd auth-storage model-registry
           resource-loader settings-manager migrated-providers]}]
  (let [is-rpc (= (or (.-mode parsed) "text") "rpc")
        agent-dir (.getAgentDir config-mod)]
    ;; Read piped stdin (skip for rpc)
    (-> (if is-rpc (p/resolved js/undefined) (read-piped-stdin))
        (p/then-let [stdin-content]
          (let [agent-mode (mode-dispatch/classify-agent-mode
                             {:mode (.-mode parsed)
                              :print? (boolean (.-print parsed))
                              :stdin? (some? stdin-content)})
                is-interactive (= agent-mode :interactive)]
            ;; Validate fork flags (pure check, then exit on error)
            (when-let [err (session-res/validate-fork-flags
                              {:fork       (.-fork parsed)
                               :session    (.-session parsed)
                               :continue   (.-continue parsed)
                               :resume     (.-resume parsed)
                               :no-session (.-noSession parsed)})]
              (.error js/console (.red chalk err))
              (.exit js/process 1))
            (.migrateKeybindingsConfigFile keybindings-mod agent-dir)
            (.initTheme theme-mod (.getTheme settings-manager) is-interactive)
            ;; Resolve async dependencies in parallel
            (-> (p/all
                  [(session-res/resolve-scoped-models parsed settings-manager model-registry)
                   (session-res/create-session-manager parsed cwd extensions-result)
                   (prepare-initial-message parsed stdin-content)])
                (p/then-let [results]
                  (let [scoped-models (aget results 0)
                        session-manager (aget results 1)
                        msg-result (aget results 2)
                        initial-message (.-initialMessage msg-result)
                        initial-images (.-initialImages msg-result)
                        session-opts (build-session-options parsed scoped-models
                                                            session-manager model-registry
                                                            settings-manager
                                                            {:auth-storage auth-storage
                                                             :resource-loader resource-loader})]
                    ;; Handle --api-key
                    (when (.-apiKey parsed)
                      (if (not (.-model session-opts))
                        (do (.error js/console (.red chalk "--api-key requires a model to be specified via --model, --provider/--model, or --models"))
                            (.exit js/process 1))
                        (.setRuntimeApiKey auth-storage
                                           (.-provider (.-model session-opts))
                                           (.-apiKey parsed))))
                    (mode-dispatch/dispatch-agent-mode
                     {:parsed parsed
                      :session-opts session-opts
                      :is-rpc (= agent-mode :rpc)
                      :is-interactive is-interactive
                      :initial-message initial-message
                      :initial-images initial-images
                      :scoped-models scoped-models
                      :migrated-providers migrated-providers
                      :settings-manager settings-manager})))))))))

;; --- Extensions Setup ---

(defn- apply-settings-overrides!
  "Apply --settings CLI overrides to the settings manager."
  [first-pass settings-manager]
  (when (.-settings first-pass)
    (doseq [entry (.-settings first-pass)]
      (let [content (if (.existsSync fs-mod entry)
                      (.readFileSync fs-mod entry "utf-8")
                      entry)]
        (try
          (let [overrides (.parse js/JSON content)]
            (when (and overrides (= "object" (goog/typeOf overrides)) (not (.isArray js/Array overrides)))
              (.applyOverrides settings-manager overrides)))
          (catch :default _e
            (.error js/console (.yellow chalk "Warning: --settings must be valid JSON or a path to a JSON file"))))))))

(defn- register-provider-extensions!
  "Register provider extensions from loaded extensions and clear pending registrations."
  [extensions-result model-registry]
  (doseq [reg (.-pendingProviderRegistrations (.-runtime extensions-result))]
    (try
      (.registerProvider model-registry (.-name reg) (.-config reg))
      (catch :default e
        (.error js/console (.red chalk (str "Extension \"" (.-extensionPath reg) "\" error: " (.-message e)))))))
  (set! (.-pendingProviderRegistrations (.-runtime extensions-result)) #js []))

(defn- collect-extension-flags
  "Collect extension flags into a Map for the second arg parse pass."
  [extensions-result]
  (let [extension-flags (js/Map.)]
    (doseq [ext (.-extensions extensions-result)]
      (when (.-flags ext)
        (.forEach (.-flags ext)
                  (fn [flag name]
                    (.set extension-flags name #js {:type (.-type flag)})))))
    extension-flags))

(defn- handle-early-exits
  "Handle early-exit CLI flags (--version, --help, --list-models, --export, --session-mode).
   Returns a Promise if handled, nil if normal operation should proceed."
  [parsed model-registry]
  (let [exit-action (session-opts/classify-early-exit
                      {:version?      (.-version parsed)
                       :help?         (.-help parsed)
                       :list-models   (.-listModels parsed)
                       :export        (.-export parsed)
                       :messages      (when (> (.-length (.-messages parsed)) 0)
                                        (vec (array-seq (.-messages parsed))))
                       :session-mode? (some? (.-sessionMode parsed))
                       :session?      (some? (.-session parsed))})]
    (case (:action exit-action)
      :version
      (do (.log js/console (.-VERSION config-mod))
          (.exit js/process 0))

      :help
      (do (.printHelp args-mod)
          (.exit js/process 0))

      :list-models
      (-> (.listModels list-models-mod model-registry (:search exit-action))
          (p/then-let [_]
            (.exit js/process 0)))

      :export
      (-> (.exportFromFile export-mod (:session-path exit-action) (:output-path exit-action))
          (p/then-let [result]
            (.log js/console (str "Exported to: " result))
            (.exit js/process 0))
          (p/catch-let [err]
            (.error js/console (.red chalk (str "Error: " (.-message err))))
            (.exit js/process 1)))

      :error
      (do (.error js/console (.red chalk (:message exit-action)))
          (.exit js/process 1))

      ;; default — nil means no early exit
      nil)))

;; --- Service Initialization ---

(defn- create-services
  "Create all service instances needed for agent operation.
   Runs migrations, parses args (first pass), creates settings/auth/registry/loader.
   Returns a map of initialized services."
  [raw-args]
  (let [migration-result (.runMigrations migrations-mod (.cwd js/process))
        first-pass (.parseArgs args-mod (to-array raw-args))
        cwd (.cwd js/process)
        agent-dir (.getAgentDir config-mod)
        settings-manager (.create (.-SettingsManager settings-mgr-mod) cwd agent-dir)
        _ (apply-settings-overrides! first-pass settings-manager)
        auth-storage (.create (.-AuthStorage auth-storage-mod))
        model-registry (new (.-ModelRegistry model-registry-mod)
                            auth-storage (.getModelsPath config-mod))
        resource-loader (new (.-DefaultResourceLoader resource-loader-mod)
                             #js {:cwd cwd
                                  :agentDir agent-dir
                                  :settingsManager settings-manager
                                  :additionalExtensionPaths (.-extensions first-pass)
                                  :additionalSkillPaths (.-skills first-pass)
                                  :additionalPromptTemplatePaths (.-promptTemplates first-pass)
                                  :additionalThemePaths (.-themes first-pass)
                                  :noExtensions (.-noExtensions first-pass)
                                  :noSkills (.-noSkills first-pass)
                                  :noPromptTemplates (.-noPromptTemplates first-pass)
                                  :noThemes (.-noThemes first-pass)
                                  :systemPrompt (.-systemPrompt first-pass)
                                  :appendSystemPrompt (.-appendSystemPrompt first-pass)})]
    {:migrated-providers (.-migratedAuthProviders migration-result)
     :cwd cwd
     :settings-manager settings-manager
     :auth-storage auth-storage
     :model-registry model-registry
     :resource-loader resource-loader}))

(defn- load-extensions-and-dispatch
  "After resource-loader is ready, register extensions, re-parse args, and dispatch.
   Handles early exits or delegates to run-normal-operation."
  [raw-args {:keys [resource-loader model-registry] :as services}]
  (let [extensions-result (.getExtensions resource-loader)]
    (register-provider-extensions! extensions-result model-registry)
    (let [extension-flags (collect-extension-flags extensions-result)
          parsed (.parseArgs args-mod (to-array raw-args) extension-flags)]
      ;; Pass flag values to extensions
      (.forEach (.-unknownFlags parsed)
                (fn [value name]
                  (.set (.-flagValues (.-runtime extensions-result)) name value)))
      (or (handle-early-exits parsed model-registry)
          (run-normal-operation
            (assoc services
                   :parsed parsed
                   :extensions-result extensions-result))))))

;; --- Main Entry Point ---

(defn main
  "Entry point for the pi coding agent.
   Parses CLI arguments, handles early exits (help, version, export, etc.),
   delegates package commands to TS main, and for normal operation creates
   an AgentSession via the TS SDK, wraps it in a Session Facade that routes
   through kernel-cljs, and runs in the appropriate mode (interactive, print, rpc)."
  []
  (let [raw-args (vec (drop 2 (js->clj (.-argv js/process))))
        offline (or (some #{"--offline"} raw-args)
                    (truthy-env-flag? (aget (.-env js/process) "PI_OFFLINE")))]

    (when offline
      (aset (.-env js/process) "PI_OFFLINE" "1")
      (aset (.-env js/process) "PI_SKIP_VERSION_CHECK" "1"))

    ;; Delegate package commands to TS main
    (if (package-command? raw-args)
      (-> (.main main-mod (to-array raw-args))
          (p/then-let [_]
            nil))

      ;; Normal agent operation
      (let [services (create-services raw-args)]
        (-> (.reload (:resource-loader services))
            (p/then-let [_]
              (load-extensions-and-dispatch raw-args services))))))
  nil)
