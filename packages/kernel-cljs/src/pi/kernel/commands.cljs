(ns pi.kernel.commands
  "Slash command system for pi-kernel interactive mode."
  (:require [clojure.string :as str]
            [datascript.core :as d]
            ["node:fs" :as fs]
            [pi.kernel.session :as session]))

(defn parse-command
  "Parse a slash command string into [command-name args-string].
   Returns nil if not a slash command."
  [input]
  (when (str/starts-with? input "/")
    (let [trimmed (subs input 1)
          idx     (str/index-of trimmed " ")]
      (if (nil? idx)
        [trimmed ""]
        [(subs trimmed 0 idx) (str/trim (subs trimmed (inc idx)))]))))

;; --- Pure formatting ---

(defn format-help-text
  "Format sorted command entries as a help string.
   entries is a seq of [name {:keys [doc]}] pairs."
  [entries]
  (str "\n**Commands:**\n"
       (apply str (map (fn [[name {:keys [doc]}]]
                         (str "  `/" name "` — " doc "\n"))
                       entries))
       "\n"))

(defn format-model-display
  "Format model config for display."
  [config]
  (str "\nCurrent model: **" (or (:model-id config) (:api config)) "**\n\n"))

(defn format-compact-info
  "Format session entry count for display."
  [entry-count]
  (str "\nContext: **" entry-count "** entries in session.\n\n"))

(defn generate-export-filename
  "Generate export filename from ISO timestamp."
  [timestamp]
  (str "/tmp/pi-kernel-export-" (str/replace timestamp #"[:]" "-") ".md"))

(defn format-export-content
  "Format messages as markdown for export.
   messages is a seq of maps with :role, :content, optionally :tool-name."
  [messages timestamp]
  (str "# pi-kernel conversation export\n"
       "Exported: " timestamp "\n\n"
       (str/join "\n\n"
         (map (fn [msg]
                (let [role (:role msg)]
                  (case role
                    "user" (str "## User\n" (:content msg))
                    "assistant" (str "## Assistant\n" (:content msg))
                    "tool-result" (str "## Tool: " (:tool-name msg) "\n" (:content msg))
                    (str "## " role "\n" (pr-str msg)))))
              messages))))

;; --- Command Handlers ---

(defn- cmd-help
  "List available commands."
  [_args ctx]
  (let [commands (sort-by first @(:command-registry ctx))]
    ((:emit! ctx) (format-help-text commands)))
  {:handled true})

(defn- cmd-model
  "Show or switch model."
  [args ctx]
  (let [emit! (:emit! ctx)
        config-atom (:config-atom ctx)
        make-config (:make-provider-config ctx)]
    (if (str/blank? args)
      ;; Show current model
      (let [config @config-atom]
        (emit! (format-model-display config)))
      ;; Switch model
      (let [new-config (make-config args)]
        (reset! config-atom new-config)
        (when-let [update-status! (:update-status! ctx)]
          (update-status! (or (:model-id new-config) args)))
        (emit! (str "\nSwitched to: **" (or (:model-id new-config) args) "**\n\n")))))
  {:handled true})

(defn- cmd-quit
  "Exit the agent."
  [_args ctx]
  (when-let [cleanup! (:cleanup! ctx)]
    (cleanup!))
  (.exit js/process 0)
  {:handled true})

(defn- cmd-clear
  "Clear conversation history."
  [_args ctx]
  (let [emit! (:emit! ctx)
        session-atom (:session-atom ctx)]
    (reset! session-atom (session/create-session))
    (when-let [clear-chat! (:clear-chat! ctx)]
      (clear-chat!))
    (emit! "\nConversation cleared.\n\n"))
  {:handled true})

(defn- cmd-compact
  "Show current context size."
  [_args ctx]
  (let [emit! (:emit! ctx)
        session @(:session-atom ctx)
        db @(:conn session)
        entries (d/q '[:find [?e ...]
                       :where [?e :entry/type _]]
                     db)
        entry-count (count entries)]
    (emit! (format-compact-info entry-count)))
  {:handled true})

(defn- cmd-export
  "Export conversation to file."
  [_args ctx]
  (let [emit! (:emit! ctx)
        build-messages (:build-messages ctx)
        session @(:session-atom ctx)
        timestamp (.toISOString (js/Date.))
        filename (generate-export-filename timestamp)
        messages (build-messages session)
        content (format-export-content messages timestamp)]
    (.writeFileSync fs filename content "utf-8")
    (emit! (str "\nExported to: **" filename "**\n\n")))
  {:handled true})

;; --- Registry ---

(def default-commands
  "Default slash command registry."
  {"help"    {:doc "List available commands" :handler cmd-help}
   "model"   {:doc "Show or switch model (/model provider/model-id)" :handler cmd-model}
   "quit"    {:doc "Exit the agent" :handler cmd-quit}
   "exit"    {:doc "Exit the agent" :handler cmd-quit}
   "clear"   {:doc "Clear conversation history" :handler cmd-clear}
   "compact" {:doc "Show context size" :handler cmd-compact}
   "export"  {:doc "Export conversation to file" :handler cmd-export}})

(defn create-command-registry
  "Create a mutable command registry initialized with defaults."
  []
  (atom default-commands))

(defn dispatch-command
  "Try to dispatch a slash command. Returns {:handled true/false}.
   ctx should contain: :emit!, :config-atom, :session-atom, :command-registry,
   :make-provider-config, :build-messages, etc."
  [input ctx]
  (if-let [[cmd-name args] (parse-command input)]
    (if-let [{:keys [handler]} (get @(:command-registry ctx) cmd-name)]
      (handler args ctx)
      (do
        ((:emit! ctx) (str "\nUnknown command: **/" cmd-name "**. Type `/help` for available commands.\n\n"))
        {:handled true}))
    {:handled false}))
