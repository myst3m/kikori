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

(ns kikori.module.gps
  (:refer-clojure :exclude [read write])
  (:require [kikori.core :refer :all]))

(refer-kikori)

(defmulti nmea-parse (fn [data] (keyword (first data))))

(defmethod nmea-parse :$GPGGA [data]
  (zipmap [:sentence-type
           :current-time
           :latitude
           :latitude-compass-direction
           :longitude
           :longitude-compass-direction
           :fix-type
           :number-of-satellties-for-fix
           :horizontal-dilution-of-precision
           :altitude-above-mean-sea-level
           :altitude-units
           :geoidal-separation
           :units-of-above-geoid-separation
           :time-since-last-differential-correction
           :differential-station-id
           :checksum-validation-value]
          data))

(defmethod nmea-parse :$GPGGA [data]
  (let [schema [[:sentence-type 1]
                [:mode 2]
                [:ids-of-svs 12]
                [:position-dilution-of-precision 1]
                [:horizontal-dilution-of-precision 1]
                [:vertical-dilution-of-precision 1]
                [:checksum-validation-value 1]]]
    (loop [s schema
           result {}
           d data]
      (if-not (seq s)
        result
        (let [[k n] (first s)]
          (recur (rest s)
                 (assoc result k (vec (take n d)))
                 (drop n d)))))))


(defmethod nmea-parse :$GPTVG [data]
  {:sentence-type (first data)})

(defmethod nmea-parse :$GPGSA [data]
  {:sentence-type (first data)})


(defmethod nmea-parse :$GPGSV [data]
  (zipmap [:sentence-type
           :total-number-of-messages
           :message-number
           :total-number-of-svs

           :sv-prn-number-1
           :elevation-in-degrees-1
           :azimuch-1
           :signal-to-noise-radio-1
           
           :sv-prn-number-2
           :elevation-in-degrees-2
           :azimuch-2
           :signal-to-noise-radio-2

           :sv-prn-number-3
           :elevation-in-degrees-3
           :azimuch-3
           :signal-to-noise-radio-3

           :sv-prn-number-4
           :elevation-in-degrees-4
           :azimuch-4
           :signal-to-noise-radio-4

           :checksum-validation-value]
          data))


(defmethod nmea-parse :$GPRMC [data]
  (zipmap [:sentence-type
           :current-time
           :data-validity-flag
           :latitude
           :latitude-compass-direction
           :longitude
           :longitude-compass-direction
           :speed
           :heading
           :date
           :magnetic-variation
           :magnetic-variation-direction
           :positioning-system-mode
           :checksum-validation-value]
          data))

(defmethod nmea-parse :default [data]
  {:sentence-type (first data)})


(def gps-listeners (atom {}))

(defn combine [state in host-in ctrl]
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
          (log/info "Closed GPS data combiner")
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




(defmodule GPS
  (init [{:keys [ctrl read-port name uart host-in] :as sensor}]
        (log/info "Configuring GPS: " name)
          (let [{:keys [in out]} uart
                mult-in (mult host-in)]
            (tap mult-in read-port)
            (log/debug "in:" in)
            (log/debug "out:" out)
            (log/debug "read-port:" read-port) 
            (if (and in out read-port)
              (if ((set @gps-listeners) name)
                (log/info "GPS data service running")
                (combine :park in host-in ctrl))
              (log/info "GPS is not available"))
            (swap! gps-listeners assoc name 0)
            (assoc sensor :mult-in mult-in)))
  
  (read [{:keys [ctrl uart read-port name]}]
        ;; record is agent which is updated by go-loop defined above
        
        (if uart
          (let [counter (get @gps-listeners name)]
            (when (and counter (< counter 1)) (put! ctrl :run))
            (let [[data ch] (alts!! [read-port (timeout 2000)])
                  {:keys [raw]} data]
              (when (and counter (< counter 1)) (put! ctrl :park))
              (if (seq raw)
                (assoc (nmea-parse (map str/trim (str/split raw #",")))
                       :raw raw)
                (do (log/warn "GPS Timeout")
                    {:result "error" :msg "Timeout"}))))
          (log/debug "GPS not available")))
  (close [{:keys [name uart ctrl mult-in worker-in host-in]}]
         (log/debug mult-in worker-in)
         (untap mult-in worker-in)
         (swap! gps-listeners update name dec)                  
         (when (< (get @gps-listeners name) 1)
             (put! ctrl :park))
         (log/info "GPS Closed: " name))
  (listen [{:keys [name ctrl host-in mult-in worker-in] :as sensor}]
          ;; Need to return ctrl port to handle go-loop
          ;; In this GPS module, only 1 loop is handled
          (swap! gps-listeners update name inc)
          (tap mult-in worker-in)
          (put! ctrl :run)
          sensor))

