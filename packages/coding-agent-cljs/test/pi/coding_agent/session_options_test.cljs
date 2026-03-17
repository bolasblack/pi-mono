(ns pi.coding-agent.session-options-test
  "Tests for pure option-building functions extracted from core."
  (:require [cljs.test :refer [deftest testing is]]
            [pi.coding-agent.session-options :as opts]))

;; ---------------------------------------------------------------------------
;; select-scoped-model
;; ---------------------------------------------------------------------------

(deftest select-scoped-model-returns-nil-when-model-already-set
  (testing "returns nil when model is already set"
    (is (nil? (opts/select-scoped-model
                {:model-already-set? true
                 :scoped-models [{:model :m1 :thinking-level nil}]
                 :is-continue? false
                 :is-resume? false
                 :saved-model-idx nil
                 :cli-thinking? false})))))

(deftest select-scoped-model-returns-nil-when-no-scoped-models
  (testing "returns nil when scoped-models is empty"
    (is (nil? (opts/select-scoped-model
                {:model-already-set? false
                 :scoped-models []
                 :is-continue? false
                 :is-resume? false
                 :saved-model-idx nil
                 :cli-thinking? false})))))

(deftest select-scoped-model-returns-nil-when-continue
  (testing "returns nil when continuing a session"
    (is (nil? (opts/select-scoped-model
                {:model-already-set? false
                 :scoped-models [{:model :m1 :thinking-level nil}]
                 :is-continue? true
                 :is-resume? false
                 :saved-model-idx nil
                 :cli-thinking? false})))))

(deftest select-scoped-model-returns-nil-when-resume
  (testing "returns nil when resuming a session"
    (is (nil? (opts/select-scoped-model
                {:model-already-set? false
                 :scoped-models [{:model :m1 :thinking-level nil}]
                 :is-continue? false
                 :is-resume? true
                 :saved-model-idx nil
                 :cli-thinking? false})))))

(deftest select-scoped-model-uses-first-when-no-saved
  (testing "selects first scoped model when no saved model found"
    (let [result (opts/select-scoped-model
                   {:model-already-set? false
                    :scoped-models [{:model :m1 :thinking-level "high"}
                                    {:model :m2 :thinking-level nil}]
                    :is-continue? false
                    :is-resume? false
                    :saved-model-idx nil
                    :cli-thinking? false})]
      (is (= :m1 (:model result)))
      (is (= "high" (:thinking-level result))))))

(deftest select-scoped-model-uses-saved-when-in-scope
  (testing "selects saved model when found in scoped models"
    (let [result (opts/select-scoped-model
                   {:model-already-set? false
                    :scoped-models [{:model :m1 :thinking-level nil}
                                    {:model :m2 :thinking-level "low"}]
                    :is-continue? false
                    :is-resume? false
                    :saved-model-idx 1
                    :cli-thinking? false})]
      (is (= :m2 (:model result)))
      (is (= "low" (:thinking-level result))))))

(deftest select-scoped-model-omits-thinking-when-cli-set
  (testing "omits thinking-level when CLI thinking flag is set"
    (let [result (opts/select-scoped-model
                   {:model-already-set? false
                    :scoped-models [{:model :m1 :thinking-level "high"}]
                    :is-continue? false
                    :is-resume? false
                    :saved-model-idx nil
                    :cli-thinking? true})]
      (is (= :m1 (:model result)))
      (is (nil? (:thinking-level result))))))

(deftest select-scoped-model-nil-thinking-level
  (testing "returns nil thinking-level when scoped model has none"
    (let [result (opts/select-scoped-model
                   {:model-already-set? false
                    :scoped-models [{:model :m1 :thinking-level nil}]
                    :is-continue? false
                    :is-resume? false
                    :saved-model-idx nil
                    :cli-thinking? false})]
      (is (= :m1 (:model result)))
      (is (nil? (:thinking-level result))))))

;; ---------------------------------------------------------------------------
;; resolve-tools-option
;; ---------------------------------------------------------------------------

(deftest resolve-tools-no-flags-returns-all
  (testing "no flags returns :all (use default tool set)"
    (is (= :all (opts/resolve-tools-option
                  {:no-tools? false :tool-names nil})))))

