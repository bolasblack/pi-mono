(ns pi.coding-agent.session-sync-test
  "Tests for DataScript <-> TS message projection."
  (:require [cljs.test :refer [deftest testing is]]
            [pi.kernel.session :as session]
            [pi.coding-agent.session-sync :as sync]))

;; --- project-messages tests ---

(deftest project-empty-session-test
  (testing "empty session projects to empty array"
    (let [sess (session/create-session)
          msgs (sync/project-messages sess)]
      (is (= 0 (.-length msgs))))))

(deftest project-user-message-test
  (testing "user message projects to JS with role=user"
    (let [sess (session/create-session)]
      (session/append-entry! sess
        {:entry/type :user-message
         :entry/data {:content "hello"}})
      (let [msgs (sync/project-messages sess)
            msg (aget msgs 0)]
        (is (= 1 (.-length msgs)))
        (is (= "user" (.-role msg)))
        (is (= 1 (.-length (.-content msg))))
        (is (= "text" (.. msg -content (at 0) -type)))
        (is (= "hello" (.. msg -content (at 0) -text)))))))

(deftest project-assistant-message-test
  (testing "assistant message projects with content blocks"
    (let [sess (session/create-session)]
      (session/append-entry! sess
        {:entry/type :assistant-message
         :entry/data {:content "I can help"
                      :tool-calls [{:id "tc-1" :name "read_file" :arguments {:path "a.txt"}}]
                      :model "claude" :provider "anthropic" :api "anthropic"
                      :stop-reason "stop"
                      :usage {:input 10 :output 5}}})
      (let [msgs (sync/project-messages sess)
            msg (aget msgs 0)]
        (is (= 1 (.-length msgs)))
        (is (= "assistant" (.-role msg)))
        ;; Should have text + toolCall content blocks
        (is (>= (.-length (.-content msg)) 2))
        (is (= "text" (.. msg -content (at 0) -type)))
        (is (= "I can help" (.. msg -content (at 0) -text)))
        (is (= "toolCall" (.. msg -content (at 1) -type)))
        (is (= "tc-1" (.. msg -content (at 1) -id)))))))

(deftest project-assistant-thinking-text-tools-test
  (testing "assistant message with thinking + text + tool-calls produces all blocks in order"
    (let [sess (session/create-session)]
      (session/append-entry! sess
        {:entry/type :assistant-message
         :entry/data {:thinking "Let me think..."
                      :content "Here is my answer"
                      :tool-calls [{:id "tc-1" :name "bash" :arguments {:command "ls"}}
                                   {:id "tc-2" :name "read_file" :arguments {:path "x.txt"}}]
                      :model "claude" :provider "anthropic" :api "anthropic"
                      :stop-reason "stop" :usage {}}})
      (let [msgs (sync/project-messages sess)
            msg (aget msgs 0)
            content (.-content msg)]
        (is (= 1 (.-length msgs)))
        ;; thinking + text + 2 toolCalls = 4 blocks
        (is (= 4 (.-length content)))
        ;; Block 0: thinking
        (is (= "thinking" (.. content (at 0) -type)))
        (is (= "Let me think..." (.. content (at 0) -thinking)))
        ;; Block 1: text
        (is (= "text" (.. content (at 1) -type)))
        (is (= "Here is my answer" (.. content (at 1) -text)))
        ;; Block 2: first toolCall
        (is (= "toolCall" (.. content (at 2) -type)))
        (is (= "tc-1" (.. content (at 2) -id)))
        (is (= "bash" (.. content (at 2) -name)))
        ;; Block 3: second toolCall
        (is (= "toolCall" (.. content (at 3) -type)))
        (is (= "tc-2" (.. content (at 3) -id)))
        (is (= "read_file" (.. content (at 3) -name)))))))

(deftest project-assistant-text-only-test
  (testing "assistant message with only text produces single text block"
    (let [sess (session/create-session)]
      (session/append-entry! sess
        {:entry/type :assistant-message
         :entry/data {:content "just text"
                      :model "claude" :provider "anthropic" :api "anthropic"
                      :stop-reason "stop" :usage {}}})
      (let [msgs (sync/project-messages sess)
            msg (aget msgs 0)
            content (.-content msg)]
        (is (= 1 (.-length content)))
        (is (= "text" (.. content (at 0) -type)))
        (is (= "just text" (.. content (at 0) -text)))))))

(deftest project-tool-result-test
  (testing "tool result projects to JS"
    (let [sess (session/create-session)]
      (session/append-entry! sess
        {:entry/type :tool-result
         :tool-call/id "tc-1"
         :tool-call/name "read_file"
         :tool-result/content "file contents"
         :tool-result/error? false})
      (let [msgs (sync/project-messages sess)
            msg (aget msgs 0)]
        (is (= 1 (.-length msgs)))
        (is (= "toolResult" (.-role msg)))
        (is (= "tc-1" (.-toolCallId msg)))
        (is (= "read_file" (.-toolName msg)))
        (is (= false (.-isError msg)))))))

