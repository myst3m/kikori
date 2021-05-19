# Change Log
## [1.7.1] - 2021-05-19
 - Moved from gitlab to git hub
 
## [1.5.2-SNAPSHOT] - 2018-03-06
### Changed
 - Handled when libbluetooth.so is missing on Linux platform.
 
## [1.5.1] - 2018-03-01
### Changed
 - Fixed that the namespace of 'bluetooth' is undefined on non-Linux platform. 

## [1.5.0] - 2018-02-28
### Changed
 - Fixed that device is not identified with new option for +device

## [1.5.0-SNAPSHOT] - 2018-02-02
### Added
 - Add /etc/default/kikori to load classpath by systemd
 - Add /var/lib/kikori and make it be on default classpath
 - Interim support of Web Camera
 - Interim support of the handling on USB plugged-in/off on Linux
 - Add :auto for UART option ':path' to search ttyACM device tied to hidrawX for Linux.
 - Implemented :prefix option in each 'place' function to be added suffix that is from the number 'X' in the name of hid raw device as hidrawX.
 - Seprate the namespace of modules to each one to make the driver to be easy to develop.

### Changed
 - [BREAKING] Change an interface 'config' to 'init' on each module for Java interoperability
 - Add the user 'kikori' to group 'input' 
 - Change the permission of /dev/hidrawX to 660 with the 'input' group by udev rule file
 - systemd and 'kikori' script load /etc/default/kikori to set default user classpath 


## [1.4.3] - 2018-01-12
### Changed
 - Improve GPS handling so as to run on read/listen. CPU consumption will be decreased


## [1.4.3-SNAPSHOT] - 2018-12-28
### Added
 - Add Frame Buffer support on Raspberry Pi
 - Add interim tick support to be aimed at using as clock

### Changed
 - GPS and Tick has control channel in own map

## [1.4.2] - 2018-12-18
### Changed
 - Update Clojure to 1.10.0


## [1.4.1] - 2018-12-03
### Changed
 - No changed from last SNAPSHOT

## [1.4.1-SNAPSHOT] - 2018-11-28
### Changed
 - Add timeout scheme to I2C read interface in order to avoid data loss from I2C

## [1.4.0-SNAPSHOT] - 2018-11-23
### Changed
 - Test implement to lock device for pararell accesess mainly from web
 - Fix that GPIO mode is changed on reading. Web interface supports only OUTPUT mode.
 - Test implement of a module for Omron D7S quake sensor

### Added
 - Add nrep directive in system.conf

## [1.3.1] - 2018-11-22
### Changed
 - Release 1.3.1

## [1.3.1-SNAPSHOT] - 2018-11-21
### Changed
 - Update d6t44l module to separate a function for measuring data


## [1.3.0] - 2018-11-20
### Changed
 - Update d6t44l module to change the data to unsigned int

## [1.3.0-SNAPSHOT] - 2018-11-20
### Changed
 - Reduce trace buffer to reduce memory consumption
 - Update the algorithm of serial port to reduce CPU consumption on Raspberry Pi Zero

## [1.2.1] - 2018-11-20
### Changed
 - Backport the changes between 1.3.0-SNAPSHOT and 1.2.0 around CPU consumption

## [1.2.0] - 2018-11-16
### Changed
 - Released 1.2.0

## [1.2.0-SNAPSHOT] - 2018-11-15
### Changed
 - Improved GPS module so as to reduce CPU usage
 - Set option  so that jline3 does not expand '!' (exclamation mark)
 - Improved the error handling when device is not connected or permission denied
 - [Breaking] Changed the arguments format of enumerate
 - Update comm port library so as to use event listener instead of go-loop (See irmagician library)
 - Add sleep to GPS module to reduce CPU usage
 - Update serial port library 'irmagician' to reduce CPU consumption

## [1.1.0] - 2018-10-27
### Changed
 - Released 1.0.0

## [1.1.0-SNAPSHOT] - 2018-10-26
### Added
 - Interim implementation to store data to InfluxDB

## [1.0.0] - 2018-10-10
### Changed
 - Released 1.0.0

## [1.0.0-beta2] - 2018-10-08
### Changed
 - Update build.boot to include system.clj in also debian package

