(ns pi.kernel.promise-test
  (:require [cljs.test :refer [deftest async is testing]]
            [pi.kernel.promise :as p]))

;; ---------------------------------------------------------------------------
;; Construction
;; ---------------------------------------------------------------------------

(deftest resolved-test
  (async done
    (.then (p/resolved 42)
      (fn [v]
        (is (= 42 v) "resolved delivers the value")
        (done)))))

(deftest rejected-test
  (async done
    (.catch (p/rejected "boom")
      (fn [v]
        (is (= "boom" v) "rejected delivers the error")
        (done)))))

(deftest create-test
  (async done
    (.then (p/create (fn [resolve _reject] (resolve "ok")))
      (fn [v]
        (is (= "ok" v) "create wraps Promise constructor")
        (done)))))

(deftest create-reject-test
  (async done
    (.catch (p/create (fn [_resolve reject] (reject "err")))
      (fn [v]
        (is (= "err" v) "create can reject")
        (done)))))

;; ---------------------------------------------------------------------------
;; Chaining
;; ---------------------------------------------------------------------------

(deftest then-test
  (async done
    (p/then (p/resolved 10)
      (fn [v]
        (is (= 10 v) "then receives resolved value")
        (done)))))

(deftest then-chaining-test
  (async done
    (-> (p/resolved 5)
        (p/then (fn [v] (* v 2)))
        (p/then (fn [v]
                  (is (= 10 v) "then chains transform values")
                  (done))))))

(deftest catch-test
  (async done
    (-> (p/rejected "fail")
        (p/catch' (fn [e]
                    (is (= "fail" e) "catch' catches rejections")
                    (done))))))

