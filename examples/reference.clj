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
(load-module "2jcie")

(on-platform
 ;; On board

 (+interface
  
  ;; Camera    
  ;;  (config :CAMERA {:index 0 :store "/tmp"})
  ;;  (place :CAMERA {:name "CAM0" })
  
  ;; Physical LCD 
  ;; (config :FB {:path "/dev/fb1" :width 128 :height 128 :view :BME280})

  ;; Omron 2JCIE BU01 (via UART)
  
  ;;;; Need to load FTDI the driver:
  ;;;;  sudo modprobe ftdi_sio
  ;;;;  sudo bash -c "echo '0590 00d4' >  /sys/bus/usb-serial/drivers/ftdi_sio/new_id"

  ;; (config :UART {:path "/dev/ttyUSB0" :baud-rate 115200})
  ;; (place :UART {:name "2JCIE-BU0" :module :BU.2JCIE :id "34:F6:4B:66:47:E7"})
  ;; to add a virtual interface that uses the actual data from UART
  ;; (place :UART {:name "2JCIE-BU1" :module :BU.2JCIE :id "34:F6:4B:66:47:E8"})  
  
  ;; Virtual Screen
  (config :FB {:width 128 :height 128})
  (place :FB {:name "LCD0" })

  ;; Tick
  (place :USER {:name "TICK0" :interval 1000 :module :Tick}))

 ;; Groovy-IoT
 (+device :hidraw
          (config :system {:PCB "1.0.0" :power :5.0})
          (config :ADC {:vrm :VDD})
          (config :DAC {:vrm :VDD})
          (bind :GP0 :GPIO)
          (bind :GP1 :ADC1)
          (bind :GP2 :ADC2)
          (bind :GP3 :DAC2)
          
          (place :GP0 {:name "GP0"})
          (place :GP1 {:name "ADC1"})
          (place :GP2 {:name "ADC2"})
          (place :GP3 {:name "DAC2"})
          
          ;; I2C
          (config :I2C)
          (place :I2C {:addr 0x76 :prefix "BME" :module :BME280})
          (place :I2C {:addr 0x77 :prefix "BME" :module :BME280})
          (place :I2C {:addr 0x0a :prefix "D6T44L" :module :D6T44L})    
          (place :I2C {:addr 0x53 :prefix "ADXL345" :module :ADXL345})
          (place :I2C {:addr 0x55 :prefix "D7S" :module :D7S :axis :auto})
          
          ;; UART
          (config :UART {:path :auto :baud-rate 9600}) 
          (place :UART {:prefix "GPS" :module :GPS}))

 (+device {:os :macosx :id :any}
          (config :system {:PCB "1.0.0" :power :5.0})
          (config :ADC {:vrm :VDD})
          (config :DAC {:vrm :VDD})
          (bind :GP0 :GPIO)
          (bind :GP1 :ADC1)
          (bind :GP2 :ADC2)
          (bind :GP3 :DAC2)
          
          (place :GP0 {:name "GP0"})
          (place :GP1 {:name "ADC1"})
          (place :GP2 {:name "ADC2"})
          (place :GP3 {:name "DAC2"})
          
          ;; I2C
          (config :I2C)
          (place :I2C {:addr 0x76 :name "BME0" :module :BME280})
          (place :I2C {:addr 0x77 :name "BME0" :module :BME280})
          (place :I2C {:addr 0x0a :name "D6T44L0" :module :D6T44L})    
          (place :I2C {:addr 0x53 :name "ADXL3450" :module :ADXL345})
          (place :I2C {:addr 0x55 :name "D7S0" :module :D7S :axis :auto})
          
          ;; UART
          ;; Specify the UART port since :auto is not supported on Mac OS X
          ;;(config :UART {:path "/dev/tty.usbmodem142301" :baud-rate 9600}) 
          ;;(place :UART {:name "GPS0" :module :GPS})
          )

 (+device {:os :windows :id :any}
          (config :system {:PCB "1.0.0" :power :5.0})
          (config :ADC {:vrm :VDD})
          (config :DAC {:vrm :VDD})
          (bind :GP0 :GPIO)
          (bind :GP1 :ADC1)
          (bind :GP2 :ADC2)
          (bind :GP3 :DAC2)
          
          (place :GP0 {:name "GP0"})
          (place :GP1 {:name "ADC1"})
          (place :GP2 {:name "ADC2"})
          (place :GP3 {:name "DAC2"})
          
          ;; I2C
          (config :I2C)
          (place :I2C {:addr 0x76 :name "BME0" :module :BME280})
          (place :I2C {:addr 0x77 :name "BME0" :module :BME280})
          (place :I2C {:addr 0x0a :name "D6T44L0" :module :D6T44L})    
          (place :I2C {:addr 0x53 :name "ADXL3450" :module :ADXL345})
          (place :I2C {:addr 0x55 :name "D7S0" :module :D7S :axis :auto})
          
          ;; UART
          ;; Specify the COM port since :auto is not supported on Windows
          ;;(config :UART {:path "COM3" :baud-rate 9600}) 
          ;;(place :UART {:prefix "GPS" :module :GPS})
          ))

;; Confirmation on boot
(confirm!)

;; Register systems
(boot!)


;; Web server
(srv/start-web-server :ip "0.0.0.0" :port 3000)

;; Console
(shell/repl)

