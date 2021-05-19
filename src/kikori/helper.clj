(ns kikori.helper
  (:refer-clojure :exclude [read])
  (:require [clojure.core.async :refer [chan poll! go-loop alts! put! >! >!! <!! <! go alts!! timeout
                                        sliding-buffer mult tap untap
                                        close! pipe pipeline pipeline-async pipeline-blocking]]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:require [kikori.core :refer :all]
            [kikori.device :as dev]
            [kikori.module :refer [defmodule]])
  (:require [taoensso.timbre :as log]))


(def serial-listeners (atom {}))

(defn- combine [state in host-in ctrl]
  (letfn [(clear-queue [q]
            (loop []
              (when (poll! q)
                (recur))))]
    ;; Discard garbage
    (clear-queue ctrl)
    (go-loop [state state
              ^StringBuilder sr (StringBuilder.)]
      (let [[x ch] (alts! (if (= state :park)
                            (do (clear-queue in)
                                [ctrl])
                            [in ctrl]))

            new-state (cond
                        (= ch ctrl) x
                        :else state)]

        (if (or (nil? x) (= :close x))
          (log/info "Closed Serial data combiner")
          (let [cnt (.length sr)]
            (if-not (number? x)
              (recur new-state sr)
              (if (and 
                   (< 0 x)
                   (<= 4 cnt)
                   (= \* (.charAt sr (- cnt 4))))
                (let [data {:raw (.toString sr)}]
                  (put! host-in data)
                  (recur new-state (.delete sr 0 (.length sr))))
                (recur new-state (.append sr (char (bit-and x 0x7f))))))))))))

(defmodule Serial
  (init [{:keys [ctrl read-port name uart host-in] :as sensor}]
        (log/info "Configuring Serial: " name)
          (let [{:keys [in out]} uart
                mult-in (mult host-in)]
            (tap mult-in read-port)
            (if (and in out read-port)
              (combine :park in host-in ctrl)
              (log/info "Serial is not available"))
            (swap! serial-listeners assoc name 0)
            (assoc sensor :mult-in mult-in)))
  
  (read [{:keys [ctrl uart read-port name]}]
        ;; record is agent which is updated by go-loop defined above
        
        (if uart
          (let [counter (get @serial-listeners name)]
            (when (and counter (< counter 1)) (put! ctrl :run))
            (let [[data ch] (alts!! [read-port (timeout 5000)])
                  {:keys [raw]} data]
              (when (and counter (< counter 1)) (put! ctrl :park))
              (if (seq raw)
                (assoc {} :raw raw)
                (do (log/warn "Serial Timeout")
                    {:result "error" :msg "Timeout"}))))
          (log/debug "Serial not available")))
  (close [{:keys [name uart ctrl mult-in worker-in host-in]}]
         (untap mult-in worker-in)
         (swap! serial-listeners update name dec)                  
         (when (< (get @serial-listeners name) 1)
             (put! ctrl :park))
         (log/info "Serial Closed: " name))
  (listen [{:keys [name ctrl host-in mult-in worker-in] :as sensor}]
          ;; Need to return ctrl port to handle go-loop
          ;; In this Serial module, only 1 loop is handled
          (swap! serial-listeners update name inc)
          (when worker-in
            (tap mult-in worker-in))
          (put! ctrl :run)
          sensor))


(gen-class
 :name kikori.helper.Serial
 :state state
 :init init
 :post-init post-init
 :constructors {[kikori.device.Sensor] []}
 :methods [[listen [] kikori.device.Sensor]]
 :prefix "helper-serial-")

(defn helper-serial-init [sensor]
  [[] (atom (assoc sensor :module :Serial))])

(defn helper-serial-post-init [this name]
  (if-let [x @(.state this)]
    (reset! (.state this) (init x))
    (log/warn "No edge device: " name))
  this)

(defn helper-serial-read [this]
  (read @(.state this)))

(defn helper-serial-listen [this]
  (log/info "Start to listen:" @(.state this))
  (listen @(.state this)))
