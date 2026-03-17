(ns pi.coding-agent.facade-pure-test
  "Tests for pure decision functions extracted from session-facade."
  (:require [cljs.test :refer [deftest testing is]]
            [pi.coding-agent.session-facade :as facade]))

;; --- retryable-error? ---

(deftest retryable-error-overloaded-test
  (testing "overloaded error is retryable"
    (is (some? (facade/retryable-error? "Server overloaded, try later")))))

(deftest retryable-error-rate-limit-test
  (testing "rate limit error is retryable"
    (is (some? (facade/retryable-error? "Rate limit exceeded")))))

(deftest retryable-error-too-many-requests-test
  (testing "too many requests is retryable"
    (is (some? (facade/retryable-error? "429 Too many requests")))))

(deftest retryable-error-529-test
  (testing "529 error is retryable"
    (is (some? (facade/retryable-error? "Error 529")))))

(deftest retryable-error-503-test
  (testing "503 service unavailable is retryable"
    (is (some? (facade/retryable-error? "503 Service Unavailable")))))

(deftest retryable-error-500-test
  (testing "500 internal server error is retryable"
    (is (some? (facade/retryable-error? "500 Internal Server Error")))))

(deftest retryable-error-capacity-test
  (testing "capacity error is retryable"
    (is (some? (facade/retryable-error? "At capacity, please retry")))))

(deftest retryable-error-non-retryable-test
  (testing "non-retryable error returns falsy"
    (is (not (facade/retryable-error? "Invalid API key")))))

(deftest retryable-error-nil-test
  (testing "nil returns falsy"
    (is (not (facade/retryable-error? nil)))))

(deftest retryable-error-non-string-test
  (testing "non-string returns falsy"
    (is (not (facade/retryable-error? 42)))))

;; --- retry-delay-ms ---

(deftest retry-delay-first-attempt-test
  (testing "first attempt = 5000ms"
    (is (= 5000 (facade/retry-delay-ms 1)))))

(deftest retry-delay-second-attempt-test
  (testing "second attempt = 10000ms"
    (is (= 10000 (facade/retry-delay-ms 2)))))

(deftest retry-delay-third-attempt-test
  (testing "third attempt = 20000ms"
    (is (= 20000 (facade/retry-delay-ms 3)))))

(deftest retry-delay-caps-at-60s-test
  (testing "delay caps at 60000ms"
    (is (= 60000 (facade/retry-delay-ms 10)))))

;; --- should-retry? ---

(deftest should-retry-retryable-under-max-test
  (testing "retryable error under max retries returns true"
    (is (true? (facade/should-retry?
                 {:auto-retry-enabled? true
                  :error-message "Server overloaded"
                  :attempt 1
                  :max-retries 3})))))

(deftest should-retry-at-max-retries-test
  (testing "at max retries returns true (attempt <= max)"
    (is (true? (facade/should-retry?
                 {:auto-retry-enabled? true
                  :error-message "rate limit"
                  :attempt 3
                  :max-retries 3})))))

(deftest should-retry-over-max-retries-test
  (testing "over max retries returns false"
    (is (false? (facade/should-retry?
                  {:auto-retry-enabled? true
                   :error-message "rate limit"
                   :attempt 4
                   :max-retries 3})))))

(deftest should-retry-non-retryable-test
  (testing "non-retryable error returns false"
    (is (false? (facade/should-retry?
                  {:auto-retry-enabled? true
                   :error-message "Invalid API key"
                   :attempt 1
                   :max-retries 3})))))

(deftest should-retry-disabled-test
  (testing "auto-retry disabled returns false"
    (is (false? (facade/should-retry?
                  {:auto-retry-enabled? false
                   :error-message "Server overloaded"
                   :attempt 1
                   :max-retries 3})))))

;; --- should-compact? ---

(deftest should-compact-over-threshold-test
  (testing "usage over threshold returns true"
    (is (true? (facade/should-compact?
                 {:auto-compaction-enabled? true
                  :percent-used 0.85})))))

(deftest should-compact-under-threshold-test
  (testing "usage under threshold returns false"
    (is (false? (facade/should-compact?
                  {:auto-compaction-enabled? true
                   :percent-used 0.5})))))

(deftest should-compact-at-threshold-test
  (testing "usage exactly at threshold returns false (> not >=)"
    (is (false? (facade/should-compact?
                  {:auto-compaction-enabled? true
                   :percent-used 0.8})))))

(deftest should-compact-disabled-test
  (testing "compaction disabled returns false"
    (is (false? (facade/should-compact?
                  {:auto-compaction-enabled? false
                   :percent-used 0.95})))))

(deftest should-compact-nil-usage-test
  (testing "nil percent-used returns false"
    (is (false? (facade/should-compact?
                  {:auto-compaction-enabled? true
                   :percent-used nil})))))

(deftest should-compact-custom-threshold-test
  (testing "custom threshold is respected"
    (is (true? (facade/should-compact?
                 {:auto-compaction-enabled? true
                  :percent-used 0.65
                  :threshold 0.6})))))

;; --- error->message ---

(deftest error->message-js-error-test
  (testing "extracts message from JS Error"
    (is (= "something broke" (facade/error->message (js/Error. "something broke"))))))

(deftest error->message-string-test
  (testing "returns string as-is"
    (is (= "plain string" (facade/error->message "plain string")))))

(deftest error->message-number-test
  (testing "converts number to string"
    (is (= "42" (facade/error->message 42)))))

(deftest error->message-nil-test
  (testing "converts nil to string"
    (is (= "" (facade/error->message nil)))))

;; --- compaction-aborted? ---

(deftest compaction-aborted-cancelled-message-test
  (testing "Compaction cancelled message is detected as aborted"
    (is (true? (facade/compaction-aborted?
                 {:error (js/Error. "Compaction cancelled")})))))

(deftest compaction-aborted-abort-error-test
  (testing "AbortError name is detected as aborted"
    (let [err (js/Error. "aborted")]
      (set! (.-name err) "AbortError")
      (is (true? (facade/compaction-aborted? {:error err}))))))

(deftest compaction-aborted-other-error-test
  (testing "other errors are not aborted"
    (is (false? (facade/compaction-aborted?
                  {:error (js/Error. "network timeout")})))))

(deftest compaction-aborted-string-error-test
  (testing "string error matching Compaction cancelled is aborted"
    (is (true? (facade/compaction-aborted?
                 {:error "Compaction cancelled"})))))

(deftest compaction-aborted-string-non-match-test
  (testing "string error not matching is not aborted"
    (is (false? (facade/compaction-aborted?
                  {:error "something else"})))))