(deftest resolve-tools-no-tools-without-names-returns-none
  (testing "--noTools without --tools returns :none"
    (is (= :none (opts/resolve-tools-option
                   {:no-tools? true :tool-names nil})))))

(deftest resolve-tools-no-tools-empty-names-returns-none
  (testing "--noTools with empty --tools returns :none"
    (is (= :none (opts/resolve-tools-option
                   {:no-tools? true :tool-names []})))))

(deftest resolve-tools-no-tools-with-names-returns-specific
  (testing "--noTools with --tools returns only specified tools"
    (is (= {:specific ["bash" "read_file"]}
           (opts/resolve-tools-option
             {:no-tools? true :tool-names ["bash" "read_file"]})))))

(deftest resolve-tools-names-without-no-tools-returns-specific
  (testing "--tools without --noTools returns specified tools"
    (is (= {:specific ["bash"]}
           (opts/resolve-tools-option
             {:no-tools? false :tool-names ["bash"]})))))

;; ---------------------------------------------------------------------------
;; resolve-model-and-thinking
;; ---------------------------------------------------------------------------

(deftest resolve-model-cli-takes-precedence
  (testing "CLI model takes precedence over scoped selection"
    (let [result (opts/resolve-model-and-thinking
                   {:cli-model :cli-m
                    :cli-thinking-level "high"
                    :scoped-selection {:model :scoped-m :thinking-level "low"}
                    :explicit-cli-thinking nil})]
      (is (= :cli-m (:model result)))
      (is (= "high" (:thinking-level result))))))

(deftest resolve-model-falls-back-to-scoped
  (testing "falls back to scoped selection when no CLI model"
    (let [result (opts/resolve-model-and-thinking
                   {:cli-model nil
                    :cli-thinking-level nil
                    :scoped-selection {:model :scoped-m :thinking-level "low"}
                    :explicit-cli-thinking nil})]
      (is (= :scoped-m (:model result)))
      (is (= "low" (:thinking-level result))))))

(deftest resolve-model-explicit-thinking-overrides-all
  (testing "explicit CLI thinking overrides all other thinking levels"
    (let [result (opts/resolve-model-and-thinking
                   {:cli-model :cli-m
                    :cli-thinking-level "high"
                    :scoped-selection nil
                    :explicit-cli-thinking "medium"})]
      (is (= :cli-m (:model result)))
      (is (= "medium" (:thinking-level result))))))

(deftest resolve-model-nil-when-no-sources
  (testing "returns nil model and thinking when no sources"
    (let [result (opts/resolve-model-and-thinking
                   {:cli-model nil
                    :cli-thinking-level nil
                    :scoped-selection nil
                    :explicit-cli-thinking nil})]
      (is (nil? (:model result)))
      (is (nil? (:thinking-level result))))))

(deftest resolve-model-explicit-thinking-with-scoped
  (testing "explicit CLI thinking works with scoped model"
    (let [result (opts/resolve-model-and-thinking
                   {:cli-model nil
                    :cli-thinking-level nil
                    :scoped-selection {:model :scoped-m :thinking-level "low"}
                    :explicit-cli-thinking "high"})]
      (is (= :scoped-m (:model result)))
      (is (= "high" (:thinking-level result))))))

(deftest resolve-model-nil-scoped-selection
  (testing "nil scoped-selection with CLI model works"
    (let [result (opts/resolve-model-and-thinking
                   {:cli-model :cli-m
                    :cli-thinking-level nil
                    :scoped-selection nil
                    :explicit-cli-thinking nil})]
      (is (= :cli-m (:model result)))
      (is (nil? (:thinking-level result))))))

;; ---------------------------------------------------------------------------
;; build-options-map
;; ---------------------------------------------------------------------------

