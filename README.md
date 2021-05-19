# Kikori

Last updated: 2021/05/19 18:30

This software is a clojure library and server, designed and implemented for Groovy-IoT which can be purchased from Omiya Giken (http://www.omiya-giken.com)

 
By using this, users can control Groovy-IoT functions such as

 - Assign GPx pins to user preferred functions as ADC, DAC, and GPIO.
 - Reading data from sensors connected on I2C
 - Drive pin in/out from/to GPIO
 - Read/Write from/to UART 
 - Getting the data as JSON by Web methods (GET and WebSocket) via the built-in web server
 - Define the new sensors on I2C, UART and GPIO by implementing read/write functions
 - Come with reference implementations for BME280, GPS and increasing more ...
 - Build once and be able to distribute to other OSes

If you are a Debian user, you can use this Debian package. Download and check the section __Run with config__ .
That package has been confirmed to work on also Raspbian. It should work on Ubuntu.

---
## News
- 2021-03-11: Released 1.7.1
- 2021-03-11: SNAPSHOT 1.7.1
- 2019-03-01: Released 1.5.1
- 2019-02-28: Released 1.5.0
- 2019-01-12: Released 1.4.3
- 2018-12-18: Released 1.4.2
- 2018-12-03: Released 1.4.1
- 2018-11-23: Released 1.3.1
- 2018-11-16: Released 1.2.0
- 2018-10-27: Released 1.1.0
- 2018-10-10: Released 1.0.0

---


## Install

### Operating System
 - Debian GNU/Linux stretch/sid
 - Mac OS X 
 - Raspbian (Raspberry/Raspberry Zero)
 - Windows 10 (See Notes)
#### Notes: Currently, it does not support to  build on Windows. Use binary package if you want to use on Windows or copy one which is built on other platforms.

### Requirements
 - JDK 8+

### Install dependencies not on maven repository
Since the library 'purejavahidapi' is not on maven repository, install manually

If no mvn command is on your PC, you can install by apt for Debian/Ubuntu users, or
use homebrew for Mac users. 

On Debian/Ubuntu
```sh
$ sudo apt install maven
```
And on Mac,

```sh
$ brew install homebrew/versions/maven31
```

Next, clone purejavahidapi and start to build .

```sh
$ git clone https://github.com/nyholku/purejavahidapi
$ cd purejavahidapi
$ git checkout 3232ad8
$ mvn install -Dmaven.javadoc.skip=true
```

### Setup kikori
```sh
$ git clone https://gitlab.com/myst3m/kikori
$ cd kikori
$ chmod +x kikori
$ ./kikori setup
```

### Build 
```sh
$ ./kikori build
```

You can see kikori-[version].jar in 'target' sub directory.

---
## Prepare device
Before you use server or library, you have to check the access permission of USB device.
For examples, you find /dev/hidraw* on Debian, then add permission.

```sh
$ sudo chmod 666 /dev/hidraw0
```

And if udev is used, you can create the following file in udev config to change permission automatically.

#### /etc/udev/rules.d/99-hid.rules

```sh
#HIDAPI/hidraw
KERNEL=="hidraw*", ATTRS{busnum}=="1", ATTRS{idVendor}=="04d8", ATTRS{idProduct}=="00dd", MODE="0666"
```

Then, run udev reload
```sh
$ sudo udevadm control -R
```

After that, plug off USB and connect again.

---
## Run
### Linux, Mac OS
Put kikori command to the directory on PATH, then
```sh
$ kikori
```
You can see help message.

To use reference.clj in examples directory, run the following command
(Assumed the jar file is in target directory)

###### Notes: If you downloaded the binary package, replace examples/reference.clj to system.clj,  also replace the path of jar file.


```sh
$ kikori -cp target/kikori-1.0.0.jar run examples/reference.clj
```

If you are using binary package. A command line should be like here.
And also important to add classpath for modules if you want to add or modify files in modules directory. 

```sh
$ kikori -cp modules -cp kikori-1.0.0.jar run system.clj
```


```sh
Booted server on  0.0.0.0 : 3000
INFO: No specified secret file

groovy-iot=>
```

If you use java command by yourself, use as below
```sh
$ java -cp target/kikori-1.0.0.jar kikori.shell examples/reference.clj
```

Also, if you use on Windows, run by java command as above. 
```sh
$ java -cp target/kikori-1.0.0.jar kikori.shell system.clj
```
You can find kikori console,  and also get data through web by accessing 3000/tcp.


## Run with config file
You can run kikori with config file instead of system.clj.
If you are using Debian package, you can check __/etc/kikori/system.conf__, and use it.
Since the script __system.clj__ is in /var/lib/kikori. You can also use if you prefer.
/var/lib/kikori is on the classpath defined in /etc/default/kikori.
You can modify the variable __CLASSPATH__ in the file. 
The file is used as EnvironmentFile in systemd when kikori starts by systemctl.

Tar ball also contains system.conf in __examples/conf__ directory.

The __server__ sub command aims to use as auto run on booting with systemd with conf file. 
Turn __confirm__ and __console__ tags to false when it would be used with auto boot.

When you want to boot server with conf /etc/kikori/system.conf, run command simply as below.
```sh
 $ kikori server 
```

Or you can use systemd as below if you installed Debian package. The conf file of systemd is placed as  /usr/lib/systemd/system/kikori.service.
```sh
 $ sudo systemctl start kikori
```

If you want to use your own jar and config file, use -cp option and give a config file.
```sh
 $ kikori -cp target/kikori-1.0.0.jar server your-system.conf
```

Also, you can use java command as
```sh
 $ java -cp /usr/share/java/kikori-1.0.0.jar kikori.server
```

---
## Read and Write
Kikori server has 2 interfaces as HTTP/GET and WebSocket

### HTTP/GET

#### scan
```sh
$ curl http://localhost:3000/scan
```
You can see the following result.

```sh
{"result":"success","v":[{"id":"85e84eef-45d9-497d-b5dc-21e0d7bc6579","name":"ADC1","module":"ADC1","bus":"GP1"},{"id":"34f758b3-56a9-4712-a309-985de9290fa8","name":"BME0","module":"BME280","bus":"I2C"},{"id":"c3bcfb2d-5e54-4aeb-a7f2-5aefe57c4045","name":"GPS","module":"GPS","bus":"UART"},{"id":"bf004712-13ce-441f-b558-1d0d2aab8db5","name":"my-sensor","module":"RANDOM","bus":"USER"}]}

```

#### sensor
And it is possible to read data by name as below
```sh
$ curl 'http://localhost:3000/sensor?ids=BME0&ids=my-sensor'
```

```sh
{"BME0":{"pressure":1006.9914940794239,"temperature":27.04355950059835,"humidity":60.3187597839653},"my-sensor":0.10540737113959708}
```

#### read
To get raw data from ADC2, you can access as below.
__ADC2__ is the name you used in your clj file.

```sh
$ curl 'http://localhost:3000/read?target=ADC2&type=raw&sampling=128'
```

The parameter __type__ can take __max__, __min__, __rms__, __mean__, __sd__ and __raw__.

```sh
$ curl 'http://localhost:3000/read?target=ADC2&type=mean&sampling=128'
```

#### write
##### Be carefull to use this. If you perform a write operation of DAC/CLKR to the ports that some sensors are connected, it may break your sensors.

For CLKR, 
The parameter __duty__ can take __:0__, __:25__, __:50__ and  __:75__. (Don't miss colon as prefix)

And __frequency__ can take __:24M__,  __:12M__, __:6M__, __:3M__, __:1.5M__, __:750K__ and  __:375K__.

If you prefer to use __divider__ parameter, you can  specify among 
__:2__ , __:4__ , __:8__ , __:16__ , __:32__ , __:64__ , __:128__ .



```sh
$ curl 'http://localhost:3000/write?target=CLKR&duty=:50&frequency=:3M'
```

For DAC, you can use the __value__ parameter. __value__ should be a number in [0 31] (5 bits).
```sh
$ curl 'http://localhost:3000/write?target=DAC2&value=20'
```

And for GPIO, you can use the __value__ parameter as well as DAC, but value should be 0 or 1.

```sh
$ curl 'http://localhost:3000/write?target=GP0&value=0'
```



---

### WebSocket

Kikori supports also WebSocket interface for read at this moment.
If you want to use it, you can connect ws://localhost:3000/ws, and send JSON 
#### Scan
```json
{"op":"scan"}
```

#### Read
```json
{"op":"listen", "v":["TICK0", "my-sensor"]}
```

Server pushes data by 'interval' milliseconds. Default is 1000ms.
If you want to modify this interval, use write operation as followings

```sh
$ curl 'http://localhost:3000/write?target=TICK0&interval=2000'
```

---

## FAQ
##### Kikori says "No device found".
 Kikori uses "/dev/hidrawX" on Linux, please check if there is the device file.
 If you used other library, it may clear the hidrawX. Unplug and plug again.

##### Kikori shows "java.io.IOException: open() failed, errno 13".
 On Linux, udev creates /dev/hidrawX with a permission 600 as default. It should be changed or
 you should add a rule to udev config. See section __Prepare device__.

##### I don't need the confirmation on boot
 You can comment out __(confirm!)__ function in used clj file (system.clj, examples/reference.clj etc..)

##### I want to pack and copy to another PCs.
 You can use 'package' commaond in kikori.
 
```sh
$ kikori package
```

This command starts building of library and make kikori-package-master directory and put all files, then create
kikori-system-master tar ball as kikori-system-master-20180930.tar.gz.

##### After plug off and in on Linux, the UART does not work.
 Since ModemManager detects the UART when ttyACM appears, you should stop that.
 
---

## Supported functions as of 18 Feb. 2019
 - I2C 
 - UART 
 - GPIO 
 - GPS via UART
 - ADC 
 - DAC 
 - CLKR 

## Built-in module for physical devices
 - GPS    (GPS via UART)
 - BME280 (Bosch BME280 for I2C)
 - D6T44L (Omron D6T-44L for I2C)
 - D7S (Omron D7S for I2C)
 - ADXL345 (Analog Devices ADXL345 for I2C)
 - irMagician (irMagician from Omiya Giken)
 - 2JCIE-BU (Omron 2JCIE USB sensor. Supports only via UART and reading the latest data)
 - USB Camera
 - LCD (via Frame Buffer, only on Linux)
 
## Built-in module for virtual functions
 - Ticker
 
## Further Infomation

You can check documents in 'docs' directory.
 - __simple.md__ (overall)
 - __bme280.md__ (to use BME280)
 - __gps.md__ (to use GPS)
 - __console.md__ (console command overall)
 - __develop-module.md__ (overall to develop new sensor)
 - __api.md__ (API overall to define device)
 - __use-multi-devices.md__ (to use multi devices)
 - __develop-in-java.md__ (overall to develop in Java)
 - __irmagician.md__ (to use irmagican on kikori framework)

 
## License

Copyright © 2018 Tsutomu Miyashita

Distributed under the Eclipse Public License either version 1.0 or any later version.
