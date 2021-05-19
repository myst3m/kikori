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

;; Rev 0.3.1

(ns groovy-iot
  (:refer-clojure :exclude [read])
  (:require [kikori.core :as k]))

(k/refer-kikori)
(set-log-level! :warn)

(load-module "bme280")
(load-module "gps")
(load-module "d6t44l")
(load-module "d7s")
(load-module "adxl345")
(load-module "irmagician")
(load-module "tick")

;; ready! is called automatically.
;; Device is passed  after sorting by device-id that is defined as "/dev/bus/usb/001/021".

(defn register-device []
  (on-platform
   (+interface
    (config :FB {:path "/dev/fb1" :width 128 :height 128})
    (place :FB {:name "LCD0"})
    (place :USER {:name "TICK0" :interval 1000 :module :Tick}))
   (+device :hidraw0
            (config :I2C)    
            (bind :GP0 :GPIO)
            (bind :GP2 :ADC2)
            (place :GP0 {:name "GP0"})
            (place :GP2 {:name "ADC2"})
            (config :UART {:path "/dev/ttyACM0"})
            (place :UART {:name "GPS0" :module :GPS})
            (place :I2C {:addr 0x76 :name "BME0" :module :BME280})
            (place :I2C {:addr 0x77 :name "BME1" :module :BME280})
            (place :I2C {:addr 0x0a :name "D6T44L" :module :D6T44L}))
   (+device :hidraw1
            (config :I2C)
            (config :UART :path "/dev/ttyACM1")
            (place :UART {:name "GPS1" :module :GPS}))))

(register-device)

(srv/start-web-server :ip "0.0.0.0" :port 3000)

(shell/repl)

