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



(ns kikori.module.2jcie
  (:refer-clojure :exclude [read write])
  (:require [kikori.core :refer :all]))

(refer-kikori)

;; Little Endiang

(defmacro le [x & [n]]
  `(little-endian ~x ~n))

(defonce ^:private FIXED-DEVICE-CODE [0x52 0x42])
(defonce ^:private READ [0x01])
(defonce ^:private WRITE [0x02])
(defonce ^:private PREAMBLE-FORMAT [2 2 1 2])

(defn- pack [payload]
  (let [header FIXED-DEVICE-CODE
        payload (flatten payload) ;; should be a little endian
        length  (le (+ (count payload) 2) 2) ;; length: payload + crc-16 (2byte)
        data [header                         ;; 2 bytes
              length                         ;; 2 bytes
              payload                        ;; N bytes
              (le (crc16 header (le (+ (count payload) 2) 2) payload)  2) ;; 2 bytes
              ]]
    (log/debug "payload:" (seq payload))
    (log/debug "length:" length)
    (log/debug "data:" data)
    (byte-array (flatten data))))

(defn- unpack [data fmt]
  (loop [r []
         xs data
         fs fmt] ;; [header length command address] 
    (if-not (seq fs)
      (conj r xs)
      (recur (conj r (take (first fs) xs)) (drop (first fs) xs) (next fs)))))


(defn memory-data-long [start end]
  [READ
   (le [0x50 0x0e]) ;; address
   (le start 4)
   (le end 4)])

(defn latest-data-long []
  [READ
   (le [0x50 0x21])])

(defn latest-data-short []
  [READ
   (le [0x50 0x22])])

(defn- read-data [in]
  (take-while some? (map (fn [x] (poll! in)) (iterate inc 0))))

(defonce ^:private jcie-commands
  {:latest-data-long latest-data-long
   :latest-data-short latest-data-short})

(defmulti -response-decode (fn [cmd _] cmd))
(defmethod -response-decode :latest-data-long [cmd raw]
  (let [[_ _ _ _ payload] (unpack raw PREAMBLE-FORMAT)
        [_ temperature humidity ambient
         pressure noise etvoc
         eco2 discomfort heat
         vibration si pga seismic
         temperature-flag humidity-flag ambient-flag
         pressure-flag noise-flag etvoc-flag
         eco2-flag discomfort-flag heat-flag
         si-flag pga-flag selsmic-flag] (map bytes->long
                                             (unpack payload [1
                                                              2 2 2
                                                              4 2 2
                                                              2 2 2
                                                              1 2 2 2
                                                              2 2 2 ;; flags
                                                              2 2 2
                                                              2 2 2
                                                              1 1 1]))]
    
    {:temperature (* temperature 0.01)
     :humidity (* humidity 0.01)
     :ambient ambient
     :pressure (* pressure 0.001)
     :noise (* noise 0.01)
     :etvoc etvoc
     :eco2 eco2
     :discomfort (* discomfort 0.01)
     :heat (* heat 0.01)
     :si (* si 0.1)
     :pga (* 0.1 pga)
     :seismic (* seismic 0.001)
     :vibration vibration
     :flags {:temperature temperature-flag
             :humidity humidity-flag
             :ambient ambient-flag
             :pressure pressure-flag
             :noise noise-flag
             :etvoc etvoc-flag
             :eco2 eco2-flag
             :discomfort discomfort-flag
             :heat heat-flag
             :si si-flag
             :pga pga-flag
             :selsmic selsmic-flag}
     }))

(defmethod -response-decode :latest-data-short [cmd raw]
  (let [[_ _ _ _ payload] (unpack raw PREAMBLE-FORMAT)
        [_ temperature humidity ambient
         pressure noise etvoc
         eco2 discomfort heat-stroke] (map bytes->long (unpack payload [1  2 2 2  4 2 2  2 2 2]))]
    {:temperature (* temperature 0.01)
     :humidity (* humidity 0.01)
     :pressure (* pressure 0.001)
     :noise (* noise 0.01)
     :etvoc etvoc
     :eco2 eco2
     :discomfort (* discomfort 0.01)
     :heat (* heat-stroke 0.01)
     :ambient ambient}))


(defmethod -response-decode :default [cmd raw]
  (log/warn "command " cmd "not" "supported"))

(defmodule BU.2JCIE
  (init [{:keys [ctrl name uart] :as sensor}]
        (log/info "Configuring 2JCIE: " name)
        sensor)
  
  (read [{:keys [uart command id] :as sensor}]
        ;; record is agent which is updated by go-loop defined above
        (let [cmd (keyword command)
              command (if (jcie-commands cmd)
                        cmd
                        (if command
                          (do (log/warn "Command" command " not found, used latest-data-short")
                              :latest-data-short)
                          :latest-data-short))]
          (if uart
            (let [{:keys [in out]} uart
                  data-fn (jcie-commands command)]

              (try (put! out (pack (data-fn)))
                   (let [[c ch] (alts!! [in (timeout 1000)])
                         raw (cons c (read-data in))
                         [l-length h-length] (take 2 (drop 2 raw))
                         payload-crc-length (+ l-length (bit-shift-left h-length 8))]
                    
                     (log/debug "response length:"  [l-length h-length] ":" payload-crc-length)
                     (if ch
                       (assoc (-response-decode command raw)
                              :raw raw)
                       {:result "error" :msg "Timeout or data is not ready"}))
                   (catch NullPointerException e (do (log/warn "Maybe data not ready. Try it again.")
                                                     {:result "error" :msg "Device may not be ready"}))))
            (log/info "2JCIE not available"))))
  
  (close [{:keys [name uart ctrl mult-in worker-in host-in]}]
         (log/info "2JCIE Closed: " name)))


;; Need to load the driver:
;;  sudo modprobe ftdi_sio
;;  sudo bash -c "echo '0590 00d4' >  /sys/bus/usb-serial/drivers/ftdi_sio/new_id"
