(ns pi.kernel.session
  (:require [datascript.core :as d]))

(def session-schema
  {:entry/type           {:db/index true}
   :entry/timestamp      {:db/index true}
   :entry/data           {}
   :tool-call/id         {}
   :tool-call/name       {:db/index true}
   :tool-call/args       {}
   :tool-call/path       {:db/index true}
   :tool-result/error?   {}
   :tool-result/content  {}
   :modification/tool-name {:db/index true}
   :modification/type      {:db/index true}})

(def default-clock-fn
  "Default clock function using js/Date.now."
  #(.now js/Date))

(defn create-session
  "Creates a new DataScript connection with session schema.
   Accepts an optional opts map with :clock-fn for injectable timestamps.
   Returns {:conn <DataScript conn> :clock-fn <fn>}."
  ([] (create-session {}))
  ([opts]
   (let [clock-fn (or (:clock-fn opts) default-clock-fn)]
     {:conn (d/create-conn session-schema)
      :clock-fn clock-fn})))

(defn append-entry!
  "Appends an entry (map) to the session store.
   Uses the session's clock-fn for default timestamps."
  [session entry]
  (let [{:keys [conn clock-fn]} session
        entry-with-ts (if (:entry/timestamp entry)
                        entry
                        (assoc entry :entry/timestamp (clock-fn)))]
    (d/transact! conn [entry-with-ts])))

(defn query-tool-error-details
  "Returns tool error entries with name and timestamp since `since-ms`.
   Each result: {:tool-call/name str :entry/timestamp number}."
  [db since-ms]
  (let [results (d/q '[:find ?name ?ts
                        :in $ ?since
                        :where
                        [?e :entry/type :tool-result]
                        [?e :tool-result/error? true]
                        [?e :entry/timestamp ?ts]
                        [(>= ?ts ?since)]
                        [?e :tool-call/name ?name]]
                     db
                     since-ms)]
    (mapv (fn [[name ts]]
            {:tool-call/name name :entry/timestamp ts})
          results)))

(defn query-errors
  "Returns tool-call names where tool-result/error? is true, since `since-ms` epoch."
  [db since-ms]
  (into #{} (map :tool-call/name) (query-tool-error-details db since-ms)))

(defn query-turn-count
  "Counts distinct user message entries."
  [db]
  (let [results (d/q '[:find ?e
                        :where
                        [?e :entry/type :user-message]]
                     db)]
    (count results)))

(defn query-edited-files
  "Returns set of file paths from edit tool calls."
  [db]
  (let [results (d/q '[:find ?path
                        :where
                        [?e :entry/type :tool-call]
                        [?e :tool-call/name "edit"]
                        [?e :tool-call/path ?path]]
                     db)]
    (set (map first results))))

(defn query-all-entries
  "Pull all session entries ordered by timestamp.
   Returns a seq of fully-pulled entity maps sorted by :entry/timestamp ascending."
  [db]
  (->> (d/q '[:find [?e ...]
              :where [?e :entry/type _]]
            db)
       (map #(d/pull db '[*] %))
       (sort-by :entry/timestamp)))

(defn query-entries-since
  "Returns all entries with timestamp >= since-ms."
  [db since-ms]
  (let [results (d/q '[:find ?e ?type ?ts
                        :in $ ?since
                        :where
                        [?e :entry/type ?type]
                        [?e :entry/timestamp ?ts]
                        [(>= ?ts ?since)]]
                     db
                     since-ms)]
    (mapv (fn [[e type ts]]
            {:db/id e :entry/type type :entry/timestamp ts})
          results)))
