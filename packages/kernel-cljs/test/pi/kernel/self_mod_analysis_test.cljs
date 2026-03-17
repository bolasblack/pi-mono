(ns pi.kernel.self-mod-analysis-test
  (:require [cljs.test :refer [deftest testing is async]]
            [cljs.core.async :refer [go <!]]
            [pi.kernel.session :as session]
            [pi.kernel.state :as state]
            [pi.kernel.interceptor :as interceptor]
            [pi.kernel.interceptors.self-mod-analysis :as sma]
            [pi.kernel.core :as core]
            [datascript.core :as d]))

(defn- make-session
  "Create a session with a fixed clock starting at `now`."
  [now]
  (session/create-session {:clock-fn (constantly now)}))

(defn- add-tool-errors!
  "Add N tool-result errors for `tool-name` at given timestamps."
  [sess tool-name timestamps]
  (doseq [ts timestamps]
    (session/append-entry! sess
      {:entry/type :tool-result
       :entry/timestamp ts
       :tool-call/name tool-name
       :tool-result/error? true
       :tool-result/content "error"})))

;; --- Test 1: No suggestion when fewer than 3 failures ---

(deftest no-suggestion-under-threshold-test
  (testing "No suggestion when fewer than 3 failures for same tool"
    (let [now 600000
          sess (make-session now)]
      (add-tool-errors! sess "read_file" [(- now 60000) (- now 30000)])
      (let [ctx (sma/self-mod-analysis-enter {:session sess})]
        (is (nil? (:self-modification-suggestion ctx)))))))

;; --- Test 2: Suggestion generated after 3+ failures ---

(deftest suggestion-on-threshold-test
  (testing "Suggestion generated after 3+ failures for same tool within 5 min"
    (let [now 600000
          sess (make-session now)]
      (add-tool-errors! sess "read_file"
        [(- now 120000) (- now 60000) (- now 30000)])
      (let [ctx (sma/self-mod-analysis-enter {:session sess})
            suggestion (:self-modification-suggestion ctx)]
        (is (some? suggestion))
        (is (= "read_file" (:tool-name suggestion)))
        (is (= 3 (:failure-count suggestion)))
        (is (= 3 (count (:recent-errors suggestion))))
        (is (string? (:suggestion suggestion)))))))

;; --- Test 3: Failures for different tools don't trigger ---

(deftest different-tools-no-trigger-test
  (testing "Failures for different tools don't trigger suggestion"
    (let [now 600000
          sess (make-session now)]
      (add-tool-errors! sess "read_file" [(- now 60000) (- now 30000)])
      (add-tool-errors! sess "bash" [(- now 45000)])
      (let [ctx (sma/self-mod-analysis-enter {:session sess})]
        (is (nil? (:self-modification-suggestion ctx)))))))

;; --- Test 4: Old failures (>5 min) don't count ---

(deftest old-failures-ignored-test
  (testing "Failures older than 5 minutes are excluded"
    (let [now 600000
          sess (make-session now)]
      ;; All 3 failures are older than 5 min (300000ms)
      (add-tool-errors! sess "read_file"
        [(- now 400000) (- now 350000) (- now 310000)])
      (let [ctx (sma/self-mod-analysis-enter {:session sess})]
        (is (nil? (:self-modification-suggestion ctx)))))))

;; --- Test 5: Success tracking in :leave ---

(deftest success-tracking-test
  (testing "Modified tool called successfully increments success count"
    (let [now 600000
          sess (make-session now)
          agent-state (state/create-state
                        {:tool-modifications
                         {"read_file" {:stats {:successes 0 :failures 0}}}})
          ctx {:session sess
               :agent-state agent-state
               :response {:tool-calls [{:id "tc1" :name "read_file" :arguments {}}]}
               :tool-results [{:content "file contents"}]}
          result (sma/self-mod-analysis-leave ctx)
          new-stats (get-in (state/get-state (:agent-state result))
                            [:tool-modifications "read_file" :stats])]
      (is (= 1 (:successes new-stats)))
      (is (= 0 (:failures new-stats))))))

;; --- Test 6: Auto-disable on >50% failure rate after 5+ uses ---

(deftest auto-disable-high-failure-rate-test
  (testing "Auto-disable modification when >50% failure rate after 5+ uses"
    (let [now 600000
          sess (make-session now)
          ;; 2 successes, 2 failures so far — next failure makes 3/5 = 60%
          agent-state (state/create-state
                        {:tool-modifications
                         {"read_file" {:stats {:successes 2 :failures 2}}}})
          ctx {:session sess
               :agent-state agent-state
               :response {:tool-calls [{:id "tc1" :name "read_file" :arguments {}}]}
               :tool-results [{:error true :content "error"}]}
          result (sma/self-mod-analysis-leave ctx)
          mod-info (get-in (state/get-state (:agent-state result))
                           [:tool-modifications "read_file"])]
      ;; Should be disabled
      (is (true? (:disabled? mod-info)))
      ;; Stats should show 3 failures
      (is (= 3 (get-in mod-info [:stats :failures])))
      ;; DataScript event recorded
      (let [db @(:conn sess)
            events (d/q '[:find ?name ?type
                          :where
                          [?e :entry/type ?type]
                          [?e :modification/tool-name ?name]
                          [(= ?type :modification-auto-disabled)]]
                        db)]
        (is (= 1 (count events)))
        (is (= #{"read_file"} (set (map first events))))))))

;; --- Test 7: Interceptor integrates into default chain ---

(deftest chain-integration-test
  (testing "Self-mod-analysis interceptor exists in default chain and doesn't break flow"
    (let [chain core/default-interceptors
          names (mapv :name chain)]
      ;; Verify it's in the chain
      (is (some #{:self-modification-analysis} names))
      ;; Verify ordering: after :context-management, before :provider
      (let [cm-idx (.indexOf names :context-management)
            sma-idx (.indexOf names :self-modification-analysis)
            prov-idx (.indexOf names :provider)]
        (is (< cm-idx sma-idx))
        (is (< sma-idx prov-idx))))))

(deftest chain-no-errors-without-failures-test
  (async done
    (testing "Interceptor passes through cleanly when no failures exist"
      (let [now 600000
            sess (make-session now)
            agent-state (state/create-state core/default-agent-state-shape)
            ;; Minimal chain: just context-management + self-mod-analysis
            chain (interceptor/create-chain
                    [{:name :context-management
                      :enter (fn [ctx] (assoc ctx :messages []))}
                     sma/interceptor
                     {:name :mock-provider
                      :enter (fn [ctx]
                               (assoc ctx :response {:text "hello" :tool-calls [] :usage {}}
                                          :skip-provider? true))}])]
        (go
          (let [ctx {:session sess
                     :agent-state agent-state
                     :messages []
                     :tool-results []}
                result (<! (interceptor/execute-async chain ctx))]
            (is (nil? (:self-modification-suggestion result)))
            (is (= "hello" (get-in result [:response :text])))
            (done)))))))
