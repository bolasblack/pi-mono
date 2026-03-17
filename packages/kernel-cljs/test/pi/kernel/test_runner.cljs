(ns pi.kernel.test-runner
  "ESM test runner. Calls deftest functions directly (no pi-ai dependency)."
  (:require [cljs.test :as t]
            [pi.kernel.lifecycle-events-test :as lifecycle]
            [pi.kernel.session-test :as session-t]
            [pi.kernel.state-test :as state-t]
            [pi.kernel.interceptor-test :as ic-t]
            [pi.kernel.interceptor-async-test :as ica-t]
            [pi.kernel.streaming-test :as stream-t]
            [pi.kernel.deftool :as deftool]
            [pi.kernel.deftool-test :as dt-t]
            [pi.kernel.session :as session]
            [pi.kernel.persistence-test :as persist-t]
            [pi.kernel.persistence-data-test :as persist-data-t]
            [pi.kernel.state-integration-test :as si-t]
            [pi.kernel.bash-streaming-test :as bash-t]
            [pi.kernel.agent-test :as agent-t]
            [pi.kernel.defaults-pure-test :as defaults-pure-t]
            [pi.kernel.interceptors.defaults-test :as defaults-extract-t]
            [pi.kernel.stream-handler-test :as sh-t]
            [pi.kernel.provider.messages-test :as msgs-t]
            [pi.kernel.self-mod-analysis-pure-test :as sma-pure-t]
            [pi.kernel.commands-test :as cmd-t]
            [pi.kernel.promise-test :as promise-t]
            [pi.kernel.bash-format-test :as bash-fmt-t]
            [pi.kernel.tools.util-test :as util-t]
            [pi.kernel.core-pure-test :as core-pure-t]
            [pi.kernel.model-config-test :as mc-t]))

