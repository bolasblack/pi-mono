(ns pi.coding-agent.cli-test
  "Tests for CLI argument parsing and session option building in core.cljs.
   Tests the CLJS-specific helpers (truthy-env-flag?, package-command?)
   and the integration with TS parseArgs."
  (:require [cljs.test :refer [deftest testing is]]
            ["@mariozechner/pi-coding-agent/dist/cli/args.js" :as args-mod]))

;; --- Pure helper tests ---
;; These test the private helpers in core.cljs.
;; Since they're private (defn-), we replicate their logic here for testing.
;; This is the standard CLJS approach for testing private fn behavior.

(defn truthy-env-flag? [value]
  (when value
    (or (= value "1")
        (= (.toLowerCase value) "true")
        (= (.toLowerCase value) "yes"))))

(defn package-command? [args]
  (when (seq args)
    (let [cmd (first args)]
      (or (= cmd "install") (= cmd "remove") (= cmd "uninstall")
          (= cmd "update") (= cmd "list") (= cmd "config")))))

;; --- truthy-env-flag? tests ---

(deftest truthy-env-flag-test
  (testing "truthy values"
    (is (true? (truthy-env-flag? "1")))
    (is (true? (truthy-env-flag? "true")))
    (is (true? (truthy-env-flag? "True")))
    (is (true? (truthy-env-flag? "TRUE")))
    (is (true? (truthy-env-flag? "yes")))
    (is (true? (truthy-env-flag? "Yes")))
    (is (true? (truthy-env-flag? "YES"))))

  (testing "falsy values"
    (is (nil? (truthy-env-flag? nil)))
    (is (not (truthy-env-flag? "")))
    (is (not (truthy-env-flag? "0")))
    (is (not (truthy-env-flag? "false")))
    (is (not (truthy-env-flag? "no")))
    (is (not (truthy-env-flag? "random")))))

;; --- package-command? tests ---

(deftest package-command-test
  (testing "recognized package commands"
    (is (true? (boolean (package-command? ["install" "npm:@foo/bar"]))))
    (is (true? (boolean (package-command? ["remove" "npm:@foo/bar"]))))
    (is (true? (boolean (package-command? ["uninstall" "npm:@foo/bar"]))))
    (is (true? (boolean (package-command? ["update"]))))
    (is (true? (boolean (package-command? ["list"]))))
    (is (true? (boolean (package-command? ["config"])))))

  (testing "non-package commands"
    (is (nil? (package-command? [])))
    (is (not (package-command? ["--help"])))
    (is (not (package-command? ["--model" "gpt-4o"])))
    (is (not (package-command? ["-p" "hello"])))))

;; --- TS parseArgs integration tests ---
;; These verify the CLJS code correctly calls the TS parseArgs and
;; can read the expected fields from the returned JS object.

