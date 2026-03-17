(ns pi.kernel.provider.messages-test
  (:require [cljs.test :refer [deftest is testing]]
            [pi.kernel.provider.messages :as msgs]))

;; --- convert-user-msg ---

(deftest convert-user-msg-basic
  (let [msg {:role "user" :content "Hello"}
        result (msgs/convert-user-msg msg 1000)]
    (is (= "user" (get result "role")))
    (is (= "Hello" (get result "content")))
    (is (= 1000 (get result "timestamp")))))

;; --- convert-tool-result-msg ---

(deftest convert-tool-result-msg-basic
  (let [msg {:role "tool-result"
             :tool-call-id "tc-1"
             :tool-name "read_file"
             :content "file contents"}
        result (msgs/convert-tool-result-msg msg 2000)]
    (is (= "toolResult" (get result "role")))
    (is (= "tc-1" (get result "toolCallId")))
    (is (= "read_file" (get result "toolName")))
    (is (= false (get result "isError")))
    (is (= 2000 (get result "timestamp")))
    (is (= [{"type" "text" "text" "file contents"}]
           (get result "content")))))

(deftest convert-tool-result-msg-error
  (let [msg {:role "tool-result"
             :tool-call-id "tc-2"
             :tool-name "bash"
             :content "command failed"
             :is-error true}
        result (msgs/convert-tool-result-msg msg 3000)]
    (is (= true (get result "isError")))))

(deftest convert-tool-result-msg-nil-content
  (let [msg {:role "tool-result"
             :tool-call-id "tc-3"
             :tool-name "bash"}
        result (msgs/convert-tool-result-msg msg 4000)]
    (is (= [{"type" "text" "text" ""}]
           (get result "content")))))

;; --- build-assistant-content ---

(deftest build-assistant-content-text-only
  (let [result (msgs/build-assistant-content "Hello" nil)]
    (is (= [{"type" "text" "text" "Hello"}] result))))

(deftest build-assistant-content-tool-calls-only
  (let [tc [{:id "tc-1" :name "read_file" :arguments {:path "a.txt"}}]
        result (msgs/build-assistant-content nil tc)]
    (is (= 1 (count result)))
    (is (= "toolCall" (get (first result) "type")))
    (is (= "tc-1" (get (first result) "id")))))

(deftest build-assistant-content-both
  (let [tc [{:id "tc-1" :name "bash" :arguments {:cmd "ls"}}]
        result (msgs/build-assistant-content "Let me check" tc)]
    (is (= 2 (count result)))
    (is (= "text" (get (first result) "type")))
    (is (= "toolCall" (get (second result) "type")))))

(deftest build-assistant-content-empty-fallback
  (let [result (msgs/build-assistant-content nil nil)]
    (is (= [{"type" "text" "text" ""}] result)))
  (let [result (msgs/build-assistant-content "" nil)]
    (is (= [{"type" "text" "text" ""}] result))))

;; --- default-assistant-stub ---

(deftest default-assistant-stub-has-required-keys
  (is (= "anthropic-messages" (get msgs/default-assistant-stub "api")))
  (is (= "anthropic" (get msgs/default-assistant-stub "provider")))
  (is (= "unknown" (get msgs/default-assistant-stub "model")))
  (is (= "stop" (get msgs/default-assistant-stub "stopReason")))
  (is (map? (get msgs/default-assistant-stub "usage"))))

;; --- convert-assistant-msg ---

(deftest convert-assistant-msg-text
  (let [msg {:role "assistant" :content "Hi there"}
        result (msgs/convert-assistant-msg msg 5000)]
    (is (= "assistant" (get result "role")))
    (is (= 5000 (get result "timestamp")))
    (is (= "stop" (get result "stopReason")))
    (is (= [{"type" "text" "text" "Hi there"}]
           (get result "content")))
    (is (map? (get result "usage")))))

(deftest convert-assistant-msg-with-tool-calls
  (let [msg {:role "assistant"
             :content "Let me read that"
             :tool-calls [{:id "tc-1" :name "read_file" :arguments {:path "x.txt"}}]}
        result (msgs/convert-assistant-msg msg 6000)
        content (get result "content")]
    (is (= 2 (count content)))
    (is (= "text" (get (first content) "type")))
    (is (= "toolCall" (get (second content) "type")))))

