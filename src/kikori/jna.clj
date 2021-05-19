(ns kikori.jna
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:require [taoensso.timbre :as log])
  (:require [clojure.core.async :as a :refer [go-loop]]
            [clojure.string :as str]
            [clojure.java.io :as io])
  (:import [java.util Properties])  
  (:import [purejavahidapi PureJavaHidApi HidDeviceInfo HidDevice InputReportListener])
  (:import [com.sun.jna Function Native Pointer PointerType Structure Platform]))

(defn get-function [x]
  `(com.sun.jna.Function/getFunction ~(namespace x) ~(name x)))

(defmacro native->fn [return-type fn-symbol]
  `(if-let [f# (try
                 ~(get-function fn-symbol)
                 (catch Throwable e#))]
     (fn [& args#]
       (.invoke f# ~return-type (to-array args#)))
     (fn [& args#]
       (log/warn ~(namespace fn-symbol) "not loaded."))))

(defmacro native->ns [new-ns lib-name fn-specs]
  `(do
     (create-ns '~new-ns)
     ~@(for [[return-type fn-name] (partition 2 fn-specs)]
         `(intern '~new-ns '~fn-name
                  (native->fn ~return-type ~(symbol (str lib-name) (str fn-name)))))
     (the-ns '~new-ns)))

(defn make-cbuf [size]
  (-> (java.nio.ByteBuffer/allocateDirect size)
      (.order java.nio.ByteOrder/LITTLE_ENDIAN)))

(defn pointer [direct-buffer]
  (when direct-buffer
    (Native/getDirectBufferPointer direct-buffer)))


(defrecord Pollfd [fields aligns])
;; (def p (Pollfd. {:fd 0 :events 0 :revents 0} [8 4 4])) ;{} should be  array map

(defn size-map [x]
  (or ({:int (Integer/BYTES)
        :char (Character/BYTES)
        :size_t (Native/SIZE_T_SIZE)
        :long (Native/LONG_SIZE)
        :short (Short/BYTES)
        :bool (Native/BOOL_SIZE)
        :pointer (Native/POINTER_SIZE)
        :float (Float/BYTES)
        :double (Double/BYTES)} x)
      (Native/POINTER_SIZE)))

(defn struct->native-buf [this]
  (reduce (fn [buf [k t]]
            (let [v ((.fields this) k)]
              (condp = t
                :char (.putChar buf v)
                :short (.putShort buf v)
                :int (.putInt buf v)
                :long (.putLong buf v)
                :float (.putFloat buf v)
                :double (.putDouble buf v))))
          (make-cbuf (reduce + (map size-map (.aligns this))))
          (zipmap (keys (.fields this)) (.aligns this))))



(cond
  (Platform/isLinux) (do
                       (native->ns bluetooth bluetooth [Integer hci_get_route
                                                        Integer hci_open_dev
                                                        Integer hci_close_dev
                                                        Integer hci_inquiry
                                                        Integer hci_le_set_scan_enable
                                                        Integer hci_le_set_scan_parameters])
                       (native->ns c c [Integer poll
                                        Integer open
                                        Integer close
                                        Integer write
                                        Integer read
                                        Integer socket
                                        Integer setsockopt
                                        Integer getsockopt])
                       (native->ns udev udev [Pointer udev_new
                         Pointer udev_monitor_new_from_netlink
                         Pointer udev_monitor_unref
                         Pointer udev_device_new_from_syspath
                         Pointer udev_enumerate_new
                         Integer udev_monitor_filter_add_match_subsystem_devtype
                         Integer udev_monitor_enable_receiving
                         Integer udev_monitor_get_fd
                         Void udev_unref
                         Void udev_device_unref
                         Void udev_enumerate_add_match_subsystem
                         Void udev_enumerate_scan_devices
                         Void udev_enumerate_unref
                         Pointer udev_device_get_properties_list_entry
                         Pointer udev_enumerate_get_list_entry
                         Pointer udev_list_entry_get_next
                         Pointer udev_monitor_receive_device
                         Pointer udev_device_get_parent_with_subsystem_devtype
                         Pointer udev_device_get_sysattr_list_entry
                         String udev_device_get_sysattr_value
                         String udev_list_entry_get_name
                         String udev_list_entry_get_value
                         String udev_device_get_syspath
                         String udev_device_get_sysname
                         String udev_device_get_devpath
                         String udev_device_get_driver
                         String udev_device_get_devnode
                         String udev_device_get_action
                                              String udev_device_get_devnode]))

  ;; For dummy to have alias as bt in hci.clj
  :else (create-ns 'bluetooth))





;; (comment
;;   #kikori.device.GroovyIoT{:product-id 221, :vendor-id 1240, :device-id "/dev/bus/usb/001/022", :hid #kikori.hid.LinuxHidDevice{:handle 254, :productString "Microchip Technology Inc. MCP2221 USB-I2C/UART Combo", :vendorId 1240, :productId 221, :deviceId "/dev/bus/usb/001/022", :path "/sys/devices/pci0000:00/0000:00:14.0/usb1/1-6/1-6:1.2/0003:04D8:00DD.000F/hidraw/hidraw0"}, :port-out #object[clojure.core.async.impl.channels.ManyToManyChannel 0x40f15af7 "clojure.core.async.impl.channels.ManyToManyChannel@40f15af7"], :port-in #object[clojure.core.async.impl.channels.ManyToManyChannel 0x4045ab65 "clojure.core.async.impl.channels.ManyToManyChannel@4045ab65"], :sensors {"D6T44L0" #kikori.device.Sensor{:name "D6T44L0", :bus :I2C, :module :D6T44L, :addr 10, :id "702ff20c-726b-47c9-8161-12bb5607aab0", :device-id "/dev/bus/usb/001/022"}}, :uart nil, :path "/sys/devices/pci0000:00/0000:00:14.0/usb1/1-6/1-6:1.2/0003:04D8:00DD.000F/hidraw/hidraw0", :GPIO1 nil, :GPIO2 nil, :GP0 :LED-UART-RX, :GP1 :LED-UART-TX, :GP2 :USBCFG, :GP3 :LED-I2C, :lock #object[java.lang.Object 0x18407ce "java.lang.Object@18407ce"], :product-string "Microchip Technology Inc. MCP2221 USB-I2C/UART Combo"})
