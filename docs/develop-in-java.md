# Develop module in Java

Last updated: 2018-11-28 20:20

This document describes how to develop module in Java for new sensors and actuators. 

Kikori engine is being developed in Clojure, because of that, it achieves highly flexible and powerful
environment for developing applications running on JVM. 

After weeks of developing, the core interfaces are getting fixed. Therefore  Kikori starts to provide interfaces those who prefer to use Java from 0.7.x .


### Module interfaces

First, Let's look over the example. This code is a simple driver for Omron D6T44L sensor on I2C.
(CRC is not implemented for ease)


As you can see in the code, Kikori provides 4 classes, 

 - kikori.device.GroovyIoT
 - kikori.interop Edge
 - kikori.interop.I2c
 - kikori.interop.Gpio

and 1 java interface to be implemented

 - kikori.interop.IModule
 

```java
import java.util.*;
import java.io.*;

import kikori.device.GroovyIoT;

import kikori.interop.Edge;
import kikori.interop.I2c;
import kikori.interop.IModule;

public class D6t44lJava implements IModule {
    Byte[] op = {0x4c};
    HashMap<String, List> result = new HashMap<String, List>();

    public HashMap measure(Edge edge) {
    
        edge.write(Arrays.asList(op));
        List raw = edge.read(35);
        
        Byte[] data = new Byte[36];
    
        if (! Objects.isNull(raw)) {
    
            raw.toArray(data);
            ArrayList<Integer> p = new ArrayList<Integer>();
    
            for (int i = 0; i < 17; i++) {
                p.add(data[2*i] + (256 * data[2*i+1]));
            }
    
            result.clear();
            result.put("PTAT", p.subList(0,1));
            result.put("PX", p.subList(1, 17));
        } 
        return result;
    };


    public GroovyIoT config(GroovyIoT dev, Edge edge) {
        System.out.println("Test config");
        return dev;
    };
    
    public HashMap read(Edge edge) {
        System.out.println("Read");
        return measure(edge);
    };

    public HashMap write(Edge edge, List data) {
        HashMap result = new HashMap();
        result.put("result", "success");
        return result;
    };

    public void close(Edge edge) {
         
    };
}


```

The IModule interface has 4 methodos to be implemented.
 - config
 - read
 - write
 - close

__config__ is called when device is opened. It is used mainly for initialize but should be implemented in case it is called for multiple times. This __config__ has 2 arguments. First one is a device info  that sensor is connected. You can check the vendor ID and product Id and so on. The second one is the instance that contains the I2C address and name for I2C, and others for each function. You can use this instance to read and write operations.

__read__ , __write__ and __close__ are no need to explain much. You should implement __read__  for user requests as Web , console and others.
__write__ might be not necessary for almost sensors.  
__close__ also should be implemented if the sensor has to be cared at end point.


### I2C and Pin functions

Kikori provides primitive __read__ and __write__ functions in __Edge__ instance 
to transfer data from/to physical portions.

```java

;; Functions defined in Edge class

java.util.List read(int size)
void           write(java.util.List data)

;; Helpers for the development on REPL 

kikori.device.GroovyIoT  Stub.getFirstDevice()
kikori.interop.Edge      I2c.query(kikori.device.GroovyIoT dev, int addr)
kikori.interop.Edge      Gpio.get(kikori.device.GroovyIoT dev, int index)

```
You can get the Edge instance by using each query function of I2c or Gpio, and call read and write .
If you use Gpio class for query, kikori set the specified pin to GPIO.
Currently, it supports only I2C and GPIO for Java.
ADC, CLKR and DAC interfaces for Java are under development.

#### REPL driven development

If you are using JDK 9+, it may be helpful to use jshell to check the behavior of I2C sensors.
To encourage that, Kikori provides 1 helpful class.

 - kikori.interop.Stub

This class can be used to find physical device.
First, run the jshell with kikori jar file as below

```sh
$ jshell --class-path kikori.0.7.0-SNAPSHOT.jar
```

After you see the prompt, you can import Stub class and I2c class.

```sh
|  Welcome to JShell -- Version 11
|  For an introduction type: /help intro

jshell> import kikori.interop.Stub;

jshell> import kikori.interop.I2c;

```

