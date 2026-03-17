(ns pi.kernel.bash-streaming-test
  "Tests for bash tool streaming (on-update callback) and abort (AbortSignal)."
  (:require [cljs.test :refer [deftest testing is async]]
            [cljs.core.async :refer [go <! timeout]]
            [pi.kernel.tools.bash :as bash]))

;; --- on-update streaming ---

(deftest bash-on-update-receives-chunks-test
  (async done
    (go
      (let [chunks (atom [])
            result (<! (bash/bash-impl
                         {:command "echo hello && echo world"
                          :on-update (fn [chunk] (swap! chunks conj chunk))}))]
        (testing "on-update called with output chunks"
          (is (pos? (count @chunks))
              "Expected at least 1 chunk via on-update"))
        (testing "final result still has full content"
          (is (some? (:content result))
              "Result should have :content"))
        (done)))))

(deftest bash-on-update-receives-stderr-test
  (async done
    (go
      (let [chunks (atom [])
            result (<! (bash/bash-impl
                         {:command "echo err >&2"
                          :on-update (fn [chunk] (swap! chunks conj chunk))}))]
        (testing "on-update receives stderr chunks"
          (is (pos? (count @chunks))
              "Expected stderr chunk via on-update"))
        (done)))))

(deftest bash-without-on-update-still-works-test
  (async done
    (go
      (let [result (<! (bash/bash-impl {:command "echo ok"}))]
        (testing "works without on-update"
          (is (string? (:content result)))
          (is (re-find #"ok" (:content result))))
        (done)))))

;; --- abort ---

(deftest bash-abort-signal-test
  (async done
    (go
      (let [ac (js/AbortController.)
            result-ch (bash/bash-impl
                        {:command "sleep 10"
                         :abort-signal (.-signal ac)})]
        ;; Abort after a short delay
        (<! (timeout 100))
        (.abort ac)
        (let [result (<! result-ch)]
          (testing "abort stops the command"
            (is (some? result) "Should get a result after abort")
            (is (or (:error result) (:content result))
                "Result should have :error or :content"))
          (testing "result indicates abort"
            (is (or (and (:error result) (re-find #"abort" (str (:error result))))
                    (and (:content result) (re-find #"abort" (str (:content result)))))
                "Result should mention abort")))
        (done)))))

(deftest bash-abort-with-on-update-test
  (async done
    (go
      (let [ac (js/AbortController.)
            chunks (atom [])
            result-ch (bash/bash-impl
                        {:command "for i in $(seq 1 100); do echo line$i; sleep 0.05; done"
                         :on-update (fn [chunk] (swap! chunks conj chunk))
                         :abort-signal (.-signal ac)})]
        ;; Let it stream a bit
        (<! (timeout 200))
        (.abort ac)
        (let [result (<! result-ch)]
          (testing "received some chunks before abort"
            (is (pos? (count @chunks))
                "Should have received chunks before abort"))
          (testing "command was stopped"
            (is (some? result))))
        (done)))))
