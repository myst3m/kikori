# Use Frame Buffer

Last updated: 2018-12-24 09:30

If you want to use LCD using Linux Frame Buffer (dev/fbX), you can use built-in driver.
In this examples, we assmue the resolution of LCD is 128x128 and is connected via SPI on Raspberry Pi.

##### Notes: If you don't have any phisical LCD or use non-Linux OS, you can use only virtual frame buffer. Skip setup,  and run kikori shell or java command directly  with  sample system.clj.

## Setup device

### Setup SPI

First, enable SPI by raspi-config

```sh
 $ sudo raspi-config
```
Enable __[Interfacing Options] - [SPI]__

### Setup Frame Buffer

Add the 2 modules to /etc/modules
```sh
$ cat /etc/modules
# /etc/modules: kernel modules to load at boot time.
#
# This file contains the names of kernel modules that should be loaded
# at boot time, one per line. Lines beginning with "#" are ignored.

i2c-dev
spi-bcm2835
fbtft_device

```
And also , you may have to configure the specific option for your LCD. 
Here is the sample for waveshare 1.44" tft lcd HAT .

```sh
$ cat /etc/modprobe.d/fbtft.conf
options fbtft_device name=fb_st7735r busnum=0 gpios=reset:27,dc:25,cs:8,led:24 speed=40000000 bgr=1 fps=60 custom=1 height=128 width=128 rotate=180
```

Then reboot system and confirm the modules are loaded and appear __/dev/fb1__

```sh
$ sudo lsmod | egrep 'spi|fbtft_device'
fbtft_device           49152  0
fbtft                  45056  2 fbtft_device,fb_st7735r
spi_bcm2835            16384  0

$ ls /dev/fb1
/dev/fb1
```


###  Configure LCD in system.clj
If you are using kikori shell with system.clj, write down the following code and run.
And remove :path on config, if you don't have a physical LCD. Virtual screen will be used.
Refer to the code described in next section.

```clojure
(on-platform
  (+interface
    (config :FB {:path "/dev/fb1" :width 128 :height 128})
    (place :FB {:name "LCD0"})))

```

If you are using /etc/kikori/system.conf from deb package, add the corresponding config to it.


### Samples of system.clj

#### For Linux

```clojure
(ns groovy-iot
  (:refer-clojure :exclude [read])
  (:require [kikori.core :as k]))

(k/refer-kikori)
(set-log-level! :warn)

(defn register-device []
  (on-platform
   (+interface
    (config :FB {:path "/dev/fb1" :width 128 :height 128})
    (place :FB {:name "LCD0"}))
   (+device :hidraw0
            (config :I2C)    
            (bind :GP0 :GPIO)
            (bind :GP1 :ADC1)
            (place :GP0 {:name "GP0"}) 
            (place :GP2 {:name "ADC2"})  
            (config :UART :path "/dev/ttyACM0")
            (place :UART {:name "GPS0" :module :GPS})
            (place :I2C {:addr 0x76 :name "BME0" :module :BME280})
            (place :I2C {:addr 0x0a :name "D6T44L" :module :D6T44L}))
   (+device :hidraw1
            (config :I2C)
            (bind :GP0 :GPIO)
            (bind :GP1 :ADC1)            
            (config :UART :path "/dev/ttyACM1")
            (place :UART {:name "GPS1" :module :GPS})
            (place :I2C {:addr 0x77 :name "BME1" :module :BME280}))))

(register-device)

(srv/start-web-server :ip "0.0.0.0" :port 3000)

(shell/repl)
```
#### For Mac with only Virtual LCD

__Notes__: Be carefull no :path is give in FB config.

```clojure
(ns groovy-iot
  (:refer-clojure :exclude [read])
  (:require [kikori.core :as k]))

(k/refer-kikori)
(set-log-level! :warn)

(defn register-device []
  (on-platform
   (+interface
    (config :FB {:width 128 :height 128})
    (place :FB {:name "LCD0"}))
   (+device :any
            (config :I2C)    
            (bind :GP0 :GPIO)
            (bind :GP2 :ADC2)
            (place :GP0 {:name "GP0"}) 
            (place :GP2 {:name "ADC2"})  
            (config :UART {:path "/dev/tty.usbmodem14021"})
            (place :UART {:name "GPS0" :module :GPS})
            (place :I2C {:addr 0x76 :name "BME0" :module :BME280})
            (place :I2C {:addr 0x0a :name "D6T44L" :module :D6T44L}))
  
(register-device)

(srv/start-web-server :ip "0.0.0.0" :port 3000)

(shell/repl)
```

### Display a picture on LCD

URL of PNG image file can be given by POST

```sh
$ curl -X POST http://192.168.11.2:3000/write --data-urlencode 'target=LCD0' --data-urlencode "url=https://1.bp.blogspot.com/-TJMjezZzMmo/XBRfBTQnZdI/AAAAAAABQ1w/pGtOUseQF78niug7oF2mPH6UH3aeNI8jQCLcBGAs/s800/gengou_happyou_blank.png"
```

Also you can give __rotate__ option. That can have 90, 120, 180, 270 as an arity like here.

```sh
$ curl -X POST http://192.168.11.2:3000/write --data-urlencode 'target=LCD0' --data-urlencode "url=https://1.bp.blogspot.com/-TJMjezZzMmo/XBRfBTQnZdI/AAAAAAABQ1w/pGtOUseQF78niug7oF2mPH6UH3aeNI8jQCLcBGAs/s800/gengou_happyou_blank.png" --data-urlencode 'rotate=90'
```


SVG text data are also able to be given as below.

```sh
$ curl  -X POST http://localhost:3000/write --data-urlencode 'target=LCD0' --data-urlencode "data=$(cat test.svg)"
```

If you want to paint screen black, you can give __clear__ option.

```sh
$ curl  -X POST http://localhost:3000/write --data-urlencode 'target=LCD0' --data-urlencode "clear=true"
```

You can check data transferred to (physical/virtual) screen by read.
The data is transferred to client as SVG format. You can ofcource use web browser to see the image.


```sh
$ curl  -X GET http://localhost:3000/read --data-urlencode 'target=LCD0'
```

### Disable Blank off on LCD

If you are using lightdm, you can modify the /etc/lightdm/lightdm.conf as below

```sh
$ cat /etc/lightdm/lightdm.conf
...

[Seat:*]
xserver-command=X -s 0 dpms

...

```