(ns pi.coding-agent.session-facade-test
  "Tests for Session Facade structure and property delegation."
  (:require [cljs.test :refer [deftest testing is]]
            [pi.coding-agent.session-facade :as facade]))

;; --- Mock TS Session ---

(defn create-mock-ts-session
  "Create a mock object mimicking TS AgentSession interface."
  []
  (let [subscribers (atom [])
        state (atom {:prompt-calls []})
        mock #js {:agent #js {:waitForIdle (fn [] (js/Promise.resolve))}
                  :sessionManager #js {:buildSessionContext (fn [] #js {:messages #js []})
                                       :getSessionName (fn [] "test-session")}
                  :settingsManager #js {:getMaxRetries (fn [] 3)}
                  :modelRegistry #js {:getAvailable (fn [] #js [])}
                  :resourceLoader #js {}
                  :extensionRunner nil
                  :promptTemplates #js []
                  :subscribe (fn [listener]
                               (swap! subscribers conj listener)
                               (fn [] (swap! subscribers
                                             (fn [subs] (vec (remove #{listener} subs))))))
                  :dispose (fn [])
                  :prompt (fn [text opts]
                            (swap! state update :prompt-calls conj {:text text :opts opts})
                            (js/Promise.resolve))
                  :steer (fn [_t] (js/Promise.resolve))
                  :followUp (fn [_t] (js/Promise.resolve))
                  :abort (fn [] (js/Promise.resolve))
                  :abortBash (fn [])
                  :abortCompaction (fn [])
                  :abortBranchSummary (fn [])
                  :abortRetry (fn [])
                  :newSession (fn [] (js/Promise.resolve true))
                  :setModel (fn [_m] (js/Promise.resolve))
                  :cycleModel (fn [_d] (js/Promise.resolve nil))
                  :setThinkingLevel (fn [_l])
                  :cycleThinkingLevel (fn [] "medium")
                  :getAvailableThinkingLevels (fn [] #js ["off" "medium" "high"])
                  :supportsThinking (fn [] true)
                  :supportsXhighThinking (fn [] false)
                  :setSteeringMode (fn [_m])
                  :setFollowUpMode (fn [_m])
                  :compact (fn [] (js/Promise.resolve #js {}))
                  :setAutoCompactionEnabled (fn [_e])
                  :bindExtensions (fn [_b] (js/Promise.resolve))
                  :reload (fn [] (js/Promise.resolve))
                  :setAutoRetryEnabled (fn [_e])
                  :executeBash (fn [_c _cb _o] (js/Promise.resolve #js {}))
                  :recordBashResult (fn [_c _r _o])
                  :switchSession (fn [_p] (js/Promise.resolve true))
                  :setSessionName (fn [_n])
                  :fork (fn [_id] (js/Promise.resolve #js {:cancelled false}))
                  :navigateTree (fn [_id _o] (js/Promise.resolve #js {:cancelled false}))
                  :getUserMessagesForForking (fn [] #js [])
                  :getSessionStats (fn [] #js {})
                  :getContextUsage (fn [] nil)
                  :exportToHtml (fn [_p] (js/Promise.resolve "out.html"))
                  :getLastAssistantText (fn [] "hello")
                  :hasExtensionHandlers (fn [_t] false)
                  :getActiveToolNames (fn [] #js ["read_file" "bash"])
                  :getAllTools (fn [] #js [])
                  :setActiveToolsByName (fn [_n])
                  :setScopedModels (fn [_m])
                  :getSteeringMessages (fn [] #js [])
                  :getFollowUpMessages (fn [] #js [])
                  :clearQueue (fn [] #js {:steering #js [] :followUp #js []})
                  :sendUserMessage (fn [_c _o] (js/Promise.resolve))
                  :sendCustomMessage (fn [_m _o] (js/Promise.resolve))}]
    (js/Object.defineProperties mock
      #js {:state #js {:get (fn [] #js {:messages #js []}) :enumerable true}
           :model #js {:get (fn [] #js {:id "claude-haiku" :provider "anthropic"}) :enumerable true}
           :thinkingLevel #js {:get (fn [] "off") :enumerable true}
           :isStreaming #js {:get (fn [] false) :enumerable true}
           :isCompacting #js {:get (fn [] false) :enumerable true}
           :isBashRunning #js {:get (fn [] false) :enumerable true}
           :hasPendingBashMessages #js {:get (fn [] false) :enumerable true}
           :systemPrompt #js {:get (fn [] "system prompt") :enumerable true}
           :retryAttempt #js {:get (fn [] 0) :enumerable true}
           :autoCompactionEnabled #js {:get (fn [] true) :enumerable true}
           :isRetrying #js {:get (fn [] false) :enumerable true}
           :autoRetryEnabled #js {:get (fn [] true) :enumerable true}
           :messages #js {:get (fn [] #js []) :enumerable true}
           :steeringMode #js {:get (fn [] "all") :enumerable true}
           :followUpMode #js {:get (fn [] "all") :enumerable true}
           :sessionFile #js {:get (fn [] "/tmp/test.json") :enumerable true}
           :sessionId #js {:get (fn [] "test-id") :enumerable true}
           :sessionName #js {:get (fn [] "test-session") :enumerable true}
           :pendingMessageCount #js {:get (fn [] 0) :enumerable true}
           :scopedModels #js {:get (fn [] #js []) :enumerable true}})
    {:mock mock :state state :subscribers subscribers}))

;; --- Tests ---

(deftest facade-exposes-ts-properties-test
  (testing "facade exposes TS session properties via getters"
    (let [{:keys [mock]} (create-mock-ts-session)
          f (facade/create-session-facade mock)]
      (is (some? (.-sessionManager f)))
      (is (some? (.-settingsManager f)))
      (is (some? (.-modelRegistry f)))
      (is (some? (.-agent f)))
      (is (= "system prompt" (.-systemPrompt f)))
      (is (= false (.-isCompacting f)))
      (is (= false (.-isBashRunning f)))
      (is (= "off" (.-thinkingLevel f)))
      (is (= true (.-autoCompactionEnabled f)))
      (is (= "all" (.-steeringMode f)))
      (is (= "all" (.-followUpMode f)))
      (is (= "test-id" (.-sessionId f)))
      (is (= "test-session" (.-sessionName f)))
      (is (= 0 (.-pendingMessageCount f))))))

(deftest facade-isStreaming-from-facade-state-test
  (testing "isStreaming reads from facade state, not TS session"
    (let [{:keys [mock]} (create-mock-ts-session)
          f (facade/create-session-facade mock)]
      ;; Initially false
      (is (= false (.-isStreaming f))))))

(deftest facade-retryAttempt-from-facade-state-test
  (testing "retryAttempt reads from facade state"
    (let [{:keys [mock]} (create-mock-ts-session)
          f (facade/create-session-facade mock)]
      (is (= 0 (.-retryAttempt f))))))

(deftest facade-state-messages-from-datascript-test
  (testing ".state.messages projects from DataScript (initially empty)"
    (let [{:keys [mock]} (create-mock-ts-session)
          f (facade/create-session-facade mock)
          msgs (.. f -state -messages)]
      (is (some? msgs))
      (is (= 0 (.-length msgs))))))

(deftest facade-subscribe-test
  (testing "subscribe returns unsubscribe function"
    (let [{:keys [mock]} (create-mock-ts-session)
          f (facade/create-session-facade mock)
          events (atom [])
          unsub (.subscribe f (fn [e] (swap! events conj e)))]
      (is (fn? unsub))
      (unsub))))

(deftest facade-internal-state-accessible-test
  (testing "facade internals accessible via __ properties"
    (let [{:keys [mock]} (create-mock-ts-session)
          f (facade/create-session-facade mock)]
      (is (some? (.-__facade_state f)))
      (is (some? (.-__kernel_session f)))
      (is (some? (.-__event_bus f))))))

(deftest facade-abort-test
  (testing "abort resolves without error"
    (let [{:keys [mock]} (create-mock-ts-session)
          f (facade/create-session-facade mock)]
      (is (some? (.abort f))))))

(deftest facade-new-session-resets-kernel-session-test
  (testing "newSession resets kernel DataScript session"
    (let [{:keys [mock]} (create-mock-ts-session)
          f (facade/create-session-facade mock)
          ks1 (.-__kernel_session f)]
      (.newSession f)
      (let [ks2 (.-__kernel_session f)]
        (is (not (identical? ks1 ks2)))))))

(deftest facade-delegates-methods-test
  (testing "facade delegates non-prompt methods to TS session"
    (let [{:keys [mock]} (create-mock-ts-session)
          f (facade/create-session-facade mock)]
      (is (= "hello" (.getLastAssistantText f)))
      (is (= false (.hasExtensionHandlers f "test")))
      (is (some? (.cycleThinkingLevel f))))))

;; --- reset-kernel-session! ---

(deftest reset-kernel-session-creates-new-session-test
  (testing "reset-kernel-session! replaces kernel-session with a fresh one"
    (let [{:keys [mock]} (create-mock-ts-session)
          f (facade/create-session-facade mock)
          fs (.-__facade_state f)
          ks1 (:kernel-session @fs)]
      (facade/reset-kernel-session! fs {})
      (let [ks2 (:kernel-session @fs)]
        (is (some? ks2))
        (is (not (identical? ks1 ks2)))))))

(deftest reset-kernel-session-merges-extra-state-test
  (testing "reset-kernel-session! merges extra state keys into facade state"
    (let [{:keys [mock]} (create-mock-ts-session)
          f (facade/create-session-facade mock)
          fs (.-__facade_state f)]
      ;; Set agent to something non-nil
      (swap! fs assoc :agent :dummy-agent :retry-attempt 5)
      (facade/reset-kernel-session! fs {:agent nil :retry-attempt 0})
      (is (nil? (:agent @fs)))
      (is (= 0 (:retry-attempt @fs))))))

(deftest reset-kernel-session-with-restore-test
  (testing "reset-kernel-session! with restore-from populates from session manager"
    (let [{:keys [mock]} (create-mock-ts-session)
          f (facade/create-session-facade mock)
          fs (.-__facade_state f)
          ks1 (:kernel-session @fs)]
      ;; Call with restore-from (mock session manager returns empty messages)
      (facade/reset-kernel-session! fs {:restore-from (.-sessionManager mock)})
      (let [ks2 (:kernel-session @fs)]
        (is (some? ks2))
        (is (not (identical? ks1 ks2)))))))
