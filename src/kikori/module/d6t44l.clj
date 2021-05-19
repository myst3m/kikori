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

(ns kikori.module.d6t44l
  (:refer-clojure :exclude [read write])
  (:require [kikori.core :refer :all]))

(refer-kikori)

(defn crc [datum]
  (loop [c datum
         n 0]
    (if (= n 8)
      c
      (recur (cond-> (bit-shift-left c 1)
               (< 0 (bit-and c 0x80)) (bit-xor 0x07)
               true (bit-and 0xff))
             (inc n)))))

(defn- valid-with-repeated? [data]
  (let [ic (->>  0x14
                 (crc)
                 (bit-xor 0x4c)
                 (crc)
                 (bit-xor 0x15)
                 (crc))]
    (loop [data data
           c ic]
      (if (= 1 (count data))
        c
        (recur (rest data) (crc (bit-xor (first data) c)))))))

;; it looks this pec should be used if it is used with MCP2221a
(defn- valid? [data]
  (let [ic (crc 0x15)]
    (= (loop [data data
              c ic]
         (if (= 1 (count data))
           c
           (recur (rest data) (crc (bit-xor (first data) c)))))
       (last data))))

(defn measure-data [data]
  (let [[ptat & px] (map (fn [[x0 x1]]
                           (let [v (+ (* 256 (bit-and 0xff x1)) (bit-and 0xff x0))]
                             ;; Map to 16 bit signed integer
                             (if (<= v 0x7fff)
                               v
                               (dec (- v 0xffff)))))
                         (partition 2 data) )]
    {:PTAT ptat
     :PX px
     :PEC (last data)
     :valid (valid? data)
     :raw data}))

(defn measure [dev addr]
  (dev/i2c-write! dev {:addr addr :data [0x4c]})
  (if-let [data (dev/i2c-read dev {:addr addr :size 35 :repeated? true})]
    (measure-data data)
    {}))



(defmodule D6T44L
  (read [{:keys [device-id addr]}]
        (measure (devices device-id) addr)))
