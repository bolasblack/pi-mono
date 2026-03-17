(ns pi.kernel.interceptors.self-mod-analysis
  "Interceptor that detects repeated tool failures and tracks
   modification success/failure rates. Attaches suggestions to context
   but does not perform modifications."
  (:require [pi.kernel.session :as session]
            [pi.kernel.state :as state]))

(def ^:private failure-window-ms
  "Time window for failure detection: 5 minutes."
  300000)

(def ^:private failure-threshold
  "Number of failures for the same tool before suggesting modification."
  3)

(def ^:private min-uses-for-auto-disable
  "Minimum total uses before auto-disable can trigger."
  5)

(def ^:private max-failure-rate
  "Failure rate threshold for auto-disabling a modification."
  0.5)

;; ============================================================================
;; Pure functions
;; ============================================================================

(defn should-auto-disable?
  "Pure predicate: returns true when stats warrant auto-disabling a modification.
   Requires >= min-uses-for-auto-disable total uses and > max-failure-rate failure rate."
  [stats]
  (let [total (or (:total stats) 0)]
    (and (>= total min-uses-for-auto-disable)
         (pos? total)
         (> (/ (:failures stats) total) max-failure-rate))))

(defn next-tool-stats
  "Pure function: given current stats (or nil) and whether the call errored,
   returns updated stats map with :successes, :failures, :total."
  [stats errored?]
  (let [s (or (:successes stats) 0)
        f (or (:failures stats) 0)]
    (if errored?
      {:successes s :failures (inc f) :total (+ s (inc f))}
      {:successes (inc s) :failures f :total (+ (inc s) f)})))

(defn- update-tool-modification
  "Pure function: update raw data map for a single tool call result.
   Returns [updated-data auto-disable-info-or-nil]."
  [data tool-name errored?]
  (let [mod-info (get-in data [:tool-modifications tool-name])]
    (if (and mod-info (not (:disabled? mod-info)))
      (let [new-stats (next-tool-stats (:stats mod-info) errored?)
            data' (assoc-in data [:tool-modifications tool-name :stats] new-stats)]
        (if (should-auto-disable? new-stats)
          [(assoc-in data' [:tool-modifications tool-name :disabled?] true)
           {:tool-name tool-name
            :failure-rate (/ (:failures new-stats) (:total new-stats))}]
          [data' nil]))
      [data nil])))

(defn compute-leave-updates
  "Pure function: analyze tool-call/result pairs against agent-state modifications.
   tool-pairs is a seq of {:tool-call {:name ...} :result {:error ...}}.
   Returns {:agent-state <updated-state-container>
            :auto-disabled [{:tool-name str :failure-rate number}]}."
  [agent-state tool-pairs]
  (let [{:keys [data auto-disabled]}
        (reduce
          (fn [{:keys [data auto-disabled]} {:keys [tool-call result]}]
            (let [errored? (and (map? result) (:error result))
                  [data' disable-info] (update-tool-modification data (:name tool-call) errored?)]
              {:data data'
               :auto-disabled (if disable-info
                                (conj auto-disabled disable-info)
                                auto-disabled)}))
          {:data (state/get-state agent-state) :auto-disabled []}
          tool-pairs)]
    {:agent-state (state/create-state data)
     :auto-disabled auto-disabled}))

;; ============================================================================
;; Query helpers (side-effectful — read from DataScript)
;; ============================================================================

(defn- query-recent-tool-failures
  "Query DataScript for tool failures in the last `window-ms` milliseconds."
  [session window-ms]
  (let [db @(:conn session)
        now ((:clock-fn session))
        since (- now window-ms)]
    (session/query-tool-error-details db since)))

(defn detect-failure-patterns
  "Group failures by tool name, return tools with >= threshold failures.
   Returns seq of {:tool-name str :count number :errors vec}."
  [failures]
  (let [grouped (group-by :tool-call/name failures)]
    (->> grouped
         (keep (fn [[tool-name entries]]
                 (when (>= (count entries) failure-threshold)
                   {:tool-name tool-name
                    :count (count entries)
                    :errors (mapv (fn [e] {:timestamp (:entry/timestamp e)})
                                  entries)})))
         (sort-by :count >))))

;; ============================================================================
;; Interceptor enter/leave
;; ============================================================================

(defn self-mod-analysis-enter
  "Check for repeated tool failure patterns.
   Attaches :self-modification-suggestion to context when threshold met."
  [ctx]
  (let [session (:session ctx)
        failures (query-recent-tool-failures session failure-window-ms)
        patterns (detect-failure-patterns failures)]
    (if (seq patterns)
      (let [top (first patterns)]
        (assoc ctx :self-modification-suggestion
               {:tool-name (:tool-name top)
                :failure-count (:count top)
                :recent-errors (:errors top)
                :suggestion "Consider modifying this tool to handle the recurring failure pattern"}))
      ctx)))

(defn- record-auto-disable-event!
  "Record an auto-disable event in the session DataScript store."
  [session tool-name failure-rate]
  (session/append-entry! session
    {:entry/type :modification-auto-disabled
     :modification/tool-name tool-name
     :entry/data {:reason "failure-rate-exceeded"
                  :failure-rate failure-rate}}))

(defn self-mod-analysis-leave
  "Track modification success/failure rates, auto-disable regressions.
   Uses compute-leave-updates for pure analysis, then applies side effects."
  [ctx]
  (let [tool-calls (get-in ctx [:response :tool-calls])
        agent-state (:agent-state ctx)
        session (:session ctx)]
    (if (or (not (seq tool-calls)) (not agent-state))
      ctx
      (let [tool-results (:tool-results ctx)
            tool-pairs (mapv (fn [tc result]
                               {:tool-call tc :result result})
                             tool-calls
                             (concat tool-results (repeat nil)))
            {:keys [agent-state auto-disabled]}
            (compute-leave-updates agent-state tool-pairs)]
        ;; Side effects: record auto-disable events
        (doseq [{:keys [tool-name failure-rate]} auto-disabled]
          (record-auto-disable-event! session tool-name failure-rate))
        (assoc ctx :agent-state agent-state)))))

(def interceptor
  "The self-modification-analysis interceptor map."
  {:name :self-modification-analysis
   :enter self-mod-analysis-enter
   :leave self-mod-analysis-leave})
