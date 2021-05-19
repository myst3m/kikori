# First step to define sensors on Groovy-IOT

Last Updated: 2018-09-11

This document indicates the first step to define sensors.
Here, see the code of examples/simple.clj

```clojure
(ns simple
  (:refer-clojure :exclude [read])
  (:require [kikori.core :as k]))

(k/refer-kikori)
(set-log-level! :warn)

(defn register-device []
  (with-first-device
    (bind :GP0 :GPIO)
    (bind :GP1 :ADC1)
    (place :GP1 {:name "ADC1"})        
    (ready!)))

(register-device)
(srv/start-web-server :ip "0.0.0.0" :port 3000)
```

Sincd the beginning 6 lines are just for preparation, you can learn later.
The important part is from next line.

kikori library provides 'with-first-device' macro to make easy.
You can configure the Groovy-IoT device which system finds first by using this macro.
From next line, each function is called for the device.

### config
'config' function configures the component of Groovy-IoT as I2C and UART.
If you want to use I2C, you need to call that with :I2C.
For about UART, it is necessary use :UART with :path as below
```clojure
(config :UART :path "/dev/ttyACM0")
```

### bind
`bind' function assigns GP pins to functions which are capable each pins
Currently, it can be used with :GPIO, :SSPND and :LED-UART-RX for :GP0, and :GPIO, :CLOCK-OUTPUT, :ADC1 :LED-UART-TX and :INTERRUPT-DETECTION for :GP1.

You can check from console as bellow

```sh
groovy-iot=> (dev/specs (*d))
{:pins #{:GP1 :GP0}, :designation {:GP0 {0 :GPIO, 1 :SSPND, 2 :LED-UART-RX}, :GP1 {0 :GPIO, 1 :CLOCK-OUTPUT, 2 :ADC1, 3 :LED-UART-TX, 4 :INTERRUPT-DETECTION}}, :capabilities {:GP0 #{:GPIO}, :GP1 #{:ADC1 :GPIO}}, :GPIO {:direction {:OUT 0, :IN 1}}, :bus #{:USER :GP1 :UART :I2C :GP0 :GPIO}}                   
```

### place
'place' function define sensors to this Groovy-IoT.
If first argument is PIN name (:GP0, GP1), read/write  will be called for the functions defined by
'bind'. In this example, read/write for ADC1 will be called.

### ready!
'ready!' will submit actual HID code to Groovy-IoT chip. This function must be called to affect.


### web/start

Finnaly, built-in web server is in the 'web' namespace, you can call 'web/start' with
ip address and port you prefer.

