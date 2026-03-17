(ns pi.coding-agent.core-test
  (:require [cljs.test :refer [deftest testing is]]
            [pi.coding-agent.system-prompt :as sp]
            [pi.kernel.agent :as agent]
            [pi.kernel.deftool :as deftool]))

;; --- Test: system prompt is coding-agent specific ---

(deftest system-prompt-is-coding-agent-specific
  (testing "system prompt contains coding-agent persona"
    (is (string? sp/coding-agent-system-prompt))
    (is (pos? (count sp/coding-agent-system-prompt)))
    (is (re-find #"coding assistant" sp/coding-agent-system-prompt))
    (is (re-find #"read_file" sp/coding-agent-system-prompt))
    (is (re-find #"edit" sp/coding-agent-system-prompt))
    (is (re-find #"bash" sp/coding-agent-system-prompt)))
  (testing "system prompt differs from kernel default"
    (is (not= sp/coding-agent-system-prompt agent/default-system-prompt))))

;; --- Test: agent with default tools has all coding tools ---

(deftest agent-with-default-tools-test
  (testing "agent with :default-tools has all coding tools"
    (let [ag (agent/create-agent {:default-tools true})
          registered (set (deftool/list-tools (:tool-registry ag)))]
      (is (contains? registered "read_file"))
      (is (contains? registered "write_file"))
      (is (contains? registered "edit"))
      (is (contains? registered "bash"))
      (is (>= (count registered) 4)))))

;; --- Test: agent with coding-agent system prompt ---

(deftest agent-with-coding-system-prompt-test
  (testing "agent can be created with coding-agent system prompt"
    (let [ag (agent/create-agent {:system-prompt sp/coding-agent-system-prompt
                                   :default-tools true})]
      (is (= sp/coding-agent-system-prompt (:system-prompt ag)))
      (is (some? (deftool/get-tool (:tool-registry ag) "bash"))))))
