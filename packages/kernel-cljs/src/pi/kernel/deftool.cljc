(ns pi.kernel.deftool
  #?(:cljs (:require [malli.core :as m]
                      [malli.json-schema :as json-schema]))
  #?(:clj (:require [malli.core :as m]
                     [malli.json-schema :as json-schema])))

;; --- Tool Registry ---
#?(:cljs
   (defn create-tool-registry
     "Creates a new tool registry instance."
     []
     (atom {})))

#?(:cljs
   (defn register-tool!
     "Register a tool in the given registry."
     [registry tool]
     (swap! registry assoc (:name tool) tool)
     tool))

#?(:cljs
   (defn get-tool
     "Get a tool by name from the given registry."
     [registry name]
     (get @registry name)))

#?(:cljs
   (defn list-tools
     "List all registered tool names in the given registry."
     [registry]
     (vec (keys @registry))))

;; --- Macro ---
#?(:clj
   (defmacro deftool
     "Define and register a tool.
      registry    - tool registry atom
      name        - string name for the tool
      docstring   - documentation string
      params-spec - malli schema for parameters
      bindings    - destructuring bindings for the params map
      body        - tool implementation"
     [registry tool-name docstring params-spec bindings & body]
     (let [source-form &form
           params-sym (gensym "params")]
       `(let [schema# ~params-spec
              json-schema# (json-schema/transform schema#)
              execute-fn# (fn [~params-sym]
                            (if (m/validate schema# ~params-sym)
                              (let [~bindings ~params-sym]
                                (do ~@body))
                              {:error (str "Validation failed: "
                                           (pr-str (m/explain schema# ~params-sym)))}))
              tool# {:name ~tool-name
                     :doc ~docstring
                     :params-spec schema#
                     :json-schema json-schema#
                     :source-form '~source-form
                     :execute execute-fn#}]
          (register-tool! ~registry tool#)
          tool#))))
