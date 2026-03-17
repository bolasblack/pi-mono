(ns pi.kernel.tools.bash
  (:require [pi.kernel.deftool :as deftool :include-macros true]
            [pi.kernel.tools.util :as tool-util]
            [cljs.core.async :refer [chan put! close!]]
            [clojure.string :as str]
            ["node:child_process" :as child-process]))

(defn- collect-output
  "Concatenate collected Buffer chunks into a UTF-8 string."
  [chunks-atom]
  (.toString (js/Buffer.concat (clj->js @chunks-atom)) "utf-8"))

(defn format-bash-output
  "Format raw command output with tail truncation and line-range annotation.
   Appends exit code message for non-zero codes. Pure function."
  [raw-output exit-code]
  (let [trunc (tool-util/truncate-tail raw-output)
        base  (if (empty? (:content trunc)) "(no output)" (:content trunc))]
    (cond-> (if (:truncated trunc)
              (let [start-line (- (:total-lines trunc) (:output-lines trunc) -1)]
                (str base
                     "\n\n[Showing lines " start-line "-" (:total-lines trunc)
                     " of " (:total-lines trunc) ".]"))
              base)
      (and exit-code (not= exit-code 0))
      (str "\n\nCommand exited with code " exit-code))))

(defn- finish!
  "CAS-guarded finalization: put result on channel and close it.
   No-ops if another path already finalized."
  [finished? result-ch result]
  (when (compare-and-set! finished? false true)
    (put! result-ch result)
    (close! result-ch)))

(defn- abort-message
  "Build abort/timeout message, prepending any existing output."
  [raw-output suffix]
  (str (when (seq raw-output) (str raw-output "\n\n")) suffix))

(defn bash-impl
  "Execute a bash command. Returns a core.async channel that yields {:content string}.
   Options:
     :command      - bash command string (required)
     :timeout      - timeout in seconds (optional)
     :on-update    - callback (fn [chunk-str]) called for each stdout/stderr chunk (optional)
     :abort-signal - JS AbortSignal to cancel execution (optional)"
  [{:keys [command timeout on-update abort-signal]}]
  (let [result-ch (chan 1)
        chunks    (atom [])
        finished? (atom false)
        child     (.spawn child-process "bash" #js ["-c" command]
                          #js {:cwd (.cwd js/process)
                               :env (.-env js/process)
                               :stdio #js ["ignore" "pipe" "pipe"]})
        on-data   (fn [data]
                    (swap! chunks conj data)
                    (when on-update
                      (on-update (.toString data "utf-8"))))]

    ;; Handle abort signal
    (when abort-signal
      (.addEventListener abort-signal "abort"
        (fn []
          (.kill child)
          (let [msg (abort-message (collect-output chunks) "Command aborted")]
            (finish! finished? result-ch {:error msg :content msg})))
        #js {:once true}))

    ;; Collect stdout + stderr with optional streaming
    (some-> (.-stdout child) (.on "data" on-data))
    (some-> (.-stderr child) (.on "data" on-data))

    ;; Set up timeout
    (let [timeout-handle
          (when (and timeout (pos? timeout))
            (js/setTimeout
              (fn []
                (.kill child)
                (let [msg (abort-message (collect-output chunks)
                                         (str "Command timed out after " timeout " seconds"))]
                  (finish! finished? result-ch {:error msg})))
              (* timeout 1000)))]

      (.on child "error"
           (fn [err]
             (when timeout-handle (js/clearTimeout timeout-handle))
             (finish! finished? result-ch {:error (.-message err)})))

      (.on child "close"
           (fn [code]
             (when timeout-handle (js/clearTimeout timeout-handle))
             (let [output (format-bash-output (collect-output chunks) code)]
               (finish! finished? result-ch
                        (if (and code (not= code 0))
                          {:error output}
                          {:content output}))))))
    result-ch))

(defn register!
  "Register the bash tool in the given registry.
   Note: bash is async - its execute function returns a core.async channel."
  [registry]
  (deftool/register-tool! registry
    {:name "bash"
     :doc "Execute a bash command in the current working directory. Returns stdout and stderr. Output is truncated to last 2000 lines or 50KB."
     :params-spec [:map
                   [:command :string]
                   [:timeout {:optional true} :int]]
     :execute (fn [params]
                (bash-impl params))}))
