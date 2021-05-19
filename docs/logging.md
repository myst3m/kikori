# Logging

Last updated: 2018-09-18 18:30

This document describes how to configure logging .

### Use Log functions

Kikori uses a great Clojure logging library [timbre](https://github.com/ptaoussanis/timbre) , and all functions in the library
can be used. For normally useage, you may use the following 5 functions

 - error
 - warn
 - info
 - debug
 - trace

And also you can change the output target by calling __set-log-output!__ as below.

```clojure
(set-log-output! "my-app.log" :console? true)
```

If __console?__ options is given as true, kikori outputs to the file and console.

And when you want to change the log level, you can use __set-log-level!__.

```clojure
(set-log-level! :warn)
```

Log leve is either __:error__, __:warn__, __:info__, __:debug__ and __:trace__.

Notes: __set-log-*__ functions are in the namespace __kikori.util__, it may need to require
below code.

```clojure
(require '[kikori.util :refer :all])
(set-log-level! :warn)
```

If you have already learned [timbre](https://github.com/ptaoussanis/timbre), you can use
the function provided by timber directly.

```clojure
(require '[taoensso.timbre :as log]
         '[taoensso.timbre.appenders.core :as appenders])
	 
(log/merge-config! {:appenders {:spit (appenders/spit-appender {:fname fname})
                                :println {:enabled? true}}})
```

				  

