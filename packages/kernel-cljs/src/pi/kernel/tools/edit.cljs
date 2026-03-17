(ns pi.kernel.tools.edit
  (:require [pi.kernel.deftool :as deftool :include-macros true]
            [pi.kernel.tools.util :as tool-util]
            [clojure.string :as str]
            ["node:fs" :as fs]))

(defn- count-occurrences
  "Count non-overlapping occurrences of needle in haystack."
  [haystack needle]
  (let [needle-len (.-length needle)]
    (loop [idx 0 cnt 0]
      (let [found (str/index-of haystack needle idx)]
        (if (nil? found)
          cnt
          (recur (+ found needle-len) (inc cnt)))))))

(defn edit-impl
  "Edit a file by replacing exact text. Returns {:content string}."
  [{:keys [path old-text new-text]}]
  (let [abs-path (tool-util/resolve-path path)]
    (when-not (.existsSync fs abs-path)
      (throw (js/Error. (str "File not found: " path))))
    (let [content (.toString (.readFileSync fs abs-path) "utf-8")
          occurrences (count-occurrences content old-text)]
      (cond
        (zero? occurrences)
        (throw (js/Error.
                 (str "Could not find the exact text in " path
                      ". The old text must match exactly including all whitespace and newlines.")))

        (> occurrences 1)
        (throw (js/Error.
                 (str "Found " occurrences " occurrences of the text in " path
                      ". The text must be unique. Please provide more context to make it unique.")))

        :else
        (let [new-content (str/replace-first content old-text new-text)]
          (when (= content new-content)
            (throw (js/Error.
                     (str "No changes made to " path
                          ". The replacement produced identical content."))))
          (.writeFileSync fs abs-path new-content "utf-8")
          {:content (str "Successfully replaced text in " path ".")})))))

(defn register!
  "Register the edit tool in the given registry."
  [registry]
  (deftool/deftool registry "edit"
    "Edit a file by replacing exact text. The oldText must match exactly (including whitespace). Use this for precise, surgical edits."
    [:map
     [:path :string]
     [:old-text :string]
     [:new-text :string]]
    {:keys [path old-text new-text]}
    (edit-impl {:path path :old-text old-text :new-text new-text})))
