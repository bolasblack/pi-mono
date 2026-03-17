(ns pi.coding-agent.mode-dispatch-test
  "Tests for mode dispatch classification logic."
  (:require [cljs.test :refer [deftest testing is]]
            [pi.coding-agent.mode-dispatch :as md]))

;; --- classify-agent-mode tests ---

(deftest classify-rpc-mode-test
  (testing "rpc mode when --mode=rpc"
    (is (= :rpc (md/classify-agent-mode
                  {:mode "rpc" :print? false :stdin? false})))))

(deftest classify-interactive-no-flags-test
  (testing "interactive mode when no print and no mode flag"
    (is (= :interactive (md/classify-agent-mode
                          {:mode nil :print? false :stdin? false})))))

(deftest classify-print-flag-test
  (testing "print mode when --print flag"
    (is (= :print (md/classify-agent-mode
                    {:mode nil :print? true :stdin? false})))))

(deftest classify-stdin-content-test
  (testing "print mode when stdin content present"
    (is (= :print (md/classify-agent-mode
                    {:mode nil :print? false :stdin? true})))))

(deftest classify-print-and-stdin-test
  (testing "print mode when both print flag and stdin"
    (is (= :print (md/classify-agent-mode
                    {:mode nil :print? true :stdin? true})))))

(deftest classify-text-mode-test
  (testing "print mode when --mode=text"
    (is (= :print (md/classify-agent-mode
                    {:mode "text" :print? false :stdin? false})))))

(deftest classify-json-mode-test
  (testing "print mode when --mode=json"
    (is (= :print (md/classify-agent-mode
                    {:mode "json" :print? false :stdin? false})))))
