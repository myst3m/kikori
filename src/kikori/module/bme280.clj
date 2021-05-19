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


;; This module is for the sensor BME280

;; Calibration is calculated in config only 1 time

(ns kikori.module.bme280
  (:refer-clojure :exclude [read write])
  (:require [kikori.core :refer :all]))

(refer-kikori)

(defonce ^:dynamic *calibration* (atom {}))

(defn- -read-byte-data [dev {:keys [addr]} cmd]
  ;;(write {:module :I2C :device-id (:device-id dev) :addr addr :data [cmd] :no-stop? true })
  (dev/i2c-write! dev {:addr addr :data [cmd]})
  (if-let [result (dev/i2c-read dev {:addr addr :size 1 :repeated? true})]
    (doall (map bit-and result  (repeat 0xff)))
    (log/debug "No data available for command:" cmd)))


(defn- -calibration [dev sensor]
  (log/debug "Start calibration")
  (try
    (let [data (mapcat #(-read-byte-data dev sensor %)
                       (concat (range 0x88 0xa0) ;; 0xa0 not include
                               [0xa1]
                               (range 0xe1 0xe8)))]

      ;; (107 110 245 103 50 0 66 145 56 214 208 11 55 37 71 255 249 255 12 48 32 209 136 19 75 72 1 0 25 44 3 30)
      (log/debug "Calibration data:" (seq data))

      (when-not (every? zero? data)
        {:dig-t (->> (partition 2 data)
                     (take 3)
                     (map (fn [[x0 x1]]
                            (bit-or (bit-shift-left x1 8) x0)))
                     (map-indexed (fn [i x]
                                    (if (and (= i 1) (= 1 (bit-and x 0x8000)))
                                      (+ (- (bit-xor x 0xffff)) 1)
                                      x))))
         :dig-p (->> (partition 2 data)
                     (drop 3)
                     (take 9)
                     (map (fn [[x0 x1]]
                            (bit-or (bit-shift-left x1 8) x0)))
                     (map-indexed (fn [i x]
                                    (if (and (<= 1 i 7) (<= 1 (bit-and x 0x8000)))
                                      (+ (- (bit-xor x 0xffff)) 1)
                                      x))))
     
         :dig-h (->> [(nth data 24)
                      (bit-or (bit-shift-left (nth data 26) 8) (nth data 25))
                      (nth data 27)
                      (bit-or (bit-shift-left (nth data 28) 4)
                              (bit-and (nth data 29) 0x0f))
                      (bit-or (bit-shift-left (nth data 30) 4)
                              (bit-and (bit-shift-right (nth data 29) 4) 0x0f))
                      (nth data 31)]
                     (map-indexed (fn [i x]
                                    (if (<= 1 (bit-and x 0x8000))
                                      (+ (- (bit-xor x 0xffff)) 1)
                                      x))))}))
    (catch Throwable e (log/error "Calibration failed"))))

(defn calib-data [{:keys [id] :as sensor}]
  (@*calibration* id))

(defn- -calc-fine [dev sensor data]
  (let [{raw :temperature} data
        {:keys [dig-t]}  (calib-data sensor)
        v1 (* (- (/ raw 16384.0) (/ (nth dig-t 0) 1024.0))
              (nth dig-t 1))
        v2 (* (- (/ raw 131072.0) (/ (nth dig-t 0) 8192.0))
              (- (/ raw 131072.0) (/ (nth dig-t 0) 8192.0))
              (nth dig-t 2))]
    (+ v1 v2)))


(defmulti ^:private -compensate (fn [obj dev adddr raw-data] obj))

(defmethod -compensate :pressure [obj dev sensor data]
  (let [{raw :pressure} data
        {:keys [dig-h dig-t dig-p]}  (calib-data sensor)
        _ (log/debug "---raw---" raw)
        _ (log/debug "---dig-p---" (seq dig-p))
        t-fine (-calc-fine dev sensor data)
        v1 (- (/ t-fine 2.0) 64000.0)
        _ (log/debug "v1:" v1)
        v2 (* (/ (* (/ v1 4.0) (/ v1 4.0)) 2048) (nth dig-p 5))
        v2 (+ v2 (* v1 (nth dig-p 4) 2.0))
        v2 (+ (/ v2 4.0) (* (nth dig-p 3) 65536.0))
        _ (log/debug "v2:" v2)
        v1 (/ (+ (/ (* (nth dig-p 2) (/ (* (/ v1 4.0) (/ v1 4.0)) 8192)) 8)
                 (/ (* (nth dig-p 1) v1) 2))
              262144)
        v1 (/ (* (+ 32768 v1) (nth dig-p 0)) 32768)
        _ (log/debug "v1-2:" v1)]
    
    (if (= 0.0 v1) 0
        (let [pressure (* (- (- 1048576 raw) (/ v2 4096.0)) 3125)
              _ (log/debug "pressure:" pressure)
              pressure (if (< pressure 0x80000000)
                         (do (log/debug "pressure-1:" (/ (* pressure 2.0) v1))
                             (/ (* pressure 2.0) v1))
                         (do (log/debug "pressure-2:" (* (/ pressure v1) 2))
                             (* (/ pressure v1) 2)))
              v1 (/ (* (nth dig-p 8)
                       (/ (* (/ pressure 8) (/ pressure 8)) 8192))
                    4096)
              _ (log/debug "v1-3" v1)
              v2 (/ (* (/ pressure 4.0)
                       (nth dig-p 7))
                    8192)
              _ (log/debug "v2-2" v1)]
          (log/debug "pressure-3" (* 0.01 (+ pressure (/ (+ v1 v2 (nth dig-p 6)) 16))))
          (* 0.01 (+ pressure (/ (+ v1 v2 (nth dig-p 6)) 16)))))))


