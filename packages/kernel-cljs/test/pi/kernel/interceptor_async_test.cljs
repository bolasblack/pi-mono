(ns pi.kernel.interceptor-async-test
  (:require [cljs.test :refer [deftest testing is async]]
            [cljs.core.async :refer [go <! chan put! timeout]]
            [cljs.core.async.impl.protocols :as async-protocols]
            [pi.kernel.interceptor :as i]))

(defn- ch-val
  "Returns a channel that yields v after optional delay."
  ([v] (go v))
  ([v ms] (go (<! (timeout ms)) v)))

(deftest execute-async-sync-only-test
  (async done
    (go
      (testing "execute-async with all sync interceptors returns same result as execute"
        (let [chain (i/create-chain [{:name :a :enter #(assoc % :a 1)}
                                     {:name :b :enter #(assoc % :b 2) :leave #(assoc % :left true)}])
              sync-result (i/execute chain {})
              async-result (<! (i/execute-async chain {}))]
          (is (= sync-result async-result))
          (is (= {:a 1 :b 2 :left true} async-result))))
      (done))))

(deftest execute-async-enter-test
  (async done
    (go
      (testing "execute-async with async :enter interceptor"
        (let [chain (i/create-chain
                      [{:name :sync-first :enter #(assoc % :a 1)}
                       {:name :async-mid
                        :enter (fn [ctx] (ch-val (assoc ctx :b 2) 10))}
                       {:name :sync-last :enter #(assoc % :c 3)}])
              result (<! (i/execute-async chain {}))]
          (is (= {:a 1 :b 2 :c 3} result))))
      (done))))

(deftest execute-async-leave-test
  (async done
    (go
      (testing "execute-async with async :leave interceptor"
        (let [log (atom [])
              chain (i/create-chain
                      [{:name :a
                        :enter (fn [ctx] (swap! log conj :a-enter) ctx)
                        :leave (fn [ctx] (swap! log conj :a-leave) ctx)}
                       {:name :b
                        :enter (fn [ctx] (swap! log conj :b-enter) ctx)
                        :leave (fn [ctx]
                                 (go
                                   (swap! log conj :b-leave)
                                   ctx))}])
              result (<! (i/execute-async chain {}))]
          (is (= [:a-enter :b-enter :b-leave :a-leave] @log))
          (is (map? result))))
      (done))))

(deftest execute-async-mixed-chain-test
  (async done
    (go
      (testing "Mixed sync/async chain executes in order"
        (let [log (atom [])
              chain (i/create-chain
                      [{:name :sync1
                        :enter (fn [ctx] (swap! log conj :sync1) (assoc ctx :sync1 true))}
                       {:name :async1
                        :enter (fn [ctx]
                                 (go
                                   (<! (timeout 5))
                                   (swap! log conj :async1)
                                   (assoc ctx :async1 true)))}
                       {:name :sync2
                        :enter (fn [ctx] (swap! log conj :sync2) (assoc ctx :sync2 true))}])
              result (<! (i/execute-async chain {}))]
          (is (= [:sync1 :async1 :sync2] @log))
          (is (= {:sync1 true :async1 true :sync2 true} result))))
      (done))))

(deftest execute-async-error-in-enter-test
  (async done
    (go
      (testing "Error in :enter triggers :error handlers right-to-left in async chain"
        (let [error-log (atom [])
              chain (i/create-chain
                      [{:name :a
                        :enter identity
                        :error (fn [ctx _err] (swap! error-log conj :a-error) ctx)}
                       {:name :b
                        :enter (fn [_ctx]
                                 (throw (ex-info "boom in async chain" {})))
                        :error (fn [ctx _err] (swap! error-log conj :b-error) ctx)}
                       {:name :c
                        :enter identity}])
              result (<! (i/execute-async chain {:ok true}))]
          ;; :b throws during enter, :c never entered
          ;; error handlers R->L from :b
          (is (= [:b-error :a-error] @error-log))
          (is (map? result))))
      (done))))

(deftest execute-async-error-handler-async-test
  (async done
    (go
      (testing "Async :error handler is awaited"
        (let [error-log (atom [])
              chain (i/create-chain
                      [{:name :a
                        :enter identity
                        :error (fn [ctx _err]
                                 (go
                                   (<! (timeout 5))
                                   (swap! error-log conj :a-error-async)
                                   (assoc ctx :recovered true)))}
                       {:name :b
                        :enter (fn [_ctx] (throw (ex-info "boom" {})))}])
              result (<! (i/execute-async chain {}))]
          (is (= [:a-error-async] @error-log))
          (is (= true (:recovered result)))))
      (done))))

(deftest execute-async-error-in-leave-with-handler-test
  (async done
    (go
      (testing "Error in :leave triggers :error handlers on remaining interceptors"
        (let [error-log (atom [])
              chain (i/create-chain
                      [{:name :a
                        :enter identity
                        :leave identity
                        :error (fn [ctx _err]
                                 (swap! error-log conj :a-error)
                                 (assoc ctx :recovered true))}
                       {:name :b
                        :enter identity
                        :leave (fn [_ctx]
                                 (throw (ex-info "leave boom" {})))}])
              result (<! (i/execute-async chain {:ok true}))]
          ;; :b's leave throws. Error handlers should run R->L on
          ;; remaining interceptors (which is :a, since :b already left).
          (is (= [:a-error] @error-log))
          (is (= true (:recovered result)))))
      (done))))

(deftest execute-async-error-in-leave-no-handler-test
  (async done
    (go
      (testing "Error in :leave without handler attaches error to context"
        (let [chain (i/create-chain
                      [{:name :a
                        :enter identity
                        :leave identity}
                       {:name :b
                        :enter identity
                        :leave (fn [_ctx]
                                 (throw (ex-info "leave boom no handler" {})))}])
              result (<! (i/execute-async chain {:ok true}))]
          ;; Without error handlers, the error should be captured on the context
          ;; rather than silently swallowed
          (is (some? (:interceptor/error result)))))
      (done))))