(deftest catch-recovery-test
  (async done
    (-> (p/rejected "fail")
        (p/catch' (fn [_e] "recovered"))
        (p/then (fn [v]
                  (is (= "recovered" v) "catch' can recover the chain")
                  (done))))))

;; ---------------------------------------------------------------------------
;; Combination
;; ---------------------------------------------------------------------------

(deftest all-with-vector-test
  (async done
    (p/then (p/all [(p/resolved 1) (p/resolved 2) (p/resolved 3)])
      (fn [results]
        (is (= [1 2 3] (vec (array-seq results)))
            "all accepts CLJS vector, returns JS array")
        (done)))))

(deftest all-with-list-test
  (async done
    (p/then (p/all (list (p/resolved :a) (p/resolved :b)))
      (fn [results]
        (is (= [:a :b] (vec (array-seq results)))
            "all accepts CLJS list")
        (done)))))

(deftest all-empty-test
  (async done
    (p/then (p/all [])
      (fn [results]
        (is (= 0 (.-length results)) "all with empty coll resolves to empty array")
        (done)))))

;; ---------------------------------------------------------------------------
;; Thread-first pipeline
;; ---------------------------------------------------------------------------

(deftest thread-first-pipeline-test
  (async done
    (-> (p/resolved 3)
        (p/then (fn [v] (* v 10)))
        (p/then (fn [v] (+ v 5)))
        (p/then (fn [v]
                  (is (= 35 v) "-> pipeline composes transforms")
                  (done))))))

(deftest nil-resolved-test
  (async done
    (p/then (p/resolved nil)
      (fn [v]
        (is (nil? v) "resolved nil delivers nil")
        (done)))))

;; ---------------------------------------------------------------------------
;; then-let macro
;; ---------------------------------------------------------------------------

(deftest then-let-basic-test
  (async done
    (p/then-let (p/resolved 42) [v]
      (is (= 42 v) "then-let binds resolved value")
      (done))))

(deftest then-let-chaining-test
  (async done
    (-> (p/resolved 5)
        (p/then-let [a]
          (p/then-let (p/resolved (* a 2)) [b]
            (is (= 10 b) "then-let chains nested")
            (done))))))

(deftest then-let-returns-promise-test
  (async done
    (-> (p/resolved 3)
        (p/then-let [v]
          (* v 10))
        (p/then (fn [v]
                  (is (= 30 v) "then-let returns a promise")
                  (done))))))

;; ---------------------------------------------------------------------------
;; catch-let macro
;; ---------------------------------------------------------------------------

(deftest catch-let-basic-test
  (async done
    (p/catch-let (p/rejected "boom") [err]
      (is (= "boom" err) "catch-let binds rejection")
      (done))))

(deftest catch-let-recovery-test
  (async done
    (-> (p/rejected "fail")
        (p/catch-let [_err]
          "recovered")
        (p/then (fn [v]
                  (is (= "recovered" v) "catch-let recovers the chain")
                  (done))))))

(deftest catch-let-skips-on-success-test
  (async done
    (-> (p/resolved "ok")
        (p/catch-let [_err]
          "should not reach")
        (p/then (fn [v]
                  (is (= "ok" v) "catch-let passes through resolved values")
                  (done))))))

;; ---------------------------------------------------------------------------
;; Thread-first with then-let and catch-let
;; ---------------------------------------------------------------------------

(deftest thread-first-then-let-test
  (async done
    (-> (p/resolved 7)
        (p/then-let [v]
          (* v 3))
        (p/then-let [v]
          (is (= 21 v) "thread-first with then-let works")
          (done)))))

(deftest thread-first-catch-let-test
  (async done
    (-> (p/rejected "oops")
        (p/catch-let [err]
          (str "caught: " err))
        (p/then (fn [v]
                  (is (= "caught: oops" v) "thread-first with catch-let works")
                  (done))))))

;; ---------------------------------------------------------------------------
;; Inline form: then-let and catch-let with binding+expr in vector
;; ---------------------------------------------------------------------------

(deftest then-let-inline-basic-test
  (async done
    (p/then-let [v (p/resolved 42)]
      (is (= 42 v) "inline then-let binds resolved value")
      (done))))

(deftest then-let-inline-chaining-test
  (async done
    (p/then-let [a (p/resolved 5)]
      (p/then-let [b (p/resolved (* a 2))]
        (is (= 10 b) "inline then-let chains nested")
        (done)))))

(deftest then-let-inline-returns-promise-test
  (async done
    (-> (p/then-let [v (p/resolved 3)]
          (* v 10))
        (p/then (fn [v]
                  (is (= 30 v) "inline then-let returns a promise")
                  (done))))))

(deftest catch-let-inline-basic-test
  (async done
    (p/catch-let [err (p/rejected "boom")]
      (is (= "boom" err) "inline catch-let binds rejection")
      (done))))

(deftest catch-let-inline-recovery-test
  (async done
    (-> (p/catch-let [_err (p/rejected "fail")]
          "recovered")
        (p/then (fn [v]
                  (is (= "recovered" v) "inline catch-let recovers the chain")
                  (done))))))

;; ---------------------------------------------------------------------------
;; delay-with-abort
;; ---------------------------------------------------------------------------

(deftest delay-with-abort-completes-test
  (async done
    (let [ac (js/AbortController.)]
      (p/then-let (p/delay-with-abort 10 ac) [result]
        (is (= :completed result) "resolves to :completed after delay")
        (done)))))

(deftest delay-with-abort-aborted-test
  (async done
    (let [ac (js/AbortController.)]
      (p/then-let (p/delay-with-abort 60000 ac) [result]
        (is (= :aborted result) "resolves to :aborted when signal fires")
        (done))
      ;; Abort immediately
      (.abort ac))))

(deftest delay-with-abort-abort-before-start-test
  (async done
    (let [ac (js/AbortController.)]
      ;; Abort before creating the delay
      (.abort ac)
      (p/then-let (p/delay-with-abort 60000 ac) [result]
        (is (= :aborted result) "resolves to :aborted when already aborted")
        (done)))))
