(ns pi.kernel.bridge.theme
  "Bridge to the TS theme module.
   Provides idiomatic CLJS access to theme colors, text styling,
   lifecycle management, syntax highlighting, and theme queries.
   Consolidates all theme functionality alongside tui-theme."
  (:require ["@mariozechner/pi-coding-agent/dist/modes/interactive/theme/theme.js" :as ts-theme-mod]))

;; The global theme instance (a Proxy that delegates to the current Theme).
(def ^:private ts-theme (.-theme ts-theme-mod))

;; --- Core accessors ---

(defn theme
  "Returns the current global Theme instance."
  []
  ts-theme)

;; --- Text styling (delegates to Theme instance methods) ---

(defn fg       [t color text] (.fg t color text))
(defn bg       [t color text] (.bg t color text))
(defn bold     [text]         (.bold ts-theme text))
(defn italic   [text]         (.italic ts-theme text))
(defn underline [text]        (.underline ts-theme text))
(defn inverse  [text]         (.inverse ts-theme text))
(defn strikethrough [text]    (.strikethrough ts-theme text))

;; --- Theme lifecycle ---

(defn init-theme!
  ([theme-name]            (.call (.-initTheme ts-theme-mod) nil theme-name true))
  ([theme-name watcher?]   (.call (.-initTheme ts-theme-mod) nil theme-name watcher?)))

(defn set-theme!          [name & [watcher?]] (.call (.-setTheme ts-theme-mod) nil name (boolean watcher?)))
(defn set-theme-instance! [instance]          (.call (.-setThemeInstance ts-theme-mod) nil instance))
(defn on-theme-change!    [callback]          (.call (.-onThemeChange ts-theme-mod) nil callback))
(defn stop-theme-watcher! []                  (.call (.-stopThemeWatcher ts-theme-mod) nil))
(defn set-registered-themes! [themes]         (.call (.-setRegisteredThemes ts-theme-mod) nil themes))

;; --- Theme queries ---

(defn get-editor-theme       []     (.call (.-getEditorTheme ts-theme-mod) nil))
(defn get-markdown-theme     []     (.call (.-getMarkdownTheme ts-theme-mod) nil))
(defn get-select-list-theme  []     (.call (.-getSelectListTheme ts-theme-mod) nil))
(defn get-settings-list-theme []    (.call (.-getSettingsListTheme ts-theme-mod) nil))
(defn get-thinking-border-color [t level] (.getThinkingBorderColor t level))
(defn get-bash-mode-border-color [t]      (.getBashModeBorderColor t))

;; --- Syntax highlighting ---

(defn highlight-code       [code lang]  (.call (.-highlightCode ts-theme-mod) nil code lang))
(defn get-language-from-path [path]     (.call (.-getLanguageFromPath ts-theme-mod) nil path))

;; --- Theme info / export ---

(defn get-available-themes            []     (.call (.-getAvailableThemes ts-theme-mod) nil))
(defn get-available-themes-with-paths []     (.call (.-getAvailableThemesWithPaths ts-theme-mod) nil))
(defn get-theme-by-name               [name] (.call (.-getThemeByName ts-theme-mod) nil name))
(defn get-resolved-theme-colors ([] (.call (.-getResolvedThemeColors ts-theme-mod) nil))
                                ([name] (.call (.-getResolvedThemeColors ts-theme-mod) nil name)))
(defn is-light-theme ([] (.call (.-isLightTheme ts-theme-mod) nil))
                     ([name] (.call (.-isLightTheme ts-theme-mod) nil name)))
(defn get-theme-export-colors ([] (.call (.-getThemeExportColors ts-theme-mod) nil))
                              ([name] (.call (.-getThemeExportColors ts-theme-mod) nil name)))
