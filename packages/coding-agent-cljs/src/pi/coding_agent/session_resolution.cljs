(ns pi.coding-agent.session-resolution
  "Session resolution logic extracted from core.
   Pure functions for flag validation and session-type classification,
   plus effectful functions for SessionManager creation."
  (:require [clojure.string :as str]
            [pi.kernel.promise :as p]
            ["@mariozechner/pi-coding-agent/dist/core/session-manager.js" :as session-mgr-mod]
            ["@mariozechner/pi-coding-agent/dist/core/model-resolver.js" :as model-resolver-mod]
            ["chalk" :default chalk]))

;; ---------------------------------------------------------------------------
;; Pure functions (testable without mocks)
;; ---------------------------------------------------------------------------

(defn validate-fork-flags
  "Validate that :fork doesn't conflict with other session flags.
   Returns an error message string if invalid, nil if valid.
   Accepts a map with keys :fork :session :continue :resume :no-session."
  [{:keys [fork session continue resume no-session]}]
  (when fork
    (let [conflicts (cond-> []
                      (some? session)  (conj "--session")
                      continue         (conj "--continue")
                      resume           (conj "--resume")
                      no-session       (conj "--no-session"))]
      (when (seq conflicts)
        (str "Error: --fork cannot be combined with "
             (str/join ", " conflicts))))))

(defn resolve-session-type
  "Classify a session argument string as :path or :id.
   Path-like strings contain / or \\\\ or end in .jsonl."
  [session-arg]
  (if (or (str/includes? session-arg "/")
          (str/includes? session-arg "\\")
          (str/ends-with? session-arg ".jsonl"))
    :path
    :id))

(defn session-mode-dispatch
  "Determine session creation strategy from parsed flags.
   Returns one of: :in-memory :create :open :auto :lookup :continue-recent :default.
   Accepts a map with keys :no-session :session :session-mode :continue."
  [{:keys [no-session session session-mode continue]}]
  (cond
    no-session                                :in-memory
    (and session (= session-mode "create"))   :create
    (and session (= session-mode "continue")) :open
    (and session session-mode)                :auto
    (and session (nil? session-mode))         :lookup
    continue                                  :continue-recent
    :else                                     :default))

;; ---------------------------------------------------------------------------
;; Effectful functions (SessionManager interop)
;; ---------------------------------------------------------------------------

