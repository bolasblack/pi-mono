(ns pi.kernel.tools.util-test
  (:require [cljs.test :refer [deftest is testing]]
            [pi.kernel.tools.util :as util]))

;; --- resolve-path ---

(deftest resolve-path-absolute
  (testing "absolute path returned as-is"
    (is (= "/foo/bar.txt" (util/resolve-path "/foo/bar.txt")))))

(deftest resolve-path-relative
  (testing "relative path resolved against cwd"
    (let [result (util/resolve-path "foo.txt")]
      (is (.startsWith result "/"))
      (is (.endsWith result "foo.txt")))))

;; --- truncate-head ---

(deftest truncate-head-no-truncation
  (testing "short text returns unchanged"
    (let [result (util/truncate-head "a\nb\nc")]
      (is (= "a\nb\nc" (:content result)))
      (is (false? (:truncated result)))
      (is (= 3 (:output-lines result)))
      (is (= 3 (:total-lines result)))
      (is (nil? (:truncated-by result))))))

(deftest truncate-head-by-lines
  (testing "text exceeding max-lines truncated from head"
    (let [text (apply str (interpose "\n" (range 10)))
          result (util/truncate-head text 5 100000)]
      (is (:truncated result))
      (is (= 5 (:output-lines result)))
      (is (= 10 (:total-lines result)))
      (is (= "lines" (:truncated-by result)))
      ;; Should contain 0..4
      (is (.startsWith (:content result) "0")))))

(deftest truncate-head-by-bytes
  (testing "text exceeding max-bytes truncated by bytes"
    (let [text "aaa\nbbb\nccc\nddd"
          result (util/truncate-head text 100 8)]
      (is (:truncated result))
      (is (= "bytes" (:truncated-by result)))
      (is (<= (.-byteLength (js/Buffer.from (:content result) "utf-8")) 8)))))

;; --- truncate-tail ---

(deftest truncate-tail-no-truncation
  (testing "short text returns unchanged"
    (let [result (util/truncate-tail "a\nb\nc")]
      (is (= "a\nb\nc" (:content result)))
      (is (false? (:truncated result)))
      (is (= 3 (:output-lines result)))
      (is (= 3 (:total-lines result)))
      (is (nil? (:truncated-by result))))))

(deftest truncate-tail-by-lines
  (testing "text exceeding max-lines truncated from tail"
    (let [text (apply str (interpose "\n" (range 10)))
          result (util/truncate-tail text 5 100000)]
      (is (:truncated result))
      (is (= 5 (:output-lines result)))
      (is (= 10 (:total-lines result)))
      (is (= "lines" (:truncated-by result)))
      ;; Should contain 5..9 (tail)
      (is (.endsWith (:content result) "9")))))

(deftest truncate-tail-by-bytes
  (testing "text exceeding max-bytes truncated by bytes"
    (let [text "aaa\nbbb\nccc\nddd"
          result (util/truncate-tail text 100 8)]
      (is (:truncated result))
      (is (= "bytes" (:truncated-by result)))
      (is (<= (.-byteLength (js/Buffer.from (:content result) "utf-8")) 8)))))

;; --- Edge cases ---

(deftest truncate-empty-string
  (testing "empty string not truncated"
    (let [head (util/truncate-head "")
          tail (util/truncate-tail "")]
      (is (= "" (:content head)))
      (is (false? (:truncated head)))
      (is (= "" (:content tail)))
      (is (false? (:truncated tail))))))

(deftest truncate-single-line
  (testing "single line within limits not truncated"
    (let [result (util/truncate-head "hello")]
      (is (= "hello" (:content result)))
      (is (false? (:truncated result)))
      (is (= 1 (:output-lines result))))))
