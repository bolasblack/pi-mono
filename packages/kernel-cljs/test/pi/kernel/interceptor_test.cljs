(ns pi.kernel.interceptor-test
  (:require [cljs.test :refer [deftest testing is]]
            [pi.kernel.interceptor :as i]))

(deftest empty-chain-test
  (testing "Empty chain returns context unchanged"
    (let [chain (i/create-chain [])
          ctx {:data 42}]
      (is (= ctx (i/execute chain ctx))))))

(deftest single-enter-test
  (testing "Single interceptor :enter modifies context"
    (let [chain (i/create-chain [{:name :add-x
                                  :enter #(assoc % :x 1)}])]
      (is (= {:x 1} (i/execute chain {}))))))

(deftest enter-leave-order-test
  (testing "Enter L->R, leave R->L"
    (let [log (atom [])
          chain (i/create-chain
                  [{:name :a
                    :enter (fn [ctx] (swap! log conj :a-enter) (update ctx :trail (fnil conj []) :a-enter))
                    :leave (fn [ctx] (swap! log conj :a-leave) (update ctx :trail conj :a-leave))}
                   {:name :b
                    :enter (fn [ctx] (swap! log conj :b-enter) (update ctx :trail conj :b-enter))
                    :leave (fn [ctx] (swap! log conj :b-leave) (update ctx :trail conj :b-leave))}
                   {:name :c
                    :enter (fn [ctx] (swap! log conj :c-enter) (update ctx :trail conj :c-enter))
                    :leave (fn [ctx] (swap! log conj :c-leave) (update ctx :trail conj :c-leave))}])
          result (i/execute chain {})]
      (is (= [:a-enter :b-enter :c-enter :c-leave :b-leave :a-leave] @log))
      (is (= [:a-enter :b-enter :c-enter :c-leave :b-leave :a-leave] (:trail result))))))

(deftest error-in-enter-test
  (testing "Error in :enter triggers :error handlers right-to-left"
    (let [error-log (atom [])
          chain (i/create-chain
                  [{:name :a
                    :enter identity
                    :error (fn [ctx _err] (swap! error-log conj :a-error) ctx)}
                   {:name :b
                    :enter (fn [_ctx] (throw (ex-info "boom" {})))
                    :error (fn [ctx _err] (swap! error-log conj :b-error) ctx)}
                   {:name :c
                    :enter identity
                    :error (fn [ctx _err] (swap! error-log conj :c-error) ctx)}])
          result (i/execute chain {:ok true})]
      ;; :b throws during enter. Error handlers called R->L from :b
      ;; :c was never entered so its :error is not called
      (is (= [:b-error :a-error] @error-log))
      (is (map? result)))))

(deftest insert-before-test
  (testing "insert-before places interceptor before named one"
    (let [chain (-> (i/create-chain [{:name :a :enter #(assoc % :a 1)}
                                     {:name :c :enter #(assoc % :c 3)}])
                    (i/insert-before :c {:name :b :enter #(assoc % :b 2)}))
          result (i/execute chain {})]
      (is (= {:a 1 :b 2 :c 3} result)))))

(deftest insert-after-test
  (testing "insert-after places interceptor after named one"
    (let [chain (-> (i/create-chain [{:name :a :enter #(assoc % :a 1)}
                                     {:name :c :enter #(assoc % :c 3)}])
                    (i/insert-after :a {:name :b :enter #(assoc % :b 2)}))
          result (i/execute chain {})]
      (is (= {:a 1 :b 2 :c 3} result)))))

(deftest remove-interceptor-test
  (testing "remove-interceptor removes by name"
    (let [chain (-> (i/create-chain [{:name :a :enter #(assoc % :a 1)}
                                     {:name :b :enter #(assoc % :b 2)}
                                     {:name :c :enter #(assoc % :c 3)}])
                    (i/remove-interceptor :b))
          result (i/execute chain {})]
      (is (= {:a 1 :c 3} result)))))

(deftest replace-interceptor-test
  (testing "replace-interceptor swaps by name"
    (let [chain (-> (i/create-chain [{:name :a :enter #(assoc % :a 1)}
                                     {:name :b :enter #(assoc % :b 2)}])
                    (i/replace-interceptor :b {:name :b :enter #(assoc % :b 99)}))
          result (i/execute chain {})]
      (is (= {:a 1 :b 99} result)))))

(deftest leave-only-interceptor-test
  (testing "Interceptor with only :leave still works"
    (let [chain (i/create-chain [{:name :a :leave #(assoc % :left true)}])
          result (i/execute chain {:left false})]
      (is (= true (:left result))))))

(deftest error-without-handler-propagates-test
  (testing "Error without :error handler still propagates through chain"
    (let [caught (atom nil)
          chain (i/create-chain
                  [{:name :a :enter identity}
                   {:name :b :enter (fn [_] (throw (ex-info "no handler" {})))}])]
      ;; No :error handlers, so the error propagates out
      (try
        (i/execute chain {})
        (catch :default e
          (reset! caught e)))
      (is (some? @caught)))))

(deftest error-in-leave-with-handler-test
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
          result (i/execute chain {:ok true})]
      (is (= [:a-error] @error-log))
      (is (= true (:recovered result))))))

(deftest error-in-leave-no-handler-propagates-test
  (testing "Error in :leave without handler propagates as exception"
    (let [caught (atom nil)
          chain (i/create-chain
                  [{:name :a :enter identity :leave identity}
                   {:name :b :enter identity
                    :leave (fn [_] (throw (ex-info "leave no handler" {})))}])]
      (try
        (i/execute chain {})
        (catch :default e
          (reset! caught e)))
      (is (some? @caught)))))
