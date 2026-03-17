(ns pi.kernel.stream-handler-test
  "Tests for stream handler pure functions extracted from defaults."
  (:require [cljs.test :refer [deftest testing is async]]
            [cljs.core.async :refer [chan close! put! go <!]]
            [clojure.string]
            [pi.kernel.stream-handler :as sh]
            [pi.kernel.streaming :as streaming]))

;; --- Pure state transition tests ---

(deftest apply-text-test
  (testing "apply-text appends to text accumulator"
    (let [s0 sh/init-state
          s1 (sh/apply-text s0 "Hello")
          s2 (sh/apply-text s1 " world")]
      (is (= "" (:text s0)))
      (is (= "Hello" (:text s1)))
      (is (= "Hello world" (:text s2))))))

(deftest apply-thinking-test
  (testing "apply-thinking appends to thinking accumulator"
    (let [s0 sh/init-state
          s1 (sh/apply-thinking s0 "hmm")
          s2 (sh/apply-thinking s1 " ok")]
      (is (= "hmm ok" (:thinking s2))))))

(deftest apply-tool-call-test
  (testing "apply-tool-call conjs a tool to the tools vector"
    (let [s0 sh/init-state
          s1 (sh/apply-tool-call s0 {:id "t1" :name "bash" :arguments {:cmd "ls"}})]
      (is (= 1 (count (:tools s1))))
      (is (= "bash" (:name (first (:tools s1))))))))

(deftest apply-tool-call-start-test
  (testing "apply-tool-call-start sets cur-tool"
    (let [s (sh/apply-tool-call-start sh/init-state {:id "tc-1" :name "read_file"})]
      (is (= "tc-1" (:id (:cur-tool s))))
      (is (= "" (:input (:cur-tool s)))))))

(deftest apply-tool-input-test
  (testing "apply-tool-input appends to cur-tool input"
    (let [s (-> sh/init-state
                (sh/apply-tool-call-start {:id "tc-1" :name "bash"})
                (sh/apply-tool-input "{\"co")
                (sh/apply-tool-input "mmand\":\"ls\"}"))]
      (is (= "{\"command\":\"ls\"}" (get-in s [:cur-tool :input]))))))

(deftest apply-tool-input-noop-without-cur-tool-test
  (testing "apply-tool-input is a no-op when no cur-tool"
    (let [s (sh/apply-tool-input sh/init-state "data")]
      (is (nil? (:cur-tool s))))))

(deftest apply-block-stop-test
  (testing "apply-block-stop finalizes cur-tool into tools vec"
    (let [s (-> sh/init-state
                (sh/apply-tool-call-start {:id "tc-1" :name "bash"})
                (sh/apply-tool-input "{\"command\":\"ls\"}")
                sh/apply-block-stop)]
      (is (nil? (:cur-tool s)))
      (is (= 1 (count (:tools s))))
      (is (= "bash" (:name (first (:tools s)))))
      (is (= {:command "ls"} (:arguments (first (:tools s))))))))

(deftest mark-started-test
  (testing "mark-started sets message-started? to true"
    (let [s (sh/mark-started sh/init-state)]
      (is (true? (:message-started? s))))))

(deftest mark-finished-test
  (testing "mark-finished sets finished? to true"
    (let [s (sh/mark-finished sh/init-state)]
      (is (true? (:finished? s))))))

;; --- build-accumulated-message tests ---

(deftest build-accumulated-message-text-only-test
  (testing "text-only message has correct structure"
    (let [msg (sh/build-accumulated-message
                "Hello world" [] "" {:model-id "claude" :api "anthropic"} "stop" {})]
      (is (= "assistant" (:role msg)))
      (is (= "stop" (:stop-reason msg)))
      (is (= "claude" (:model msg)))
      (is (= "anthropic" (:provider msg)))
      (is (= 1 (count (:content msg))))
      (is (= :text (:type (first (:content msg)))))
      (is (= "Hello world" (:text (first (:content msg))))))))

