(ns pi.kernel.self-mod-analysis-pure-test
  (:require [cljs.test :refer [deftest testing is]]
            [pi.kernel.interceptors.self-mod-analysis :as sma]
            [pi.kernel.state :as state]))

;; --- should-auto-disable? ---

(deftest should-auto-disable?-under-min-uses-test
  (testing "Returns false when total uses below minimum threshold"
    (is (false? (sma/should-auto-disable? {:successes 1 :failures 2 :total 3})))))

(deftest should-auto-disable?-low-failure-rate-test
  (testing "Returns false when failure rate is at or below threshold"
    (is (false? (sma/should-auto-disable? {:successes 4 :failures 2 :total 6})))))

(deftest should-auto-disable?-high-failure-rate-test
  (testing "Returns true when failure rate exceeds threshold after min uses"
    (is (true? (sma/should-auto-disable? {:successes 2 :failures 4 :total 6})))))

(deftest should-auto-disable?-exactly-at-threshold-test
  (testing "Returns false when failure rate is exactly 50% (not exceeding)"
    (is (false? (sma/should-auto-disable? {:successes 3 :failures 3 :total 6})))))

(deftest should-auto-disable?-zero-total-test
  (testing "Returns false when no uses at all"
    (is (false? (sma/should-auto-disable? {:successes 0 :failures 0 :total 0})))))

;; --- next-tool-stats ---

(deftest next-tool-stats-success-test
  (testing "Increments successes on non-error result"
    (let [stats {:successes 1 :failures 0 :total 1}
          result (sma/next-tool-stats stats false)]
      (is (= {:successes 2 :failures 0 :total 2} result)))))

(deftest next-tool-stats-failure-test
  (testing "Increments failures on error result"
    (let [stats {:successes 1 :failures 0 :total 1}
          result (sma/next-tool-stats stats true)]
      (is (= {:successes 1 :failures 1 :total 2} result)))))

(deftest next-tool-stats-nil-stats-test
  (testing "Handles nil stats (first use)"
    (is (= {:successes 1 :failures 0 :total 1} (sma/next-tool-stats nil false)))
    (is (= {:successes 0 :failures 1 :total 1} (sma/next-tool-stats nil true)))))

;; --- compute-leave-updates ---

(deftest compute-leave-updates-no-modifications-test
  (testing "No changes when tools have no active modifications"
    (let [agent-state (state/create-state {:tool-modifications {}})
          tool-pairs [{:tool-call {:name "read_file"} :result {:content "ok"}}]
          result (sma/compute-leave-updates agent-state tool-pairs)]
      (is (= {:tool-modifications {}} (state/get-state (:agent-state result))))
      (is (empty? (:auto-disabled result))))))

(deftest compute-leave-updates-success-increment-test
  (testing "Increments success count for modified tool on success"
    (let [agent-state (state/create-state
                       {:tool-modifications
                        {"read_file" {:stats {:successes 0 :failures 0}}}})
          tool-pairs [{:tool-call {:name "read_file"} :result {:content "ok"}}]
          result (sma/compute-leave-updates agent-state tool-pairs)
          stats (get-in (state/get-state (:agent-state result))
                        [:tool-modifications "read_file" :stats])]
      (is (= 1 (:successes stats)))
      (is (= 0 (:failures stats)))
      (is (empty? (:auto-disabled result))))))

(deftest compute-leave-updates-failure-increment-test
  (testing "Increments failure count for modified tool on error"
    (let [agent-state (state/create-state
                       {:tool-modifications
                        {"read_file" {:stats {:successes 0 :failures 0}}}})
          tool-pairs [{:tool-call {:name "read_file"} :result {:error true :content "err"}}]
          result (sma/compute-leave-updates agent-state tool-pairs)
          stats (get-in (state/get-state (:agent-state result))
                        [:tool-modifications "read_file" :stats])]
      (is (= 0 (:successes stats)))
      (is (= 1 (:failures stats)))
      (is (empty? (:auto-disabled result))))))

(deftest compute-leave-updates-auto-disable-test
  (testing "Triggers auto-disable when failure rate exceeds threshold"
    (let [agent-state (state/create-state
                       {:tool-modifications
                        {"read_file" {:stats {:successes 2 :failures 2}}}})
          tool-pairs [{:tool-call {:name "read_file"} :result {:error true :content "err"}}]
          result (sma/compute-leave-updates agent-state tool-pairs)
          mod-info (get-in (state/get-state (:agent-state result))
                           [:tool-modifications "read_file"])]
      (is (true? (:disabled? mod-info)))
      (is (= 3 (get-in mod-info [:stats :failures])))
      (is (= 1 (count (:auto-disabled result))))
      (is (= "read_file" (:tool-name (first (:auto-disabled result))))))))

(deftest compute-leave-updates-skips-disabled-test
  (testing "Skips tools already disabled"
    (let [agent-state (state/create-state
                       {:tool-modifications
                        {"read_file" {:disabled? true
                                      :stats {:successes 0 :failures 5}}}})
          tool-pairs [{:tool-call {:name "read_file"} :result {:error true :content "err"}}]
          result (sma/compute-leave-updates agent-state tool-pairs)]
      ;; Stats should not change for disabled tool
      (is (= 5 (get-in
                 (state/get-state (:agent-state result))
                 [:tool-modifications "read_file" :stats :failures])))
      (is (empty? (:auto-disabled result))))))

(deftest compute-leave-updates-multiple-tools-test
  (testing "Processes multiple tool calls independently"
    (let [agent-state (state/create-state
                       {:tool-modifications
                        {"read_file" {:stats {:successes 3 :failures 0}}
                         "bash" {:stats {:successes 2 :failures 2}}}})
          tool-pairs [{:tool-call {:name "read_file"} :result {:content "ok"}}
                      {:tool-call {:name "bash"} :result {:error true :content "fail"}}]
          result (sma/compute-leave-updates agent-state tool-pairs)
          data (state/get-state (:agent-state result))]
      ;; read_file: 4 successes
      (is (= 4 (get-in data [:tool-modifications "read_file" :stats :successes])))
      ;; bash: auto-disabled (3/5 = 60% > 50%)
      (is (true? (get-in data [:tool-modifications "bash" :disabled?])))
      (is (= 1 (count (:auto-disabled result))))
      (is (= "bash" (:tool-name (first (:auto-disabled result))))))))
