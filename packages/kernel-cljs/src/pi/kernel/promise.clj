(ns pi.kernel.promise
  "Macros for pi.kernel.promise. Loaded via :require-macros self-reference.")

(defmacro then-let
  "Chain .then on a promise with a binding form.
   Two calling conventions:
   1) Thread-first: (-> (some-promise) (p/then-let [result] body...))
      or standalone:  (p/then-let (some-promise) [result] body...)
   2) Inline:        (p/then-let [result (some-promise)] body...)"
  [first-arg & rest]
  (if (vector? first-arg)
    ;; Inline form: (then-let [binding expr] body...)
    (let [[binding expr] first-arg]
      `(.then ~expr (fn [~binding] ~@rest)))
    ;; Thread-first form: (then-let promise [binding] body...)
    (let [promise first-arg
          [bindings & body] rest
          [binding] bindings]
      `(.then ~promise (fn [~binding] ~@body)))))

(defmacro catch-let
  "Chain .catch on a promise with a binding form.
   Two calling conventions:
   1) Thread-first: (-> (some-promise) (p/catch-let [err] body...))
      or standalone:  (p/catch-let (some-promise) [err] body...)
   2) Inline:        (p/catch-let [err (some-promise)] body...)"
  [first-arg & rest]
  (if (vector? first-arg)
    ;; Inline form: (catch-let [binding expr] body...)
    (let [[binding expr] first-arg]
      `(.catch ~expr (fn [~binding] ~@rest)))
    ;; Thread-first form: (catch-let promise [binding] body...)
    (let [promise first-arg
          [bindings & body] rest
          [binding] bindings]
      `(.catch ~promise (fn [~binding] ~@body)))))
