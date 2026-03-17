(ns pi.kernel.promise
  "Thin promise utilities for idiomatic CLJS async code.
   Alias as p: [pi.kernel.promise :as p]

   Macros (then-let, catch-let) are loaded via self-referencing :require-macros."
  (:require-macros [pi.kernel.promise]))

;; ---------------------------------------------------------------------------
;; Construction
;; ---------------------------------------------------------------------------

(defn resolved
  "Return a resolved promise. Wraps js/Promise.resolve."
  [v]
  (js/Promise.resolve v))

(defn rejected
  "Return a rejected promise. Wraps js/Promise.reject."
  [v]
  (js/Promise.reject v))

(defn create
  "Construct a promise from (fn [resolve reject] ...). Wraps js/Promise constructor."
  [f]
  (js/Promise. f))

;; ---------------------------------------------------------------------------
;; Chaining
;; ---------------------------------------------------------------------------

(defn then
  "Chain a success handler. Returns a new promise."
  [p f]
  (.then p f))

(defn catch'
  "Chain an error handler. Returns a new promise.
   Named catch' because catch is a special form in try/catch."
  [p f]
  (.catch p f))

;; ---------------------------------------------------------------------------
;; Combination
;; ---------------------------------------------------------------------------

(defn all
  "Wait for all promises in a CLJS collection. Returns a promise of a JS array.
   Accepts any seqable (vector, list, set, etc.) — converts to JS array internally."
  [coll]
  (js/Promise.all (to-array coll)))

;; ---------------------------------------------------------------------------
;; Timed
;; ---------------------------------------------------------------------------

(defn delay-with-abort
  "Returns a Promise that resolves to :completed after delay-ms,
   or to :aborted if the AbortController's signal fires first.
   If the signal is already aborted, resolves to :aborted immediately."
  [delay-ms ^js abort-controller]
  (create
    (fn [resolve _]
      (if (.. abort-controller -signal -aborted)
        (resolve :aborted)
        (let [timer (js/setTimeout #(resolve :completed) delay-ms)]
          (.addEventListener (.-signal abort-controller) "abort"
            (fn [] (js/clearTimeout timer) (resolve :aborted))
            #js {:once true}))))))