;; --- populate-from-ts-messages tests ---

(deftest populate-user-message-test
  (testing "populating from JS user message creates DataScript entry"
    (let [sess (session/create-session)
          js-msgs #js [#js {:role "user"
                            :content #js [#js {:type "text" :text "hi there"}]
                            :timestamp 12345}]]
      (sync/populate-from-ts-messages! sess js-msgs)
      (let [projected (sync/project-messages sess)]
        (is (= 1 (.-length projected)))
        (is (= "user" (.. projected (at 0) -role)))))))

(deftest populate-assistant-message-test
  (testing "populating from JS assistant message creates DataScript entry"
    (let [sess (session/create-session)
          js-msgs #js [#js {:role "assistant"
                            :content #js [#js {:type "text" :text "hello"}
                                          #js {:type "toolCall" :id "tc-1"
                                               :name "bash"
                                               :arguments #js {:command "ls"}}]
                            :model "claude" :provider "anthropic" :api "anthropic"
                            :stopReason "stop"
                            :timestamp 12345}]]
      (sync/populate-from-ts-messages! sess js-msgs)
      (let [projected (sync/project-messages sess)]
        (is (= 1 (.-length projected)))
        (is (= "assistant" (.. projected (at 0) -role)))))))

(deftest populate-tool-result-test
  (testing "populating from JS tool result creates DataScript entry"
    (let [sess (session/create-session)
          js-msgs #js [#js {:role "toolResult"
                            :toolCallId "tc-1"
                            :toolName "bash"
                            :content #js [#js {:type "text" :text "output"}]
                            :isError false
                            :timestamp 12345}]]
      (sync/populate-from-ts-messages! sess js-msgs)
      (let [projected (sync/project-messages sess)]
        (is (= 1 (.-length projected)))
        (is (= "toolResult" (.. projected (at 0) -role)))
        (is (= "tc-1" (.. projected (at 0) -toolCallId)))))))

;; --- roundtrip test ---

(deftest roundtrip-test
  (testing "DataScript -> JS -> DataScript roundtrip preserves messages"
    (let [sess1 (session/create-session)]
      ;; Build a conversation
      (session/append-entry! sess1
        {:entry/type :user-message :entry/data {:content "hello"}})
      (session/append-entry! sess1
        {:entry/type :assistant-message
         :entry/data {:content "hi there"
                      :model "claude" :provider "anthropic" :api "anthropic"
                      :stop-reason "stop" :usage {}}})
      (session/append-entry! sess1
        {:entry/type :user-message :entry/data {:content "do stuff"}})
      (session/append-entry! sess1
        {:entry/type :assistant-message
         :entry/data {:content "running..."
                      :tool-calls [{:id "tc-1" :name "bash" :arguments {:command "ls"}}]
                      :model "claude" :provider "anthropic" :api "anthropic"
                      :stop-reason "stop" :usage {}}})
      (session/append-entry! sess1
        {:entry/type :tool-result
         :tool-call/id "tc-1" :tool-call/name "bash"
         :tool-result/content "file1.txt" :tool-result/error? false})

      ;; Project to JS
      (let [js-msgs (sync/project-messages sess1)]
        (is (= 5 (.-length js-msgs)))

        ;; Populate a new session from JS
        (let [sess2 (session/create-session)]
          (sync/populate-from-ts-messages! sess2 js-msgs)

          ;; Re-project and compare
          (let [js-msgs2 (sync/project-messages sess2)]
            (is (= 5 (.-length js-msgs2)))
            (is (= "user" (.. js-msgs2 (at 0) -role)))
            (is (= "assistant" (.. js-msgs2 (at 1) -role)))
            (is (= "user" (.. js-msgs2 (at 2) -role)))
            (is (= "assistant" (.. js-msgs2 (at 3) -role)))
            (is (= "toolResult" (.. js-msgs2 (at 4) -role)))))))))

;; --- Pure map builder tests ---

(deftest build-user-message-map-test
  (testing "builds a CLJS map from a user-message entry"
    (let [entry {:entry/type :user-message
                 :entry/timestamp 99999
                 :entry/data {:content "hello world"}}
          result (sync/build-ts-message-map entry)]
      (is (map? result) "should return a CLJS map, not a JS object")
      (is (= "user" (:role result)))
      (is (= 99999 (:timestamp result)))
      (is (= [{:type "text" :text "hello world"}] (:content result))))))

