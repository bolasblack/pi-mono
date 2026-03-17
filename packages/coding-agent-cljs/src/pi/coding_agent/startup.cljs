(ns pi.coding-agent.startup
  "Startup checks and notifications.
   Extracted from interactive-mode for modularity and testability."
  (:require [clojure.string :as str]
            [pi.kernel.bridge.theme :as theme]
            [pi.kernel.promise :as p]
            ["node:child_process" :as child-process]
            ["@mariozechner/pi-tui" :as tui-mod]
            ["@mariozechner/pi-coding-agent/dist/config.js" :as config-mod]
            ["@mariozechner/pi-coding-agent/dist/utils/changelog.js" :as changelog-mod]
            ["@mariozechner/pi-coding-agent/dist/modes/interactive/components/dynamic-border.js" :as dynamic-border-mod]))

;; ============================================================================
;; Pure decision helpers
;; ============================================================================

(defn tmux-keyboard-warning
  "Given tmux setting values, return a warning message or nil. Pure."
  [extended-keys extended-keys-format]
  (cond
    (and (not= extended-keys "on")
         (not= extended-keys "always"))
    "tmux extended-keys is off. Modified Enter keys may not work. Add `set -g extended-keys on` to ~/.tmux.conf and restart tmux."

    (= extended-keys-format "xterm")
    "tmux extended-keys-format is xterm. Pi works best with csi-u. Add `set -g extended-keys-format csi-u` to ~/.tmux.conf and restart tmux."

    :else nil))

(defn changelog-display-action
  "Decide changelog display action based on session state. Pure.
   Returns :skip | :record-version | :check-entries"
  [message-count last-version]
  (cond
    (> message-count 0) :skip
    (nil? last-version) :record-version
    :else :check-entries))

;; ============================================================================
;; Version check
;; ============================================================================

(defn check-for-new-version
  "Check npm registry for a newer version. Returns Promise<string|nil>.
   Takes version string directly (no state atom needed)."
  [version]
  (if (or (.-PI_SKIP_VERSION_CHECK js/process.env)
          (.-PI_OFFLINE js/process.env))
    (p/resolved nil)
    (-> (js/fetch "https://registry.npmjs.org/@mariozechner/pi-coding-agent/latest"
                  #js {:signal (.timeout js/AbortSignal 10000)})
        (p/then-let [response]
          (if (not (.-ok response))
            nil
            (-> (.json response)
                (p/then-let [data]
                  (when data
                    (let [latest (.-version data)]
                      (when (and latest (not= latest version))
                        latest)))))))
        (p/catch-let [_]
          nil))))

;; ============================================================================
;; Tmux keyboard check
;; ============================================================================

(defn check-tmux-keyboard-setup
  "Check tmux keyboard setup. Returns Promise<string|nil>."
  []
  (if-not (.-TMUX js/process.env)
    (p/resolved nil)
    (let [run-tmux-show
          (fn [option]
            (p/create
              (fn [resolve]
                (let [proc (.spawn child-process "tmux" #js ["show" "-gv" option]
                                   #js {:stdio #js ["ignore" "pipe" "ignore"]})
                      stdout (atom "")
                      timer (js/setTimeout (fn [] (.kill proc) (resolve nil)) 2000)]
                  (when (.-stdout proc)
                    (.on (.-stdout proc) "data"
                         (fn [data] (swap! stdout str (.toString data)))))
                  (.on proc "error"
                       (fn [_] (js/clearTimeout timer) (resolve nil)))
                  (.on proc "close"
                       (fn [code]
                         (js/clearTimeout timer)
                         (resolve (when (= code 0) (str/trim @stdout)))))))))]
      (-> (p/all [(run-tmux-show "extended-keys")
                  (run-tmux-show "extended-keys-format")])
          (p/then-let [results]
            (let [extended-keys (aget results 0)
                  extended-keys-format (aget results 1)]
              (tmux-keyboard-warning extended-keys extended-keys-format)))))))

;; ============================================================================
;; Changelog display
;; ============================================================================

(defn get-changelog-for-display
  "Get changelog entries to display on startup. Returns markdown string or nil.
   Takes explicit params instead of state atom for better decoupling."
  [message-count version settings-manager]
  (let [last-version (.getLastChangelogVersion settings-manager)
        action (changelog-display-action message-count last-version)]
    (case action
      :skip nil
      :record-version
      (do (.setLastChangelogVersion settings-manager version)
          nil)
      :check-entries
      (let [get-changelog-path (.-getChangelogPath changelog-mod)
            parse-changelog (.-parseChangelog changelog-mod)
            get-new-entries (.-getNewEntries changelog-mod)
            changelog-path (get-changelog-path)
            entries (parse-changelog changelog-path)
            new-entries (get-new-entries entries last-version)]
        (when (seq (array-seq new-entries))
          (.setLastChangelogVersion settings-manager version)
          (.join (.map new-entries (fn [e] (.-content e))) "\n\n"))))))

;; ============================================================================
;; Version notification
;; ============================================================================

(defn show-new-version-notification!
  "Show a notification about a new version being available."
  [chat-container ui new-version]
  (let [DynamicBorder (.-DynamicBorder dynamic-border-mod)
        Spacer (.-Spacer tui-mod)
        Text (.-Text tui-mod)
        t (theme/theme)
        get-update-instruction (.-getUpdateInstruction config-mod)
        action (theme/fg t "accent" (get-update-instruction "@mariozechner/pi-coding-agent"))
        update-instruction (str (theme/fg t "muted" (str "New version " new-version " is available. "))
                                action)
        changelog-url (theme/fg t "accent"
                                "https://github.com/badlogic/pi-mono/blob/main/packages/coding-agent/CHANGELOG.md")
        changelog-line (str (theme/fg t "muted" "Changelog: ") changelog-url)]
    (.addChild chat-container (Spacer. 1))
    (.addChild chat-container (DynamicBorder. (fn [text] (theme/fg t "warning" text))))
    (.addChild chat-container
               (Text. (str (theme/bold (theme/fg t "warning" "Update Available"))
                           "\n" update-instruction "\n" changelog-line) 1 0))
    (.addChild chat-container (DynamicBorder. (fn [text] (theme/fg t "warning" text))))
    (.requestRender ui)))
