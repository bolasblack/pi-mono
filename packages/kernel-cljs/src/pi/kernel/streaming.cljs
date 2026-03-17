(ns pi.kernel.streaming
  (:require [cljs.core.async :refer [chan dropping-buffer mult tap untap close! put! go-loop <!]]))

(defn create-event-bus
  "Create an event bus backed by a core.async channel and mult.
   Returns {:channel ch :mult m}."
  []
  (let [ch (chan 32)]
    {:channel ch
     :mult (mult ch)}))

(defn- subscribe-with-channel
  "Internal: tap a channel onto the event bus mult, run a go-loop delivering
   events to callback via transform-fn, and return an unsubscribe fn."
  [event-bus callback tap-ch transform-fn]
  (tap (:mult event-bus) tap-ch)
  (go-loop []
    (let [event (<! tap-ch)]
      (if (nil? event)
        (callback (transform-fn {:type :closed}))
        (do
          (callback (transform-fn event))
          (recur)))))
  (fn unsubscribe []
    (untap (:mult event-bus) tap-ch)
    (close! tap-ch)))

(defn subscribe
  "Subscribe a CLJS callback to the event bus via mult/tap.
   Callback receives native CLJS data (maps with keyword keys).
   Returns an unsubscribe function."
  [event-bus callback]
  (subscribe-with-channel event-bus callback (chan 32) identity))

(defn subscribe-js
  "Subscribe a JS callback to the event bus via mult/tap.
   Callback receives clj->js converted events for JS interop.
   Returns an unsubscribe function."
  [event-bus js-callback]
  (subscribe-with-channel event-bus js-callback (chan 32) clj->js))

(defn subscribe-buffered
  "Subscribe with a dropping buffer of the given size.
   Callback receives native CLJS data.
   Events beyond the buffer capacity are silently dropped.
   Returns an unsubscribe function."
  [event-bus callback buffer-size]
  (subscribe-with-channel event-bus callback (chan (dropping-buffer buffer-size)) identity))

(defn emit!
  "Emit an event onto the event bus channel.
   Returns true if successfully put, false if channel is closed."
  [event-bus event]
  (put! (:channel event-bus) event))

(defn close-bus!
  "Close the event bus channel, signaling all subscribers."
  [event-bus]
  (close! (:channel event-bus)))
