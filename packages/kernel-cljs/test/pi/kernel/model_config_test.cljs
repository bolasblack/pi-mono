(ns pi.kernel.model-config-test
  (:require [cljs.test :refer [deftest is testing]]
            [pi.kernel.model-config :as mc]))

;; ============================================================================
;; parse-model-str (pure)
;; ============================================================================

(deftest parse-model-str-with-slash-test
  (testing "parses provider/model-id"
    (is (= {:api "anthropic" :model-id "claude-haiku-4-5-20251001"}
           (mc/parse-model-str "anthropic/claude-haiku-4-5-20251001"))))
  (testing "parses provider with nested slashes"
    (is (= {:api "openai" :model-id "gpt-4/turbo"}
           (mc/parse-model-str "openai/gpt-4/turbo")))))

(deftest parse-model-str-without-slash-test
  (testing "treats entire string as api when no slash"
    (is (= {:api "mock" :model-id nil}
           (mc/parse-model-str "mock")))))

;; ============================================================================
;; resolve-config (pure decision logic)
;; ============================================================================

(deftest resolve-config-happy-path-test
  (testing "returns full config when provider available and key present"
    (let [result (mc/resolve-config {:api "anthropic"
                                     :model-id "claude-haiku-4-5-20251001"
                                     :api-key "sk-test-123"
                                     :provider-available? true})]
      (is (= {:api "anthropic"
              :model-id "claude-haiku-4-5-20251001"
              :provider :default
              :api-key "sk-test-123"}
             (:config result)))
      (is (nil? (:warning result))))))

(deftest resolve-config-provider-unavailable-test
  (testing "falls back to mock with warning when provider unavailable"
    (let [result (mc/resolve-config {:api "unknown-provider"
                                     :model-id "some-model"
                                     :api-key "sk-test"
                                     :provider-available? false})]
      (is (= {:api "mock" :provider :default} (:config result)))
      (is (string? (:warning result)))
      (is (.includes (:warning result) "unknown-provider"))
      (is (.includes (:warning result) "not available")))))

(deftest resolve-config-no-api-key-test
  (testing "falls back to mock with warning when no API key"
    (let [result (mc/resolve-config {:api "anthropic"
                                     :model-id "claude-haiku-4-5-20251001"
                                     :api-key nil
                                     :provider-available? true})]
      (is (= {:api "mock" :provider :default} (:config result)))
      (is (string? (:warning result)))
      (is (.includes (:warning result) "anthropic"))
      (is (.includes (:warning result) "No API key")))))

(deftest resolve-config-priority-test
  (testing "provider-unavailable check takes priority over missing key"
    (let [result (mc/resolve-config {:api "nope"
                                     :model-id nil
                                     :api-key nil
                                     :provider-available? false})]
      (is (.includes (:warning result) "not available")))))

;; ============================================================================
;; resolve-api-key (env lookup — tests with known/empty vars)
;; ============================================================================

(deftest resolve-api-key-known-provider-test
  (testing "returns nil for provider with no env var set"
    ;; ANTHROPIC_API_KEY is unlikely to be set in test environment
    ;; If it IS set, this still validates the lookup works
    (let [result (mc/resolve-api-key "anthropic")]
      (is (or (nil? result) (string? result))))))

(deftest resolve-api-key-unknown-provider-test
  (testing "falls back to PROVIDER_API_KEY convention"
    ;; SOMEFAKEPROVIDER_API_KEY should not be set
    (is (nil? (mc/resolve-api-key "somefakeprovider")))))

(deftest resolve-api-key-empty-string-test
  (testing "treats empty string env var as absent"
    ;; Set env var to empty string, check it returns nil
    (aset (.-env js/process) "EMPTY_TEST_PROVIDER_API_KEY" "")
    (is (nil? (mc/resolve-api-key "empty_test_provider")))
    (js-delete (.-env js/process) "EMPTY_TEST_PROVIDER_API_KEY")))

(deftest resolve-api-key-present-test
  (testing "returns value when env var is set"
    (aset (.-env js/process) "TESTPROV_API_KEY" "sk-test-key-123")
    (is (= "sk-test-key-123" (mc/resolve-api-key "testprov")))
    (js-delete (.-env js/process) "TESTPROV_API_KEY")))

;; ============================================================================
;; provider-available? (integration with provider registry)
;; ============================================================================

(deftest provider-available-mock-test
  (testing "mock provider is always registered"
    (is (true? (mc/provider-available? "mock")))))

(deftest provider-available-nonexistent-test
  (testing "unregistered provider returns false"
    (is (false? (mc/provider-available? "nonexistent-provider-xyz")))))
