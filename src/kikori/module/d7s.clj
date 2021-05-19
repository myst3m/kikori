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


;; This module is for D6T44L-06 from Omron
;; It might be that Communication speed should be about 30khz - 40khz even the sensor
;; specs is max 100khz.
;; Otherwise, data is almost not ready. 

(ns kikori.module.d7s
  (:refer-clojure :exclude [read write])
  (:require [kikori.core :refer :all]))

(refer-kikori)


(defn d7s-get-state [dev addr]
  (dev/i2c-write! dev {:addr addr :data [0x10 0x00]})
  (let [result (dev/i2c-read dev {:addr addr :size 1})]
    ({0x00 :normal
      0x01 :normal-other
      0x02 :initial
      0x03 :offset
      0x04 :self-test} (first result))))

(defn d7s-get-axis-state [dev addr]
  (dev/i2c-write! dev {:addr addr :data [0x10 0x01]})
  (let [result (dev/i2c-read dev {:addr addr :size 1})]
    ({0x00 :y-z
      0x01 :x-z
      0x02 :x-y} (first result))))

(defn d7s-get-ctrl-state [dev addr]
  (dev/i2c-write! dev {:addr addr :data [0x10 0x04]})
  (let [result (dev/i2c-read dev {:addr addr :size 1})]
    (if (seq result)
      {:threshold (let [thresh (bit-and 2r00001000 (first result))]
                    ({0x00 :H
                      0x01 :L} thresh))
       :axis (let [axis (bit-shift-right (bit-and 2r01110000 (first result)) 4)]
               ({0x00 :y-z
                 0x01 :x-z
                 0x02 :x-y
                 0x03 :auto
                 0x04 :auto-on-initialize} axis))}
      (log/warn "No data"))))

(defn d7s-set-ctrl-state! [dev addr axis threshold]
  (let [a ({:y-z 0x00
            :x-z 0x01
            :x-y 0x02
            :auto 0x03
            :auto-on-initialize 0x04} (or axis :auto))
        t ({:H 0x00
            :L 0x01} (or threshold :H))]
    (if (and a t)
      (dev/i2c-write! dev {:addr addr :data [0x10 0x04 (bit-or (bit-shift-left a 4) (bit-shift-left t 3))]})
      (log/warn "Given axis and threshold might be incorrect. " "axis:" axis " threshold:" threshold))))

(defn d7s-set-mode! [dev addr mode]
  (let [n ({:normal 0x01
            :initial 0x02
            :offset 0x03
            :self-test 0x04} mode)]
    (if n
      (dev/i2c-write! dev {:addr addr :data [0x10 0x03 n]})
      (log/warn "Mode is incorrect"))))



(defn d7s-get-initial-data [dev addr]
  (dev/i2c-write! dev {:addr addr :data [0x40 0x00]})
  (let [result (dev/i2c-read dev {:addr addr :size (inc 0x14)})]
    result))

(defn d7s-get-offset-data [dev addr]
  (dev/i2c-write! dev {:addr addr :data [0x41 0x00]})
  (let [result (dev/i2c-read dev {:addr addr :size (inc 0x14)})]
    result))

(defn d7s-get-test-data [dev addr]
  (dev/i2c-write! dev {:addr addr :data [0x42 0x00]})
  (let [result (dev/i2c-read dev {:addr addr :size (inc 0x0e)})]
    result))

(defn d7s-clear-data [dev addr target]
  (let [n ({:set-offset 2r00001000
            :recent-offset 2r00000100
            :self-test 2r00000010
            :quake 2r00000001} target)]
    (if n
      (dev/i2c-write! dev {:addr addr :data [0x10 05 n]})
      (log/warn "Not found target to be cleard"))))

(defn d7s-get-processing-quake-data [dev addr]
  (dev/i2c-write! dev {:addr addr :data [0x20 0x00]})
  (let [result (dev/i2c-read dev {:addr addr :size 4})]
    (if (seq result)
      (let [data (map #(bit-and 0xff %) result)]
        {:si (* 0.1 (bit-or (bit-shift-left (nth data 0) 8) (nth data 1)))
         :pga (bit-or (bit-shift-left (nth data 2) 8) (nth data 3))})
      (do (log/warn "No data") result))))

(defn d7s-get-quake-data [dev addr index]
  ;; index should be in [1 - 5] following Omron specs
  (dev/i2c-write! dev {:addr addr :data [(+ 0x30 (dec index)) 0x00]})
  (let [result (dev/i2c-read dev {:addr addr :size (inc 0x0b)})]
    (if (seq result)
      (conj {:raw result}
            (zipmap [:offset-x 
                     :offset-y 
                     :offset-z
                     :t-ave]
                    (map (fn [[x0 x1]]
                           (let [v (+ (bit-shift-left (bit-and 0xff x0) 8) (bit-and 0xff x1))]
                             ;; Map to 16 bit signed integer
                             (if (<= v 0x7fff)
                               (* 0.1 v)
                               (* 0.1 (dec (- v 0xffff))))))
                         (partition 2 (take 8 result))))
            (zipmap [:si :pga]
                    (map (fn [[x0 x1]]
                           (* 0.1 (bit-or (bit-shift-left (bit-and 0xff x0) 8) (bit-and 0xff x1))))
                         (partition 2 (take 4 (drop 8 result))))))
      (do (log/warn "No data") result))))


(defmodule D7S
  (init [{:keys [device-id addr id axis threshold] :as sensor}]
          ;; axis can be :auto :y-z :x-z :x-y
          ;; threshold can be :H or :L
          (let [dev (devices device-id)]
            (doto dev
              (d7s-set-ctrl-state! addr axis threshold)
              (d7s-set-mode! addr :initial))
            sensor))

  (read [{:keys [device-id addr index]}]
        (let [dev (devices device-id)]
          (assoc (d7s-get-quake-data  dev addr (or index 1))
                 :processing (d7s-get-processing-quake-data dev addr)))))
