(ns pi.kernel.interceptors.defaults-test
  "Tests for pure helpers extracted in round 22: normalize-tool-result,
   merge-tool-opts, and build-messages integration."
  (:require [cljs.test :refer [deftest testing is]]
            [pi.kernel.interceptors.defaults :as defaults]
            [pi.kernel.session :as session]))

;; ---------------------------------------------------------------------------
;; normalize-tool-result
;; ---------------------------------------------------------------------------

(deftest normalize-tool-result-nil
  (testing "nil input returns default content"
    (is (= {:content "No result"} (defaults/normalize-tool-result nil)))))

(deftest normalize-tool-result-with-content
  (testing "result with :content passes through unchanged"
    (is (= {:content "hello"} (defaults/normalize-tool-result {:content "hello"})))))

(deftest normalize-tool-result-error-without-content
  (testing "result with :error but no :content copies error to content"
    (let [result (defaults/normalize-tool-result {:error "something broke"})]
      (is (= "something broke" (:content result)))
      (is (= "something broke" (:error result))))))

(deftest normalize-tool-result-error-with-content
  (testing "result with both :error and :content preserves both"
    (is (= {:error "err" :content "detail"}
           (defaults/normalize-tool-result {:error "err" :content "detail"})))))

(deftest normalize-tool-result-extra-keys
  (testing "extra keys are preserved"
    (is (= {:content "ok" :foo :bar}
           (defaults/normalize-tool-result {:content "ok" :foo :bar})))))

;; ---------------------------------------------------------------------------
;; merge-tool-opts
;; ---------------------------------------------------------------------------

(deftest merge-tool-opts-nil-opts
  (testing "nil opts returns args unchanged"
    (is (= {:x 1} (defaults/merge-tool-opts {:x 1} nil)))))

(deftest merge-tool-opts-empty-opts
  (testing "empty opts returns args unchanged"
    (is (= {:x 1} (defaults/merge-tool-opts {:x 1} {})))))

(deftest merge-tool-opts-on-update
  (testing "on-update is merged into args"
    (let [f (fn [_] nil)
          result (defaults/merge-tool-opts {:x 1} {:on-update f})]
      (is (= f (:on-update result)))
      (is (= 1 (:x result))))))

(deftest merge-tool-opts-abort-signal
  (testing "abort-signal is merged into args"
    (let [sig #js {}
          result (defaults/merge-tool-opts {:x 1} {:abort-signal sig})]
      (is (= sig (:abort-signal result)))
      (is (= 1 (:x result))))))

(deftest merge-tool-opts-both
  (testing "both on-update and abort-signal are merged"
    (let [f (fn [_] nil)
          sig #js {}
          result (defaults/merge-tool-opts {:x 1} {:on-update f :abort-signal sig})]
      (is (= f (:on-update result)))
      (is (= sig (:abort-signal result)))
      (is (= 1 (:x result))))))

;; ---------------------------------------------------------------------------
;; build-messages (integration with session)
;; ---------------------------------------------------------------------------

(deftest build-messages-empty-session
  (testing "empty session returns empty vec"
    (let [session (session/create-session)]
      (is (= [] (defaults/build-messages session))))))

(deftest build-messages-with-entries
  (testing "entries are converted and ordered"
    (let [session (session/create-session {:clock-fn (let [c (atom 0)] #(swap! c inc))})
          _ (session/append-entry! session {:entry/type :user-message
                                            :entry/data {:content "hi"}})
          _ (session/append-entry! session {:entry/type :assistant-message
                                            :entry/data {:content "hello" :tool-calls []}})
          msgs (defaults/build-messages session)]
      (is (= 2 (count msgs)))
      (is (= "user" (:role (first msgs))))
      (is (= "assistant" (:role (second msgs)))))))
