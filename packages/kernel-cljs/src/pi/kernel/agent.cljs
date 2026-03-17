(ns pi.kernel.agent
  "Agent instance — holds per-agent configuration.
   Each agent has its own tool-registry, system-prompt, interceptors, and model-config.
   Multiple agents with different configurations can coexist."
  (:require [pi.kernel.deftool :as deftool]
            [pi.kernel.tools.read-file :as read-file-tool]
            [pi.kernel.tools.write-file :as write-file-tool]
            [pi.kernel.tools.edit :as edit-tool]
            [pi.kernel.tools.bash :as bash-tool]))

(def default-system-prompt
  "You are pi, a coding assistant. You help users with programming tasks.")

(def default-model-config
  {:api "mock" :provider :default})

(defn register-default-tools!
  "Register the standard coding tools into a tool registry."
  [registry]
  (read-file-tool/register! registry)
  (write-file-tool/register! registry)
  (edit-tool/register! registry)
  (bash-tool/register! registry)
  registry)

(defn create-agent
  "Create an agent instance with its own configuration.

   Options:
     :system-prompt  - string (default: coding assistant prompt)
     :tools          - vector of tool definition maps to register
     :default-tools  - if true, register read_file/write_file/edit/bash (default: false)
     :model-config   - provider config map {:api :model-id :provider :api-key}
     :interceptors   - custom interceptor chain (default: nil, uses default-interceptors)
     :max-tool-turns - max consecutive tool turns (default: 20)

   Returns a map:
     {:tool-registry   - atom of tools
      :system-prompt   - string
      :model-config    - map
      :interceptors    - vector or nil (nil means use default)
      :max-tool-turns  - number}"
  ([] (create-agent {}))
  ([opts]
   (let [registry (deftool/create-tool-registry)]
     ;; Register default tools if requested
     (when (:default-tools opts)
       (register-default-tools! registry))
     ;; Register custom tools
     (doseq [tool (:tools opts)]
       (deftool/register-tool! registry tool))
     {:tool-registry  registry
      :system-prompt  (or (:system-prompt opts) default-system-prompt)
      :model-config   (or (:model-config opts) default-model-config)
      :interceptors   (:interceptors opts)
      :max-tool-turns (or (:max-tool-turns opts) 20)})))
