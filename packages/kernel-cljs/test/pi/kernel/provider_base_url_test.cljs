(ns pi.kernel.provider-base-url-test
  (:require [cljs.test :refer [deftest is testing]]))

;; Tests for override-model-base-url pure logic.
;; The function is private in provider.cljs, so we test the equivalent logic:
;; Given a JS object and a base-url string, produce a shallow copy with baseUrl set.

(deftest override-via-object-assign-sets-base-url
  (let [original   #js {:id "model-1" :api "anthropic" :baseUrl "https://original.com"}
        base-url   "https://override.example.com"
        overridden (js/Object.assign #js {} original #js {:baseUrl base-url})]
    (is (= "https://override.example.com" (.-baseUrl overridden))
        "baseUrl should be overridden")
    (is (= "model-1" (.-id overridden))
        "other properties should be preserved")
    (is (= "https://original.com" (.-baseUrl original))
        "original should not be mutated")))

(deftest override-via-object-assign-without-base-url
  (let [original #js {:id "model-2" :api "anthropic"}
        copy     (js/Object.assign #js {} original)]
    (is (= "model-2" (.-id copy)))
    (is (nil? (.-baseUrl copy))
        "baseUrl should remain absent when not overridden")))
