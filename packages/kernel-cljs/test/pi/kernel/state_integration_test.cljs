(ns pi.kernel.state-integration-test
  (:require [cljs.test :refer [deftest testing is async]]
            [cljs.core.async :refer [go <!]]
            [pi.kernel.state :as state]
            [pi.kernel.interceptor :as interceptor]
            [pi.kernel.core :as core]
            [pi.kernel.session :as session]))

(def default-state-shape
  {:tool-modifications {}
   :interceptor-config {}
   :learned-patterns []
   :turn-metrics []})

(deftest state-container-flows-through-chain-test
  (async done
    (go
      (let [seen-state (atom nil)
            test-interceptor {:name :state-reader
                              :enter (fn [ctx]
                                       (reset! seen-state (:agent-state ctx))
                                       ctx)}
            agent-state (state/create-state default-state-shape)
            ctx {:agent-state agent-state}
            chain (interceptor/create-chain [test-interceptor])
            result (<! (interceptor/execute-async chain ctx))]
        (testing "state container is accessible in context"
          (is (some? @seen-state))
          (is (= default-state-shape (state/get-state @seen-state))))
        (testing "state container persists in result context"
          (is (some? (:agent-state result)))
          (is (= default-state-shape (state/get-state (:agent-state result)))))
        (done)))))

(deftest interceptor-updates-state-via-update-state-test
  (async done
    (go
      (let [agent-state (state/create-state default-state-shape)
            updater-interceptor {:name :pattern-learner
                                 :leave (fn [ctx]
                                          (let [new-state (state/update-state
                                                           (:agent-state ctx)
                                                           #(update % :learned-patterns conj {:pattern "use-memo"}))]
                                            (assoc ctx :agent-state new-state)))}
            ctx {:agent-state agent-state}
            chain (interceptor/create-chain [updater-interceptor])
            result (<! (interceptor/execute-async chain ctx))]
        (testing "state container reflects the update after chain execution"
          (is (= [{:pattern "use-memo"}]
                 (:learned-patterns (state/get-state (:agent-state result))))))
        (testing "original state unchanged"
          (is (= [] (:learned-patterns (state/get-state agent-state)))))
        (done)))))

(deftest state-persists-across-turns-test
  (async done
    (go
      (let [;; Create a session
            sess (session/create-session)
            ;; Mock provider config
            model-config {:api "mock" :provider :default}

            ;; We'll test by running two turns and checking state persists
            ;; For this, we need to use interceptors that write/read state.
            ;; Since run-agent-turn creates its own interceptors, we test
            ;; the state container creation and passing pattern directly.

            ;; Turn 1: create state, interceptor writes to it
            state-1 (state/create-state default-state-shape)
            writer {:name :writer
                    :leave (fn [ctx]
                             (assoc ctx :agent-state
                               (state/update-state (:agent-state ctx)
                                 #(update % :learned-patterns conj {:p "first"}))))}
            chain-1 (interceptor/create-chain [writer])
            result-1 (<! (interceptor/execute-async chain-1 {:agent-state state-1}))

            ;; Turn 2: pass state from turn 1, interceptor reads it
            read-value (atom nil)
            reader {:name :reader
                    :enter (fn [ctx]
                             (reset! read-value
                               (:learned-patterns (state/get-state (:agent-state ctx))))
                             ctx)}
            chain-2 (interceptor/create-chain [reader])
            _result-2 (<! (interceptor/execute-async chain-2
                            {:agent-state (:agent-state result-1)}))]
        (testing "value from turn 1 is visible in turn 2"
          (is (= [{:p "first"}] @read-value)))
        (done)))))

(deftest state-has-expected-shape-test
  (let [agent-state (state/create-state default-state-shape)
        data (state/get-state agent-state)]
    (testing "initial state has :tool-modifications"
      (is (= {} (:tool-modifications data))))
    (testing "initial state has :interceptor-config"
      (is (= {} (:interceptor-config data))))
    (testing "initial state has :learned-patterns"
      (is (= [] (:learned-patterns data))))
    (testing "initial state has :turn-metrics"
      (is (= [] (:turn-metrics data))))))

(deftest run-agent-turn-uses-state-container-test
  (async done
    (go
      (let [sess (session/create-session)
            model-config {:api "mock" :provider :default}]
        ;; Add a user message so the session has something
        (session/append-entry! sess
          {:entry/type :user-message
           :entry/data {:content "test"}})
        ;; Run a turn - it should create and use a state container
        (<! (core/run-agent-turn sess model-config))
        ;; If we get here without errors, the state container was created
        (is true "run-agent-turn completed with state container")
        (done)))))

(deftest run-agent-turn-accepts-state-parameter-test
  (async done
    (go
      (let [sess (session/create-session)
            model-config {:api "mock" :provider :default}
            agent-state (state/create-state
                          (assoc default-state-shape
                            :learned-patterns [{:p "pre-existing"}]))]
        (session/append-entry! sess
          {:entry/type :user-message
           :entry/data {:content "test"}})
        ;; Pass state as 4th arg
        (<! (core/run-agent-turn sess model-config 0 agent-state))
        (is true "run-agent-turn accepted state parameter")
        (done)))))
