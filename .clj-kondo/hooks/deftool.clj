(ns hooks.deftool
  (:require [clj-kondo.hooks-api :as api]))

(defn deftool
  "Hook for pi.kernel.deftool/deftool macro.
   Signature: (deftool registry name docstring params-spec bindings & body)
   Rewrites to: (let [_ registry bindings nil] body) so kondo sees the bindings
   and registry is marked as used."
  [{:keys [node]}]
  (let [children (rest (:children node))
        ;; children: [registry name docstring params-spec bindings & body]
        registry-node (nth children 0)
        bindings-node (nth children 4)
        body-nodes (drop 5 children)
        new-node (api/list-node
                   (list*
                     (api/token-node 'let)
                     (api/vector-node
                       [(api/token-node '_) registry-node
                        bindings-node (api/token-node nil)])
                     body-nodes))]
    {:node new-node}))