(deftest build-assistant-message-map-test
  (testing "builds a CLJS map from an assistant-message entry with all fields"
    (let [entry {:entry/type :assistant-message
                 :entry/timestamp 12345
                 :entry/data {:content "I can help"
                              :thinking "Let me think"
                              :tool-calls [{:id "tc-1" :name "bash" :arguments {:cmd "ls"}}]
                              :model "claude" :provider "anthropic" :api "anthropic"
                              :stop-reason "stop"
                              :usage {:input 10 :output 5}}}
          result (sync/build-ts-message-map entry)]
      (is (map? result))
      (is (= "assistant" (:role result)))
      (is (= "claude" (:model result)))
      (is (= "anthropic" (:provider result)))
      (is (= "anthropic" (:api result)))
      (is (= "stop" (:stopReason result)))
      (is (= 12345 (:timestamp result)))
      ;; Content blocks: thinking + text + 1 toolCall = 3
      (let [blocks (:content result)]
        (is (= 3 (count blocks)))
        (is (= "thinking" (:type (nth blocks 0))))
        (is (= "Let me think" (:thinking (nth blocks 0))))
        (is (= "text" (:type (nth blocks 1))))
        (is (= "I can help" (:text (nth blocks 1))))
        (is (= "toolCall" (:type (nth blocks 2))))
        (is (= "tc-1" (:id (nth blocks 2))))
        (is (= "bash" (:name (nth blocks 2))))
        (is (= {:cmd "ls"} (:arguments (nth blocks 2))))))))

(deftest build-assistant-text-only-map-test
  (testing "assistant with only text produces single text block"
    (let [entry {:entry/type :assistant-message
                 :entry/timestamp 1
                 :entry/data {:content "just text"
                              :model "m" :provider "p" :api "a"
                              :stop-reason "stop" :usage {}}}
          result (sync/build-ts-message-map entry)]
      (is (= 1 (count (:content result))))
      (is (= {:type "text" :text "just text"} (first (:content result)))))))

(deftest build-assistant-no-content-map-test
  (testing "assistant with empty content and no thinking/tools produces empty blocks"
    (let [entry {:entry/type :assistant-message
                 :entry/timestamp 1
                 :entry/data {:content ""
                              :model "m" :provider "p" :api "a"
                              :stop-reason "stop" :usage {}}}
          result (sync/build-ts-message-map entry)]
      (is (= [] (:content result))))))

(deftest build-tool-result-map-test
  (testing "builds a CLJS map from a tool-result entry"
    (let [entry {:entry/type :tool-result
                 :entry/timestamp 55555
                 :tool-call/id "tc-1"
                 :tool-call/name "read_file"
                 :tool-result/content "file contents here"
                 :tool-result/error? false}
          result (sync/build-ts-message-map entry)]
      (is (map? result))
      (is (= "toolResult" (:role result)))
      (is (= "tc-1" (:toolCallId result)))
      (is (= "read_file" (:toolName result)))
      (is (= [{:type "text" :text "file contents here"}] (:content result)))
      (is (= false (:isError result)))
      (is (= 55555 (:timestamp result))))))

(deftest build-tool-result-error-map-test
  (testing "tool result with error flag"
    (let [entry {:entry/type :tool-result
                 :entry/timestamp 1
                 :tool-call/id "tc-2"
                 :tool-call/name "bash"
                 :tool-result/content "command failed"
                 :tool-result/error? true}
          result (sync/build-ts-message-map entry)]
      (is (= true (:isError result))))))

(deftest build-unknown-entry-returns-nil-test
  (testing "unknown entry type returns nil"
    (let [entry {:entry/type :unknown-thing}]
      (is (nil? (sync/build-ts-message-map entry))))))

(deftest build-assistant-content-blocks-test
  (testing "builds content block vector with thinking + text + tool-calls"
    (let [data {:thinking "hmm"
                :content "answer"
                :tool-calls [{:id "t1" :name "bash" :arguments {:cmd "ls"}}
                             {:id "t2" :name "read_file" :arguments {:path "x"}}]}
          blocks (sync/build-assistant-content-block-maps data)]
      (is (vector? blocks))
      (is (= 4 (count blocks)))
      (is (= {:type "thinking" :thinking "hmm"} (nth blocks 0)))
      (is (= {:type "text" :text "answer"} (nth blocks 1)))
      (is (= "toolCall" (:type (nth blocks 2))))
      (is (= "t1" (:id (nth blocks 2))))
      (is (= "toolCall" (:type (nth blocks 3))))
      (is (= "t2" (:id (nth blocks 3))))))

  (testing "text-only returns single block"
    (let [blocks (sync/build-assistant-content-block-maps {:content "hello"})]
      (is (= [{:type "text" :text "hello"}] blocks))))

  (testing "empty content and no extras returns empty vector"
    (let [blocks (sync/build-assistant-content-block-maps {:content ""})]
      (is (= [] blocks)))))
