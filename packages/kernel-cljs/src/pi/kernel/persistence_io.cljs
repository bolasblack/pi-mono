(ns pi.kernel.persistence-io
  "File-based session persistence for pi-kernel.
   Stores sessions as EDN files under ~/.pi-kernel/sessions/<session-id>.edn.
   Uses persistence.cljs serialize/deserialize functions."
  (:require [datascript.core :as d]
            [pi.kernel.persistence :as persist]
            [pi.kernel.session :as session]
            [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]))


(defn sessions-dir
  "Returns the sessions directory path: ~/.pi-kernel/sessions/"
  []
  (let [home (.homedir os)]
    (.join path home ".pi-kernel" "sessions")))

(defn ensure-sessions-dir!
  "Ensure the sessions directory exists."
  []
  (let [dir (sessions-dir)]
    (when-not (.existsSync fs dir)
      (.mkdirSync fs dir #js {:recursive true}))
    dir))

(defn session-file-path
  "Returns the file path for a given session ID."
  [session-id]
  (.join path (sessions-dir) (str session-id ".edn")))

(defn- create-file-handler
  "Create a persistence handler that reads/writes to the sessions directory.
   NOTE: Intentionally creates a fresh handler each call (stateless handler pattern).
   The handler is cheap to construct — it only closes over two lambdas."
  []
  (ensure-sessions-dir!)
  (persist/create-persistence-handler
    {:write-fn (fn [key data]
                 (let [filepath (session-file-path key)]
                   (.writeFileSync fs filepath data "utf-8")))
     :read-fn  (fn [key]
                 (let [filepath (session-file-path key)]
                   (when (.existsSync fs filepath)
                     (.readFileSync fs filepath "utf-8"))))}))

(defn generate-session-id
  "Generate a short session ID (first 8 chars of a UUID)."
  []
  (subs (str (random-uuid)) 0 8))

(defn save-session!
  "Save a session to disk. Returns the session-id used.
   session: the DataScript session map {:conn <conn> :clock-fn <fn>}
   agent-state: the state container
   session-id: string ID for this session"
  [session agent-state session-id]
  (let [handler (create-file-handler)
        db @(:conn session)]
    (persist/save-full-session! handler session-id db agent-state)
    session-id))

(defn load-session
  "Load a session from disk by ID.
   Returns {:conn <DataScript conn> :agent-state <state-container>} or nil."
  [session-id]
  (let [handler (create-file-handler)]
    (persist/load-full-session handler session-id)))

(defn- extract-first-user-message
  "Extract the first user message content from a DataScript db."
  [db]
  (let [results (try
                  (d/q
                    '[:find ?data ?ts
                      :where
                      [?e :entry/type :user-message]
                      [?e :entry/data ?data]
                      [?e :entry/timestamp ?ts]]
                    db)
                  (catch :default _
                    ;; Deserialization may have produced an incomplete db;
                    ;; treat query failure as "no results" rather than crashing.
                    nil))]
    (when (seq results)
      (let [sorted (sort-by second results)
            first-entry (first sorted)
            data (first first-entry)]
        (get data :content "")))))

(defn list-sessions
  "List all saved sessions with metadata.
   Returns a vector of maps: {:id, :modified, :preview}
   sorted by modification time (newest first).
   PERF: Loads and deserializes every session from disk to extract a preview
   message. This is O(n) in session count and will be slow for large directories.
   Consider an index file or cached preview if this becomes a bottleneck."
  []
  (let [handler (create-file-handler)
        dir (sessions-dir)
        files (try
                (vec (.readdirSync fs dir))
                (catch :default _ []))]
    (->> files
           (filter #(str/ends-with? % ".edn"))
           (map (fn [filename]
                  (let [id (str/replace filename #"\.edn$" "")
                        filepath (.join path dir filename)
                        stats (.statSync fs filepath)
                        mtime (.-mtime stats)
                        ;; Try to get first user message as preview
                        preview (try
                                  (let [data (persist/load-full-session handler id)]
                                    (when data
                                      (let [db @(:conn data)]
                                        (or (extract-first-user-message db)
                                            "(no messages)"))))
                                  (catch :default _ "(unreadable)"))]
                    {:id id
                     :modified mtime
                     :preview (if (> (count preview) 60)
                                (str (subs preview 0 57) "...")
                                preview)})))
           (sort-by :modified #(compare %2 %1))
           vec)))
