(ns pi.coding-agent.event-convert-test
  "Tests for clj->js-event conversion."
  (:require [cljs.test :refer [deftest testing is]]
            [pi.coding-agent.event-convert :as ec]))

(deftest agent-start-conversion-test
  (testing "converts :agent-start to JS"
    (let [js-evt (ec/clj->js-event {:type :agent-start})]
      (is (some? js-evt))
      (is (= "agent_start" (.-type js-evt))))))

(deftest agent-end-conversion-test
  (testing "converts :agent-end to JS"
    (let [js-evt (ec/clj->js-event {:type :agent-end})]
      (is (= "agent_end" (.-type js-evt))))))

(deftest message-start-conversion-test
  (testing "converts :message-start with message and role"
    (let [msg {:role "assistant"
               :content [{:type :text :text "hello"}]
               :stop-reason "stop"
               :usage {:input 10 :output 5}
               :model "claude" :provider "anthropic" :api "anthropic"
               :timestamp 12345}
          js-evt (ec/clj->js-event {:type :message-start :message msg :role "assistant"})]
      (is (= "message_start" (.-type js-evt)))
      (is (= "assistant" (.-role js-evt)))
      (is (some? (.-message js-evt)))
      (is (= "assistant" (.. js-evt -message -role)))
      (is (= 1 (.-length (.. js-evt -message -content))))
      (is (= "text" (.. js-evt -message -content (at 0) -type)))
      (is (= "hello" (.. js-evt -message -content (at 0) -text))))))

(deftest message-update-conversion-test
  (testing "converts :message-update"
    (let [msg {:role "assistant" :content [{:type :text :text "partial"}]
               :model "m" :provider "p" :api "a" :timestamp 1}
          js-evt (ec/clj->js-event {:type :message-update :message msg})]
      (is (= "message_update" (.-type js-evt)))
      (is (some? (.-message js-evt))))))

(deftest message-end-conversion-test
  (testing "converts :message-end"
    (let [msg {:role "assistant" :content [] :model "m" :provider "p" :api "a" :timestamp 1}
          js-evt (ec/clj->js-event {:type :message-end :message msg})]
      (is (= "message_end" (.-type js-evt)))
      (is (some? (.-message js-evt))))))

(deftest tool-execution-start-conversion-test
  (testing "converts :tool-execution-start"
    (let [js-evt (ec/clj->js-event {:type :tool-execution-start
                                     :tool-call-id "tc-1"
                                     :tool-name "bash"
                                     :args {:command "ls"}})]
      (is (= "tool_execution_start" (.-type js-evt)))
      (is (= "tc-1" (.-toolCallId js-evt)))
      (is (= "bash" (.-toolName js-evt)))
      (is (= "ls" (.. js-evt -args -command))))))

(deftest tool-execution-update-conversion-test
  (testing "converts :tool-execution-update"
    (let [js-evt (ec/clj->js-event {:type :tool-execution-update
                                     :tool-call-id "tc-1"
                                     :tool-name "bash"
                                     :partial-result {:content "output"}})]
      (is (= "tool_execution_update" (.-type js-evt)))
      (is (= "tc-1" (.-toolCallId js-evt)))
      (is (some? (.-partialResult js-evt))))))

(deftest tool-execution-end-conversion-test
  (testing "converts :tool-execution-end"
    (let [js-evt (ec/clj->js-event {:type :tool-execution-end
                                     :tool-call-id "tc-1"
                                     :tool-name "bash"
                                     :content "done"
                                     :is-error false})]
      (is (= "tool_execution_end" (.-type js-evt)))
      (is (= "tc-1" (.-toolCallId js-evt)))
      (is (= "bash" (.-toolName js-evt)))
      (is (= false (.-isError js-evt))))))

(deftest tool-execution-end-error-conversion-test
  (testing "converts :tool-execution-end with error"
    (let [js-evt (ec/clj->js-event {:type :tool-execution-end
                                     :tool-call-id "tc-1"
                                     :tool-name "bash"
                                     :content "failed"
                                     :is-error true})]
      (is (= true (.-isError js-evt))))))

(deftest auto-retry-start-conversion-test
  (testing "converts :auto-retry-start"
    (let [js-evt (ec/clj->js-event {:type :auto-retry-start
                                     :attempt 2
                                     :max-attempts 5
                                     :delay-ms 3000
                                     :error-message "rate limited"})]
      (is (= "auto_retry_start" (.-type js-evt)))
      (is (= 2 (.-attempt js-evt)))
      (is (= 5 (.-maxAttempts js-evt)))
      (is (= 3000 (.-delayMs js-evt)))
      (is (= "rate limited" (.-errorMessage js-evt))))))

(deftest auto-compaction-end-conversion-test
  (testing "converts :auto-compaction-end with result"
    (let [js-evt (ec/clj->js-event {:type :auto-compaction-end
                                     :aborted false
                                     :will-retry false
                                     :result {:summary "compacted" :tokens-before 5000}})]
      (is (= "auto_compaction_end" (.-type js-evt)))
      (is (= false (.-aborted js-evt)))
      (is (some? (.-result js-evt))))))

(deftest unknown-event-returns-nil-test
  (testing "unknown event type returns nil"
    (is (nil? (ec/clj->js-event {:type :unknown-event})))))