(deftest build-accumulated-message-with-thinking-test
  (testing "thinking block precedes text block"
    (let [msg (sh/build-accumulated-message
                "answer" [] "let me think" {:model-id "m" :api "a"} "stop" {})]
      (is (= 2 (count (:content msg))))
      (is (= :thinking (:type (first (:content msg)))))
      (is (= "let me think" (:thinking (first (:content msg)))))
      (is (= :text (:type (second (:content msg)))))
      (is (= "answer" (:text (second (:content msg))))))))

(deftest build-accumulated-message-with-tool-calls-test
  (testing "tool calls included in content"
    (let [tools [{:id "tc-1" :name "read_file" :arguments {:path "x.txt"}}]
          msg (sh/build-accumulated-message
                "" tools "" {:model-id "m" :api "a"} "stop" {})]
      (is (= 1 (count (:content msg))))
      (is (= :tool-call (:type (first (:content msg)))))
      (is (= "tc-1" (:id (first (:content msg)))))
      (is (= "read_file" (:name (first (:content msg))))))))

(deftest build-accumulated-message-empty-test
  (testing "empty accumulation returns empty content"
    (let [msg (sh/build-accumulated-message
                "" [] "" {:model-id "m" :api "a"} "stop" {})]
      (is (= [] (:content msg))))))

(deftest build-accumulated-message-nil-model-config-test
  (testing "nil model config uses 'unknown' defaults"
    (let [msg (sh/build-accumulated-message
                "hi" [] "" nil "stop" nil)]
      (is (= "unknown" (:model msg)))
      (is (= "unknown" (:provider msg)))
      (is (= {} (:usage msg))))))

(deftest build-accumulated-message-all-three-test
  (testing "thinking + text + tool calls all present"
    (let [tools [{:id "t1" :name "bash" :arguments {:command "ls"}}]
          msg (sh/build-accumulated-message
                "here" tools "hmm" {:model-id "m" :api "a"} "stop" {:input 10})]
      (is (= 3 (count (:content msg))))
      (is (= :thinking (:type (nth (:content msg) 0))))
      (is (= :text (:type (nth (:content msg) 1))))
      (is (= :tool-call (:type (nth (:content msg) 2))))
      (is (= {:input 10} (:usage msg))))))

;; --- build-finalization-result tests ---

(deftest build-finalization-result-normal-test
  (testing "builds result map for normal stop"
    (let [result (sh/build-finalization-result
                   "hello" [{:id "t1" :name "bash" :arguments {}}]
                   {:input 5 :output 10} nil)]
      (is (= "hello" (:text (:response result))))
      (is (= 1 (count (:tool-calls (:response result)))))
      (is (= {:input 5 :output 10} (:usage (:response result))))
      (is (nil? (:stop? result))))))

(deftest build-finalization-result-aborted-test
  (testing "aborted result appends marker and sets stop?"
    (let [result (sh/build-finalization-result
                   "partial" [] {} :aborted)]
      (is (clojure.string/includes? (:text (:response result)) "*[Operation aborted]*"))
      (is (true? (:stop? result)))
      (is (= [] (:tool-calls (:response result)))))))

;; --- handle-stream-event! integration tests (using snapshot) ---

(deftest handle-stream-event-text-accumulates-test
  (testing "text events accumulate in the stream context"
    (let [bus (streaming/create-event-bus)
          result-ch (chan 1)
          sctx (sh/make-stream-ctx {:model-id "m" :api "a"} bus result-ch {})]
      (sh/handle-stream-event! sctx {:type :text :content "Hello"})
      (sh/handle-stream-event! sctx {:type :text :content " world"})
      (is (= "Hello world" (:text (sh/snapshot sctx))))
      (streaming/close-bus! bus)
      (close! result-ch))))

(deftest handle-stream-event-thinking-accumulates-test
  (testing "thinking events accumulate in the stream context"
    (let [bus (streaming/create-event-bus)
          result-ch (chan 1)
          sctx (sh/make-stream-ctx {:model-id "m" :api "a"} bus result-ch {})]
      (sh/handle-stream-event! sctx {:type :thinking :content "hmm"})
      (sh/handle-stream-event! sctx {:type :thinking :content " ok"})
      (is (= "hmm ok" (:thinking (sh/snapshot sctx))))
      (streaming/close-bus! bus)
      (close! result-ch))))

