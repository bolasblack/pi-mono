(ns pi.kernel.state
  "Persistent state with forking for speculative execution.
   All functions are pure — they return new state containers.")

(defn create-state
  "Takes an initial map, returns a state container."
  [initial-map]
  {:data initial-map})

(defn get-state
  "Returns the current state map from a state container."
  [state-container]
  (:data state-container))

(defn update-state
  "Applies update-fn to the current state map, returns a new state container.
   Original container is unchanged."
  [state-container update-fn]
  {:data (update-fn (:data state-container))})

(defn fork
  "Returns a new independent state container with the same data."
  [state-container]
  {:data (:data state-container)})

(defn merge-fork
  "Merges a forked state back into the original.
   merge-fn is called as (merge-fn key original-val fork-val) for keys
   that differ between original and fork. Keys only in fork are added.
   Keys only in original are kept."
  [original forked merge-fn]
  (let [orig-data (:data original)
        fork-data (:data forked)
        all-keys  (into (set (keys orig-data)) (keys fork-data))
        merged    (reduce
                    (fn [acc k]
                      (let [in-orig (contains? orig-data k)
                            in-fork (contains? fork-data k)
                            ov      (get orig-data k)
                            fv      (get fork-data k)]
                        (cond
                          (and in-orig in-fork (= ov fv)) (assoc acc k ov)
                          (and in-orig in-fork)           (assoc acc k (merge-fn k ov fv))
                          in-orig                         (assoc acc k ov)
                          :else                           (assoc acc k fv))))
                    {}
                    all-keys)]
    {:data merged}))

(defn state-diff
  "Returns the set of keys that differ between two state containers.
   Includes keys present in one but not the other."
  [state-a state-b]
  (let [da (:data state-a)
        db (:data state-b)
        all-keys (into (set (keys da)) (keys db))]
    (into #{}
          (filter (fn [k] (not= (get da k ::absent) (get db k ::absent))))
          all-keys)))