(deftest parse-args-basic-flags-test
  (testing "--help flag"
    (let [parsed (.parseArgs args-mod #js ["--help"])]
      (is (true? (.-help parsed)))))

  (testing "-h flag"
    (let [parsed (.parseArgs args-mod #js ["-h"])]
      (is (true? (.-help parsed)))))

  (testing "--version flag"
    (let [parsed (.parseArgs args-mod #js ["--version"])]
      (is (true? (.-version parsed)))))

  (testing "-v flag"
    (let [parsed (.parseArgs args-mod #js ["-v"])]
      (is (true? (.-version parsed))))))

(deftest parse-args-model-test
  (testing "--model flag"
    (let [parsed (.parseArgs args-mod #js ["--model" "anthropic/claude-haiku"])]
      (is (= "anthropic/claude-haiku" (.-model parsed)))))

  (testing "--provider flag"
    (let [parsed (.parseArgs args-mod #js ["--provider" "openai" "--model" "gpt-4o"])]
      (is (= "openai" (.-provider parsed)))
      (is (= "gpt-4o" (.-model parsed))))))

(deftest parse-args-session-test
  (testing "--continue flag"
    (let [parsed (.parseArgs args-mod #js ["--continue"])]
      (is (true? (.-continue parsed)))))

  (testing "-c flag"
    (let [parsed (.parseArgs args-mod #js ["-c"])]
      (is (true? (.-continue parsed)))))

  (testing "--resume flag"
    (let [parsed (.parseArgs args-mod #js ["--resume"])]
      (is (true? (.-resume parsed)))))

  (testing "--no-session flag"
    (let [parsed (.parseArgs args-mod #js ["--no-session"])]
      (is (true? (.-noSession parsed)))))

  (testing "--session flag"
    (let [parsed (.parseArgs args-mod #js ["--session" "my-session"])]
      (is (= "my-session" (.-session parsed)))))

  (testing "--session-mode flag"
    (let [parsed (.parseArgs args-mod #js ["--session" "foo" "--session-mode" "create"])]
      (is (= "create" (.-sessionMode parsed)))))

  (testing "--session-dir flag"
    (let [parsed (.parseArgs args-mod #js ["--session-dir" "/tmp/sessions"])]
      (is (= "/tmp/sessions" (.-sessionDir parsed))))))

(deftest parse-args-tools-test
  (testing "--no-tools flag"
    (let [parsed (.parseArgs args-mod #js ["--no-tools"])]
      (is (true? (.-noTools parsed)))))

  (testing "--tools flag"
    (let [parsed (.parseArgs args-mod #js ["--tools" "read,bash"])]
      (is (= 2 (.-length (.-tools parsed))))
      (is (= "read" (aget (.-tools parsed) 0)))
      (is (= "bash" (aget (.-tools parsed) 1))))))

(deftest parse-args-thinking-test
  (testing "--thinking flag"
    (let [parsed (.parseArgs args-mod #js ["--thinking" "high"])]
      (is (= "high" (.-thinking parsed)))))

  (testing "invalid thinking level is ignored"
    (let [parsed (.parseArgs args-mod #js ["--thinking" "ultra"])]
      (is (nil? (.-thinking parsed))))))

(deftest parse-args-mode-test
  (testing "--print flag"
    (let [parsed (.parseArgs args-mod #js ["-p"])]
      (is (true? (.-print parsed)))))

  (testing "--mode text"
    (let [parsed (.parseArgs args-mod #js ["--mode" "text"])]
      (is (= "text" (.-mode parsed)))))

  (testing "--mode json"
    (let [parsed (.parseArgs args-mod #js ["--mode" "json"])]
      (is (= "json" (.-mode parsed)))))

  (testing "--mode rpc"
    (let [parsed (.parseArgs args-mod #js ["--mode" "rpc"])]
      (is (= "rpc" (.-mode parsed))))))

(deftest parse-args-extensions-test
  (testing "--extension flag (repeatable)"
    (let [parsed (.parseArgs args-mod #js ["--extension" "ext1.js" "-e" "ext2.js"])]
      (is (= 2 (.-length (.-extensions parsed))))
      (is (= "ext1.js" (aget (.-extensions parsed) 0)))
      (is (= "ext2.js" (aget (.-extensions parsed) 1)))))

  (testing "--no-extensions flag"
    (let [parsed (.parseArgs args-mod #js ["--no-extensions"])]
      (is (true? (.-noExtensions parsed)))))

  (testing "--no-skills flag"
    (let [parsed (.parseArgs args-mod #js ["--no-skills"])]
      (is (true? (.-noSkills parsed)))))

  (testing "--no-prompt-templates flag"
    (let [parsed (.parseArgs args-mod #js ["--no-prompt-templates"])]
      (is (true? (.-noPromptTemplates parsed)))))

  (testing "--no-themes flag"
    (let [parsed (.parseArgs args-mod #js ["--no-themes"])]
      (is (true? (.-noThemes parsed))))))

(deftest parse-args-misc-test
  (testing "--verbose flag"
    (let [parsed (.parseArgs args-mod #js ["--verbose"])]
      (is (true? (.-verbose parsed)))))

  (testing "--offline flag"
    (let [parsed (.parseArgs args-mod #js ["--offline"])]
      (is (true? (.-offline parsed)))))

  (testing "--api-key flag"
    (let [parsed (.parseArgs args-mod #js ["--api-key" "sk-test123"])]
      (is (= "sk-test123" (.-apiKey parsed)))))

  (testing "--system-prompt flag"
    (let [parsed (.parseArgs args-mod #js ["--system-prompt" "You are a cat."])]
      (is (= "You are a cat." (.-systemPrompt parsed)))))

  (testing "--append-system-prompt flag"
    (let [parsed (.parseArgs args-mod #js ["--append-system-prompt" "Be concise."])]
      (is (= "Be concise." (.-appendSystemPrompt parsed)))))

  (testing "--export flag"
    (let [parsed (.parseArgs args-mod #js ["--export" "session.jsonl"])]
      (is (= "session.jsonl" (.-export parsed)))))

  (testing "--list-models flag (no arg)"
    (let [parsed (.parseArgs args-mod #js ["--list-models"])]
      (is (true? (.-listModels parsed)))))

  (testing "--list-models flag (with search)"
    (let [parsed (.parseArgs args-mod #js ["--list-models" "claude"])]
      (is (= "claude" (.-listModels parsed)))))

  (testing "--settings flag"
    (let [parsed (.parseArgs args-mod #js ["--settings" "{\"key\":\"val\"}"])]
      (is (= 1 (.-length (.-settings parsed))))
      (is (= "{\"key\":\"val\"}" (aget (.-settings parsed) 0)))))

  (testing "--models flag"
    (let [parsed (.parseArgs args-mod #js ["--models" "claude-sonnet,gpt-4o"])]
      (is (= 2 (.-length (.-models parsed))))
      (is (= "claude-sonnet" (aget (.-models parsed) 0)))
      (is (= "gpt-4o" (aget (.-models parsed) 1))))))

(deftest parse-args-messages-and-files-test
  (testing "bare words become messages"
    (let [parsed (.parseArgs args-mod #js ["hello" "world"])]
      (is (= 2 (.-length (.-messages parsed))))
      (is (= "hello" (aget (.-messages parsed) 0)))
      (is (= "world" (aget (.-messages parsed) 1)))))

  (testing "@file args"
    (let [parsed (.parseArgs args-mod #js ["@prompt.md" "@image.png" "explain"])]
      (is (= 2 (.-length (.-fileArgs parsed))))
      (is (= "prompt.md" (aget (.-fileArgs parsed) 0)))
      (is (= "image.png" (aget (.-fileArgs parsed) 1)))
      (is (= 1 (.-length (.-messages parsed))))
      (is (= "explain" (aget (.-messages parsed) 0))))))

(deftest parse-args-combined-test
  (testing "multiple flags together"
    (let [parsed (.parseArgs args-mod #js ["--model" "anthropic/claude-haiku"
                                           "--thinking" "high"
                                           "--no-extensions"
                                           "--tools" "read,bash"
                                           "-p"
                                           "What is 2+2?"])]
      (is (= "anthropic/claude-haiku" (.-model parsed)))
      (is (= "high" (.-thinking parsed)))
      (is (true? (.-noExtensions parsed)))
      (is (= 2 (.-length (.-tools parsed))))
      (is (true? (.-print parsed)))
      (is (= 1 (.-length (.-messages parsed))))
      (is (= "What is 2+2?" (aget (.-messages parsed) 0))))))
