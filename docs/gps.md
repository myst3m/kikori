# Using GPS module

Last Updated: 2018-09-18 13:30

You can use GPS connected to UART on Groovy-IoT.

Notes: It may be necessary to change baud rate for your device.

```clojure
(with-first-device
    (config :UART {:path "/dev/ttyACM0" :baud-rate 9600})
    (place :UART {:name "GPS" :module :GPS})
    (ready!)
```


Full code to run with built-in web server is as below.
```clojure
(ns gps-sample
  (:refer-clojure :exclude [read])
  (:require [kikori.core :as k]))

(k/refer-kikori)
(set-log-level! :warn)

(load-module "gps")

(defn register-device []
  (with-first-device
    (config :UART {:path "/dev/ttyACM0" :baud-rate 9600})
    (place :UART {:name "GPS" :module :GPS})
    (ready!)))

(register-device)
(srv/start-web-server :ip "0.0.0.0" :port 3000)

```    

Save to file (here, gps-sample.clj) and run as below.
(It is necessary to change jar file to the name you are using)

```sh
$ kikori -cp target/kikori-0.2-SNAPSHOT.jar run gps-sample.clj
```

And access HTTP/GET or WebSockets descrived in README.md