(deftest convert-assistant-msg-uses-default-stub
  (let [msg {:role "assistant" :content "test"}
        result (msgs/convert-assistant-msg msg 1000)]
    (is (= "anthropic-messages" (get result "api")))
    (is (= "anthropic" (get result "provider")))
    (is (= "unknown" (get result "model")))))

(deftest convert-assistant-msg-with-custom-defaults
  (let [msg {:role "assistant" :content "test"}
        defaults {"api" "openai-chat" "provider" "openai" "model" "gpt-4"}
        result (msgs/convert-assistant-msg msg 1000 defaults)]
    (is (= "openai-chat" (get result "api")))
    (is (= "openai" (get result "provider")))
    (is (= "gpt-4" (get result "model")))
    ;; stopReason and usage still from default-assistant-stub
    (is (= "stop" (get result "stopReason")))
    (is (map? (get result "usage")))))

(deftest convert-assistant-msg-partial-defaults-override
  (let [msg {:role "assistant" :content "test"}
        defaults {"model" "claude-3"}
        result (msgs/convert-assistant-msg msg 1000 defaults)]
    (is (= "claude-3" (get result "model")))
    (is (= "anthropic-messages" (get result "api")))
    (is (= "anthropic" (get result "provider")))))

;; --- convert-messages-for-pi-ai ---

(deftest convert-messages-for-pi-ai-mixed
  (let [messages [{:role "user" :content "Hi"}
                  {:role "assistant" :content "Hello"}
                  {:role "user" :content "Read foo"}
                  {:role "assistant" :content "Sure"
                   :tool-calls [{:id "tc-1" :name "read_file" :arguments {:path "foo"}}]}
                  {:role "tool-result" :tool-call-id "tc-1"
                   :tool-name "read_file" :content "bar"}]
        result (msgs/convert-messages-for-pi-ai messages)]
    (is (= 5 (count result)))
    (is (= "user" (get (nth result 0) "role")))
    (is (= "assistant" (get (nth result 1) "role")))
    (is (= "user" (get (nth result 2) "role")))
    (is (= "assistant" (get (nth result 3) "role")))
    (is (= "toolResult" (get (nth result 4) "role")))
    ;; All share same timestamp
    (let [ts (get (first result) "timestamp")]
      (is (number? ts))
      (is (every? #(= ts (get % "timestamp")) result)))))

(deftest convert-messages-for-pi-ai-with-assistant-defaults
  (let [messages [{:role "user" :content "Hi"}
                  {:role "assistant" :content "Hello"}]
        defaults {"api" "openai-chat" "provider" "openai" "model" "gpt-4"}
        result (msgs/convert-messages-for-pi-ai messages defaults)]
    (is (= 2 (count result)))
    (is (= "openai-chat" (get (second result) "api")))
    (is (= "openai" (get (second result) "provider")))
    (is (= "gpt-4" (get (second result) "model")))))

;; --- tool-json-schema ---

(deftest tool-json-schema-from-json-schema
  (let [schema {:type "object" :properties {:path {:type "string"}}}
        tool {:json-schema schema}]
    (is (= schema (msgs/tool-json-schema tool)))))

(deftest tool-json-schema-nil-when-missing
  (is (nil? (msgs/tool-json-schema {}))))

;; --- tools-for-provider ---

(deftest tools-for-provider-basic
  (let [registry (atom {"read_file" {:name "read_file"
                                      :doc "Read a file"
                                      :json-schema {:type "object"
                                                    :properties {:path {:type "string"}}}}
                         "bash" {:name "bash"
                                 :doc "Run a command"
                                 :json-schema {:type "object"
                                               :properties {:cmd {:type "string"}}}}})]
    (let [result (msgs/tools-for-provider registry)]
      (is (= 2 (count result)))
      (is (every? #(contains? % "name") result))
      (is (every? #(contains? % "description") result))
      (is (every? #(contains? % "parameters") result)))))

(deftest tools-for-provider-nil-registry
  (is (nil? (msgs/tools-for-provider nil))))

(deftest tools-for-provider-skips-no-schema
  (let [registry (atom {"no-schema" {:name "no-schema" :doc "No schema"}})]
    (is (empty? (msgs/tools-for-provider registry)))))
