# Use multiple Groovy-IoTs

Last Updated: 2018-09-16 17:30

Kikori can well handle multiple Groovy-IoT.
In order to use them, you can use __on-platform__ macro and __+device__ macro like below.
At this moment, kikori try to bind each config to the actual device in the order of which is found.
__+device__ macro takes the device key to match with found devices.
When you want to know your device-id and device path, kikori helps by providing __scan+__ function
to find it. Run (scan+) on your console. You can find device-id and path.
__+device__ macro trys to match the given keyword or string with found devices.

If you have only 1 device, you can use :any to match it.


```clojure
  (on-platform
   (+device :hidraw0
            (config :UART {:path "/dev/ttyACM0" :baud-rate 9600})
            (bind :GP0 :GPIO)
            (bind :GP1 :ADC1)
            (config :I2C)
            (place :I2C {:addr 0x76 :name "BME0" :module :BME280})
            (place :I2C {:addr 0x77 :name "BME1" :module :BME280})
            (place :I2C {:addr 0x0a :name "D6T44L" :module :D6T44L})
            (place :UART {:name "GPS0" :module :GPS}))
   (+device :hidraw1
            (config :UART :path "/dev/ttyACM1")
            (place :UART {:name "GPS1" :module :GPS})) )
```

Full source with the definition is as below.
```clojure
(ns groovy-iot
  (:refer-clojure :exclude [read])
  (:require [kikori.core :as k]))

(k/refer-kikori)
(set-log-level! :warn)

(load-module "bme280")
(load-module "gps")
(load-module "d6t44l")

(defn register-device []
  (on-platform
   (+device :hidraw0
            (config :UART {:path "/dev/ttyACM0"})
            (bind :GP0 :GPIO)
            (bind :GP1 :ADC1)
            (config :I2C)
            (place :I2C {:addr 0x76 :name "BME0" :module :BME280})
            (place :I2C {:addr 0x77 :name "BME1" :module :BME280})
            (place :I2C {:addr 0x0a :name "D6T44L" :module :D6T44L})
            (place :UART {:name "GPS0" :module :GPS}))
   (+device :hidraw1
            (config :UART {:path "/dev/ttyACM1"})
            (place :UART {:name "GPS1" :module :GPS})) ))

(register-device)

(srv/start-web-server :ip "0.0.0.0" :port 3000)
(shell/repl)
```