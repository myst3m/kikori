
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

(ns kikori.core
  (:refer-clojure :exclude [read])
  (:require [clojure.string :as str]
            [clojure.core.async :as a :refer [<!! put! chan sliding-buffer close! go-loop alts! poll!]])
  (:require [kikori.device :as dev]
            [kikori.hid :as hid]
            [kikori.util :as util])
  (:require [taoensso.timbre :as log]
            [taoensso.timbre.appenders.core :as appenders])
  (:import [purejavahidapi DeviceRemovalListener HidDevice]))


(defonce ^:private ^:dynamic *devices* (atom {}))
(defonce ^:dynamic *watches* (atom {}))



(defn devices
  ([]
   (vals @*devices*))
  ([id]
   (@*devices* id)))


(defn enumerate [& {:keys [vid pid product-string with-loopback?] :as args}]
  (cond->> (apply dev/enumerate (flatten (seq args)))
    product-string (filter #(re-find (re-pattern product-string) (:product-string %)))
    vid (filter #(= (:vendor-id %) vid))
    pid (filter #(= (:product-id %) pid))
    :always (map-indexed (fn [i x]
                           ;; For OS that does not add index in hid device as Linux
                           ;; The enumerate-devices for Linux adds an suffix of hidrawX as index
                           (if (:index x)
                             x
                             (assoc x :index i))))))


;; Naming convention:
;; fn* : a low level function to be used in upper layer

(defn- -dispatch [dev sensor-or-pin-or-fn]
  (let [k (or (and (map? sensor-or-pin-or-fn)
                   (:module sensor-or-pin-or-fn)
                   sensor-or-pin-or-fn)
              sensor-or-pin-or-fn)]
    ;; Priority
    ;; - user defined fn like :BME200
    ;; - use pin fn like :GP1
    ;; - configured fn on pin like :ADC1 etc..
    
    (if (and (keyword? k) (keyword? (k dev)))
      (k dev)
      k)))



;; Interfaces to be implemented in any function like GPIO, ADC1, ADC2 etc..
;; (defmulti bind (fn [dev pin fn-id] (mapv #(-> % name str/upper-case keyword) [pin fn-id])))
(defn bind [dev pin fn-id & [opts]]
  (dev/pin-bind dev pin fn-id opts))

(defmulti config (fn [dev sensor-or-pin-or-fn  & _]
                   (keyword (-dispatch dev sensor-or-pin-or-fn))))

(defmulti init (fn [{:keys [module] :as m}]
                 (keyword module)))


(defmulti read (fn [{:keys [module op] :as m}]
                 (keyword (or module op))))

(defmulti write (fn [{:keys [module op] :as m}]
                  (keyword (or module op))))

(defmulti status (fn [{:keys [module op] :as m}]
                   (keyword (or module op))))

(defmulti close (fn [{:keys [module op] :as m}]
                  (keyword (or module op))))

(defmulti listen (fn [{:keys [module op] :as m}]
                   (keyword (or module op))))

(defmulti view (fn [{{v :view} :frame-buffer :as m}]
                 (keyword v)))

(defmethod write :default [m]
  {:error 1 :msg "No :module or 'write' defined,  or name is incorrect." :data (select-keys m [:name :module :id :device-id])})


(defmethod read :default [m]
  {:error 1 :msg "No :module or 'read' defined,  or name is incorrect." :data (select-keys m [:name :module :id :device-id])})

(defmethod close :default [{:keys [name]}]
  (log/info "Closed as generic device: " name))

(defmethod config :default [dev op & opts]
  (when opts (log/info "Pass without config: " (or op "no-type") (pr-str opts)))
  dev)

(defmethod view :default [{{v :view} :frame-buffer}]
  (log/info "No support of view interface: " v))

(defmethod status :default [{:keys [module]}]
  (log/info "No support of status interface: " module))

(defmethod listen :default [{:keys [module]}]
  (log/info "No support of listen interface: " module))

(defmethod init :default [{:keys [module]}]
  (log/info "No support of init interface: " module))

(defmacro on-platform [& body]
  (let [body (vec body)
        def-fn (list 'defn 'boot! '[])]
    ;; Need doall otherwise, that does not run even it is lazy
    `(do (~@def-fn
          (let [enumerated# (enumerate)]
            (when-not (seq enumerated#)
              (log/warn "No device found"))
            
            (if (seq @*watches*)
              (log/info "USB event listener is running" (keys @*watches*))
              (do (log/info "USB event listener starts.")
                  (hid/usb-register-event-listner (util/os)
                                                  :add (fn [m#]
                                                         (log/info m#)
                                                         (let [dev# (dev/map->device m#)]
                                                           (log/info "USB device plugged in:" dev#)
                                                           (util/confirm! false)
                                                           (dorun (map (fn [f#]
                                                                         (f# [dev#]))
                                                                       (vals @*watches*)))))
                                                  :remove (fn [m#]
                                                            (log/info "USB device plugged off:" m#)
                                                            (let [dev# (devices (:deviceId m#))]
                                                              (when dev#
                                                                (shutdown dev#)))))))
            (doall (keep identity (for [construct# ~body]
                                    (construct# enumerated#)))))))))

(defmacro +interface [& body]
  `(fn [_#]
     (try
       (-> (dev/map->SystemInterface {:device-id (str (gensym "interface_"))
                                      :lock (Object.)
                                      ;; :hid (hid/map->LoopbackHid {})
                                      })
           ~@body
           (ready!))
       (catch Throwable e# (do (shutdown (ex-data e#))
                               (throw e#))))))

(defmacro +device [id-or-ctx & body]
  `(let [ctx# (if (map? ~id-or-ctx)
                ~id-or-ctx
                {:id (keyword (name ~id-or-ctx))})
         f# (fn [enumerated#]
              (let [devs# (->> enumerated#
                               (filter (fn [e#]
                                         (let [id# (name (:id ctx#))
                                               ;; Change all values to string
                                               query-ctx# (->> (dissoc ctx# :id)
                                                               (map (fn [[k# v#]] [k# (name v#)]) )
                                                               (into {}))
                                               m# (->> (select-keys e# (keys query-ctx#))
                                                       (map (fn [[k# v#]] [k# (name v#)]) )
                                                       (into {}))]
                                           (and
                                            (= m# query-ctx#)
                                            (or (some #(re-find (re-pattern id#) (% e#)) [:path :device-id])
                                                (= :any (keyword id#)))))))
                               (filter identity))]
                (doseq [dev# devs#]
                  (try
                    (-> dev#
                        ~@body
                        (ready!))
                    (catch Throwable e# (do (shutdown (ex-data e#))
                                            (throw e#)))))))]
     (swap! *watches* assoc (:id ctx#) f#)
     f#))

(defmacro on-loopback [f & body]
  `(binding [dev/*loopback-transducer* (map ~f)]
     ~@body))


(defn open [dev]
  (-> dev (dev/open) (dev/listen)))

(defn shutdown
  ([{:keys [port-in port-out develop?] :as dev}]
   (when (not develop?)
     (close {:op :sensors :device dev}))
   (dev/close dev)
   (swap! *devices* dissoc (:device-id dev)))
  ([]
   (doseq [dev (vals @*devices*) :when dev]
     (shutdown dev))))



(defn reset
  ([]
   (doall (map reset @*devices*)))
  ([dev]
   (dev/reset-chip dev)
   (close {:device-id (devices)})))

(defn develop [dev & [x]]
  (assoc dev :develop? true))

(defn ready! [dev]
  (letfn [(dump [dev#]
            (log/debug "set-sram-settings:" (util/hex (:set-sram-settings dev#)))
            (log/debug "status-set-parameters:" (util/hex (:status-set-parameters dev#)))
            dev#)]

    (if dev
      (try
        (log/debug "the following data is delivered to device")
        (dump dev)
        (if (util/confirm dev (dev/specs dev))
          (let [stage-1-dev (if (:develop? dev)
                              dev
                              (dev/ready! dev))
                ;; Register once to be get in module 'init' config by 'devices' function
                ;; See (config :sensors)
                _ (swap! *devices* assoc (:device-id dev) stage-1-dev)
                stage-2-dev (if (:develop? stage-1-dev)
                              stage-1-dev
                              (-> stage-1-dev
                                  (config  :sensors)
                                  (dump)
                                  (dissoc :set-sram-settings
                                          :status-set-parameters)))]
            (swap! *devices* assoc (:device-id dev) stage-2-dev)
            stage-2-dev)
          
          (do (log/warn "Skip configuration due to user selection")
              dev))
        (catch Throwable e (do (shutdown (ex-data e))
                               (throw e))))
      (log/warn "Device missing"))))


(defn place [dev bus {:keys [name module prefix id] :as sensor}]
  (let [{:keys [index]} (:hid dev)
        id (str (or id (java.util.UUID/randomUUID)))
        actual-name (or name
                              (when prefix (str prefix index))
                              (str "anonyumous-"
                                   (->> (:sensors dev)
                                        (vals)
                                        (map :name)
                                        (filter #(re-find #"anonyumous" (str %)))
                                        (count))))
        snsr (assoc sensor
                    :bus bus
                    :id id
                    :device-id (:device-id dev)
                    :module (or module
                                (let [default-module (-dispatch dev bus)]
                                  (log/info default-module "will be used : " actual-name)
                                  default-module))
                    :name actual-name)]
    (assoc-in dev [:sensors (:name snsr)] snsr)))

(defn first-device 
  ([]
   (first (vals @*devices*)))
  ([product-string]
   (-> (enumerate :product-string product-string) first))
  ([vid pid]
   (-> (enumerate :vid vid :pid pid) first)))

(defmacro with-first-device [& body]
  `(if-let [dev# (first-device "MCP2221")] 
     (-> dev# ~@body)
     (throw (ex-info "No device found." {}))))


(defn stub []
  (or (first (devices))
      (try
        (with-first-device
          (open)
          (config :I2C)
          (ready!))
        (catch Throwable e false))))



;; For sensor
(defn sensors
  ([]
   (map dev/map->Sensor (mapcat (fn [dev]
                                  (map (fn [m]
                                         (assoc m :device-id (:device-id dev)))
                                       (vals (:sensors dev))))
                                (vals @*devices*))))
  ([name-or-id]
   (when-let [s (first (filter #(or (= (name name-or-id) (:name %))
                                    (= (name name-or-id) (:id %))) (sensors)))]
     (dev/map->Sensor s))))


(defn quit []
  (shutdown)
  (System/exit 0))


(defn refer-kikori []
  (refer-clojure :exclude '[read write])
  (require '[clojure.core.async :refer [chan]])
  (require '[kikori.core :refer :all]
           '[kikori.module :refer [load-module load-java-module load-modules defmodule]]
           '[kikori.device :as dev]
           '[kikori.operation]
           '[kikori.shell :as shell]
           '[kikori.server :as srv :refer [start-web-server
                                           stop-web-server
                                           start-nrepl-server]]
           '[kikori.command :refer :all]
           '[kikori.util :refer :all]
           '[kikori.interop :as interop]
           '[kikori.graphic :as g]
           '[kikori.facade :refer :all]
           '[kikori.hid :refer :all]
           '[taoensso.timbre :as log])
  (require '[clojure.java.io :as io]
           '[clojure.string :as str]
           '[clojure.core.async :refer [chan poll! go-loop alts! put! >! >!! <!! <! go alts!!
                                        mult tap untap
                                        sliding-buffer timeout
                                        close! pipe pipeline pipeline-async pipeline-blocking]]))



;; syntax sugar
(defn *d
  ([]
   (first (filter #(instance? kikori.device.GroovyIoT %) (vals @*devices*))))
  ([id]
   (first (filter #(re-find (re-pattern id) (:device-id %)) (vals @*devices*)))))

(defn *i
  ([]
   (first (filter #(instance? kikori.device.SystemInterface %) (vals @*devices*))))
  ([id]
   (first (filter #(re-find (re-pattern id) (:device-id %)) (vals @*devices*)))))


;; For debug
(defn refer-kikori-debug []
  (refer-clojure :exclude '[read write])
  (refer-kikori)
  (require '[kikori.chip.mcp2221a :as mc]))



