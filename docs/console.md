# Using console

Last updated: 2018-09-16 17:30

Kikori has a console interface by adding __(shell/repl)__ in your script.
Let's see what commands you can run with the following system.clj

```clojure
;; system.clj

(ns groovy-iot
  (:refer-clojure :exclude [read])
  (:require [kikori.core :as k]))

(k/refer-kikori)
(set-log-level! :warn)

(load-module "bme280")
(load-module "gps")
(load-module "d6t44l")


(defn register-device []
  (with-first-device
    (config :UART {:path "/dev/ttyACM0" :baud-rate 9600})
    (config :I2C)
    (bind :GP0 :GPIO)
    (bind :GP1 :ADC1)
    (place :GP1 {:name "ADC1"})    
    (place :I2C {:addr 0x76 :name "BME0" :module :BME280})
    (place :I2C {:addr 0x77 :name "BME1" :module :BME280})
    (place :I2C {:addr 0x0a :name "D6T44L0" :module :D6T44L})    
    (place :UART {:name "GPS" :module :GPS})
    (ready!)))

(register-device)

(srv/start-web-server :ip "0.0.0.0" :port 3000)

(shell/repl)

```

Run as
```sh
$ kikori -cp modules -cp kikori-0.4.0-SNAPSHOT.jar run system.clj
```

Then, you can see console as below
```sh
Booted server on  0.0.0.0 : 3000
INFO: No specified secret file

groovy-iot=>
```

### Commands for sensors

#### List sensors
```sh
groovy-iot=> (list-sensors)
("BME1" "GPIOOUT" "GPS" "D6T44L0" "ADC1")
```

#### Get context of the sensor
```sh
groovy-iot=> (sensor "BME1)
#kikori.device.Sensor{:name "BME1", :addr 119, :module :BME280, :bus :I2C, :id "d85b8916-57b0-4c43-943c-f27c22a8c434", :device-id "/dev/bus/usb/001/005"}
```


#### Read data from sensors
```sh
groovy-iot=> (read-sensors)
{:BME1 {:pressure 1009.8065865875349, :temperature 28.740051815105836, :humidity 63.3634188251792}, :GPIOOUT {:error 1, :msg "no :module defined on sensor or no defined function"}, :GPS {:sentence-type "$GPGSA", :raw "$GPGSA,A,3,25,10,24,193,31,20,,,,,,,6.79,5.31,4.23*33\r"}, :D6T44L0 {:PTAT 298, :PX (302 301 302 301 301 301 299 300 301 301 301 300 301 302 300 301), :PEC -52, :valid false}, :ADC1 205}

```

### Commands for modules
#### Load module
When you develop your module and place it the directory that is on class path.
Use this command to load.


```sh
groovy-iot=> (load-module "your-new-module-on-classpath")
```

#### Load module developed in Java
When you want to load Java class, Use this command to load.


```sh
groovy-iot=> (load-java-module "you-own-class")
```


#### Close/Open devices
When you want to close all Groovy-Iots, use shutdown command.
```sh
groovy-iot=> (shutdown)
```

And after that, you can open again with __(register-device)__ defined in system.clj

##### Notes: Since this function is defined in user file (system.clj), it is not core function.

```sh
groovy-iot=> (register-device)
```

#### Change Log level
You can change the log level to :error,  :warn, :info, :debug and :trace.
If you cange to :trace, you can see the USB packet trace.
Notes: __set_log_level!__ is in namespace kikori.core used as __k__.
```sh
groovy-iot=> (set-log-level! :info)
```

#### Change Log output target
You can change the log output target to file. 

```sh
groovy-iot=> (set-log-output! "app.log")
```

If you want to watch also on console, you can add the option __console? true__.
```sh
groovy-iot=> (set-log-output! "app.log" :console? true)
```

Finally, if you want to stop logging to file, you can just call without option.

```sh
groovy-iot=> (set-log-output!)
```




#### Quit console
```sh
groovy-iot=> (quit)
```


### Other useful commands
#### scan Groovy-IoT 
This command works on Linux and partially on Mac (ACM not appears). Not confirmed on Windows

```sh
groovy-iot=> (scan+)
/dev/bus/usb/001/004 : hidraw0 : ttyACM0 : (0xa 0x77)
```

### Restriction 

If no option is given, __repl__ is up without asking password.
If you prefer to prohibit  any user to use console, you can set :secrets option
as the argument of the function shell/repl in your system.clj.

```clojure
(shell/repl {:secrets "secrets"})
```