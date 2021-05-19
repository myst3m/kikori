# APIs for device configuration 

Last updated: 2018-12-22 15:00

This document describes how to configure devices on Kikori.

### Find devices
When the devices are connected to PC ,Raspberry Pi and Mac , Kikori tries to find them as USB HID devices. 
After the query and has been found successfully, you can use both 2 interfaces to identify them.

 - __with-first-device__
 - __on-platform__
   * +device

The 1st interface is useful when you are using just 1 device. Kikori provides the first device context to you after the enumeration. 
The Example 1 shows how to use __with-first-device__. 

__Example 1:__
```clojure
(with-first-device
  (config :system {:PCB "1.0.0" :power :3.3})
  (bind :GP0 :GPIO)
  (bind :GP1 :ADC1)
  (place :GP1 {:name "ADC1"})        
  (ready!)
```
When you use __with-first-device__, you have to call __ready!__ to perform on physical device.


And next, go to Example 2.
You can define each device as Example 2 by declaring with device ID.
If you use __on-platform__, you don't need to call __ready!__ .

__Example 2:__
```clojure
(on-platform
  (+device :hidraw0
    (config :system {:PCB "1.0.0" :power :3.3})
    ...)
  (+device :hidraw1
    ...))
  
```

Since the device ID is the defined by each OS, 
you can check on console by calling __scan+__ function like below.
The 1st value of the response is the actual device ID but you can use the second one to identify
After checking device ID, you can give it as keyword,  then able to configure by the following code.


```clojure
$ kikori -cp kikori-0.7.0-SNAPSHOT.jar run
INFO: No specified secret file

kikori.shell=> (scan+)
/dev/bus/usb/001/004 : hidraw0 : ttyACM0 : (0x77)
```

### Configure devices
From here, Let's see each function to configure.
Kikori provides basically 3 interfaces to configure.

- config
- bind
- place

Each interface has diffrent options by each configurable targets, like :ADC

### config <target> [option]
__config__ interface performs mainly system-wide configurations.

The target can be specified among the followings

- :system
- :ADC
- :DAC
- :CLKR
- :UART
- :I2C
- :FB

And each of configurable options are here.

    :system {:PCB <string>,  :power < :3.3 | :5.0 > }

    :ADC {:vrm < :OFF | :1.024 | :2.048 | :4.096 >}

    :DAC {:vrm < :OFF | :1.024 | :2.048 | :4.096 >
          :value <number> }

    :CLKR {:duty < :0 | :25 | :50 | :75 >
           :divider  < :2 | :4 | :8 | :16 | :32 | :64 | :128 >
           :frequency < :24M | :12M | :6M | :3M | :1.5M | :750K | :375K >}

    :UART {:path <string> 
           :baud-rate <number> }

    :I2C {:speed <number>} ;; default is 100000 (100k)

    :FB {:width <number>  ;; default is 128
         :height <number> ;; default is 128
         :path <string> }
	 


Each :divider value means the divisor of 48MHz. 
If both :divider and :frequency are described, :divider will be used.


__Example 3:__

```clojure
(on-platform
  (+device :hidraw0
    (config :system {:PCB "1.0.0" :power :3.3})      
    (config :I2C)
    (config :UART {:path "/dev/ttyACM0"})))
```

### bind <target> <function>
__bind__ interface is mainly the function to configure each PIN.

Available targets are 

 - :GP0
 - :GP1
 - :GP2
 - :GP3

Each pin can be set as below

    :GP0 :GPIO

    :GP1 < :GPIO | :ADC1 | :CLKR >

    :GP2 < :GPIO | :ADC2 | :DAC1 >

    :GP3 < :GPIO | :ADC3 | :DAC2 >


__Example 4:__
```clojure
    (on-platform
      (+device :hidraw0
        (config :system {:PCB "1.0.0" :power :3.3})
        (config :ADC {:vrm :OFF})
        (bind :GP1 :ADC1)))

```

### place <bus or pin name> <sensor info>

__place__ interface mainly registers the sensor or pin-function to the system.
The sensor info should have the own name to idnetify and the module name to control.
Module name is defined as keyword as :BME280 by __defmodule__ .


Available bus/pin names are

 - :I2C
 - :UART
 - :GP0
 - :GP1
 - :GP2
 - :GP3
 - :user
 
If pin name is specified, system uses the function module defined by __bind__ .
Also you can use virtual bus named __:user__ instead of physical bus or pin to use your own module.

    :I2C {:name <string>
          :addr <number>
          :module <keyword> }

    :UART {:name <string>
           :module <keyword>}

    :GP0 {:name <string> }

    :GP1 {:name <string> }

    :GP2 {:name <string> }

    :GP3 {:name <string> }

    :user {:name <string> 
           :module <keyword> }

__Example 5:__

In this example, you can get the data from GP1 by using ADC function.

```clojure
(on-platform
  (+device :hidraw0
    (config :system {:PCB "1.0.0" :power :3.3})
    (config :ADC {:vrm :OFF})
    (bind :GP1 :ADC1)
    (place :GP1 {:name "adc1-data"})))
```

__Example 6:__

In this example, you can get sensor data by using built-in BME280 module.

```clojure
(on-platform
  (+device :hidraw0
    (config :system {:PCB "1.0.0" :power :3.3})
    (config :I2C)
    (place :I2C {:name "BME0" :addr 0x77 :module :BME280})))
```

__Example 7:__
If you use Frame Buffer on Rasberry Pi, add +interface with config/place

```clojure
(on-platform
  (+interface
    (config :FB {:path "/dev/fb1" :width 128 :height 128})
    (place :FB {:name "LCD0"})))

```


__Example of ful code__

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
   (+interface
     (config :FB {:path "/dev/fb1" :width 128 :height 128})
     (place :FB {:name "LCD0"}))
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
            (config :UART :path "/dev/ttyACM1")
            (place :UART {:name "GPS1" :module :GPS})) ))

(register-device)

(srv/start-web-server :ip "0.0.0.0" :port 3000)
(shell/repl)
```