(defn main []
  (let [pass (atom 0)
        fail (atom 0)
        error (atom 0)]

    (defmethod t/report [:cljs.test/default :pass] [_m]
      (swap! pass inc) (print "."))
    (defmethod t/report [:cljs.test/default :fail] [m]
      (swap! fail inc)
      (println "\nFAIL:" (:message m))
      (println "  expected:" (pr-str (:expected m)))
      (println "  actual:" (pr-str (:actual m))))
    (defmethod t/report [:cljs.test/default :error] [m]
      (swap! error inc)
      (println "\nERROR:" (:message m))
      (println "  " (:actual m)))

    ;; lifecycle-events-test (async)
    (println "Testing pi.kernel.lifecycle-events-test")
    (lifecycle/message-lifecycle-events-test)
    (lifecycle/tool-execution-lifecycle-events-test)
    (lifecycle/tool-execution-update-events-test)
    (lifecycle/message-accumulation-test)

    ;; session-test
    (println "\nTesting pi.kernel.session-test")
    (session-t/create-and-query-user-message-test)
    (session-t/query-tool-errors-test)
    (session-t/query-entries-since-test)
    (session-t/query-turn-count-test)
    (session-t/query-edited-files-test)
    (session-t/injectable-clock-test)

    ;; state-test
    (println "\nTesting pi.kernel.state-test")
    (state-t/create-and-get-state-test)
    (state-t/update-state-immutability-test)
    (state-t/fork-independence-test)
    (state-t/merge-fork-test)
    (state-t/state-diff-test)
    (state-t/state-diff-added-removed-keys-test)
    (state-t/merge-fork-with-new-keys-test)

    ;; interceptor-test
    (println "\nTesting pi.kernel.interceptor-test")
    (ic-t/empty-chain-test)
    (ic-t/single-enter-test)
    (ic-t/enter-leave-order-test)
    (ic-t/error-in-enter-test)
    (ic-t/insert-before-test)
    (ic-t/insert-after-test)
    (ic-t/remove-interceptor-test)
    (ic-t/replace-interceptor-test)
    (ic-t/leave-only-interceptor-test)
    (ic-t/error-without-handler-propagates-test)
    (ic-t/error-in-leave-with-handler-test)
    (ic-t/error-in-leave-no-handler-propagates-test)

    ;; interceptor-async-test (async)
    (println "\nTesting pi.kernel.interceptor-async-test")
    (ica-t/execute-async-sync-only-test)
    (ica-t/execute-async-enter-test)
    (ica-t/execute-async-leave-test)
    (ica-t/execute-async-mixed-chain-test)
    (ica-t/execute-async-error-in-enter-test)
    (ica-t/execute-async-error-handler-async-test)
    (ica-t/execute-async-error-in-leave-with-handler-test)
    (ica-t/execute-async-error-in-leave-no-handler-test)

    ;; streaming-test (async)
    (println "\nTesting pi.kernel.streaming-test")
    (stream-t/create-event-bus-text-event)
    (stream-t/multiple-consumers-receive-events)
    (stream-t/dropping-buffer-consumer)
    (stream-t/event-types-received)
    (stream-t/close-channel-signals-consumers)
    (stream-t/unsubscribe-stops-delivery)
    (stream-t/subscribe-js-delivers-js-objects)

    ;; deftool-test (has use-fixtures :each, must set *registry* manually)
    (println "\nTesting pi.kernel.deftool-test")
    (let [setup (fn [] (set! dt-t/*registry* (deftool/create-tool-registry)))
          teardown (fn [] (set! dt-t/*registry* nil))
          run (fn [f] (setup) (f) (teardown))]
      (run dt-t/test-source-form-metadata)
      (run dt-t/test-json-schema-generation)
      (run dt-t/test-validation-error-on-invalid-params)
      (run dt-t/test-valid-execution)
      (run dt-t/test-list-tools)
      (run dt-t/test-get-tool-returns-nil-for-unknown))

    ;; persistence-test (has use-fixtures :each, must set *session* manually)
    (println "\nTesting pi.kernel.persistence-test")
    (let [setup (fn []
                  (set! persist-t/*session*
                        (session/create-session
                          {:clock-fn (let [c (atom 0)] (fn [] (swap! c inc)))})))
          teardown (fn [] (set! persist-t/*session* nil))
          run (fn [f] (setup) (f) (teardown))]
      (run persist-t/test-serialize-deserialize-roundtrip)
      (run persist-t/test-create-persistence-handler)
      (run persist-t/test-save-session-calls-write-fn)
      (run persist-t/test-load-session-calls-read-fn)
      (run persist-t/test-save-load-roundtrip-preserves-queryable-data)
      (run persist-t/test-serialize-full-session-includes-both)
      (run persist-t/test-deserialize-full-session-restores-both)
      (run persist-t/test-roundtrip-tool-modifications)
      (run persist-t/test-roundtrip-learned-patterns)
      (run persist-t/test-save-load-full-session-roundtrip)
      (run persist-t/test-backward-compat-v1-format))

    ;; persistence-data-test (data-level functions)
    (println "\nTesting pi.kernel.persistence-data-test")
    (let [setup (fn []
                  (set! persist-data-t/*session*
                        (session/create-session
                          {:clock-fn (let [c (atom 0)] (fn [] (swap! c inc)))})))
          teardown (fn [] (set! persist-data-t/*session* nil))
          run (fn [f] (setup) (f) (teardown))]
      (run persist-data-t/session->entities-returns-vector-test)
      (run persist-data-t/session->entities-preserves-data-test)
      (run persist-data-t/entities->conn-roundtrip-test)
      (run persist-data-t/full-session-data-roundtrip-test)
      (run persist-data-t/session->entities-is-serializable-test))

    ;; state-integration-test (async, skip run-agent-turn tests — need pi-ai)
    (println "\nTesting pi.kernel.state-integration-test")
    (si-t/state-container-flows-through-chain-test)
    (si-t/interceptor-updates-state-via-update-state-test)
    (si-t/state-persists-across-turns-test)
    (si-t/state-has-expected-shape-test)

    ;; agent-test
    (println "\nTesting pi.kernel.agent-test")
    (agent-t/create-agent-returns-map-test)
    (agent-t/create-agent-default-system-prompt-test)
    (agent-t/create-agent-custom-system-prompt-test)
    (agent-t/create-agent-custom-tools-test)
    (agent-t/create-agent-default-tools-test)
    (agent-t/create-agent-default-tools-plus-custom-test)
    (agent-t/create-agent-model-config-test)
    (agent-t/create-agent-max-tool-turns-test)
    (agent-t/two-agents-independent-tools-test)
    (agent-t/two-agents-independent-prompts-test)
    (agent-t/mutating-one-registry-doesnt-affect-other-test)

    ;; bash-streaming-test (async)
    (println "\nTesting pi.kernel.bash-streaming-test")
    (bash-t/bash-on-update-receives-chunks-test)
    (bash-t/bash-on-update-receives-stderr-test)
    (bash-t/bash-without-on-update-still-works-test)
    (bash-t/bash-abort-signal-test)
    (bash-t/bash-abort-with-on-update-test)

    ;; defaults-pure-test (entry->message, tool-call->result-entry, max-turns)
    (println "\nTesting pi.kernel.defaults-pure-test")
    (defaults-pure-t/entry->message-user-test)
    (defaults-pure-t/entry->message-assistant-text-only-test)
    (defaults-pure-t/entry->message-assistant-with-tools-test)
    (defaults-pure-t/entry->message-tool-result-test)
    (defaults-pure-t/entry->message-unknown-type-test)
    (defaults-pure-t/tool-call->result-entry-success-test)
    (defaults-pure-t/tool-call->result-entry-error-test)
    (defaults-pure-t/tool-call->result-entry-empty-result-test)
    (defaults-pure-t/max-turns-exceeded-test)
    (defaults-pure-t/build-accumulated-message-reexport-test)
    (defaults-pure-t/build-finalization-result-reexport-test)

    ;; interceptors/defaults-test (normalize-tool-result, merge-tool-opts, build-messages)
    (println "\nTesting pi.kernel.interceptors.defaults-test")
    (defaults-extract-t/normalize-tool-result-nil)
    (defaults-extract-t/normalize-tool-result-with-content)
    (defaults-extract-t/normalize-tool-result-error-without-content)
    (defaults-extract-t/normalize-tool-result-error-with-content)
    (defaults-extract-t/normalize-tool-result-extra-keys)
    (defaults-extract-t/merge-tool-opts-nil-opts)
    (defaults-extract-t/merge-tool-opts-empty-opts)
    (defaults-extract-t/merge-tool-opts-on-update)
    (defaults-extract-t/merge-tool-opts-abort-signal)
    (defaults-extract-t/merge-tool-opts-both)
    (defaults-extract-t/build-messages-empty-session)
    (defaults-extract-t/build-messages-with-entries)

    ;; stream-handler-test (pure state transitions + helpers + event handling)
    (println "\nTesting pi.kernel.stream-handler-test")
    (sh-t/apply-text-test)
    (sh-t/apply-thinking-test)
    (sh-t/apply-tool-call-test)
    (sh-t/apply-tool-call-start-test)
    (sh-t/apply-tool-input-test)
    (sh-t/apply-tool-input-noop-without-cur-tool-test)
    (sh-t/apply-block-stop-test)
    (sh-t/mark-started-test)
    (sh-t/mark-finished-test)
    (sh-t/build-accumulated-message-text-only-test)
    (sh-t/build-accumulated-message-with-thinking-test)
    (sh-t/build-accumulated-message-with-tool-calls-test)
    (sh-t/build-accumulated-message-empty-test)
    (sh-t/build-accumulated-message-nil-model-config-test)
    (sh-t/build-accumulated-message-all-three-test)
    (sh-t/build-finalization-result-normal-test)
    (sh-t/build-finalization-result-aborted-test)
    (sh-t/handle-stream-event-text-accumulates-test)
    (sh-t/handle-stream-event-thinking-accumulates-test)
    (sh-t/handle-stream-event-tool-call-accumulates-test)
    (sh-t/handle-stream-event-ignores-after-finished-test)
    (sh-t/handle-stream-event-stop-finalizes-test)
    (sh-t/handle-stream-event-text-sets-started-and-state-test)
    (sh-t/handle-stream-event-thinking-sets-started-and-state-test)
    (sh-t/handle-stream-event-tool-call-sets-started-and-state-test)

    ;; provider/messages-test
    (println "\nTesting pi.kernel.provider.messages-test")
    (msgs-t/convert-user-msg-basic)
    (msgs-t/convert-tool-result-msg-basic)
    (msgs-t/convert-tool-result-msg-error)
    (msgs-t/convert-tool-result-msg-nil-content)
    (msgs-t/build-assistant-content-text-only)
    (msgs-t/build-assistant-content-tool-calls-only)
    (msgs-t/build-assistant-content-both)
    (msgs-t/build-assistant-content-empty-fallback)
    (msgs-t/convert-assistant-msg-text)
    (msgs-t/convert-assistant-msg-with-tool-calls)
    (msgs-t/convert-messages-for-pi-ai-mixed)
    (msgs-t/tool-json-schema-from-json-schema)
    (msgs-t/tool-json-schema-nil-when-missing)
    (msgs-t/tools-for-provider-basic)
    (msgs-t/tools-for-provider-nil-registry)
    (msgs-t/tools-for-provider-skips-no-schema)

    ;; self-mod-analysis-pure-test
    (println "\nTesting pi.kernel.self-mod-analysis-pure-test")
    (sma-pure-t/should-auto-disable?-under-min-uses-test)
    (sma-pure-t/should-auto-disable?-low-failure-rate-test)
    (sma-pure-t/should-auto-disable?-high-failure-rate-test)
    (sma-pure-t/should-auto-disable?-exactly-at-threshold-test)
    (sma-pure-t/should-auto-disable?-zero-total-test)
    (sma-pure-t/next-tool-stats-success-test)
    (sma-pure-t/next-tool-stats-failure-test)
    (sma-pure-t/next-tool-stats-nil-stats-test)
    (sma-pure-t/compute-leave-updates-no-modifications-test)
    (sma-pure-t/compute-leave-updates-success-increment-test)
    (sma-pure-t/compute-leave-updates-failure-increment-test)
    (sma-pure-t/compute-leave-updates-auto-disable-test)
    (sma-pure-t/compute-leave-updates-skips-disabled-test)
    (sma-pure-t/compute-leave-updates-multiple-tools-test)

    ;; commands-test
    (println "\nTesting pi.kernel.commands-test")
    (cmd-t/parse-command-basic-test)
    (cmd-t/format-help-text-test)
    (cmd-t/format-model-display-test)
    (cmd-t/format-compact-info-test)
    (cmd-t/generate-export-filename-test)
    (cmd-t/format-export-content-test)

    ;; model-config-test
    (println "\nTesting pi.kernel.model-config-test")
    (mc-t/parse-model-str-with-slash-test)
    (mc-t/parse-model-str-without-slash-test)
    (mc-t/resolve-config-happy-path-test)
    (mc-t/resolve-config-provider-unavailable-test)
    (mc-t/resolve-config-no-api-key-test)
    (mc-t/resolve-config-priority-test)
    (mc-t/resolve-api-key-known-provider-test)
    (mc-t/resolve-api-key-unknown-provider-test)
    (mc-t/resolve-api-key-empty-string-test)
    (mc-t/resolve-api-key-present-test)
    (mc-t/provider-available-mock-test)
    (mc-t/provider-available-nonexistent-test)

    ;; promise-test (async)
    (println "\nTesting pi.kernel.promise-test")
    (promise-t/resolved-test)
    (promise-t/rejected-test)
    (promise-t/create-test)
    (promise-t/create-reject-test)
    (promise-t/then-test)
    (promise-t/then-chaining-test)
    (promise-t/catch-test)
    (promise-t/catch-recovery-test)
    (promise-t/all-with-vector-test)
    (promise-t/all-with-list-test)
    (promise-t/all-empty-test)
    (promise-t/thread-first-pipeline-test)
    (promise-t/nil-resolved-test)

    ;; bash-format-test
    (println "\nTesting pi.kernel.bash-format-test")
    (bash-fmt-t/format-bash-output-normal-test)
    (bash-fmt-t/format-bash-output-empty-test)
    (bash-fmt-t/format-bash-output-nonzero-exit-test)
    (bash-fmt-t/format-bash-output-nil-exit-code-test)
    (bash-fmt-t/format-bash-output-truncated-test)
    (bash-fmt-t/format-bash-output-truncated-with-error-test)

    ;; tools/util-test
    (println "\nTesting pi.kernel.tools.util-test")
    (util-t/resolve-path-absolute)
    (util-t/resolve-path-relative)
    (util-t/truncate-head-no-truncation)
    (util-t/truncate-head-by-lines)
    (util-t/truncate-head-by-bytes)
    (util-t/truncate-tail-no-truncation)
    (util-t/truncate-tail-by-lines)
    (util-t/truncate-tail-by-bytes)
    (util-t/truncate-empty-string)
    (util-t/truncate-single-line)

    ;; core-pure-test (pure functions from agent loop)
    (println "\nTesting pi.kernel.core-pure-test")
    (core-pure-t/should-continue-turn?-has-results-and-room-test)
    (core-pure-t/should-continue-turn?-no-results-test)
    (core-pure-t/should-continue-turn?-stopped-test)
    (core-pure-t/should-continue-turn?-at-max-turns-test)
    (core-pure-t/should-continue-turn?-above-max-turns-test)
    (core-pure-t/should-continue-turn?-aborted-test)
    (core-pure-t/should-continue-turn?-nil-results-test)
    (core-pure-t/build-turn-context-minimal-test)
    (core-pure-t/build-turn-context-non-root-turn-test)
    (core-pure-t/build-turn-context-explicit-root-turn-test)
    (core-pure-t/build-turn-context-with-abort-signal-test)
    (core-pure-t/build-turn-context-without-abort-signal-test)
    (core-pure-t/build-turn-context-with-pi-ai-model-test)
    (core-pure-t/build-turn-context-max-tool-turns-from-agent-test)

    ;; Wait for async tests
    (js/setTimeout
      (fn []
        (let [total (+ @pass @fail @error)]
          (println (str "\n\nRan " total " assertions. "
                        @pass " passed, " @fail " failed, " @error " errors."))
          (.exit js/process (if (pos? (+ @fail @error)) 1 0))))
      5000)))
