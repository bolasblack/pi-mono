(ns pi.kernel.agent-test
  "Tests for the agent instance model.
   Each agent holds its own tool-registry, system-prompt, interceptors, model-config.
   Multiple agents with different configurations can coexist."
  (:require [cljs.test :refer [deftest testing is]]
            [pi.kernel.agent :as agent]
            [pi.kernel.deftool :as deftool]))

;; --- Helper ---

(defn make-echo-tool
  "Create a tool that echoes its input with a prefix."
  [prefix]
  {:name (str prefix "-echo")
   :doc (str "Echo with " prefix)
   :params-spec [:map [:text :string]]
   :execute (fn [params]
              {:content (str prefix ": " (:text params))})})

;; --- Tests ---

(deftest create-agent-returns-map-test
  (testing "create-agent returns a map with required keys"
    (let [ag (agent/create-agent)]
      (is (some? ag))
      (is (some? (:tool-registry ag)))
      (is (string? (:system-prompt ag)))
      (is (some? (:model-config ag)))
      (is (number? (:max-tool-turns ag))))))

(deftest create-agent-default-system-prompt-test
  (testing "default system prompt is set"
    (let [ag (agent/create-agent)]
      (is (= agent/default-system-prompt (:system-prompt ag))))))

(deftest create-agent-custom-system-prompt-test
  (testing "create-agent accepts custom system prompt"
    (let [ag (agent/create-agent {:system-prompt "You are a SQL expert."})]
      (is (= "You are a SQL expert." (:system-prompt ag))))))

(deftest create-agent-custom-tools-test
  (testing "create-agent accepts custom tools"
    (let [tool (make-echo-tool "test")
          ag (agent/create-agent {:tools [tool]})]
      (is (some? (deftool/get-tool (:tool-registry ag) "test-echo")))
      (is (nil? (deftool/get-tool (:tool-registry ag) "read_file"))
          "Should NOT have default tools when only custom tools are provided"))))

(deftest create-agent-default-tools-test
  (testing "create-agent with :default-tools registers standard tools"
    (let [ag (agent/create-agent {:default-tools true})]
      (is (some? (deftool/get-tool (:tool-registry ag) "read_file")))
      (is (some? (deftool/get-tool (:tool-registry ag) "bash")))
      (is (some? (deftool/get-tool (:tool-registry ag) "edit")))
      (is (some? (deftool/get-tool (:tool-registry ag) "write_file"))))))

(deftest create-agent-default-tools-plus-custom-test
  (testing "default-tools + custom tools coexist"
    (let [tool (make-echo-tool "custom")
          ag (agent/create-agent {:default-tools true :tools [tool]})]
      (is (some? (deftool/get-tool (:tool-registry ag) "read_file")))
      (is (some? (deftool/get-tool (:tool-registry ag) "custom-echo"))))))

(deftest create-agent-model-config-test
  (testing "create-agent accepts model config"
    (let [config {:api "anthropic" :model-id "claude-3" :provider :default}
          ag (agent/create-agent {:model-config config})]
      (is (= config (:model-config ag))))))

(deftest create-agent-max-tool-turns-test
  (testing "create-agent accepts max-tool-turns"
    (let [ag (agent/create-agent {:max-tool-turns 5})]
      (is (= 5 (:max-tool-turns ag))))))

(deftest two-agents-independent-tools-test
  (testing "two agents have independent tool registries"
    (let [tool-a (make-echo-tool "agent-a")
          tool-b (make-echo-tool "agent-b")
          agent-a (agent/create-agent {:tools [tool-a]})
          agent-b (agent/create-agent {:tools [tool-b]})]
      (is (some? (deftool/get-tool (:tool-registry agent-a) "agent-a-echo")))
      (is (nil? (deftool/get-tool (:tool-registry agent-a) "agent-b-echo")))
      (is (some? (deftool/get-tool (:tool-registry agent-b) "agent-b-echo")))
      (is (nil? (deftool/get-tool (:tool-registry agent-b) "agent-a-echo"))))))

(deftest two-agents-independent-prompts-test
  (testing "two agents have independent system prompts"
    (let [agent-a (agent/create-agent {:system-prompt "I am agent A"})
          agent-b (agent/create-agent {:system-prompt "I am agent B"})]
      (is (= "I am agent A" (:system-prompt agent-a)))
      (is (= "I am agent B" (:system-prompt agent-b))))))

(deftest mutating-one-registry-doesnt-affect-other-test
  (testing "registering a tool in one agent doesn't affect another"
    (let [agent-a (agent/create-agent {:default-tools true})
          agent-b (agent/create-agent {:default-tools true})
          extra-tool (make-echo-tool "extra")]
      (deftool/register-tool! (:tool-registry agent-a) extra-tool)
      (is (some? (deftool/get-tool (:tool-registry agent-a) "extra-echo")))
      (is (nil? (deftool/get-tool (:tool-registry agent-b) "extra-echo"))))))