Then get the first device. Some debug messages might appear. 
You can change the log level as you like.
The levels are from 0 to 4 that each number means __trace__, __debug__ , __info__ , __warn__ and __error__.

Now Let's set __warn__ level.

```sh
jshell> Stub.setLogLevel(3)
```


```sh
jshell> var a = Stub.getFirstDevice();

a ==> kikori.device.GroovyIoT@b708d95a

```

After you've gotten the device class, you can get Edge class by the following way.

```sh
jshell> var edge = I2c.query(a, 0x77);

edge ==> kikori.interop.Edge@228627c8
```

Then, you can control your own sensor/actuator by writing/reading operation with this Edge class.

```sh

jshell> Byte[] op = {0x4c}
op ==> Byte[1] { 76 }

jshell> edge.write(Arrays.asList(op));

jshell> var result = edge.read(35);
result ==> (31 1 21 1 16 1 15 1 12 1 15 1 15 1 14 1 15 1 11  ... 1 11 1 12 1 11 1 11 1 111)

...
```

Regarding to GPIO, you can call as simlar way.

```sh
jshell> import kikori.interop.Gpio;

jshell> var gp1 = Gpio.get(a, 1);
gp1 ==> kikori.interop.Edge@969c6fb2

jshell> Byte[] data = {0x01}
data ==> Byte[1] { 1 }


jshell> gp1.write(Arrays.asList(data))

...

```



### Compile 

To compile your code, you can run as the following command.

```shell
 $ javac -cp kikori-0.7.0-SNAPSHOT.jar D6t44lJava.java

```

And place the class file in some directory on the classpath.


### Use on system

When you download the binary package from [here](https://theorems.co/kikori/kikori-system-latest.tar.gz), you can see system.clj in tar ball.
To load the new module, you can use __load-java-module__ function as below.


```clojure
(load-module "bme280")
(load-module "gps")

(load-java-module "D6t44lJava")
...
```

And use __place__ function with the module name as the same as other sensors.

Notes: Please pay attetion not to use __:D6T44L__ since it is built-in module .

```clojure
    ...
    (place :I2C {:addr 0x77 :name "BME1" :module :BME280})
    (place :I2C {:addr 0x0a :name "D6T44L0" :module :D6t44lJava})
    ...

```

### Run

Finally you can run the server with the this module. Here, it is assumed that the class file of new module is in __modules__ directory.


```sh
$ kikori -cp modules -cp kikori-0.7.0-SNAPSHOT.jar run system.clj
```

Finnaly, if you run __(read-sensors)__ and can see some sensor data, it works fine.

Notes: D6T44L returns data 1 time in several read tries. Therefore, if you don't get any data, it may necessary to retry several times.


### Debug

When you have some troubles, you can call __(tracing!)__ function on Kikori console (not jshell) to watch messages between your application and the devices. 

Since  __(tracing!)__  stores messages and it is sliding buffer, you may see several messages that are already in bufffer.
After you checking, you can call one more to stop it.


```sh

Booted Web server on  0.0.0.0 : 3000
INFO: No specified secret file

groovy-iot=> (tracing!)
18-09-17 11:50:31 galois INFO [kikori.util:54] - Tracer start
#object[clojure.core.async.impl.channels.ManyToManyChannel 0x1c4a8c93 "clojure.core.async.impl.channels
.ManyToManyChannel@1c4a8c93"]                                                                         
groovy-iot=> 18-09-17 11:50:31 galois TRACE [kikori.util:62] - [:h==>d (0x61 0x0 0x0 0x0 0x0 0x0 0x0 0x
0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0
x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 
0x0 0x0 0x0 0x0 0x0)]                                                                                 
18-09-17 11:50:31 galois TRACE [kikori.util:62] - [:h<==d (0x61 0x0 0x12 0x4 0x78 0x12 0x88 0x6f 0xd8 0
x4 0xdd 0x0 0x80 0x32 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x2 0x30 0x10 0xf1 0xd 0xf0 0x0 0x0 0x0 0x30 
0x10 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x0 0x
0 0x0 0x0 0x0 0x0 0x0)]

...


groovy-iot=> (tracing!)
true
groovy-iot=> 18-09-17 11:51:15 galois INFO [kikori.util:61] - Tracer quit

```

__:h==>d__ is the message transferred from host to device. And the other is one of opposite way.