(defmethod -compensate :temperature [obj dev sensor data]
  (let [{raw :temperature} data
        {:keys [dig-t]}  (calib-data sensor)
        v1 (* (- (/ raw 16384.0) (/ (nth dig-t 0) 1024.0))
              (nth dig-t 1))
        v2 (* (- (/ raw 131072.0) (/ (nth dig-t 0) 8192.0))
              (- (/ raw 131072.0) (/ (nth dig-t 0) 8192.0))
              (nth dig-t 2))]
    (/ (+ v1 v2) 5120.0)))

(defmethod -compensate :humidity [obj dev sensor data]
  (let [{raw-t :temperature
         raw-h :humidity} data
        {:keys [dig-h dig-t]}  
        (calib-data sensor)
        v1 (* (- (/ raw-t 16384.0) (/ (nth dig-t 0) 1024.0))
              (nth dig-t 1))
        v2 (* (- (/ raw-t 131072.0) (/ (nth dig-t 0) 8192.0))
              (- (/ raw-t 131072.0) (/ (nth dig-t 0) 8192.0))
              (nth dig-t 2))
        h (-  (+ v1 v2) 76800)]
    
;;     ---raw---
;; 39474
;; ---dig-h---
;; [75, 328, 0, 412, 50, 30]
;; ---var-h---
;; 76536.55851605535
;; ---var-h2---
;; 66.62926807173528
;; ---var-h3---
;;65.99419831564104

    (if (= 0.0 h) 0
        (let [h (* (- raw-h (+ (* (nth dig-h 3) 64)
                             (* (/ (nth dig-h 4) 16384.0) h)))
                   (* (/ (nth dig-h 1) 65536.0)
                      (+ 1.0 (* (/ (nth dig-h 5) 67108864.0) h
                                (+ 1.0 (* (/ (nth dig-h 2) 67108864.0) h))))))]
          
          (cond
            (< 100.0 h) 100
            (> 0 h) 0
            :else h)))))


(defmodule BME280
  (init [{:keys [addr id device-id] :as sensor}]
        (log/info "Configurring BME280: " (format "0x%x" addr))
    (let [dev (devices device-id)
          temperrature 1
          pressure 1
          humidity 1
          mode 3
          standby 5
          filter? 0
          spi-3? 0

          measure-reg (bit-or (bit-shift-left temperrature 5)
                              (bit-shift-left pressure 2)
                              mode)
          config-reg (bit-or (bit-shift-left standby 5)
                             (bit-shift-left filter? 2)
                             spi-3?)
          humidity-reg humidity]
      (when addr
        (log/debug "Write 0xF2 for humidity register")
        (dev/i2c-write! dev {:addr addr :data [0xF2 humidity-reg]})
        (log/debug "Write 0xF4 for temperature/pressure register")
        (dev/i2c-write! dev {:addr addr :data [0xF4 measure-reg]})
        (log/debug "Write 0xF5 for config register")
        (dev/i2c-write! dev {:addr addr :data [0xF5 config-reg]})
        (swap! *calibration* assoc id (-calibration dev sensor))
        sensor)))
  
  
  (read [{:keys [module addr device-id] :as sensor}]
        (let [dev (devices device-id)]
          (if (and addr (calib-data sensor))
            (let [raw (take 8 (map (fn [cmd] (-read-byte-data dev sensor cmd)) (iterate inc 0xf7)))                  
                  _ (log/debug (seq raw))]
              (if (every? some? raw)
                (let [raw (flatten raw)
                      data {:pressure (bit-or (bit-shift-left (nth raw 0) 12)
                                              (bit-shift-left (nth raw 1) 4)
                                              (bit-shift-right (nth raw 2) 4))
                            :temperature (bit-or (bit-shift-left (nth raw 3) 12)
                                                 (bit-shift-left (nth raw 4) 4)
                                                 (bit-shift-right (nth raw 5) 4))
                            :humidity (bit-or (bit-shift-left (nth raw 6) 8)
                                              (nth raw 7))}]
                  (reduce (fn [m k]
                            (assoc m k (-compensate k dev sensor data)))
                          {}
                          [:pressure :temperature :humidity]))
                (do (log/debug "Invalid raw data:" (seq raw))
                    {})))
            (do (log/debug addr (calib-data sensor))
                {:result :error :msg "No device found or no calibration data"}))))

  ;; For dispaly 
  (view [{:keys [data frame-buffer] :as m}]
        (log/info "Generate view as SVG:" data)
        (let [{w :width h :height} frame-buffer
              {:keys [pressure temperature humidity]} data]
          (log/debug "data:" pressure temperature humidity)
          (g/svg w h [:g {:font-family "sans-serif" :font-size 15 :fill "lightblue"}
                      [:text {:x 5 :y 20} "BME280"]
                      [:text {:x 10 :y 40}  (if temperature (format "T: %.6f" (float temperature)) "-")]
                      [:text {:x 10 :y 60}  (if pressure (format "P: %.6f" (float pressure)) "-")]
                      [:text {:x 10 :y 80} (if humidity (format "H: %.6f" (float humidity)) "-")]]))))



;; ---raw--- 306640
;; ---dig-p--- [37153, -10704, 3024, 8632, -78, -7, 9900, -10230, 4285]
;; v1:  -2067.9180979132652
;; v2:  565787172.427547
;; v1-2:  37200.922242340996
;; pressure:  1886888644.0829873
;; pressure-1:  101443.1111030568
;; v1-3:  20533.64646914165
;; v2-2:  -31670.014239021944
;; pressure-3:  101365.83811743929
;; temp : 24.19  c
;; hum :  78.42 percent
;; pressure : 1013.66 hPa
;; data:  [74, 221, 0, 129, 200, 0, 130, 217]

