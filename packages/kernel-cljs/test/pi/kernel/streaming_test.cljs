(ns pi.kernel.streaming-test
  (:require [cljs.test :refer [deftest async is testing]]
            [cljs.core.async :refer [go <! timeout]]
            [pi.kernel.streaming :as streaming]))

(deftest create-event-bus-text-event
  (async done
    (let [bus (streaming/create-event-bus)
          received (atom [])]
      (streaming/subscribe bus
        (fn [event]
          (swap! received conj event)))
      (streaming/emit! bus {:type :text :content "hello"})
      (go
        (<! (timeout 50))
        (is (= 1 (count @received)))
        (is (= :text (:type (first @received))))
        (is (= "hello" (:content (first @received))))
        (streaming/close-bus! bus)
        (<! (timeout 50))
        (done)))))

(deftest multiple-consumers-receive-events
  (async done
    (let [bus (streaming/create-event-bus)
          received-a (atom [])
          received-b (atom [])]
      (streaming/subscribe bus
        (fn [event]
          (swap! received-a conj event)))
      (streaming/subscribe bus
        (fn [event]
          (swap! received-b conj event)))
      (streaming/emit! bus {:type :text :content "broadcast"})
      (go
        (<! (timeout 50))
        (is (= 1 (count @received-a)))
        (is (= 1 (count @received-b)))
        (is (= "broadcast" (:content (first @received-a))))
        (is (= "broadcast" (:content (first @received-b))))
        (streaming/close-bus! bus)
        (<! (timeout 50))
        (done)))))

(deftest dropping-buffer-consumer
  (async done
    (let [bus (streaming/create-event-bus)
          received (atom [])]
      (streaming/subscribe-buffered bus
        (fn [event]
          (swap! received conj event))
        2)
      (doseq [i (range 20)]
        (streaming/emit! bus {:type :text :content (str "msg-" i)}))
      (go
        (<! (timeout 100))
        (is (pos? (count @received)))
        (streaming/close-bus! bus)
        (<! (timeout 50))
        (done)))))

(deftest event-types-received
  (async done
    (let [bus (streaming/create-event-bus)
          received (atom [])]
      (streaming/subscribe bus
        (fn [event]
          (swap! received conj event)))
      (streaming/emit! bus {:type :text :content "hi"})
      (streaming/emit! bus {:type :tool-call :id "tc-1" :name "read_file"})
      (streaming/emit! bus {:type :stop :reason :end-turn})
      (go
        (<! (timeout 50))
        (is (= 3 (count @received)))
        (is (= :text (:type (first @received))))
        (is (= :tool-call (:type (second @received))))
        (is (= :stop (:type (nth @received 2))))
        (streaming/close-bus! bus)
        (<! (timeout 50))
        (done)))))

(deftest close-channel-signals-consumers
  (async done
    (let [bus (streaming/create-event-bus)
          received (atom [])]
      (streaming/subscribe bus
        (fn [event]
          (swap! received conj event)))
      (streaming/close-bus! bus)
      (go
        (<! (timeout 50))
        (is (= 1 (count @received)))
        (is (= :closed (:type (first @received))))
        (done)))))

(deftest unsubscribe-stops-delivery
  (async done
    (let [bus (streaming/create-event-bus)
          received (atom [])
          unsub (streaming/subscribe bus
                  (fn [event]
                    (swap! received conj event)))]
      (streaming/emit! bus {:type :text :content "before"})
      (go
        (<! (timeout 50))
        (is (= 1 (count @received)))
        (is (= :text (:type (first @received))))
        (unsub)
        (streaming/emit! bus {:type :text :content "after"})
        (<! (timeout 50))
        (is (empty? (filter #(= "after" (:content %)) @received)))
        (streaming/close-bus! bus)
        (<! (timeout 50))
        (done)))))

(deftest subscribe-js-delivers-js-objects
  (async done
    (let [bus (streaming/create-event-bus)
          received (atom [])]
      (streaming/subscribe-js bus
        (fn [event]
          (swap! received conj event)))
      (streaming/emit! bus {:type :text :content "hello"})
      (go
        (<! (timeout 50))
        (is (= 1 (count @received)))
        ;; subscribe-js delivers JS objects, not CLJS maps
        (let [evt (first @received)]
          (is (= "text" (.-type evt)))
          (is (= "hello" (.-content evt))))
        (streaming/close-bus! bus)
        (<! (timeout 50))
        (done)))))
