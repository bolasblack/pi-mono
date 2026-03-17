(ns pi.kernel.deftool-test
  (:require [cljs.test :refer [deftest is testing use-fixtures]]
            [pi.kernel.deftool :as dt :include-macros true]))

(def ^:dynamic *registry* nil)

(use-fixtures :each
  {:before (fn [] (set! *registry* (dt/create-tool-registry)))
   :after  (fn [] (set! *registry* nil))})

(deftest test-source-form-metadata
  (testing "deftool stores source-form as data"
    (dt/deftool *registry* "source-test"
      "A tool for testing source form"
      [:map [:x :int]]
      {:keys [x]}
      (inc x))
    (let [tool (dt/get-tool *registry* "source-test")]
      (is (some? tool) "Tool should be registered")
      (is (some? (:source-form tool)) "Source form should be present")
      (is (list? (:source-form tool)) "Source form should be a list (quoted form)"))))

(deftest test-json-schema-generation
  (testing "deftool generates JSON Schema from malli spec"
    (dt/deftool *registry* "schema-test"
      "A tool for testing schema generation"
      [:map [:path :string] [:content :string]]
      {:keys [path content]}
      {:path path :content content})
    (let [tool (dt/get-tool *registry* "schema-test")
          schema (:json-schema tool)]
      (is (some? schema) "JSON Schema should be generated")
      (is (= "object" (get schema :type)) "Schema type should be object")
      (let [props (get schema :properties)]
        (is (some? (get props :path)) "Should have :path property")
        (is (some? (get props :content)) "Should have :content property")))))

(deftest test-validation-error-on-invalid-params
  (testing "Tool returns validation error for invalid params"
    (dt/deftool *registry* "validate-test"
      "A tool for testing validation"
      [:map [:path :string] [:content :string]]
      {:keys [path content]}
      {:path path :content content})
    (let [tool (dt/get-tool *registry* "validate-test")
          result ((:execute tool) {:path 123 :content 456})]
      (is (string? (:error result)) "Should return validation error string")
      (is (re-find #"Validation failed" (:error result)) "Should describe the validation failure"))))

(deftest test-valid-execution
  (testing "Tool executes correctly with valid params"
    (dt/deftool *registry* "exec-test"
      "A tool for testing execution"
      [:map [:a :int] [:b :int]]
      {:keys [a b]}
      (+ a b))
    (let [tool (dt/get-tool *registry* "exec-test")
          result ((:execute tool) {:a 3 :b 4})]
      (is (= 7 result) "Should return result of execution directly"))))

(deftest test-list-tools
  (testing "list-tools returns all registered tool names"
    (dt/deftool *registry* "tool-a" "A" [:map [:x :int]] {:keys [x]} x)
    (dt/deftool *registry* "tool-b" "B" [:map [:x :int]] {:keys [x]} x)
    (let [tools (dt/list-tools *registry*)]
      (is (vector? tools) "Should return a vector")
      (is (= 2 (count tools)) "Should have 2 tools"))))

(deftest test-get-tool-returns-nil-for-unknown
  (testing "get-tool returns nil for unregistered tool"
    (is (nil? (dt/get-tool *registry* "nonexistent-tool-xyz")))))
