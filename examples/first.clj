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

;; Rev. 0.4.0


(ns groovy-iot
  (:refer-clojure :exclude [read])
  (:require [kikori.core :as k]))

(k/refer-kikori)

;; Set log level to :warn to elide lower logs
(set-log-level! :warn)

;; If you want to output to file, you can use the following function.
;; The console? option can be used if you prefer to output to console also.

;; (set-log-output! "application.log" :console? true)

;; Load module you want to use (there should be on classpath)

(load-module "bme280")
(load-module "gps")
(load-module "d6t44l")
(load-module "d7s")
(load-module "adxl345")
(load-module "irmagician")
(load-module "tick")

;; Here , define Groovy-IOT with new sensor

(defn build-device []
  (with-first-device
    ;; system
    (config :system {:PCB "1.0.0" :power :5.0})
    
    ;; ADC
    (config :ADC {:vrm :VDD})
    (bind :GP0 :GPIO)
    (bind :GP2 :ADC2)
    (place :GP0 {:name "GP0"})
    (place :GP2 {:name "ADC2"})
    
    ;; Declare to use I2C and UART

    (config :I2C)
    ;; If no address on I2C, sensor info will be removed.
    (place :I2C {:addr 0x76 :name "BME0" :module :BME280})
    (place :I2C {:addr 0x77 :name "BME1" :module :BME280})
    (place :I2C {:addr 0x0a :name "D6T44L0" :module :D6T44L})    
    (place :I2C {:addr 0x53 :name "ADXL3450" :module :ADXL345})
    (place :I2C {:addr 0x55 :name "D7S0" :module :D7S :axis :auto})
    ;; If no :module is specified, it is queried by the second arity and called the read function defined on that.
    ;; (config :UART {:path "/dev/ttyACM0" :baud-rate 9600}) ;; For Linux/RasPi
    (config :UART {:path "/dev/tty.usbmodem142301" :baud-rate 9600}) ;; For Macintosh Mojave+, Confirm wiht "ls /dev/tty.*" after plugin
    ;; (config :UART {:path "COMx" :baud-rate 9600}) ;; For Windows
    ;; Will be called read function  by :module    
    (place :UART {:name "GPS" :module :GPS})))

(defn boot! []
  (-> (build-device) (ready!)))

;; Start web server if required. Here specify 3000/tcp to listen
;; You can connect GET/Websocket as the following

;; HTTP:
;; * Get sensors
;;  - GET: http://localhost:3000/scan
;; * Read data from sensors
;;  - GET: http://localhost:3000/sensor?ids=BME-0&ids=BME-1

;; WebSocket
;; * Connect
;;  - WS: ws://localhost:3000/ws
;; * Get sensors
;;   [notes: now EDN format is used, JSON will be soon)]
;;  - send {:op :scan} to web socket 
;; * Read data from sensors
;;   [notes: currently, data will be pushed by server by 1 second, user will be able to change the duration near future]
;;  - send {:op :register :v ["BME-0" "BME-1" "my-sensor"]} 

(confirm!)

(boot!)

(srv/start-web-server :ip "0.0.0.0" :port 3000)

;; Comment out to store data to InfluxDB
;; * Options:
;;   :ip <the address of influxdb to be used>
;;   :port <the tcp port of influxdb: defualt 8086>
;;   :user <user to access: default 'root'>
;;   :password <password to access: default 'root'>
;;   :targets <the names of sensors defined above>
;;   :tags <tags used with store data>

;; (start-influxdb-client :ip "your-host" :targets [:BME1 :ADC2] :tags {:loc "Akasaka"})


;; Start repl with file that contains user/password
;; If no option is given, repl is up without asking password
;; If you prefer to prohibit to be used by any user, you can set {:secrets <your filename>} 
;; as the argument of the function shell/repl like

;; (shell/repl {:secrets "secrets"})

;; Here, the file named secrets looks as
;; -- secrets --
;; user password
;;

(shell/repl)



