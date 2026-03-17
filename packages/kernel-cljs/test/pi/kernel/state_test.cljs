(ns pi.kernel.state-test
  (:require [cljs.test :refer [deftest testing is]]
            [pi.kernel.state :as state]))

(deftest create-and-get-state-test
  (testing "create-state + get-state roundtrip"
    (let [s (state/create-state {:a 1 :b 2})]
      (is (= {:a 1 :b 2} (state/get-state s))))))

(deftest update-state-immutability-test
  (testing "update-state returns new container, original unchanged"
    (let [s1 (state/create-state {:x 10})
          s2 (state/update-state s1 #(assoc % :x 20 :y 30))]
      (is (= {:x 10} (state/get-state s1)))
      (is (= {:x 20 :y 30} (state/get-state s2))))))

(deftest fork-independence-test
  (testing "fork creates independent copy"
    (let [s1 (state/create-state {:a 1 :b 2})
          s2 (state/fork s1)
          s3 (state/update-state s2 #(assoc % :a 99))]
      (is (= {:a 1 :b 2} (state/get-state s1)))
      (is (= {:a 1 :b 2} (state/get-state s2)))
      (is (= {:a 99 :b 2} (state/get-state s3))))))

(deftest merge-fork-test
  (testing "merge-fork combines changes with merge-fn"
    (let [original (state/create-state {:a 1 :b 2 :c 3})
          forked   (-> (state/fork original)
                       (state/update-state #(assoc % :a 10 :b 20)))
          merged   (state/merge-fork original forked
                                     (fn [_key orig-val fork-val]
                                       (+ orig-val fork-val)))]
      ;; :a and :b differ, so merge-fn is called
      ;; :c is same in both, so kept as-is
      (is (= {:a 11 :b 22 :c 3} (state/get-state merged))))))

(deftest state-diff-test
  (testing "state-diff identifies changed keys"
    (let [s1 (state/create-state {:a 1 :b 2 :c 3})
          s2 (state/update-state s1 #(assoc % :a 99 :c 99))]
      (is (= #{:a :c} (state/state-diff s1 s2))))))

(deftest state-diff-added-removed-keys-test
  (testing "state-diff detects added and removed keys"
    (let [s1 (state/create-state {:a 1 :b 2})
          s2 (state/update-state s1 #(-> % (dissoc :b) (assoc :c 3)))]
      (is (= #{:b :c} (state/state-diff s1 s2))))))

(deftest merge-fork-with-new-keys-test
  (testing "merge-fork handles keys only in fork"
    (let [original (state/create-state {:a 1})
          forked   (-> (state/fork original)
                       (state/update-state #(assoc % :b 2)))
          merged   (state/merge-fork original forked
                                     (fn [_key _orig fork-val] fork-val))]
      (is (= {:a 1 :b 2} (state/get-state merged))))))
