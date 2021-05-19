;; *   Kikori
;; *
;; *   Copyright (c) Tsutomu Miyashita. All rights reserved.
;; *
;; *   The use and distribution terms for this software are covered by the
;; *   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; *   which can be found in the file epl-v10.html at the root of this distribution.
;; *   By using this software in any fashion, you are agreeing to be bound by
;; * 	 the terms of this license.
;; *   You must not remove this notice, or any other, from this software.


(ns kikori.module.tick
  (:refer-clojure :exclude [read write])
  (:require [kikori.core :refer :all]))

(refer-kikori)

(defn- run [port ctrl]
  ;; Discard garbage
  (loop []
    (when-let [garbage (poll! ctrl)]
      (recur)))

  (go-loop [v 0
            state :park
            interval 1000]
    (let [[x ch] (alts! (if (= state :park)
                          [ctrl]
                          [ctrl (timeout interval)]))]
      (>! port v)
      (cond
        (and (vector? x) (= :close (first x))) (log/info "Closed Tick generator")
        (and (vector? x) (= :run (first x))) (do (log/info "Run ")
                                                 (recur (mod (inc v) 2) :run interval))
        (and (vector? x) (= :update (first x))) (do (log/info "Update interval: " (last x))
                                                    (recur (mod (inc v) 2) state (last x)))
        :else  (recur (mod (inc v) 2) (first x) interval)))))

(def tick-listners (atom {}))

(defmodule Tick
  (init [{:keys [device-id host-in ctrl name] :as m}]
        (log/info "Configuring Tick: " name)
        (run host-in ctrl)
        (swap! tick-listners assoc name 0)
        (assoc m :mult-in (mult host-in)))
  
  (write [{:keys [ctrl host-in interval] :as sensor}]
         (log/info "Change interval to " interval)
         (>!! ctrl [:update (Long/parseLong interval)]))
  (read [_]
        {:msg "Use listen interface for WebSocket"})
  (listen [{:keys [ctrl worker-in mult-in host-in  name] :as sensor}]
          ;; Need to return ctrl port to handle go-loop
          ;; In this module, each thread
          (tap mult-in worker-in)
          (swap! tick-listners update name inc)
          (>!! ctrl [:run])
          sensor)
  (close [{:keys [ctrl worker-in mult-in name]}]
         (untap mult-in worker-in)
         (let [counter (get @tick-listners name)]
           (when counter
             (swap! tick-listners update name dec)
             (when (< counter 1)
               (put! ctrl [:park]))))         
         (log/info "Tick Closed: " ctrl)))
