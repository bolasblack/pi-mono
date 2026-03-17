(ns pi.kernel.bash-format-test
  "Tests for the pure format-bash-output function extracted from bash tool."
  (:require [cljs.test :refer [deftest testing is]]
            [pi.kernel.tools.bash :as bash]))

(deftest format-bash-output-normal-test
  (testing "normal output with exit code 0"
    (let [result (bash/format-bash-output "hello world" 0)]
      (is (= "hello world" result)))))

(deftest format-bash-output-empty-test
  (testing "empty output produces (no output)"
    (let [result (bash/format-bash-output "" 0)]
      (is (= "(no output)" result)))))

(deftest format-bash-output-nonzero-exit-test
  (testing "non-zero exit code appends message"
    (let [result (bash/format-bash-output "some output" 42)]
      (is (re-find #"some output" result))
      (is (re-find #"Command exited with code 42" result)))))

(deftest format-bash-output-nil-exit-code-test
  (testing "nil exit code does not append exit message"
    (let [result (bash/format-bash-output "output" nil)]
      (is (= "output" result)))))

(deftest format-bash-output-truncated-test
  (testing "large output is truncated with line range annotation"
    (let [;; Generate 2500 lines — exceeds default-max-lines (2000)
          big-output (apply str (interpose "\n" (map #(str "line-" %) (range 2500))))
          result (bash/format-bash-output big-output 0)]
      (is (re-find #"\[Showing lines" result)
          "Should include truncation annotation")
      (is (re-find #"of 2500" result)
          "Should mention total line count"))))

(deftest format-bash-output-truncated-with-error-test
  (testing "truncated output with non-zero exit code has both annotations"
    (let [big-output (apply str (interpose "\n" (map #(str "line-" %) (range 2500))))
          result (bash/format-bash-output big-output 1)]
      (is (re-find #"\[Showing lines" result))
      (is (re-find #"Command exited with code 1" result)))))