(defn- resolve-session-path
  "Resolve a session argument to a path. Returns a Promise of
   #js {:type <string> :path <string> ...}."
  [session-arg cwd session-dir]
  (let [SessionManager (.-SessionManager session-mgr-mod)]
    (if (= :path (resolve-session-type session-arg))
      (p/resolved #js {:type "path" :path session-arg})
      ;; Try local sessions first, then global
      (-> (.list SessionManager cwd session-dir)
          (p/then-let [local-sessions]
            (let [matches (.filter local-sessions
                                   (fn [s] (str/starts-with? (.-id s) session-arg)))]
              (if (>= (.-length matches) 1)
                #js {:type "local" :path (.-path (aget matches 0))}
                (-> (.listAll SessionManager)
                    (p/then-let [all-sessions]
                      (let [gmatches (.filter all-sessions
                                              (fn [s] (str/starts-with? (.-id s) session-arg)))]
                        (if (>= (.-length gmatches) 1)
                          #js {:type "global"
                               :path (.-path (aget gmatches 0))
                               :cwd  (.-cwd (aget gmatches 0))}
                          #js {:type "not_found" :arg session-arg})))))))))))

(defn- handle-create-mode
  "Handle --session-mode=create: error if session exists, else create new."
  [session-arg cwd session-dir]
  (let [SessionManager (.-SessionManager session-mgr-mod)]
    (-> (.list SessionManager cwd session-dir)
        (p/then-let [sessions]
          (let [existing (.find sessions (fn [s] (= (.-id s) session-arg)))]
            (if existing
              (do (.error js/console
                    (.red chalk (str "Session '" session-arg "' already exists")))
                  (.exit js/process 1))
              (let [sm (.create SessionManager cwd session-dir)]
                (.newSession sm #js {:id session-arg})
                sm)))))))

(defn- handle-open-mode
  "Handle --session-mode=continue: resolve and open, error if not found."
  [session-arg cwd session-dir]
  (let [SessionManager (.-SessionManager session-mgr-mod)]
    (-> (resolve-session-path session-arg cwd session-dir)
        (p/then-let [resolved]
          (if (= (.-type resolved) "not_found")
            (do (.error js/console
                  (.red chalk (str "Session '" session-arg "' not found")))
                (.exit js/process 1))
            (.open SessionManager (.-path resolved) session-dir))))))

(defn- handle-auto-mode
  "Handle --session-mode=auto: open if found, create if not."
  [session-arg cwd session-dir]
  (let [SessionManager (.-SessionManager session-mgr-mod)]
    (-> (resolve-session-path session-arg cwd session-dir)
        (p/then-let [resolved]
          (if (= (.-type resolved) "not_found")
            (let [sm (.create SessionManager cwd session-dir)]
              (.newSession sm #js {:id session-arg})
              sm)
            (.open SessionManager (.-path resolved) session-dir))))))

(defn- handle-lookup-mode
  "Handle --session without --session-mode: prefix match, error if not found."
  [session-arg cwd session-dir]
  (let [SessionManager (.-SessionManager session-mgr-mod)]
    (-> (resolve-session-path session-arg cwd session-dir)
        (p/then-let [resolved]
          (case (.-type resolved)
            "path"   (.open SessionManager (.-path resolved) session-dir)
            "local"  (.open SessionManager (.-path resolved) session-dir)
            "global" (do (.log js/console
                           (.yellow chalk
                             (str "Session found in different project: "
                                  (.-cwd resolved))))
                         (.open SessionManager (.-path resolved) session-dir))
            "not_found"
            (do (.error js/console
                  (.red chalk
                    (str "No session found matching '"
                         (.-arg resolved) "'")))
                (.exit js/process 1)))))))

(defn create-session-manager
  "Create a SessionManager based on parsed CLI args.
   Returns a Promise<SessionManager|undefined>."
  [parsed cwd _extensions-result]
  (let [SessionManager (.-SessionManager session-mgr-mod)
        session-arg    (.-session parsed)
        session-dir    (.-sessionDir parsed)
        mode (session-mode-dispatch
               {:no-session   (.-noSession parsed)
                :session      session-arg
                :session-mode (.-sessionMode parsed)
                :continue     (.-continue parsed)})]
    (case mode
      :in-memory       (p/resolved (.inMemory SessionManager))
      :create          (handle-create-mode session-arg cwd session-dir)
      :open            (handle-open-mode session-arg cwd session-dir)
      :auto            (handle-auto-mode session-arg cwd session-dir)
      :lookup          (handle-lookup-mode session-arg cwd session-dir)
      :continue-recent (p/resolved
                         (.continueRecent SessionManager cwd session-dir))
      :default         (p/resolved js/undefined))))

(defn resolve-scoped-models
  "Resolve scoped models from CLI --models or settings.
   Returns a Promise<Array>."
  [parsed settings-manager model-registry]
  (let [model-patterns (or (.-models parsed)
                           (.getEnabledModels settings-manager))]
    (if (and model-patterns (> (.-length model-patterns) 0))
      (.resolveModelScope model-resolver-mod model-patterns model-registry)
      (p/resolved #js []))))

(defn format-scoped-model-name
  "Format a scoped model JS object as a display string.
   Appends :thinkingLevel suffix when present."
  [^js sm]
  (let [thinking-str (if (.-thinkingLevel sm)
                       (str ":" (.-thinkingLevel sm))
                       "")]
    (str (.-id (.-model sm)) thinking-str)))

(defn log-scoped-models
  "Log scoped model info if verbose or not quiet startup."
  [scoped-models parsed settings-manager]
  (when (and (> (.-length scoped-models) 0)
             (or (.-verbose parsed)
                 (not (.getQuietStartup settings-manager))))
    (let [model-list (->> (array-seq scoped-models)
                          (map format-scoped-model-name)
                          (str/join ", "))]
      (.log js/console
        (.dim chalk (str "Model scope: " model-list " "
                         (.gray chalk "(Ctrl+P to cycle)")))))))
