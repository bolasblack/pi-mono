(ns pi.kernel.tools.read-file
  (:require [pi.kernel.deftool :as deftool :include-macros true]
            [pi.kernel.tools.util :as tool-util]
            [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(def ^:private image-extensions
  #{"jpg" "jpeg" "png" "gif" "webp"})

(def ^:private mime-types
  {"jpg"  "image/jpeg"
   "jpeg" "image/jpeg"
   "png"  "image/png"
   "gif"  "image/gif"
   "webp" "image/webp"})

(defn- get-extension [filepath]
  (let [ext (.extname path filepath)]
    (when (and ext (pos? (count ext)))
      (str/lower-case (subs ext 1)))))

(defn- image-file? [filepath]
  (contains? image-extensions (get-extension filepath)))

(defn read-file-impl
  "Read a file. Returns {:content string} or throws."
  [{:keys [path offset limit]}]
  (let [abs-path (tool-util/resolve-path path)]
    (when-not (.existsSync fs abs-path)
      (throw (js/Error. (str "File not found: " path))))
    (if (image-file? abs-path)
      ;; Image file
      (let [buffer (.readFileSync fs abs-path)
            base64 (.toString buffer "base64")
            ext (get-extension abs-path)
            mime (get mime-types ext "application/octet-stream")]
        {:content (str "Read image file [" mime "]\n[base64 data: " (count base64) " chars]")
         :image {:data base64 :mime-type mime}})
      ;; Text file
      (let [text (.toString (.readFileSync fs abs-path) "utf-8")
            all-lines (str/split text #"\n" -1)
            total-lines (count all-lines)
            start-line (if offset (max 0 (dec offset)) 0)]
        (when (and offset (>= start-line total-lines))
          (throw (js/Error. (str "Offset " offset " is beyond end of file (" total-lines " lines total)"))))
        (let [selected (if limit
                         (subvec (vec all-lines) start-line (min (+ start-line limit) total-lines))
                         (subvec (vec all-lines) start-line))
              selected-text (str/join "\n" selected)
              trunc (tool-util/truncate-head selected-text)
              start-display (inc start-line)]
          (if (:truncated trunc)
            (let [end-display (+ start-display (:output-lines trunc) -1)
                  next-offset (inc end-display)]
              {:content (str (:content trunc)
                             "\n\n[Showing lines " start-display "-" end-display
                             " of " total-lines ". Use offset=" next-offset " to continue.]")})
            (if (and limit (< (+ start-line (count selected)) total-lines))
              (let [remaining (- total-lines (+ start-line (count selected)))
                    next-offset (+ start-line (count selected) 1)]
                {:content (str (:content trunc)
                               "\n\n[" remaining " more lines in file. Use offset=" next-offset " to continue.]")})
              {:content (:content trunc)})))))))

(defn register!
  "Register the read_file tool in the given registry."
  [registry]
  (deftool/deftool registry "read_file"
    "Read the contents of a file. Supports text files and images (jpg, png, gif, webp). For text files, output is truncated to 2000 lines or 50KB."
    [:map
     [:path :string]
     [:offset {:optional true} :int]
     [:limit {:optional true} :int]]
    {:keys [path offset limit]}
    (read-file-impl {:path path :offset offset :limit limit})))
