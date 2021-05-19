# First step to develop own module 

Last updated: 2018-09-16 17:30

In order to support new sensors, you can develop your own module.

##### You can download the binary used in this document from [here](https://theorems.co/kikori/kikori-system-0.4.0-SNAPSHOT.tar.gz)




### Your first module
Let's develop the first module to return random number.

Create file that contains the following code, and save it to system.clj

```clojure
;;; system.clj

(ns random
  (:refer-clojure :exclude [read])
  (:require [kikori.core :as k]))

(k/refer-kikori)
(set-log-level! :warn)

(defmodule RANDOM
  (read [_]
        (rand)))

(defn register-device []
  (with-first-device
    (place :user {:name "my-sensor" :module :RANDOM})
    (ready!)))

(register-device)
(srv/start-web-server :ip "0.0.0.0" :port 3000)
```

#### 1. Define functions by 'defmodule'
__defmodule__ is the macro that define new module. This module name can be looked up by the name
whichi is specified as the second arity.

In this example, only __read__ is defined, but __config__, __write__ and __close__ are also definable.


#### 2. Link the sensor info to device by __place__
__place__ is used to register sensor info on Groovy-IoT. The __place__ has 2 arguments.
The first one is the keyword of bus. The available buses are as below.
 - :I2C
 - :UART
 - :GP0
 - :GP1
 - :user

The second argument is the sensor information that should contains for each module specified by :module.
If :module is not provided, the name of :bus is used instead.

#### 3. Let's run with 1 main file
```sh
$ kikori -cp kikori-0.4.0-SNAPSHOT.jar run system.clj
```

#### 4. Separate module definition to another file
Here, Let's separate the definition of module to another file as below.

```clojure
;; random.clj

(defmodule RANDOM
  (read [_]
        (rand)))
```

Then,  remove module defintion and add 1 function `(load-module "random")` in system.clj




```clojure
;; system.clj

(ns random
  (:refer-clojure :exclude [read])
  (:require [kikori.core :as k]))

(k/set-log-level! :warn)
(k/refer-kikori)


(load-module "random")

(defn register-device []
  (with-first-device
    (place :user {:name "my-sensor" :module :RANDOM})
    (ready!)))

(register-device)
(web/start :ip "0.0.0.0" :port 3000)

```

Then, files are placed like here
```sh
./
├── kikori-0.4.0-SNAPSHOT.jar
├── modules
│   └── random.clj
└── system.clj

```

Finally, we can run the system.clj. Please pay attention to the class path is added !
The class path should be added to search your module.

```sh
$ kikori -cp modules -cp kikori-0.4.0-SNAPSHOT.jar run system.clj
Booted server on  0.0.0.0 : 3000
INFO: No specified secret file

system=>
```
Then check by __(list-sensors)__ if your sensor appears.

```sh
system=> (list-sensors)
("my-sensor")
```

If you can find your sensor, read the value.
```sh
system=> (read-sensors "my-sensor") 
{:my-sensor 0.8400287846065536}
```
or You can see data of all sensors on device if you run without argument.
(In this case, since no other sensor on the device, only 'my-sensor' appears)

```sh
system=> (read-sensors)
{:my-sensor 0.8400287846065536}
```

### 5. Wrap up
Here, you studied
 - Where you place the module definition (modules/random.clj)
 - What function should be added in main function (system.clj)
 - What command you should run (required __-cp modules__)
 
Next step is how you develop more complex config/read/write/close function for your sensors.



	