(ns pi.kernel.commands-test
  (:require [cljs.test :refer [deftest is testing]]
            [pi.kernel.commands :as cmd]))

;; ============================================================================
;; parse-command (pure)
;; ============================================================================

(deftest parse-command-basic-test
  (testing "parses slash command without args"
    (is (= ["help" ""] (cmd/parse-command "/help"))))
  (testing "parses slash command with args"
    (is (= ["model" "gpt-4"] (cmd/parse-command "/model gpt-4"))))
  (testing "trims args"
    (is (= ["model" "gpt-4"] (cmd/parse-command "/model  gpt-4 "))))
  (testing "returns nil for non-command"
    (is (nil? (cmd/parse-command "hello world"))))
  (testing "returns nil for empty string"
    (is (nil? (cmd/parse-command "")))))

;; ============================================================================
;; format-help-text (pure)
;; ============================================================================

(deftest format-help-text-test
  (testing "formats sorted commands as markdown list"
    (let [commands [["clear" {:doc "Clear history"}]
                    ["help"  {:doc "List commands"}]]]
      (is (= "\n**Commands:**\n  `/clear` — Clear history\n  `/help` — List commands\n\n"
             (cmd/format-help-text commands)))))
  (testing "handles empty commands"
    (is (= "\n**Commands:**\n\n"
           (cmd/format-help-text [])))))

;; ============================================================================
;; format-model-display (pure)
;; ============================================================================

(deftest format-model-display-test
  (testing "shows model-id when present"
    (is (= "\nCurrent model: **gpt-4**\n\n"
           (cmd/format-model-display {:model-id "gpt-4" :api "openai"}))))
  (testing "falls back to api when no model-id"
    (is (= "\nCurrent model: **openai**\n\n"
           (cmd/format-model-display {:api "openai"})))))

;; ============================================================================
;; format-compact-info (pure)
;; ============================================================================

(deftest format-compact-info-test
  (testing "formats entry count"
    (is (= "\nContext: **5** entries in session.\n\n"
           (cmd/format-compact-info 5))))
  (testing "handles zero entries"
    (is (= "\nContext: **0** entries in session.\n\n"
           (cmd/format-compact-info 0)))))

;; ============================================================================
;; generate-export-filename (pure)
;; ============================================================================

(deftest generate-export-filename-test
  (testing "generates filename from timestamp"
    (let [result (cmd/generate-export-filename "2025-03-16T12:34:56.789Z")]
      (is (.startsWith result "/tmp/pi-kernel-export-"))
      (is (.endsWith result ".md"))
      (is (not (.includes result ":"))))))

;; ============================================================================
;; format-export-content (pure)
;; ============================================================================

(deftest format-export-content-test
  (testing "formats messages as markdown"
    (let [messages [{:role "user" :content "Hello"}
                    {:role "assistant" :content "Hi there"}]
          result (cmd/format-export-content messages "2025-03-16T12:34:56.789Z")]
      (is (.startsWith result "# pi-kernel conversation export\n"))
      (is (.includes result "## User\nHello"))
      (is (.includes result "## Assistant\nHi there"))))
  (testing "handles tool-result role"
    (let [messages [{:role "tool-result" :tool-name "bash" :content "output"}]
          result (cmd/format-export-content messages "2025-01-01T00:00:00Z")]
      (is (.includes result "## Tool: bash\noutput"))))
  (testing "handles unknown role"
    (let [messages [{:role "system" :content "sys"}]
          result (cmd/format-export-content messages "2025-01-01T00:00:00Z")]
      (is (.includes result "## system\n")))))
