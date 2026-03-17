(ns pi.kernel.bridge.ai-test
  (:require [cljs.test :refer [deftest async is testing]]
            [cljs.core.async :refer [go <! chan put! close!]]
            [pi.kernel.bridge.ai :as bridge]))

;; --- Test helpers: fake AsyncIterable ---

(defn- make-async-iterable
  "Create a fake JS AsyncIterable from a sequence of JS event objects."
  [events]
  (let [idx (atom 0)
        arr (to-array events)
        iter-obj #js {}]
    ;; Define .next() on the iterator
    (set! (.-next iter-obj)
      (fn []
        (js/Promise.
          (fn [resolve _reject]
            (let [i @idx]
              (if (< i (.-length arr))
                (do (swap! idx inc)
                    (resolve #js {:value (aget arr i) :done false}))
                (resolve #js {:value js/undefined :done true})))))))
    ;; Create the iterable with Symbol.asyncIterator
    (let [iterable #js {}]
      (aset iterable js/Symbol.asyncIterator (fn [] iter-obj))
      iterable)))

(deftest stream-simple-returns-channel-with-events
  (async done
    (let [events [#js {:type "start" :partial #js {:role "assistant" :content #js []}}
                  #js {:type "text_delta" :contentIndex 0 :delta "Hello"
                       :partial #js {:role "assistant"}}
                  #js {:type "text_delta" :contentIndex 0 :delta " world"
                       :partial #js {:role "assistant"}}
                  #js {:type "done" :reason "stop"
                       :message #js {:role "assistant"
                                     :content #js [#js {:type "text" :text "Hello world"}]}}]
          fake-iterable (make-async-iterable events)
          ;; Mock pi-ai module with streamSimple that returns our fake iterable
          mock-module #js {:streamSimple (fn [_model _ctx _opts] fake-iterable)}
          mock-model #js {:id "test" :api "mock"}
          ch (bridge/stream-simple mock-module mock-model
               {:system-prompt "You are helpful."
                :messages [{:role "user" :content "Hi"}]}
               nil)
          collected (atom [])]
      (go
        (loop []
          (let [v (<! ch)]
            (when v
              (swap! collected conj v)
              (recur))))
        ;; Channel closed, check events
        (is (pos? (count @collected)) "Should have received events")
        ;; First event is :message-start
        (is (= :message-start (:type (first @collected))))
        ;; Last event is :stop
        (is (= :stop (:type (last @collected))))
        (done)))))

(deftest text-delta-events-converted
  (async done
    (let [events [#js {:type "text_delta" :contentIndex 0 :delta "Hello"
                       :partial #js {:role "assistant"}}
                  #js {:type "text_delta" :contentIndex 0 :delta " world"
                       :partial #js {:role "assistant"}}
                  #js {:type "done" :reason "stop"
                       :message #js {:role "assistant" :content #js []}}]
          fake-iterable (make-async-iterable events)
          mock-module #js {:streamSimple (fn [_ _ _] fake-iterable)}
          ch (bridge/stream-simple mock-module #js {} {:messages []} nil)
          collected (atom [])]
      (go
        (loop []
          (let [v (<! ch)]
            (when v
              (swap! collected conj v)
              (recur))))
        (let [text-events (filter #(= :text (:type %)) @collected)]
          (is (= 2 (count text-events)))
          (is (= "Hello" (:content (first text-events))))
          (is (= " world" (:content (second text-events)))))
        (done)))))

(deftest toolcall-events-converted
  (async done
    (let [events [#js {:type "toolcall_start" :contentIndex 0
                       :partial #js {:role "assistant"
                                     :content #js [#js {:type "toolCall"
                                                        :id "tc-1"
                                                        :name "read_file"
                                                        :input #js {}}]}}
                  #js {:type "toolcall_delta" :contentIndex 0 :delta "{\"path\":\"foo\"}"
                       :partial #js {:role "assistant"}}
                  #js {:type "toolcall_end" :contentIndex 0
                       :toolCall #js {:type "toolCall"
                                      :id "tc-1"
                                      :name "read_file"
                                      :input #js {:path "foo.txt"}}}
                  #js {:type "done" :reason "toolUse"
                       :message #js {:role "assistant" :content #js []}}]
          fake-iterable (make-async-iterable events)
          mock-module #js {:streamSimple (fn [_ _ _] fake-iterable)}
          ch (bridge/stream-simple mock-module #js {} {:messages []} nil)
          collected (atom [])]
      (go
        (loop []
          (let [v (<! ch)]
            (when v
              (swap! collected conj v)
              (recur))))
        ;; Check tool-call-start
        (let [starts (filter #(= :tool-call-start (:type %)) @collected)]
          (is (= 1 (count starts)))
          (is (= "tc-1" (:id (first starts))))
          (is (= "read_file" (:name (first starts)))))
        ;; Check tool-input
        (let [deltas (filter #(= :tool-input (:type %)) @collected)]
          (is (= 1 (count deltas))))
        ;; Check tool-call (end)
        (let [ends (filter #(= :tool-call (:type %)) @collected)]
          (is (= 1 (count ends)))
          (is (= "tc-1" (:id (first ends))))
          (is (= "read_file" (:name (first ends))))
          (is (= {:path "foo.txt"} (:arguments (first ends)))))
        (done)))))

(deftest error-events-propagated
  (async done
    (let [events [#js {:type "error" :reason "error"
                       :error #js {:role "assistant"
                                   :errorMessage "Something broke"}}]
          fake-iterable (make-async-iterable events)
          mock-module #js {:streamSimple (fn [_ _ _] fake-iterable)}
          ch (bridge/stream-simple mock-module #js {} {:messages []} nil)
          collected (atom [])]
      (go
        (loop []
          (let [v (<! ch)]
            (when v
              (swap! collected conj v)
              (recur))))
        (let [errors (filter #(= :error (:type %)) @collected)]
          (is (= 1 (count errors)))
          (is (= "Something broke" (:message (first errors)))))
        (done)))))

(deftest done-event-produces-stop
  (async done
    (let [events [#js {:type "done" :reason "stop"
                       :message #js {:role "assistant"
                                     :content #js [#js {:type "text" :text "Hi"}]
                                     :stopReason "stop"}}]
          fake-iterable (make-async-iterable events)
          mock-module #js {:streamSimple (fn [_ _ _] fake-iterable)}
          ch (bridge/stream-simple mock-module #js {} {:messages []} nil)
          collected (atom [])]
      (go
        (loop []
          (let [v (<! ch)]
            (when v
              (swap! collected conj v)
              (recur))))
        (is (= 1 (count @collected)))
        (let [evt (first @collected)]
          (is (= :stop (:type evt)))
          (is (= :stop (:reason evt)))
          (is (map? (:message evt))))
        (done)))))

(deftest stream-simple-with-signal-passes-signal-in-opts
  (async done
    (let [captured-opts (atom nil)
          fake-signal   #js {:aborted false}
          events        [#js {:type "done" :reason "stop"
                              :message #js {:role "assistant" :content #js []}}]
          fake-iterable (make-async-iterable events)
          mock-module   #js {:streamSimple
                             (fn [_model _ctx opts]
                               (reset! captured-opts opts)
                               fake-iterable)}
          ch (bridge/stream-simple-with-signal
               mock-module #js {} {:messages []} nil fake-signal)]
      (go
        (loop []
          (when (<! ch) (recur)))
        (is (some? @captured-opts) "opts should have been passed")
        (is (identical? fake-signal (.-signal @captured-opts))
            "AbortSignal should be set on opts.signal")
        (done)))))

(deftest stream-simple-with-signal-nil-signal-omits-signal
  (async done
    (let [captured-opts (atom nil)
          events        [#js {:type "done" :reason "stop"
                              :message #js {:role "assistant" :content #js []}}]
          fake-iterable (make-async-iterable events)
          mock-module   #js {:streamSimple
                             (fn [_model _ctx opts]
                               (reset! captured-opts opts)
                               fake-iterable)}
          ch (bridge/stream-simple-with-signal
               mock-module #js {} {:messages []} nil nil)]
      (go
        (loop []
          (when (<! ch) (recur)))
        (is (some? @captured-opts))
        (is (nil? (.-signal @captured-opts))
            "signal should not be set when abort-signal is nil")
        (done)))))

(deftest stream-simple-with-signal-merges-opts-and-signal
  (async done
    (let [captured-opts (atom nil)
          fake-signal   #js {:aborted false}
          events        [#js {:type "done" :reason "stop"
                              :message #js {:role "assistant" :content #js []}}]
          fake-iterable (make-async-iterable events)
          mock-module   #js {:streamSimple
                             (fn [_model _ctx opts]
                               (reset! captured-opts opts)
                               fake-iterable)}
          ch (bridge/stream-simple-with-signal
               mock-module #js {} {:messages []}
               {:maxTokens 1024} fake-signal)]
      (go
        (loop []
          (when (<! ch) (recur)))
        (is (= 1024 (.-maxTokens @captured-opts))
            "custom opts should be preserved")
        (is (identical? fake-signal (.-signal @captured-opts))
            "signal should be merged into opts")
        (done)))))
