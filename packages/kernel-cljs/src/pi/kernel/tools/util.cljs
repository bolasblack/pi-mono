(ns pi.kernel.tools.util
  "Shared utilities for tool implementations:
   path resolution and output truncation."
  (:require [clojure.string :as str]
            ["node:path" :as path]))

(def default-max-lines
  "Maximum number of lines before truncation."
  2000)

(def default-max-bytes
  "Maximum byte size before truncation."
  (* 50 1024))

(defn resolve-path
  "Resolve a file path to an absolute path.
   If already absolute, returns as-is; otherwise resolves relative to cwd."
  [filepath]
  (if (.isAbsolute path filepath)
    filepath
    (.resolve path (.cwd js/process) filepath)))

(defn- byte-length
  "UTF-8 byte length of a string."
  [s]
  (.-byteLength (js/Buffer.from s "utf-8")))

(defn- truncate-by-bytes
  "Given a vector of lines, find the largest prefix (or suffix when tail?)
   that fits within max-bytes when joined. Returns the subset vector."
  [lines max-bytes tail?]
  (loop [n (count lines)]
    (if (<= n 0)
      []
      (let [subset (if tail? (vec (take-last n lines)) (vec (take n lines)))
            text (str/join "\n" subset)]
        (if (<= (byte-length text) max-bytes)
          subset
          (recur (dec n)))))))

(defn- truncate*
  "Shared truncation logic. direction is :head or :tail."
  [text max-lines max-bytes direction]
  (let [tail?       (= direction :tail)
        select-fn   (if tail? take-last take)
        lines       (str/split text #"\n" -1)
        total-lines (count lines)]
    (if (and (<= total-lines max-lines)
             (<= (byte-length text) max-bytes))
      {:content text :truncated false
       :output-lines total-lines :total-lines total-lines}
      (let [selected (vec (select-fn max-lines lines))
            joined   (str/join "\n" selected)]
        (if (<= (byte-length joined) max-bytes)
          {:content      joined
           :truncated    (> total-lines max-lines)
           :output-lines (count selected)
           :total-lines  total-lines
           :truncated-by "lines"}
          (let [subset (truncate-by-bytes selected max-bytes tail?)]
            {:content      (str/join "\n" subset)
             :truncated    true
             :output-lines (count subset)
             :total-lines  total-lines
             :truncated-by "bytes"}))))))

(defn truncate-head
  "Keep the first max-lines or max-bytes of output (head truncation).
   Returns {:content string :truncated boolean :output-lines number
            :total-lines number :truncated-by string|nil}."
  ([text] (truncate-head text default-max-lines default-max-bytes))
  ([text max-lines max-bytes]
   (truncate* text max-lines max-bytes :head)))

(defn truncate-tail
  "Keep the last max-lines or max-bytes of output (tail truncation).
   Returns {:content string :truncated boolean :output-lines number
            :total-lines number :truncated-by string|nil}."
  ([text] (truncate-tail text default-max-lines default-max-bytes))
  ([text max-lines max-bytes]
   (truncate* text max-lines max-bytes :tail)))
