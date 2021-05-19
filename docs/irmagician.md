# Use irMagician module

Last Updated: 2018-11-27 15:30

Let's use irMagician which can issue and recieve infrared that is also available from Omiya Giken.
The configuration is just like here. That's it.

The :data-path option should be the directory name which contains Ir data captured by irMagician.


```clojure
(on-platform
   (+interface
    (config :UART {:path "/dev/ttyACM0" })
    (place :UART {:name "IR0" :module :IrMagician :data-path "/tmp/json"}))
   (+device :hidraw0
            (config :system {:PCB "1.0.0" :power :5.0})))
```

The __:data-path__ directory is like here

```sh
$ ls /tmp/json
tv-on.json  vol-down.json  vol-up.json

```

Full code to run with built-in web server is as below.
```clojure
(ns groovy-iot
  (:refer-clojure :exclude [read])
  (:require [kikori.core :as k]))

(k/refer-kikori)
(set-log-level! :warn)

(load-module "irmagician")

(on-platform
  (+interface
    (config :UART {:path "/dev/ttyACM0" })
    (place :UART {:name "IR0" :module :IrMagician :data-path "/tmp/json"}))
  (+device :hidraw0
          (config :system {:PCB "1.0.0" :power :5.0})))

(boot!)
(srv/start-web-server :ip "0.0.0.0" :port 3000)
(shell/repl)

```    

Save to file (here, irmagician-sample.clj) and run as below.

(It is necessary to change jar file to the name you are using)

```sh
$ kikori -cp target/kikori-1.5.0-SNAPSHOT.jar run irmagician-sample.clj
```

And access HTTP/GET or WebSockets descrived in README.md

Finally, you can issue the infrared as here.

```sh
$ curl "http://localhost:3000/write?target=ir0&op=tv-on"
```

__tv-on__ is the file name without postfix "json".
That means there is a file as  /tmp/json/tv-on.json .

If you want to capture ir code, you can call __(ir-capture)__ on kikori shell like below.
And save to the file.

```sh
Booted server on  0.0.0.0 : 3000
INFO: No specified secret file

groovy-iot=> (irmagician.core/ir-capture)
```

```sh
groovy-iot=> (irmagician.core/ir-save "/tmp/json/light-on.json")
```

Then, you can access as well as tv-on

```sh
$ curl "http://localhost:3000/write?target=ir0&op=light-on"
```


