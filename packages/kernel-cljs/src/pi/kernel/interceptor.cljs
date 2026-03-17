(ns pi.kernel.interceptor
  "Interceptor chain for the agent turn pipeline.
   Data-driven middleware with enter/leave/error phases.
   All functions are pure — they return new chains or contexts."
  (:require [cljs.core.async :refer [go <!]]
            [cljs.core.async.impl.protocols :as async-protocols]))

(defn create-chain
  "Takes a vector of interceptor maps, returns a chain.
   Each interceptor: {:name keyword, :enter fn, :leave fn, :error fn}.
   :enter, :leave, and :error are all optional."
  [interceptors]
  (vec interceptors))

(defn- find-index
  "Returns the index of the interceptor with the given name, or nil."
  [chain name]
  (first (keep-indexed (fn [i ic] (when (= name (:name ic)) i)) chain)))

(defn insert-before
  "Insert an interceptor before the one with the given name."
  [chain before-name interceptor]
  (if-let [idx (find-index chain before-name)]
    (into (subvec chain 0 idx)
          (cons interceptor (subvec chain idx)))
    chain))

(defn insert-after
  "Insert an interceptor after the one with the given name."
  [chain after-name interceptor]
  (if-let [idx (find-index chain after-name)]
    (let [after-idx (inc idx)]
      (into (subvec chain 0 after-idx)
            (cons interceptor (subvec chain after-idx))))
    chain))

(defn remove-interceptor
  "Remove the interceptor with the given name."
  [chain name]
  (vec (remove #(= name (:name %)) chain)))

(defn replace-interceptor
  "Replace the interceptor with the given name."
  [chain name new-interceptor]
  (mapv #(if (= name (:name %)) new-interceptor %) chain))

(defn- run-error-handlers
  "Run :error handlers right-to-left on the given interceptors."
  [interceptors ctx error]
  (reduce
    (fn [current-ctx ic]
      (if-let [error-fn (:error ic)]
        (error-fn current-ctx error)
        current-ctx))
    ctx
    (reverse interceptors)))

(defn execute
  "Runs the chain on a context map.
   Calls :enter fns left-to-right, then :leave fns right-to-left.
   If any :enter or :leave throws, calls :error on the remaining
   interceptors (those not yet processed in that phase), right-to-left.
   If no :error handlers exist, the exception propagates."
  [chain ctx]
  (let [;; Enter phase
        [entered-ctx entered has-error?]
        (reduce
          (fn [[current-ctx entered-so-far _] ic]
            (try
              (let [enter-fn (or (:enter ic) identity)
                    new-ctx  (enter-fn current-ctx)]
                [new-ctx (conj entered-so-far ic) false])
              (catch :default e
                (let [entered-with-current (conj entered-so-far ic)
                      has-handler? (some :error entered-with-current)]
                  (if has-handler?
                    (let [error-ctx (run-error-handlers entered-with-current current-ctx e)]
                      (reduced [error-ctx entered-with-current true]))
                    (throw e))))))
          [ctx [] false]
          chain)]
    (if has-error?
      entered-ctx
      ;; Leave phase — right-to-left through entered interceptors
      (loop [remaining (reverse entered)
             current-ctx entered-ctx]
        (if (empty? remaining)
          current-ctx
          (let [ic (first remaining)
                rst (rest remaining)]
            (if-let [leave-fn (:leave ic)]
              (let [result (try
                             {:ok (leave-fn current-ctx)}
                             (catch :default e
                               {:error e}))]
                (if-let [e (:error result)]
                  (let [has-handler? (some :error rst)]
                    (if has-handler?
                      (run-error-handlers rst current-ctx e)
                      (throw e)))
                  (recur rst (:ok result))))
              (recur rst current-ctx))))))))

(defn- maybe-await
  "If val is a channel, take from it. Otherwise wrap in a go block."
  [val]
  (if (satisfies? async-protocols/ReadPort val)
    val
    (go val)))

(defn- run-error-handlers-async
  "Run :error handlers right-to-left, awaiting any that return channels."
  [interceptors ctx error]
  (go
    (loop [ics (reverse interceptors)
           current-ctx ctx]
      (if (empty? ics)
        current-ctx
        (let [ic (first ics)]
          (if-let [error-fn (:error ic)]
            (let [raw (try
                        (error-fn current-ctx error)
                        (catch :default _e
                          current-ctx))
                  resolved (<! (maybe-await raw))]
              (recur (rest ics) resolved))
            (recur (rest ics) current-ctx)))))))

(defn execute-async
  "Async variant of execute. Returns a core.async channel yielding the final context.
   :enter/:leave/:error fns may return a context map (sync) or a channel yielding one (async).
   If a :leave fn throws, :error handlers run on remaining interceptors.
   If no :error handlers exist, the error is attached as :interceptor/error on the context."
  [chain ctx]
  (go
    (let [;; Enter phase — left to right
          [entered-ctx entered has-error?]
          (loop [remaining chain
                 current-ctx ctx
                 entered-so-far []]
            (if (empty? remaining)
              [current-ctx entered-so-far false]
              (let [ic (first remaining)
                    enter-fn (or (:enter ic) identity)]
                (let [result (try
                               {:ok (<! (maybe-await (enter-fn current-ctx)))}
                               (catch :default e
                                 {:error e}))]
                  (if-let [e (:error result)]
                    ;; Error during enter — run error handlers
                    (let [entered-with-current (conj entered-so-far ic)
                          has-handler? (some :error entered-with-current)]
                      (if has-handler?
                        (let [error-ctx (<! (run-error-handlers-async entered-with-current current-ctx e))]
                          [error-ctx entered-with-current true])
                        (throw e)))
                    (recur (rest remaining) (:ok result) (conj entered-so-far ic)))))))]
      (if has-error?
        entered-ctx
        ;; Leave phase — right to left, with error handling
        (loop [remaining (reverse entered)
               current-ctx entered-ctx]
          (if (empty? remaining)
            current-ctx
            (let [ic (first remaining)
                  rst (rest remaining)]
              (if-let [leave-fn (:leave ic)]
                (let [result (try
                               {:ok (<! (maybe-await (leave-fn current-ctx)))}
                               (catch :default e
                                 {:error e}))]
                  (if-let [e (:error result)]
                    (let [has-handler? (some :error rst)]
                      (if has-handler?
                        (<! (run-error-handlers-async rst current-ctx e))
                        (assoc current-ctx :interceptor/error e)))
                    (recur rst (:ok result))))
                (recur rst current-ctx)))))))))
