(ns pi.kernel.tools-test
  (:require [cljs.test :refer [deftest is testing async use-fixtures]]
            [cljs.core.async :refer [<! go]]
            [pi.kernel.deftool :as deftool :include-macros true]
            [pi.kernel.tools.read-file :as read-file]
            [pi.kernel.tools.write-file :as write-file]
            [pi.kernel.tools.edit :as edit-tool]
            [pi.kernel.tools.bash :as bash-tool]))

(def ^:private fs (js/require "fs"))
(def ^:private path-mod (js/require "path"))

(def ^:dynamic *registry* nil)

(use-fixtures :each
  {:before (fn []
             (set! *registry* (deftool/create-tool-registry)))
   :after  (fn []
             (set! *registry* nil))})

;; --- Helpers ---

(defn- tmp-path [& parts]
  (apply str "/tmp/pi-kernel-test-" parts))

(defn- cleanup [path]
  (try
    (.rmSync fs path #js {:recursive true :force true})
    (catch :default _ nil)))

;; --- read_file tests ---

(deftest test-read-file-existing
  (testing "read_file reads an existing file"
    (let [p (tmp-path "read-existing.txt")]
      (.writeFileSync fs p "line1\nline2\nline3" "utf-8")
      (try
        (let [result (read-file/read-file-impl {:path p})]
          (is (some? (:content result)))
          (is (re-find #"line1" (:content result)))
          (is (re-find #"line2" (:content result)))
          (is (re-find #"line3" (:content result))))
        (finally (cleanup p))))))

(deftest test-read-file-with-offset-limit
  (testing "read_file with offset and limit"
    (let [p (tmp-path "read-offset.txt")]
      (.writeFileSync fs p "a\nb\nc\nd\ne" "utf-8")
      (try
        (let [result (read-file/read-file-impl {:path p :offset 2 :limit 2})]
          (is (some? (:content result)))
          ;; Should contain lines 2-3 (b and c)
          (is (re-find #"b" (:content result)))
          (is (re-find #"c" (:content result)))
          ;; Should NOT contain line 1
          (is (not (re-find #"^a$" (:content result)))))
        (finally (cleanup p))))))

(deftest test-read-file-nonexistent
  (testing "read_file throws for nonexistent file"
    (is (thrown? js/Error
          (read-file/read-file-impl {:path "/tmp/pi-kernel-test-nonexistent-xyz.txt"})))))

(deftest test-read-file-offset-beyond-end
  (testing "read_file throws when offset is beyond end of file"
    (let [p (tmp-path "read-beyond.txt")]
      (.writeFileSync fs p "one\ntwo" "utf-8")
      (try
        (is (thrown? js/Error
              (read-file/read-file-impl {:path p :offset 999})))
        (finally (cleanup p))))))

;; --- write_file tests ---

(deftest test-write-file-basic
  (testing "write_file writes content to file"
    (let [p (tmp-path "write-basic.txt")]
      (try
        (let [result (write-file/write-file-impl {:path p :content "hello world"})]
          (is (re-find #"Successfully wrote" (:content result)))
          (is (= "hello world\n" (.toString (.readFileSync fs p) "utf-8"))))
        (finally (cleanup p))))))

(deftest test-write-file-auto-create-dirs
  (testing "write_file creates parent directories automatically"
    (let [dir (tmp-path "write-nested-" (js/Date.now))
          p (str dir "/sub/dir/file.txt")]
      (try
        (let [result (write-file/write-file-impl {:path p :content "nested content"})]
          (is (re-find #"Successfully wrote" (:content result)))
          (is (= "nested content\n" (.toString (.readFileSync fs p) "utf-8"))))
        (finally (cleanup dir))))))

;; --- edit tests ---

(deftest test-edit-replace-text
  (testing "edit replaces exact text in file"
    (let [p (tmp-path "edit-replace.txt")]
      (.writeFileSync fs p "hello world\nfoo bar\nbaz" "utf-8")
      (try
        (let [result (edit-tool/edit-impl {:path p :old-text "foo bar" :new-text "replaced"})]
          (is (re-find #"Successfully replaced" (:content result)))
          (let [content (.toString (.readFileSync fs p) "utf-8")]
            (is (re-find #"replaced" content))
            (is (not (re-find #"foo bar" content)))))
        (finally (cleanup p))))))

(deftest test-edit-old-text-not-found
  (testing "edit throws when old-text not found"
    (let [p (tmp-path "edit-notfound.txt")]
      (.writeFileSync fs p "hello world" "utf-8")
      (try
        (is (thrown? js/Error
              (edit-tool/edit-impl {:path p :old-text "nonexistent text" :new-text "x"})))
        (finally (cleanup p))))))

(deftest test-edit-multiple-occurrences
  (testing "edit throws when old-text matches multiple locations"
    (let [p (tmp-path "edit-multi.txt")]
      (.writeFileSync fs p "abc\nabc\nabc" "utf-8")
      (try
        (is (thrown? js/Error
              (edit-tool/edit-impl {:path p :old-text "abc" :new-text "x"})))
        (finally (cleanup p))))))

(deftest test-edit-file-not-found
  (testing "edit throws for nonexistent file"
    (is (thrown? js/Error
          (edit-tool/edit-impl {:path "/tmp/pi-kernel-test-nofile.txt"
                                :old-text "a" :new-text "b"})))))

;; --- bash tests ---

(deftest test-bash-echo
  (testing "bash executes echo command"
    (async done
      (go
        (let [ch (bash-tool/bash-impl {:command "echo hello"})
              result (<! ch)]
          (is (some? (:content result)))
          (is (re-find #"hello" (:content result)))
          (done))))))

(deftest test-bash-timeout
  (testing "bash handles timeout"
    (async done
      (go
        (let [ch (bash-tool/bash-impl {:command "sleep 10" :timeout 1})
              result (<! ch)]
          (is (some? (:error result)))
          (is (re-find #"timed out" (:error result)))
          (done))))))

(deftest test-bash-nonzero-exit
  (testing "bash reports non-zero exit code"
    (async done
      (go
        (let [ch (bash-tool/bash-impl {:command "exit 42"})
              result (<! ch)]
          (is (some? (:error result)))
          (is (re-find #"exit.*code.*42" (:error result)))
          (done))))))

(deftest test-bash-stderr
  (testing "bash captures stderr"
    (async done
      (go
        (let [ch (bash-tool/bash-impl {:command "echo err-msg >&2"})
              result (<! ch)]
          ;; stderr goes to output, exit code 0
          (is (some? (:content result)))
          (is (re-find #"err-msg" (:content result)))
          (done))))))

;; --- Registration tests ---

(deftest test-register-all-tools
  (testing "All tools can be registered"
    (read-file/register! *registry*)
    (write-file/register! *registry*)
    (edit-tool/register! *registry*)
    (bash-tool/register! *registry*)
    (let [tools (deftool/list-tools *registry*)]
      (is (some #(= "read_file" %) tools))
      (is (some #(= "write_file" %) tools))
      (is (some #(= "edit" %) tools))
      (is (some #(= "bash" %) tools))
      (is (= 4 (count tools))))))