(deftest build-options-map-includes-all-fields
  (testing "includes all fields when all are provided"
    (let [result (opts/build-options-map
                   {:session-manager :sm
                    :model :m
                    :thinking-level "high"
                    :scoped-models [:s1 :s2]
                    :tools [:t1]
                    :auth-storage :as
                    :model-registry :mr
                    :resource-loader :rl})]
      (is (= :sm (:sessionManager result)))
      (is (= :m (:model result)))
      (is (= "high" (:thinkingLevel result)))
      (is (= [:s1 :s2] (:scopedModels result)))
      (is (= [:t1] (:tools result)))
      (is (= :as (:authStorage result)))
      (is (= :mr (:modelRegistry result)))
      (is (= :rl (:resourceLoader result))))))

(deftest build-options-map-omits-nil-fields
  (testing "omits fields that are nil"
    (let [result (opts/build-options-map
                   {:session-manager nil
                    :model :m
                    :thinking-level nil
                    :scoped-models nil
                    :tools nil
                    :auth-storage nil
                    :model-registry nil
                    :resource-loader nil})]
      (is (= {:model :m} result)))))

(deftest build-options-map-empty-when-all-nil
  (testing "returns empty map when all inputs are nil"
    (is (= {} (opts/build-options-map
                {:session-manager nil :model nil :thinking-level nil
                 :scoped-models nil :tools nil :auth-storage nil
                 :model-registry nil :resource-loader nil})))))

;; ---------------------------------------------------------------------------
;; options-map->js
;; ---------------------------------------------------------------------------

(deftest options-map->js-creates-js-object
  (testing "creates a JS object with correct properties"
    (let [result (opts/options-map->js {:model "m1" :thinkingLevel "high"})]
      (is (= "m1" (.-model result)))
      (is (= "high" (.-thinkingLevel result))))))

(deftest options-map->js-preserves-js-values
  (testing "does not recursively convert JS object values"
    (let [js-model #js {:id "test" :provider "openai"}
          result (opts/options-map->js {:model js-model})]
      (is (identical? js-model (.-model result))))))

(deftest options-map->js-empty-map
  (testing "creates empty JS object for empty map"
    (let [result (opts/options-map->js {})]
      (is (= 0 (.-length (.keys js/Object result)))))))

;; ---------------------------------------------------------------------------
;; classify-early-exit
;; ---------------------------------------------------------------------------

(deftest classify-early-exit-version
  (testing "--version returns :version action"
    (is (= {:action :version}
           (opts/classify-early-exit {:version? true})))))

(deftest classify-early-exit-help
  (testing "--help returns :help action"
    (is (= {:action :help}
           (opts/classify-early-exit {:help? true})))))

(deftest classify-early-exit-list-models-no-search
  (testing "--list-models without search returns :list-models with nil search"
    (is (= {:action :list-models :search nil}
           (opts/classify-early-exit {:list-models true})))))

(deftest classify-early-exit-list-models-with-search
  (testing "--list-models with search string returns :list-models with search"
    (is (= {:action :list-models :search "claude"}
           (opts/classify-early-exit {:list-models "claude"})))))

(deftest classify-early-exit-export-no-output
  (testing "--export returns :export with session path"
    (is (= {:action :export :session-path "session.jsonl" :output-path nil}
           (opts/classify-early-exit {:export "session.jsonl"})))))

(deftest classify-early-exit-export-with-output
  (testing "--export with message as output path"
    (is (= {:action :export :session-path "session.jsonl" :output-path "out.html"}
           (opts/classify-early-exit {:export "session.jsonl"
                                      :messages ["out.html"]})))))

(deftest classify-early-exit-session-mode-without-session
  (testing "--session-mode without --session returns error"
    (is (= {:action :error
            :message "--session-mode requires --session <path|id>"}
           (opts/classify-early-exit {:session-mode? true :session? false})))))

(deftest classify-early-exit-session-mode-with-session
  (testing "--session-mode with --session is not an early exit"
    (is (nil? (opts/classify-early-exit {:session-mode? true :session? true})))))

(deftest classify-early-exit-normal-operation
  (testing "no early-exit flags returns nil"
    (is (nil? (opts/classify-early-exit {})))))

(deftest classify-early-exit-precedence
  (testing "--version takes precedence over --help"
    (is (= {:action :version}
           (opts/classify-early-exit {:version? true :help? true}))))
  (testing "--help takes precedence over --list-models"
    (is (= {:action :help}
           (opts/classify-early-exit {:help? true :list-models true})))))
