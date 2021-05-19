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

(ns kikori.interop
  (:refer-clojure :exclude [read])  
  (:require [kikori.core :refer :all]
            [kikori.device :as dev]
            [kikori.util :refer [set-log-level!]]
            [kikori.operation]
            [kikori.parser :refer [parse]]
            [taoensso.timbre :as log]))


;; Edge
(gen-class
 :name kikori.interop.Edge
 :state state
 :prefix "interop-edge-"
 :implements [clojure.lang.Associative]
 :init init
 :constructors {[kikori.device.Sensor] []}
 :methods [[read [int] "[B"]
           [read [] "[B"]
           [getName [] String]
           [getContext [] kikori.device.Sensor]
           [write [java.util.List] void]])

(defn interop-edge-init 
  ([snsr] [[] snsr]))

(defn interop-edge-read
  ([this]
   (log/debug "Edge read w/o option")
   (interop-edge-read this 1))
  ([this size]
   (let [s (.state this)
         dev (devices (:device-id s))]
     ;; Use primitive read
     (log/debug "Edge read:" s size)
     (byte-array (read (assoc s :module (or (-> dev ((:bus s))) (:bus s)) :size size))))))

(defn interop-edge-write
  ([this data]
   (let [s (.state this)
         dev (devices (:device-id s))]
     (log/debug "Edge write" s data)
     ;; Use primitive read
     (write (assoc s :module (or (-> dev ((:bus s))) (:bus s)) :data (seq data))))))

(defn interop-edge-listen [this]
  (let [state (.state this)
        {:keys [mult-in worker-in]} state]
    (when (and mult-in worker-in)

      )
    )
  
  )
(defn invoke-edge-constructor [^kikori.device.Sensor sensor]
  (clojure.lang.Reflector/invokeConstructor
   (resolve 'kikori.interop.Edge)
   (to-array [sensor])))

(defn interop-edge-getName [this]
  (:name (.state this)))

(defn interop-edge-valAt [this key]
  ((keyword key) (.state this)))

(defn interop-edge-getContext [this]
  (.state this))

;; Need to implement to be associative
(defn interop-edge-seq [this]
  (seq (.state this)))


;; Stub
(gen-class
 :name kikori.interop.Stub
 :methods [^:static
           [getFirstDevice [] kikori.device.GroovyIoT]
           ^:static
           [setLogLevel [int] void]
           ^:static
           [load [String] void]
           ^:static
           [shutdown [] void]
           ^:static
           [getEdge [String] kikori.interop.Edge]
           ]
 
 :prefix "interop-stub-")

(defn interop-stub-getFirstDevice []
  (stub))

(defn interop-stub-load [fname]
  (binding [*ns* (create-ns 'kikori.shell)]
    (kikori.core/refer-kikori)
    (try
      (eval (parse (slurp fname)))
      (catch Exception e (do (log/error "Load error: " fname)
                             (log/error "Check if the config has correct syntax and module is on the class path."))))))

(defn interop-stub-shutdown []
  (shutdown))

(defn interop-stub-getEdge [name]
  (when-let [s (sensors name)]
    (clojure.lang.Reflector/invokeConstructor
     (resolve 'kikori.interop.Edge)
     (to-array [s]))))

(defn interop-stub-setLogLevel [level-idx]
  (set-log-level! (condp = level-idx
                    0 :trace
                    1 :debug
                    2 :info
                    3 :warn
                    4 :error
                    :error)))


;; Edge Class to wrap Sensor used inside

(definterface IModule
  (^kikori.interop.Edge init [^kikori.interop.Edge edge])
  (^java.util.HashMap read [^kikori.interop.Edge edge])
  (^java.util.HashMap write [^kikori.interop.Edge edge ^java.util.List data])
  (^void close [^kikori.interop.Edge edge]))

(definterface IListen
  (^kikori.interop.Edge listen [^kikori.interop.Edge edge]))



;; I2C
(gen-class
 :name kikori.interop.I2c
 :methods [^:static
           [query [kikori.device.GroovyIoT int] kikori.interop.Edge]
           ^:static
           [ping [kikori.device.GroovyIoT int] Boolean]]
 :prefix "interop-i2c-")

(defn interop-i2c-query [^kikori.device.GroovyIoT dev addr]
  (when (dev/i2c-ping dev addr)
    (clojure.lang.Reflector/invokeConstructor
     (resolve 'kikori.interop.Edge)
     (to-array [(dev/map->Sensor {:name (str addr)
                                  :addr addr
                                  :bus :I2C
                                  :module :I2C
                                  :device-id (:device-id dev)})]))))

(defn interop-i2c-ping [^kikori.device.GroovyIoT dev addr]
  (if (dev/i2c-ping dev addr) true false))


;; GPIO

(gen-class
 :name kikori.interop.Gpio
 :methods [^:static
           [get [kikori.device.GroovyIoT int] kikori.interop.Edge]
           ^:static
           [setMode [kikori.device.Sensor int] void]]
 :prefix "interop-gpio-")

(defn interop-gpio-get [dev pin-idx]
  (let [pin (keyword (str "GP" pin-idx))]
    (log/debug "Gpio.get:" pin)
    (when ((-> (dev/specs dev) :pins) pin)
      (let [xdev (-> dev (bind pin :GPIO) (ready!))]
        (invoke-edge-constructor (dev/map->Sensor {:name (name pin)
                                                   :pin pin
                                                   :bus pin
                                                   :module :GPIO
                                                   :device-id (:device-id xdev)}))))))

(defn interop-gpio-setMode [{:keys [pin bus device-id] :as sensor} mode-idx]
  (let [dev (devices device-id)
        pin (or pin bus)
        mode (-> (dev/specs dev) :GPIO :direction clojure.set/map-invert (get mode-idx))]
    (log/debug "Gpio.setMode:" device-id pin mode)
    (if mode
      (dev/gpio-set-mode! dev pin mode)
      (log/warn "Mode should be 0 (OUT) or 1 (IN)"))))

