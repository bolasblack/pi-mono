(ns pi.kernel.model-config
  "Model configuration: parsing model strings, resolving API keys,
   building provider configs with automatic mock fallback."
  (:require [clojure.string :as str]
            [pi.kernel.provider :as provider]))

(def default-model
  "Default model identifier used when none is specified."
  "anthropic/claude-haiku-4-5-20251001")

(defn parse-model-str
  "Parse 'provider/model-id' into {:api str :model-id str}.
   If no slash is found, the whole string is treated as the API name."
  [s]
  (let [idx (str/index-of s "/")]
    (if (some? idx)
      {:api (subs s 0 idx)
       :model-id (subs s (inc idx))}
      {:api s :model-id nil})))

(defn provider-available?
  "Check if a stream-provider method is registered for [api :default]."
  [api]
  (let [dispatch-val [api :default]]
    (contains? (methods provider/stream-provider) dispatch-val)))

(def ^:private provider-env-keys
  "Map provider name to its API-key environment variable."
  {"anthropic" "ANTHROPIC_API_KEY"
   "openai"    "OPENAI_API_KEY"
   "google"    "GOOGLE_API_KEY"
   "mistral"   "MISTRAL_API_KEY"})

(defn resolve-api-key
  "Look up the API key for a provider from the environment.
   Falls back to the generic convention PROVIDER_API_KEY (uppercased)."
  [api]
  (let [env-var (or (get provider-env-keys api)
                    (str (str/upper-case api) "_API_KEY"))
        value   (aget (.-env js/process) env-var)]
    (when (and value (not= "" value))
      value)))

(defn resolve-config
  "Pure decision logic: given parsed model info and availability context,
   returns {:config map :warning string-or-nil}.
   Separates fallback decisions from side effects for testability."
  [{:keys [api model-id api-key provider-available?]}]
  (cond
    (not provider-available?)
    {:config  {:api "mock" :provider :default}
     :warning (str "Provider '" api "' not available, falling back to mock provider")}

    (nil? api-key)
    {:config  {:api "mock" :provider :default}
     :warning (str "No API key set for '" api "', falling back to mock provider")}

    :else
    {:config  {:api      api
               :model-id model-id
               :provider :default
               :api-key  api-key}
     :warning nil}))

(defn make-provider-config
  "Build provider config from model string.
   Falls back to mock if provider unavailable or no API key."
  [model-str]
  (let [{:keys [api model-id]} (parse-model-str (or model-str default-model))
        api-key (resolve-api-key api)
        {:keys [config warning]} (resolve-config
                                   {:api                 api
                                    :model-id            model-id
                                    :api-key             api-key
                                    :provider-available? (provider-available? api)})]
    (when warning
      (.write js/process.stderr (str "Warning: " warning "\n")))
    config))
