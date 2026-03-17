(ns pi.coding-agent.session-options
  "Pure functions for building session options from CLI flags.
   Extracted from core.cljs for testability.")

(defn select-scoped-model
  "Select which model to use from scoped models based on current state.
   Returns {:model M :thinking-level TL} or nil if no selection should be made.

   Parameters (all in a single map):
     :model-already-set?  - true if a model was already resolved from CLI
     :scoped-models       - seq of {:model M :thinking-level TL}
     :is-continue?        - true if --continue flag is set
     :is-resume?          - true if --resume flag is set
     :saved-model-idx     - index of saved model in scoped-models, or nil
     :cli-thinking?       - true if --thinking was explicitly set on CLI"
  [{:keys [model-already-set? scoped-models is-continue? is-resume?
           saved-model-idx cli-thinking?]}]
  (when (and (not model-already-set?)
             (seq scoped-models)
             (not is-continue?)
             (not is-resume?))
    (let [selected (if (some? saved-model-idx)
                     (nth scoped-models saved-model-idx)
                     (first scoped-models))]
      (cond-> {:model (:model selected)}
        (and (not cli-thinking?) (:thinking-level selected))
        (assoc :thinking-level (:thinking-level selected))))))

(defn resolve-tools-option
  "Determine which tools to use based on CLI flags.
   Returns :all, :none, or {:specific [tool-name-strings]}.

   Parameters:
     :no-tools?   - true if --noTools flag is set
     :tool-names  - seq of tool name strings from --tools flag, or nil"
  [{:keys [no-tools? tool-names]}]
  (cond
    (and no-tools? (seq tool-names)) {:specific (vec tool-names)}
    no-tools?                        :none
    (seq tool-names)                 {:specific (vec tool-names)}
    :else                            :all))

(defn resolve-model-and-thinking
  "Determine the final model and thinking level from multiple sources.

   Precedence for model: cli-model > scoped-selection
   Precedence for thinking: explicit-cli-thinking > source's thinking level

   Parameters:
     :cli-model             - model resolved from --model/--provider CLI flags, or nil
     :cli-thinking-level    - thinking level from CLI model resolution, or nil
     :scoped-selection      - result of select-scoped-model, or nil
     :explicit-cli-thinking - value of --thinking flag, or nil

   Returns {:model M :thinking-level TL} where either may be nil."
  [{:keys [cli-model cli-thinking-level scoped-selection explicit-cli-thinking]}]
  (let [from-cli? (some? cli-model)
        model (if from-cli? cli-model (:model scoped-selection))
        base-thinking (if from-cli? cli-thinking-level (:thinking-level scoped-selection))
        thinking (or explicit-cli-thinking base-thinking)]
    {:model model :thinking-level thinking}))

(defn build-options-map
  "Assemble session options as a Clojure map from pre-resolved components.
   Keys use camelCase keywords matching the JS property names.
   Values are left as-is (may be JS objects that should not be deeply converted).

   Returns a map suitable for shallow conversion via options-map->js."
  [{:keys [session-manager model thinking-level scoped-models tools
           auth-storage model-registry resource-loader]}]
  (cond-> {}
    session-manager       (assoc :sessionManager session-manager)
    model                 (assoc :model model)
    thinking-level        (assoc :thinkingLevel thinking-level)
    (some? scoped-models) (assoc :scopedModels scoped-models)
    (some? tools)         (assoc :tools tools)
    auth-storage          (assoc :authStorage auth-storage)
    model-registry        (assoc :modelRegistry model-registry)
    resource-loader       (assoc :resourceLoader resource-loader)))

(defn options-map->js
  "Shallow-convert a session options map to a JS object.
   Only the top-level keys are converted; values are passed through as-is.
   This preserves JS object values (models, registries, etc.) without
   recursive conversion."
  [m]
  (let [obj #js {}]
    (doseq [[k v] m]
      (unchecked-set obj (name k) v))
    obj))

(defn classify-early-exit
  "Classify parsed CLI flags into an early-exit action descriptor, or nil
   for normal operation. Pure function — no side effects.

   Parameters (map):
     :version?      - true if --version flag is set
     :help?         - true if --help flag is set
     :list-models   - true or search string if --list-models is set, nil otherwise
     :export        - session path string if --export is set, nil otherwise
     :messages      - seq of message strings from CLI args
     :session-mode? - true if --session-mode flag is set
     :session?      - true if --session flag is set

   Returns a map describing the action, or nil:
     {:action :version}
     {:action :help}
     {:action :list-models, :search <string|nil>}
     {:action :export, :session-path <string>, :output-path <string|nil>}
     {:action :error, :message <string>}
     nil  — normal operation"
  [{:keys [version? help? list-models export messages session-mode? session?]}]
  (cond
    version?
    {:action :version}

    help?
    {:action :help}

    (some? list-models)
    {:action :list-models
     :search (when (string? list-models) list-models)}

    (some? export)
    {:action :export
     :session-path export
     :output-path (first messages)}

    (and session-mode? (not session?))
    {:action :error
     :message "--session-mode requires --session <path|id>"}

    :else nil))
