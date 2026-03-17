(ns pi.kernel.commands.persistence
  "Persistence-related slash commands: /sessions, /resume.
   Registers into the command registry created by pi.kernel.commands."
  (:require [clojure.string :as str]
            [pi.kernel.persistence-io :as pio]
            [pi.kernel.session :as session]))

(defn- format-date
  "Format a JS Date to a readable string."
  [^js date]
  (if date
    (let [iso (.toISOString date)]
      ;; "2025-03-16T12:34:56.789Z" -> "2025-03-16 12:34"
      (-> iso
          (str/replace "T" " ")
          (subs 0 16)))
    "unknown"))

(defn- cmd-sessions
  "List saved sessions."
  [_args ctx]
  (let [emit! (:emit! ctx)
        sessions (pio/list-sessions)]
    (if (empty? sessions)
      (emit! "\nNo saved sessions.\n\n")
      (do
        (emit! "\n**Saved Sessions:**\n\n")
        (doseq [{:keys [id modified preview]} sessions]
          (emit! (str "  `" id "` — " (format-date modified) " — " preview "\n")))
        (emit! "\nUse `/resume <id>` to load a session.\n\n"))))
  {:handled true})

(defn- cmd-resume
  "Resume a saved session by ID."
  [args ctx]
  (let [emit! (:emit! ctx)
        session-id (str/trim (or args ""))]
    (if (str/blank? session-id)
      (do
        (emit! "\nUsage: `/resume <session-id>`\n\n")
        (emit! "Use `/sessions` to see available sessions.\n\n"))
      (let [loaded (pio/load-session session-id)]
        (if loaded
          (let [session-atom (:session-atom ctx)
                agent-state-atom (:agent-state-atom ctx)
                session-id-atom (:session-id-atom ctx)]
            ;; Restore session: create a proper session map from the loaded conn
            (let [conn (:conn loaded)
                  clock-fn (or (:clock-fn @session-atom)
                               session/default-clock-fn)
                  restored-session {:conn conn :clock-fn clock-fn}]
              (reset! session-atom restored-session)
              (when agent-state-atom
                (reset! agent-state-atom (:agent-state loaded)))
              (when session-id-atom
                (reset! session-id-atom session-id))
              (when-let [clear-chat! (:clear-chat! ctx)]
                (clear-chat!))
              (emit! (str "\nResumed session `" session-id "`.\n\n"))))
          (emit! (str "\nSession `" session-id "` not found.\n\n"))))))
  {:handled true})

(defn register-commands!
  "Register persistence commands into the given command registry atom."
  [registry]
  (swap! registry merge
    {"sessions" {:doc "List saved sessions" :handler cmd-sessions}
     "resume"   {:doc "Resume a saved session (/resume <id>)" :handler cmd-resume}}))
