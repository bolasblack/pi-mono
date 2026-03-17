(ns pi.kernel.tools.write-file
  (:require [pi.kernel.deftool :as deftool :include-macros true]
            [pi.kernel.tools.util :as tool-util]
            [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(defn write-file-impl
  "Write content to a file. Creates parent directories automatically.
   Returns {:content string}."
  [{:keys [path content]}]
  (let [abs-path (tool-util/resolve-path path)
        dir (.dirname path abs-path)
        content (if (or (empty? content) (str/ends-with? content "\n"))
                  content
                  (str content "\n"))]
    (.mkdirSync fs dir #js {:recursive true})
    (.writeFileSync fs abs-path content "utf-8")
    {:content (str "Successfully wrote " (.-byteLength (js/Buffer.from content "utf-8")) " bytes to " path)}))

(defn register!
  "Register the write_file tool in the given registry."
  [registry]
  (deftool/deftool registry "write_file"
    "Write content to a file. Creates the file if it doesn't exist, overwrites if it does. Automatically creates parent directories."
    [:map
     [:path :string]
     [:content :string]]
    {:keys [path content]}
    (write-file-impl {:path path :content content})))