(deftest handle-stream-event-tool-call-accumulates-test
  (testing "tool-call events accumulate in tools vector"
    (let [bus (streaming/create-event-bus)
          result-ch (chan 1)
          sctx (sh/make-stream-ctx {:model-id "m" :api "a"} bus result-ch {})]
      (sh/handle-stream-event! sctx {:type :tool-call :id "t1" :name "bash" :arguments {:cmd "ls"}})
      (let [snap (sh/snapshot sctx)]
        (is (= 1 (count (:tools snap))))
        (is (= "bash" (:name (first (:tools snap))))))
      (streaming/close-bus! bus)
      (close! result-ch))))

(deftest handle-stream-event-ignores-after-finished-test
  (testing "events are ignored once finished? is true"
    (let [bus (streaming/create-event-bus)
          result-ch (chan 1)
          sctx (sh/make-stream-ctx {:model-id "m" :api "a"} bus result-ch {})]
      (swap! (:state sctx) sh/mark-finished)
      (sh/handle-stream-event! sctx {:type :text :content "ignored"})
      (is (= "" (:text (sh/snapshot sctx))))
      (streaming/close-bus! bus)
      (close! result-ch))))

(deftest handle-stream-event-stop-finalizes-test
  (testing "stop event marks stream as finished and puts result on channel"
    (async done
      (let [bus (streaming/create-event-bus)
            result-ch (chan 1)
            sctx (sh/make-stream-ctx {:model-id "m" :api "a"} bus result-ch {:session :fake})]
        (sh/handle-stream-event! sctx {:type :text :content "hello"})
        (sh/handle-stream-event! sctx {:type :stop :message {:usage {:input 5}}})
        (go
          (let [result (<! result-ch)]
            (is (true? (:finished? (sh/snapshot sctx))))
            (is (= "hello" (get-in result [:response :text])))
            (streaming/close-bus! bus)
            (done)))))))

(deftest handle-stream-event-text-sets-started-and-state-test
  (testing "text event sets message-started? and accumulates text via shared content handler"
    (let [bus (streaming/create-event-bus)
          result-ch (chan 1)
          sctx (sh/make-stream-ctx {:model-id "m" :api "a"} bus result-ch {})]
      (is (false? (:message-started? (sh/snapshot sctx))))
      (sh/handle-stream-event! sctx {:type :text :content "hi"})
      (is (true? (:message-started? (sh/snapshot sctx))))
      (is (= "hi" (:text (sh/snapshot sctx))))
      (sh/handle-stream-event! sctx {:type :text :content " there"})
      (is (= "hi there" (:text (sh/snapshot sctx))))
      (streaming/close-bus! bus)
      (close! result-ch))))

(deftest handle-stream-event-thinking-sets-started-and-state-test
  (testing "thinking event sets message-started? and accumulates thinking via shared content handler"
    (let [bus (streaming/create-event-bus)
          result-ch (chan 1)
          sctx (sh/make-stream-ctx {:model-id "m" :api "a"} bus result-ch {})]
      (is (false? (:message-started? (sh/snapshot sctx))))
      (sh/handle-stream-event! sctx {:type :thinking :content "hmm"})
      (is (true? (:message-started? (sh/snapshot sctx))))
      (is (= "hmm" (:thinking (sh/snapshot sctx))))
      (streaming/close-bus! bus)
      (close! result-ch))))

(deftest handle-stream-event-tool-call-sets-started-and-state-test
  (testing "tool-call event sets message-started? and adds tool via shared content handler"
    (let [bus (streaming/create-event-bus)
          result-ch (chan 1)
          sctx (sh/make-stream-ctx {:model-id "m" :api "a"} bus result-ch {})]
      (is (false? (:message-started? (sh/snapshot sctx))))
      (sh/handle-stream-event! sctx {:type :tool-call :id "t1" :name "bash" :arguments {:cmd "ls"}})
      (is (true? (:message-started? (sh/snapshot sctx))))
      (is (= 1 (count (:tools (sh/snapshot sctx)))))
      (is (= "bash" (:name (first (:tools (sh/snapshot sctx))))))
      (streaming/close-bus! bus)
      (close! result-ch))))
