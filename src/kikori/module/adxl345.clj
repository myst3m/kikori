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


;; This module is for ADXL345 from ANALOG DEVICES
;; Default is FULL_RES mode. Scale factor is 0.4mG/LSB

(ns kikori.module.adxl345
  (:refer-clojure :exclude [read write])
  (:require [kikori.core :refer :all]))

(refer-kikori)

(def ^:private data-sheet
  {:register {:DEV_ID 0x00
              :MEASURE 0x08
              :DATA-FORMAT 0x31
              :AXIS-DATA 0x32
              :BW-RATE 0x2c
              :POWER-CTL 0x2d}
   :rate {:25 0x09
          :50 0x0a
          :100 0x0b
          :200 0x0c
          :400 0x0d
          :800 0x0e
          :1600 0x0f}
   :range {:2 0x00
           :4 0x01
           :8 0x02
           :16 0x03}})


(defn set-band-width-rate [dev {:keys [rate] :as m}]
  (let [op (-> data-sheet :register :BW-RATE)
        rt (-> data-sheet :rate rate (bit-and 0x0f))]
    (dev/i2c-write! dev (assoc m :data [op rt]))))

(defn set-range [dev {:keys [self-test full-resolution range addr]
                      :or {range :16
                           self-test 0
                           full-resolution 1} :as m}]
  (let [data-format (-> data-sheet :register :DATA-FORMAT)
        rg (-> data-sheet :range range)]
    (dev/i2c-write! dev (assoc m :data [data-format
                                        (bit-or (bit-shift-left self-test 7)
                                                (bit-shift-left full-resolution 3)
                                                rg)]))))

(defn enable-measurement [dev m]
  (let [op (-> data-sheet :register :POWER-CTL)
        v (-> data-sheet :register :MEASURE)]
    (dev/i2c-write! dev (assoc m :data [op v]))))


(def SCALE-MULTIPLIER 1)

(defn i2c-block-read [dev m base size]
  (dev/i2c-write! dev (assoc m :data [base]))
  (dev/i2c-read dev (assoc m :repeated? true :size 6)))

(defn- get-axis [dev m]
  (let [data (i2c-block-read dev m (-> data-sheet :register :AXIS-DATA) 6)]
    (zipmap [:x :y :z :raw] (conj
                             (conj (vec (map (fn [[a0 a1]]
                                               (+ (* a1 256) (bit-and 0xff a0)))
                                             (partition 2 data)))
                                   (vec (map #(symbol (format "0x%x" (bit-and 0xff %))) data)))))))

(defmodule ADXL345
  (init [{:keys [addr device-id] :as sensor}]
        (let [dev (devices device-id)]
          (try
            (set-band-width-rate dev (assoc sensor :rate :100))
            (set-range dev (assoc sensor :range :16))
            (enable-measurement dev sensor)
            (log/debug "ADXL345: config")
            (catch Throwable e (log/error "Config error")))))
  
  (read [{:keys [device-id addr] :as sensor}]
        (get-axis (devices device-id) sensor)))
