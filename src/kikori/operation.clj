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


(ns kikori.operation
  (:refer-clojure :exclude [read])
  (:require [clojure.string :as str]
            [clojure.core.async :as a :refer [<!! put! chan sliding-buffer close!]])
  (:require [kikori.core :refer :all]
            [kikori.util :as util]
            [kikori.hci :refer [map->LinuxHciDevice hci-config! hci-read hci-write! hci-close ]]
            [kikori.device :refer [clear-queue
                                   i2c-config i2c-read i2c-write!
                                   gpio-write! gpio-read 
                                   uart-config! uart-read uart-write! padding
                                   adc-config adc-read
                                   dac-config dac-write!
                                   clkr-config clkr-write!
                                   fb-config! fb-write! fb-read fb-clear!
                                   sram-read parse 
                                   specs
                                   map->Sensor]])
  (:require [taoensso.timbre :as log]))

(defmacro defoperation [op-name & body]
  (let [dispatch-key (keyword op-name)]
    (apply list
           'do
           (map (fn [[fname & opts]]
                  `(defmethod ~fname ~dispatch-key ~@opts))
                body))))

;; I2C ;;
(defoperation I2C
  (config [dev op & [{:keys [device-id] :as m}]]
          (i2c-config dev  m))
  (read [{:keys [device-id] :as m}]
        (when-let [dev (devices device-id)]
          (i2c-read dev  m)))
  (write [{:keys [device-id] :as m}]
         (when-let [dev (devices device-id)]
           (i2c-write! dev m))))

;; GPIO ;;
(defoperation GPIO
  (write [{:keys [device-id pin bus value data] :as m}]
         (let [device (devices device-id)
               value (or value (first data))
               pin (or pin bus)]
           (log/debug "GPIO pin/value:" pin value)
           (when (and device pin value)
             (log/debug "Device:" device)
             (gpio-write! device (assoc m
                                        :pin pin
                                        :value (if (number? value)
                                                 value
                                                 (Long/parseLong value)))))))
  (read [{:keys [device-id] :as m}]
        (when-let [dev (devices device-id)]
          (gpio-read dev (assoc m :count (or (:size m) 1))))))

;; ADC ;;
(defoperation ADC1
  (read [{:keys [device-id] :as m}]
        (when-let [dev (devices device-id)]
          (:ADC1 (adc-read dev m)))))
(defoperation ADC2
  (read [{:keys [device-id] :as m}]
        (when-let [dev (devices device-id)]
          (:ADC2 (adc-read dev m)))))
(defoperation ADC3
  (read [{:keys [device-id] :as m}]
        (when-let [dev (devices device-id)]
          (:ADC3 (adc-read dev m)))))
(defoperation ADC
  (config [dev op {:keys [device-id] :as m}]
          (adc-config dev m))
  (read [{:keys [device-id] :as m}]
        (when-let [dev (devices device-id)]
          (adc-read dev m))))


;; CLKR
(defoperation CLKR
  (config [dev op & [{:keys [device-id duty divider frequency] :as m}]]
          (clkr-config dev m))
  (write [{:keys [device-id duty divider frequency] :as m}]
         (let [device (devices device-id)]
           (when (and device duty (or divider frequency))
             (clkr-write! device (cond-> m
                                   duty (update :duty read-string)
                                   divider (update :divider read-string)
                                   frequency (update :frequency read-string))))))
  (status [{:keys [device-id] :as m}]
          (let [device (devices device-id)]
            (when device
              (parse device (sram-read device))))))

;; DAC
(defoperation DAC
  (config [dev op & [{:keys [device-id vrm value] :as m}]]
          (dac-config dev m)))
(defoperation DAC1
  (write [{:keys [device-id value] :as m}]
         (let [device (devices device-id)]
           (when (and device value)
             (dac-write! device m))))
  (status [{:keys [device-id] :as m}]
          (let [device (devices device-id)]
            (when device
              (parse device (sram-read device))))))
(defoperation DAC2
  (write [{:keys [device-id value] :as m}]
         (let [device (devices device-id)]
           (when (and device value)
             (dac-write! device m))))
  (status [{:keys [device-id] :as m}]
          (let [device (devices device-id)]
            (when device
              (parse device (sram-read device))))))

(defoperation FB
  (config [dev op & [{:keys [device-id vrm value] :as m}]]
          (fb-config! dev m))
  (read [{:keys [device-id]}]
        (fb-read (devices device-id)))
  (write [{:keys [device-id url data clear] :as m}]
         (let [device (devices device-id)
               {v :view :as fb} (:frame-buffer device)]
           (when (and device (or clear data url))
             (if clear
               (fb-clear! device)
               (fb-write! device (assoc m :data (if v (view (assoc m :frame-buffer fb)) data))))))))

(defoperation HCI
  (config [dev op & [{:keys [device-id] :as m}]]
          (assoc dev :hci (condp = (util/os)
                            :linux (hci-config! (map->LinuxHciDevice {}) m)
                            nil)))
  (read [{:keys [device-id] :as m}]
        (hci-read (devices device-id) m))
  (write [{:keys [device-id ] :as m}]
         (hci-write! (devices device-id) m))
  (close [{:keys [device-id ] :as m}]
         (let [dev (devices device-id)]
           (when-let [d (:hci dev)]
             (hci-close d)))))


;; UART
(defoperation UART
  (config [dev op {:keys [device-id] :as m}]
          (uart-config! dev m))
  (read [{:keys [device-id]}]
        (uart-read (devices device-id)))
  (write [{:keys [device-id string-data raw]}]
         (if raw
           (uart-write! (devices device-id) (seq (map #(Long/parseLong
                                                        (str/replace (str/trim %) #"^0[xX]" "") 16)
                                                      (str/split raw #","))))
           (uart-write! (devices device-id) string-data))))

(defn- async? [sensor]
  (some? ((methods listen) (keyword (or (:module sensor) (:bus sensor))))))


;; For manage operations ;;
(defoperation sensors
  (config [dev op & _]
          (reduce (fn [r {:keys [name host-in host-out read-port]
                          :or {host-in (chan (sliding-buffer 8))
                               host-out (chan (sliding-buffer 8)) 
                               read-port (chan (sliding-buffer 8))}
                          :as sensor}]

                    (let [sensor (cond-> sensor
                                   (= :UART (:bus sensor)) (-> (assoc :uart (select-keys (:uart dev) [:in :out]))
                                                               (assoc-in [:uart :path] (-> r :uart :port :path))
                                                               (assoc :read-port read-port))
                                   (async? sensor) (-> (assoc :host-out host-out)
                                                       (assoc :host-in (chan (sliding-buffer 8)))
                                                       (assoc :ctrl (chan))
                                                       (assoc :async true)))                          
                          ;; Built-in function as GPIO, ADC does not have 'init'.
                          ;; Thus init is called for physical/virtual edges
                          initialized (init sensor)]
                      (assoc-in r [:sensors name] (map->Sensor (or initialized sensor)))))
                  dev
                  (vals (:sensors dev))))
  (read [{:keys [ids]}]
        (log/debug "read:" (seq ids))
        (if (seq ids)
          (transduce (comp
                      (map (fn [{:keys [device-id id] :as sensor}]
                             (log/debug "sensor:" sensor)
                             (let [dev (devices device-id)]
                               {(keyword (:name sensor)) (try
                                                           (read sensor)
                                                           (catch Throwable e
                                                             (do (log/warn (.getMessage e) (ex-data e))
                                                                 ;; For care of packet lost or duplicate
                                                                 (clear-queue dev))))}))))
                     conj
                     {}
                     (filter identity (if (seq ids) (map sensors ids) (sensors))))
          ;; Pararell read by device 
          (into {} (apply concat (pmap (fn [xs]
                                         (try
                                           (doall (map (fn [[k x]] {k (read (map->Sensor x))}) xs))
                                           (catch Throwable e (when-first [s xs]
                                                                (when-let [dev (:device-id s)]
                                                                  (log/warn (.getMessage e))
                                                                  (clear-queue dev))))))
                                       (map :sensors (devices)))))))
  
  (close [{:keys [ids device-id device] :as m}]
         (doseq [sensor (vals (:sensors (or (devices device-id) device)))]
           (let [{:keys [bus port read-port]} sensor]
             (when (async? sensor)
               (and port (close! port))
               (and read-port (close! read-port))))
           (close sensor))))

(defoperation system
  (config [dev op {:keys [power] :or {power :3.3} :as m}]
          (let [sv (get-in (specs dev) [:system :power])]
            (if (sv power)
              (assoc dev op m)
              (do (log/warn "System power not configurable:" power)
                  (log/warn "Available:" sv)
                  dev)))))


