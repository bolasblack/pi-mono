(ns pi.kernel.core
  (:require [cljs.core.async :refer [go <!]]
            [pi.kernel.interceptor :as interceptor]
            [pi.kernel.interceptors.defaults :as defaults]
            [pi.kernel.model-config :as model-config]
            [pi.kernel.state :as state]
            [pi.kernel.streaming :as streaming]))

;; ---------------------------------------------------------------------------
;; Re-exports from interceptors.defaults (public API)
;; ---------------------------------------------------------------------------

(def default-interceptors
  "The default interceptor chain for an agent turn.
   Delegates to pi.kernel.interceptors.defaults."
  defaults/default-interceptors)

(def execute-tool
  "Execute a tool call against a tool registry. See defaults/execute-tool."
  defaults/execute-tool)

(def query-all-entries
  "Pull all session entries ordered by timestamp. See defaults/query-all-entries."
  defaults/query-all-entries)

(def build-messages
  "Build a messages vector from session history. See defaults/build-messages."
  defaults/build-messages)

;; ---------------------------------------------------------------------------
;; Re-exports from model-config (public API)
;; ---------------------------------------------------------------------------

(def default-model
  "The default model string. See pi.kernel.model-config."
  model-config/default-model)

(def parse-model-str
  "Parse 'provider/model-id'. See pi.kernel.model-config."
  model-config/parse-model-str)

(def provider-available?
  "Check if a provider is registered. See pi.kernel.model-config."
  model-config/provider-available?)

(def make-provider-config
  "Build provider config with mock fallback. See pi.kernel.model-config."
  model-config/make-provider-config)

;; ---------------------------------------------------------------------------
;; Agent state
;; ---------------------------------------------------------------------------

(def default-agent-state-shape
  "Initial shape for the agent state container."
  {:tool-modifications {}
   :interceptor-config {}
   :learned-patterns []
   :turn-metrics []})

;; ---------------------------------------------------------------------------
;; Pure functions extracted from the agent loop
;; ---------------------------------------------------------------------------

(defn should-continue-turn?
  "Pure predicate: should the agent loop recurse for another tool turn?
   Takes a map with :tool-results, :stop?, :tool-turn, :max-turns, :aborted?."
  [{:keys [tool-results stop? tool-turn max-turns aborted?]}]
  (boolean
    (and (seq tool-results)
         (not stop?)
         (< tool-turn max-turns)
         (not aborted?))))

(defn build-turn-context
  "Build the interceptor context map for an agent turn. Pure function.
   agent:   map from pi.kernel.agent/create-agent
   session: DataScript session
   opts:    {:tool-turn, :agent-state, :event-bus, :turn-id, :start-time,
             :root-turn? (optional), :abort-signal (optional), :pi-ai-model (optional)}"
  [agent session opts]
  (let [{:keys [tool-turn agent-state event-bus turn-id start-time
                abort-signal pi-ai-model]} opts
        root-turn? (if (contains? opts :root-turn?)
                     (:root-turn? opts)
                     (zero? tool-turn))]
    (cond-> {:session        session
             :messages       []
             :system-prompt  (:system-prompt agent)
             :model-config   (:model-config agent)
             :tool-registry  (:tool-registry agent)
             :agent-state    agent-state
             :event-bus      event-bus
             :response       nil
             :tool-results   []
             :skip-provider? false
             :stop?          false
             :tool-turn      tool-turn
             :max-tool-turns (:max-tool-turns agent)
             :turn-id        turn-id
             :start-time     start-time
             :root-turn?     root-turn?}
      abort-signal  (assoc :abort-signal abort-signal)
      pi-ai-model   (assoc :pi-ai-model pi-ai-model))))

;; ---------------------------------------------------------------------------
;; Agent loop
;; ---------------------------------------------------------------------------

(declare run-agent-turn*)

(defn run-agent-turn
  "Run a single agent turn: execute interceptor chain, handle tool calls, recurse if needed.
   Returns a core.async channel that closes when the turn (and any sub-turns) completes.

   agent:   map from pi.kernel.agent/create-agent
   session: DataScript session from pi.kernel.session/create-session
   opts:    {:event-bus, :agent-state, :abort-signal, :pi-ai-model, :extra-interceptors}"
  ([agent session] (run-agent-turn agent session {}))
  ([agent session opts] (run-agent-turn* agent session opts)))

(defn- run-agent-turn*
  "Internal implementation of run-agent-turn. Takes agent map, session, opts."
  [agent session opts]
  (go
    (let [tool-turn (or (:tool-turn opts) 0)
          agent-state (or (:agent-state opts) (state/create-state default-agent-state-shape))
          event-bus (or (:event-bus opts) (streaming/create-event-bus))
          abort-signal (:abort-signal opts)
          enriched-opts (assoc opts
                               :tool-turn tool-turn
                               :agent-state agent-state
                               :event-bus event-bus
                               :turn-id (str (random-uuid))
                               :start-time (js/Date.now))
          ctx (build-turn-context agent session enriched-opts)
          root-turn? (:root-turn? ctx)
          base-interceptors (or (:interceptors agent) default-interceptors)
          extra-interceptors (:extra-interceptors opts)
          chain (cond-> (interceptor/create-chain base-interceptors)
                  (seq extra-interceptors)
                  (into extra-interceptors))]

      ;; Emit agent-start on root turn
      (when (and root-turn? event-bus)
        (streaming/emit! event-bus {:type :agent-start}))

      ;; Emit turn-start
      (when event-bus
        (streaming/emit! event-bus {:type :turn-start}))

      (let [result (<! (interceptor/execute-async chain ctx))]

        ;; Emit turn-end
        (when event-bus
          (streaming/emit! event-bus {:type :turn-end
                                      :message (:response result)
                                      :tool-results (:tool-results result)}))

        ;; Recurse or finish
        (if (should-continue-turn?
              {:tool-results (:tool-results result)
               :stop? (:stop? result)
               :tool-turn tool-turn
               :max-turns (:max-tool-turns agent)
               :aborted? (some-> abort-signal .-aborted)})
          (<! (run-agent-turn* agent session
                               (assoc opts
                                      :tool-turn (inc tool-turn)
                                      :agent-state (:agent-state result)
                                      :event-bus event-bus
                                      :root-turn? false)))
          ;; Emit agent-end on root turn when done
          (when (and root-turn? event-bus)
            (streaming/emit! event-bus {:type :agent-end})))))))
