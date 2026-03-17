(ns pi.kernel.persistence
  (:require [pi.kernel.session :as session]
            [pi.kernel.state :as state]
            [datascript.core :as d]
            [cljs.reader :as reader]))

(defn session->entities
  "Extract all entities from a DataScript db as a vector of maps.
   Pure data — no EDN string conversion."
  [db]
  (let [datoms (d/datoms db :eavt)]
    (vec
      (vals
        (reduce
          (fn [acc ^js datom]
            (let [e (.-e datom)
                  a (.-a datom)
                  v (.-v datom)]
              (update acc e assoc a v)))
          {}
          datoms)))))

(defn entities->conn
  "Hydrate a vector of entity maps into a DataScript connection.
   Pure data — no EDN string parsing."
  [entities]
  (let [conn (d/create-conn session/session-schema)]
    (d/transact! conn entities)
    conn))

(defn serialize-session
  "Takes a session (DataScript db), returns an EDN string of all entities."
  [db]
  (pr-str (session->entities db)))

(defn deserialize-session
  "Takes an EDN string, returns a hydrated DataScript connection."
  [edn-str]
  (entities->conn (reader/read-string edn-str)))

(defn create-persistence-handler
  "Takes {:write-fn (fn [key data] ...), :read-fn (fn [key] ...)} returns a handler."
  [{:keys [write-fn read-fn]}]
  {:write-fn write-fn
   :read-fn read-fn})

(defn save-session!
  "Takes handler + session-key + session db, serializes and calls write-fn."
  [handler session-key db]
  (let [edn-str (serialize-session db)]
    ((:write-fn handler) session-key edn-str)))

(defn load-session
  "Takes handler + session-key, calls read-fn and deserializes."
  [handler session-key]
  (let [edn-str ((:read-fn handler) session-key)]
    (when edn-str
      (deserialize-session edn-str))))

(def ^:private default-agent-state
  "Default agent state for backward compatibility with v1 format."
  {:tool-modifications {}
   :interceptor-config {}
   :learned-patterns []
   :turn-metrics []})

(defn serialize-full-session
  "Serialize DataScript session + agent state to EDN string.
   Uses session->entities directly to avoid double EDN roundtrip."
  [db agent-state]
  (pr-str {:version 2
           :session-data (session->entities db)
           :agent-state (state/get-state agent-state)}))

(defn deserialize-full-session
  "Deserialize EDN to {:conn <DataScript conn>, :agent-state <state container>}.
   Handles both v1 (DataScript only) and v2 (full) formats.
   Uses entities->conn directly to avoid double EDN roundtrip."
  [edn-str]
  (let [data (reader/read-string edn-str)]
    (if (:version data)
      ;; v2 format — session-data is already a vector of entities
      {:conn (entities->conn (:session-data data))
       :agent-state (state/create-state (:agent-state data))}
      ;; v1 format (backward compat) — data is just the entity vector
      {:conn (entities->conn data)
       :agent-state (state/create-state default-agent-state)})))

(defn save-full-session!
  "Save full session (DataScript + state) via handler."
  [handler session-key db agent-state]
  (let [edn-str (serialize-full-session db agent-state)]
    ((:write-fn handler) session-key edn-str)))

(defn load-full-session
  "Load full session from handler. Returns {:conn ... :agent-state ...} or nil."
  [handler session-key]
  (let [edn-str ((:read-fn handler) session-key)]
    (when edn-str
      (deserialize-full-session edn-str))))
