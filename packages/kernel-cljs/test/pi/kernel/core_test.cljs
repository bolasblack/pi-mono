(ns pi.kernel.core-test
  (:require [cljs.test :refer [deftest testing is async]]
            [cljs.core.async :refer [go <! timeout]]
            [pi.kernel.core :as core]
            [pi.kernel.interceptor :as interceptor]
            [pi.kernel.session :as session]))

(deftest main-entry-point-test
  (testing "main is callable and returns without error"
    (is (nil? (core/main)))))

;; --- Interceptor chain structure tests ---

(deftest default-interceptors-structure-test
  (testing "default-interceptors has expected interceptors in order"
    (let [names (mapv :name core/default-interceptors)]
      (is (= [:logging :context-management :self-modification-analysis :provider :token-counting :tool-execution :store-assistant-response]
             names))))

  (testing "each interceptor has a :name keyword"
    (doseq [ic core/default-interceptors]
      (is (keyword? (:name ic))))))

;; --- Individual interceptor tests ---

(deftest logging-interceptor-test
  (testing ":enter sets :start-time"
    (let [ic (first (filter #(= :logging (:name %)) core/default-interceptors))
          ctx ((:enter ic) {})]
      (is (number? (:start-time ctx)))
      (is (pos? (:start-time ctx)))))

  (testing ":leave passes context through"
    (let [ic (first (filter #(= :logging (:name %)) core/default-interceptors))
          ctx {:start-time 1000 :foo :bar}]
      (is (= ctx ((:leave ic) ctx))))))

(deftest context-management-interceptor-test
  (testing ":enter builds messages from session"
    (let [ic (first (filter #(= :context-management (:name %)) core/default-interceptors))
          sess (session/create-session)]
      (session/append-entry! sess
        {:entry/type :user-message
         :entry/data {:content "Hello"}})
      (let [ctx ((:enter ic) {:session sess})]
        (is (vector? (:messages ctx)))
        (is (= 1 (count (:messages ctx))))
        (is (= "user" (:role (first (:messages ctx)))))
        (is (= "Hello" (:content (first (:messages ctx)))))))))

;; --- Agent turn integration tests (async) ---

(deftest run-agent-turn-mock-provider-test
  (async done
    (go
      (let [sess (session/create-session)
            config {:api "mock" :provider :default}]
        (session/append-entry! sess
          {:entry/type :user-message
           :entry/data {:content "test prompt"}})
        (<! (core/run-agent-turn sess config))
        ;; Check assistant message was stored in session
        (let [db @(:conn sess)
              entries (core/query-all-entries db)
              assistant-entries (filter #(= :assistant-message (:entry/type %)) entries)]
          (is (= 1 (count assistant-entries)))
          (is (= "Hello world!"
                 (get-in (first assistant-entries) [:entry/data :content])))))
      (done))))

(deftest run-agent-turn-tool-calls-test
  (async done
    (go
      (let [sess (session/create-session)
            config {:api "mock-tools" :provider :default}]
        (session/append-entry! sess
          {:entry/type :user-message
           :entry/data {:content "read a file"}})
        (<! (core/run-agent-turn sess config))
        ;; Should have assistant message + tool result + second assistant message (from recursion)
        (let [db @(:conn sess)
              entries (core/query-all-entries db)
              types (mapv :entry/type entries)]
          ;; user-message, assistant-message (with tool call), tool-result, assistant-message (recurse with mock)
          (is (some #(= :tool-result %) types))
          (is (>= (count (filter #(= :assistant-message %) types)) 1))))
      (done))))

(deftest run-agent-turn-max-tool-turns-test
  (async done
    (go
      (let [sess (session/create-session)
            config {:api "mock-tools" :provider :default}]
        (session/append-entry! sess
          {:entry/type :user-message
           :entry/data {:content "read a file"}})
        ;; Use max-tool-turns=0 via binding to stop immediately
        (binding [core/*max-tool-turns* 0]
          (<! (core/run-agent-turn sess config)))
        ;; Should have only one assistant message, no tool recursion
        (let [db @(:conn sess)
              entries (core/query-all-entries db)
              tool-results (filter #(= :tool-result (:entry/type %)) entries)]
          ;; With max-tool-turns=0, tool calls are in the response but no execution
          (is (= 0 (count tool-results)))))
      (done))))

(deftest interceptor-chain-execute-async-test
  (async done
    (go
      (let [sess (session/create-session)
            _ (session/append-entry! sess
                {:entry/type :user-message
                 :entry/data {:content "test"}})
            ctx {:session sess
                 :model-config {:api "mock" :provider :default}
                 :system-prompt core/system-prompt
                 :tool-registry core/tool-registry
                 :tool-turn 0
                 :max-tool-turns 20
                 :skip-provider? false
                 :stop? false
                 :turn-id (str (random-uuid))
                 :start-time (js/Date.now)}
            chain (interceptor/create-chain core/default-interceptors)
            result (<! (interceptor/execute-async chain ctx))]
        (is (some? (:response result)))
        (is (string? (get-in result [:response :text])))
        (is (= "Hello world!" (get-in result [:response :text]))))
      (done))))