(deftest tool-call-content-block-test
  (testing "message with tool-call content blocks"
    (let [msg {:role "assistant"
               :content [{:type :text :text "Let me help"}
                         {:type :tool-call :id "tc-1" :name "read_file"
                          :arguments {:path "foo.txt"}}]
               :model "m" :provider "p" :api "a" :timestamp 1}
          js-evt (ec/clj->js-event {:type :message-end :message msg})
          content (.. js-evt -message -content)]
      (is (= 2 (.-length content)))
      (is (= "text" (.. content (at 0) -type)))
      (is (= "toolCall" (.. content (at 1) -type)))
      (is (= "tc-1" (.. content (at 1) -id)))
      (is (= "read_file" (.. content (at 1) -name))))))

;; --- New tests for pure message building ---

(deftest message-usage-fields-test
  (testing "message usage fields are correctly converted"
    (let [msg {:role "assistant"
               :content [{:type :text :text "hi"}]
               :model "claude" :provider "anthropic" :api "anthropic"
               :timestamp 1
               :usage {:input 100 :output 50
                       :cache-read 20 :cache-write 10
                       :total-tokens 180}}
          js-evt (ec/clj->js-event {:type :message-end :message msg})
          usage (.. js-evt -message -usage)]
      (is (some? usage) "usage object should be present")
      (is (= 100 (.-input usage)))
      (is (= 50 (.-output usage)))
      (is (= 20 (.-cacheRead usage)))
      (is (= 10 (.-cacheWrite usage)))
      (is (= 180 (.-totalTokens usage))))))

(deftest message-without-usage-test
  (testing "message without usage omits usage field"
    (let [msg {:role "assistant" :content []
               :model "m" :provider "p" :api "a" :timestamp 1}
          js-evt (ec/clj->js-event {:type :message-end :message msg})
          js-msg (.-message js-evt)]
      (is (= js/undefined (.-usage js-msg))
          "usage should not be present when not provided"))))

(deftest message-error-fields-test
  (testing "message with error-message and retry-attempt"
    (let [msg {:role "assistant" :content []
               :model "m" :provider "p" :api "a" :timestamp 1
               :error-message "overloaded"
               :retry-attempt 2}
          js-evt (ec/clj->js-event {:type :message-end :message msg})
          js-msg (.-message js-evt)]
      (is (= "overloaded" (.-errorMessage js-msg)))
      (is (= 2 (.-retryAttempt js-msg))))))

(deftest thinking-content-block-test
  (testing "thinking content blocks are converted"
    (let [msg {:role "assistant"
               :content [{:type :thinking :thinking "Let me reason about this"}]
               :model "m" :provider "p" :api "a" :timestamp 1}
          js-evt (ec/clj->js-event {:type :message-end :message msg})
          block (.. js-evt -message -content (at 0))]
      (is (= "thinking" (.-type block)))
      (is (= "Let me reason about this" (.-thinking block))))))

(deftest nil-message-returns-nil-in-turn-end-test
  (testing "turn-end with nil message has no message property"
    (let [js-evt (ec/clj->js-event {:type :turn-end})]
      (is (= "turn_end" (.-type js-evt)))
      (is (= js/undefined (.-message js-evt))))))

(deftest build-content-block-map-test
  (testing "build-content-block-map returns CLJS maps for each block type"
    (is (= {:type "text" :text "hello"}
           (ec/build-content-block-map {:type :text :text "hello"})))
    (is (= {:type "thinking" :thinking "hmm"}
           (ec/build-content-block-map {:type :thinking :thinking "hmm"})))
    (let [tc (ec/build-content-block-map {:type :tool-call :id "t1" :name "read" :arguments {:path "x"}})]
      (is (= "toolCall" (:type tc)))
      (is (= "t1" (:id tc)))
      (is (= "read" (:name tc)))
      (is (= {:path "x"} (:arguments tc))))))

(deftest build-message-map-test
  (testing "build-message-map returns a CLJS map with camelCase keys"
    (let [msg {:role "assistant"
               :content [{:type :text :text "hi"}]
               :model "claude" :provider "anthropic" :api "anthropic"
               :timestamp 1 :stop-reason "end_turn"
               :usage {:input 10 :output 5 :cache-read 0 :cache-write 0 :total-tokens 15}}
          result (ec/build-message-map msg)]
      (is (= "assistant" (:role result)))
      (is (= "claude" (:model result)))
      (is (= "end_turn" (:stopReason result)))
      (is (= 1 (count (:content result))))
      (is (map? (get-in result [:usage])))
      (is (= 10 (get-in result [:usage :input])))))

  (testing "build-message-map omits usage when not present"
    (let [result (ec/build-message-map {:role "assistant" :content []
                                         :model "m" :provider "p" :api "a" :timestamp 1})]
      (is (not (contains? result :usage)))))

  (testing "build-message-map includes error fields when present"
    (let [result (ec/build-message-map {:role "assistant" :content []
                                         :model "m" :provider "p" :api "a" :timestamp 1
                                         :error-message "fail" :retry-attempt 3})]
      (is (= "fail" (:errorMessage result)))
      (is (= 3 (:retryAttempt result)))))

  (testing "build-message-map returns nil for nil input"
    (is (nil? (ec/build-message-map nil)))))