## [0.8.0-SNAPSHOT] - 2018-10-06
### Changed
 - Replace kikori.shell by using jline3 to handle history and completion even on Windows

## [0.7.6-SNAPSHOT] - 2018-10-01
### Changed
 - Separate HID implementations by OS
 - Linux HID implementation changed to almost synchoronous write and read

## [0.7.5] - 2018-10-01
### Released
 - Windows 10 support by binary package
 
## [0.7.5-SNAPSHOT] - 2018-10-01
### Added
 - Interim own HID implement for Windows 10 to improve response

## [0.7.4-SNAPSHOT] - 2018-9-30
### Added
 - Interim support of Windows 10

## [0.7.3-SNAPSHOT] - 2018-9-28
### Added
 - Added support of ADXL345 
 - Added test implement of parser to be able to boot with config file instead of using clj script

### Changed
 - Fix Websocket interface for UART that it does not push data


## [0.7.2-SNAPSHOT] - 2018-9-22
### Changed
 - Fix CPU much consuming when the device is unplugged before shutdown
 
### Added
 - Added GPIO write interface via Web

## [0.7.0-SNAPSHOT] - 2018-9-16
### Changed
 - [Breaking] start command of 'web' server changed. See examples/reference.clj
 - The all functions defined in modules moves to  kikori.module name space to keep user name space clean
 
### Added
 - Add interfaces to deveop with Java Language
 - Implement irmagician module

## [0.6.0-SNAPSHOT] - 2018-9-12
### Changed
 - Update dependent libraries
 - [Breaking] +device macro changed so as to have device-id as the 1st argument.

### Added
 - Config function: (config :ADC), (config :CLKR) added. 
 - (config :I2C) required back


## [0.5.0-SNAPSHOT] - 2018-9-10
### Changed
 - Internal design has been changed to support options of Vrm for ADC and so on
 - [Breaking] (config :I2C) is no more required. 

## [0.4.0-SNAPSHOT] - 2018-9-07
### Added
- Document 'develop-module.md' has been updated to study the overview of module develop
- Supported DAC Output
- Supported CLCKOUT 
### Changed
- Changed to use sliding buffer for UART in order avoid buffer full when no user connected.
- [Breaking] config function in each module now use options as map.
- [Breaking] Kikori server provides data from GPS by push when you use :listen operation on WebSocket.
- [Breaking] For sensors on I2C, data are served by given interval as the same as 0.3.x series.
- [Breaking] config function in each module now use options as map.
- [Breaking] Not load built-in modules automatically. It needs explicitly call of load-module

```clojure
 ;; Old style
 (config dev :UART :path "/dev/ttyACM0" :baud-rate 9600)

 ;; Now
 (config dev :UART {:path "/dev/ttyACM0" :baud-rate 9600})
```


## [0.3.3] - 2018-9-2
### Released

## [0.3.3-SNAPSHOT] - 2018-9-2
### Changed
- Fixed that data provider for websocket boots each new order even for connected client.


## [0.3.2] - 2018-9-2
### Changed
- Design for UART is changed to use blocking read with timeout to reduce CPU workload.


## [0.3.2-SNAPSHOT] - 2018-9-2
### Added
 - Added Omron D6T-44L module

### Changed
- GPS implement is changed that agent is used to keep record instead of channel


## [0.3.1-SNAPSHOT] - 2018-9-2
### Changed
- GPS implement is changed so as to avoid much CPU consumption even on no request .

## [0.3.0-SNAPSHOT] - 2018-8-31
### Added
- Add syntax sugar for multiple devices

### Changed
- Breaking chage of close interface. Arguments are changed.
- Not send sensor data if value is empty to WebSocket clients
- Fix that much CPU is consumed on  USB plugged off when system open UART.
- Breaking change that i2c-read returns signed values.
- Breaking change of interfaces. All I2C/GPIO/UART/ADC functions are in operations.



## [0.2.0] - 2018-8-30
### Added
- Support BME280
- Partially Support GPS connected to UART
- Web interface scan, listen by HTTP/GET and WebSocket

## [0.1.0] - 2018-08-15
### Added
- Initial upload


[Unreleased]: https://github.com/your-name/hidclj/compare/0.1.1...HEAD
[0.1.1]: https://github.com/your-name/hidclj/compare/0.1.0...0.1.1
