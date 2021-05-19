;; *   Kikori
;; *
;; *   Copyright (c) Tsutomu Miyashita. All rights reserved.
;; *
;; *   The use and distribution terms for this software are covered by the
;; *   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; *   which can be found in the file epl-v10.html at the root of this distribution.
;; *   By using this software in any fashion, you are agreeing to be bound by
;; * 	 the terms of this license.
;; *   You must not remove this notice, or any other, from this software.

(ns kikori.shell
  (:refer-clojure :exclude [read])
  (:gen-class)
  (:import (clojure.lang Compiler Compiler$CompilerException
                         LineNumberingPushbackReader RT))
  (:import [purejavahidapi.linux CLibrary UdevLibrary])
  (:require [clojure.main :as m])
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:require [clojure.tools.cli :refer [parse-opts]])
  (:require [clojure.core.async :as a :refer [mult tap chan <! >! put! >!! <!! go go-loop alts! close! pipe poll! alts!! timeout thread]])
  (:require [kikori.core :refer :all]
            [kikori.device :as dev]
            [kikori.check :refer [scan]]
            [kikori.chip.mcp2221a :as mc]
            [kikori (module :refer :all) operation (command  :refer [scan+])]
            [kikori.chip.mcp2221a :refer [get-sram-settings parse*]]
            [kikori.util :as util :refer :all]
            [kikori.server :refer [stop-web-server start-web-server]]
            [kikori.facade :refer :all]
            [kikori.parser :refer [parse]]
            [kikori.hid :as hid]
            [kikori.graphic :as g])

  (:require [taoensso.timbre :as log]
            [taoensso.timbre.appenders.core :as appenders])
  (:require [org.httpkit.client :as http])
  (:require [clojure.pprint :refer [pprint]])
  (:require [irmagician.core :refer :all])
  (:import [org.jline.reader LineReader LineReaderBuilder LineReader$Option]
           [org.jline.terminal Terminal TerminalBuilder Terminal$SignalHandler]
           [org.jline.builtins Completers$FileNameCompleter]
           [org.jline.reader.impl.completer ArgumentCompleter StringsCompleter]
           [org.jline.reader.impl DefaultParser]
           [org.jline.reader EndOfFileException UserInterruptException]
           [org.jline.reader Completer Candidate]
           [org.jline.reader.impl.history DefaultHistory]
           [java.nio.file Paths])
  (:import [org.freedesktop.dbus.connections.impl DBusConnection DBusConnection$DBusBusType]
           [org.freedesktop.dbus.interfaces DBusInterface]
           [org.freedesktop.dbus.connections.impl DirectConnection]
           [org.freedesktop DBus]
           [org.freedesktop.dbus.interfaces ObjectManager]
           [org.bluez GattManager1 LEAdvertisingManager1]
           [com.github.hypfvieh.bluetooth DeviceManager]
           [com.github.hypfvieh.bluetooth.wrapper
            BluetoothAdapter BluetoothDevice BluetoothGattCharacteristic
            BluetoothGattService]))


;; config: call on bus
;; place : call on module

(defn- terminal []
  (on-platform
   (+device :loopback
            (config :UART {:path "/dev/tty5"})
            (place :UART {:module :Terminal :name "term"}))))

(defn tick []
  (on-platform
   (+interface
    (place :USER {:name "TICK0" :interval 100 :module :Tick}))
   (+interface
    (place :USER {:name "TICK1" :interval 100 :module :Tick}))
   (+device :hidraw1
            (config :I2C))))

(defn ble-2jcie []
  (on-platform
   (+interface
    (config :HCI)
    (place :HCI {:name "JC0"}))))

(defn omron []
  (on-platform
   (+interface
    (config :UART {:path "/dev/ttyUSB0" :baud-rate 115200})
    (place :UART {:name "2JCIE-BU0" :module :BU.2JCIE :id "34:F6:4B:66:47:E7"})
    (place :UART {:name "2JCIE-BU1" :module :BU.2JCIE :id "34:F6:4B:66:47:E8"}))
   (+device :hidraw0
            (config :system {:PCB "1.0.0" :power :5.0})
            (config :I2C)
            (config :UART {:path :auto})
            (place :UART {:name "GPS" :module :GPS})
            (place :I2C {:addr 0x76 :prefix "BME" :module :BME280})
            (place :I2C {:addr 0x77 :prefix "BME" :module :BME280}))))

(defn bme280 []
  (on-platform
   (+device :hidraw0
            (config :I2C)
            (place :I2C {:addr 0x76 :prefix "BME0" :module :BME280})
            (place :I2C {:addr 0x77 :prefix "BME1" :module :BME280}))))

(defn ble []
  (on-platform
   (+interface
    (config :BLE {:index 0})
    (place :BLE {:name "BLE0" }))))

(defn camera []
  (on-platform
   (+interface
    (place :CAMERA {:name "CAM0" :index 0 :store "/tmp" :width 1280}))))

(defn lcd []
  (on-platform
   (+interface
    (config :FB {:path "/dev/fb1" :width 128 :height 128 :view :BME280})
    (place :FB {:name "LCD0" }))))


(defn standard
  "Standard device config for Linux"
  []
  (on-platform
   (+interface
    (config :UART {:path "/dev/ttyACM1" })
    (place :UART {:name "IR0" :module :IrMagician :data-path "/tmp/json"}))
   (+device :hidraw
            (config :system {:PCB "1.0.0" :power :5.0})
            (config :I2C)
            (config :UART {:path :auto})
            (config :ADC {:vrm :2.048})
            (bind :GP0 :GPIO)
            (bind :GP1 :GPIO)
            (bind :GP2 :ADC2)
            (bind :GP3 :GPIO)
            (place :GP0 {:name "LED0.0"})
            (place :GP2 {:name "ADC2.0"})
            (place :I2C {:addr 0x76 :name "BME0" :module :BME280})
            (place :I2C {:addr 0x77 :name "BME1" :module :BME280})
            (place :I2C {:addr 0x0a :name "D6T44L0" :module :D6T44L})
            (place :I2C {:addr 0x53 :name "ADXL3450" :module :ADXL345})            
            (place :UART {:name "GPS0.0" :module :GPS}))))


(defn serial []
  (on-platform
   (+device :hidraw
            (config :system {:PCB "1.0.0"})
            (config :UART {:path :auto})
            (place :UART {:prefix "serial"}))))

(defn dac []
  (on-platform
   (+device {:os :linux :id :hidraw0}
            (config :system {:PCB "1.0.0" :power :5.0})
            (config :ADC {:vrm :VDD})
            (config :DAC {:vrm :VDD})
            (bind :GP0 :GPIO)
            (bind :GP1 :ADC1)
            (bind :GP2 :DAC1)
            (bind :GP3 :DAC2)
            
            (place :GP0 {:name "GP0"})
            (place :GP1 {:name "ADC1"})
            (place :GP2 {:prefix "DAC1"})
            (place :GP3 {:prefix "DAC2"})
            )))

(defn gpio1 []
  (on-platform
   (+device :hidraw0
            (config :system {:PCB "1.0.0"})
            (bind :GP0 :GPIO)
            (bind :GP1 :ADC1)
            (place :GPIO2 {:name "GPIO1"}))))

(defn zsmpb02b []
  (on-platform
   (+device :hidraw0
            (config :I2C)            
            (place :I2C {:addr 0x70 :name "2SMPB0" :module :ZSMPB02B}))))

(defn d7s []
  (on-platform
   (+device :hidraw0
            (config :I2C)            
            (place :I2C {:addr 0x55 :name "D7S0" :module :D7S :axis :auto :threshold :H}))))

(defn d6t44l []
  (on-platform
   (+device :hidraw0
            (config :I2C)            
            (place :I2C {:addr 0x0a :name "D6T44L0" :module :D6T44L}))))

(defn gps
  "GPS config"
  []
  (on-platform
   (+device :hidraw
            (config :system {:PCB "1.0.0" :power :5.0})
            (config :UART {:path :auto})
            (place :UART {:prefix "GPS" :module :GPS}))))

(defn gps2
  "GPS config"
  []
  (on-platform
   (+device :hidraw
            (config :system {:PCB "1.0.0" :power :5.0})
            (config :UART {:path :auto})
            (place :UART {:prefix "GPS" :module :GPS}))))

(defn irmagician
  "irMagician config, use :op for write as (write (conj sensor {:op \"tv-on\"}))"
  []
  (on-platform
   (+interface
    (config :UART {:path "/dev/ttyACM0" })
    (place :UART {:name "IR0" :module :IrMagician :data-path "/tmp/json"}))
   (+device :hidraw0
            (config :system {:PCB "1.0.0" :power :5.0})
            (bind :GP1 :GPIO)
            (place :GP1 {:name "B0"}))))



(defn- full []
  (on-platform
   (+device :hidraw
            (config :system {:PCB "1.0.0" :power :5.0})
            (config :I2C)
            (config :UART {:path :auto})
            (config :ADC {:vrm :VDD})
            (bind :GP0 :GPIO)
            (bind :GP1 :GPIO)
            (bind :GP2 :ADC2)
            (bind :GP3 :GPIO)
            (place :GP0 {:prefix "LED0."})
            (place :GP2 {:prefix "ADC2."})
            (place :I2C {:addr 0x76 :prefix "BME0." :module :BME280})
            (place :I2C {:addr 0x77 :prefix "BME1." :module :BME280})
            (place :I2C {:addr 0x0a :prefix "D6T44L" :module :D6T44L})
            (place :I2C {:addr 0x53 :prefix "ADXL345" :module :ADXL345})            
            (place :UART {:prefix "GPS" :module :GPS}))))

(defn- loopback []
  (on-platform
   (+device :loopback
            (config :system {:PCB "1.0.0" :power :5.0})
            (config :UART {:path "/dev/ttyACM0" :baud-rate 9600})
            (place :UART {:name "ir0" :data-path "/tmp/irmagician" :module :IrMagician }))))





(defn- read-eval [& options]
  (let [cl (.getContextClassLoader (Thread/currentThread))]
    (.setContextClassLoader (Thread/currentThread) (clojure.lang.DynamicClassLoader. cl)))
  (let [{:keys [init flush read eval print caught]
         :or {init        #(do
                             (in-ns 'kikori.shell)
                             (refer-kikori))
              flush       flush
              read        m/repl-read
              eval        eval
              print       prn
              caught      m/repl-caught}}
        (apply hash-map options)
        request-prompt (Object.)        
        request-exit (Object.)
        read-eval-print
        (fn []
          (try
            (let [read-eval *read-eval*
                  input (m/with-read-known (read request-prompt request-exit))]
              (let [value (binding [*read-eval* read-eval] (eval input))]
                (print value)
                (set! *3 *2)
                (set! *2 *1)
                (set! *1 value)))
            (catch Throwable e
              (caught e)
              (set! *e e))))]
    (m/with-bindings
      (try
        (init)
        (catch Throwable e
          (caught e)
          (set! *e e)))
      (flush)
      (when-not 
          (try (identical? (read-eval-print) request-exit)
               (catch Throwable e
                 (caught e)
                 (set! *e e)
                 nil))
        (flush)))))


(defn repl [& [options]]
  (let [cl (.getContextClassLoader (Thread/currentThread))]
    (.setContextClassLoader (Thread/currentThread) (clojure.lang.DynamicClassLoader. cl)))
  (let [pfile (or (:secrets options)
                  (System/getProperty "kikori.secrets"))
        
        user-fns (->> (the-ns 'kikori.facade)
                      (ns-publics)
                      ;; (filter (fn [[k f]] (re-find #"-(read)|(write)$" (str k))))
                      (map first)
                      (map str))

        argument-completer (proxy [Completer] []
                             (complete [rdr line candidates]
                               (let [cmd (if (seq (-> line (.words) first))
                                           (-> line (.words) first  (subs 1) keyword)
                                           :none)]
                                 (condp =  (keyword (str (.wordIndex line)))
                                   :0 (doseq [c user-fns]
                                        (.add candidates (Candidate. (str "(" c)
                                                                     c
                                                                     (let [group (str/replace c #"^([a-zA-Z0-9]+)-.*" "$1")]
                                                                       (if (#{"gpio" "i2c"} group)
                                                                         (str/upper-case group)
                                                                         "Misc"))
                                                                     nil nil c true)))
                                   :1 (if (= 1 (.wordIndex line))
                                        (condp = cmd
                                          :i2c-read (doseq [edge (sensors) :when (= :I2C (:bus edge))]
                                                      (-> candidates (.add (Candidate. (str "\"" (:name edge) "\"")
                                                                                       (str "\"" (:name edge) "\"")
                                                                                       "name"
                                                                                       (str (hex (:addr edge)) ", " (:device-id edge))
                                                                                       nil
                                                                                       (:name edge)
                                                                                       true))))
                                          :i2c-write (doseq [edge (sensors) :when (= :I2C (:bus edge))]
                                                       (-> candidates (.add (Candidate. (str "\"" (:name edge) "\"")
                                                                                        (str "\"" (:name edge) "\"")
                                                                                        "name"
                                                                                        (str (hex (:addr edge)) ", " (:device-id edge))
                                                                                        nil
                                                                                        (:name edge)
                                                                                        true))))
                                          :gpio-read (doseq [edge (sensors) :when (= :GPIO (:module edge))]
                                                       (-> candidates (.add (Candidate. (str "\"" (:name edge) "\"")
                                                                                        (:name edge)
                                                                                        "pin"
                                                                                        (:name edge)
                                                                                        nil
                                                                                        (:name edge)
                                                                                        true))))
                                          :gpio-write (doseq [edge (sensors) :when (= :GPIO (:module edge))]
                                                        (-> candidates (.add (Candidate. (str "\"" (:name edge) "\"")
                                                                                         (:name edge)
                                                                                         "pin"
                                                                                         (:name edge)
                                                                                         nil
                                                                                         (:name edge)
                                                                                         true))))
                                          :load-modules (-> candidates (.add (Candidate. ")")))
                                          nil))
                                   
                                   :2 (condp = cmd
                                        :i2c-read (doseq [edge (sensors) :when (= :I2C (:bus edge))]
                                                    (-> candidates (.add (Candidate. ""
                                                                                     ""
                                                                                     "Size"
                                                                                     "Read size"
                                                                                     nil
                                                                                     "size"
                                                                                     true))))
                                        :i2c-write (doseq [edge (sensors) :when (= :I2C (:bus edge))]
                                                     (-> candidates (.add (Candidate. ""
                                                                                      ""
                                                                                      "Data"
                                                                                      "Data like [0x40 0x00 ...]"
                                                                                      nil
                                                                                      "size"
                                                                                      true))))
                                        :gpio-read (-> candidates (.add (Candidate. ")")))
                                        :gpio-write (doseq [edge (sensors) :when (= :GPIO (:module edge))]
                                                      (doto candidates
                                                        (.add (Candidate. (str 0)
                                                                          (str 0)
                                                                          "OUT"
                                                                          "LOW"
                                                                          nil
                                                                          "LOW"
                                                                          true))
                                                        (.add (Candidate. (str 1)
                                                                          (str 1)
                                                                          "OUT"
                                                                          "HIGH"
                                                                          nil
                                                                          "HIGH"
                                                                          true))))
                                        nil)
                                   :3 (condp = cmd
                                        :gpio-write (-> candidates (.add (Candidate. ")")))
                                        nil)
                                   nil))))
        term (-> (TerminalBuilder/builder)
                 (.name "Kikori terminal")
                 (.system true)
                 (.nativeSignals true)
                 ;;(.signalHandler (Terminal$SignalHandler/SIG_IGN))
                 (.build))
        rdr (doto (-> (LineReaderBuilder/builder)
                      (.terminal term)
                      (.completer argument-completer)
                      (.parser (doto (DefaultParser.)
                                 (.setQuoteChars (char-array []))))
                      (.build))
              (.setVariable LineReader/HISTORY_FILE
                            (Paths/get  "." (into-array String [".history"])))
              (.unsetOpt LineReader$Option/HISTORY_INCREMENTAL)
              (.setOpt LineReader$Option/DISABLE_EVENT_EXPANSION))
        
        history (DefaultHistory. rdr)
        wtr (-> term (.writer))]

    (-> wtr (.println "\nWelcome to Kikori shell !\n"))

    (binding [*ns* (create-ns (if (= "clojure.core" (str *ns*))
                                'kikori.shell
                                (symbol (str *ns*))))]
      (refer-kikori)
      (set-log-level! :warn)
      (try
        (if pfile
          (loop []
            (let [[user passwd] [(.readLine rdr "User: " nil) (.readLine rdr "Password: " \*)]]
              (try (with-open [acct-rdr (io/reader (io/file pfile))]
                     (when acct-rdr
                       (let [secrets (reduce (fn [r user-secret]
                                               (->> (str/split user-secret #"[ \t]+")
                                                    (map str/trim )
                                                    (apply hash-map)
                                                    (conj r)))
                                             {}
                                             (line-seq acct-rdr))]
                         (when-not (and user passwd (= passwd (secrets user)))
                           (-> wtr (.println "\nWrong user/password.\n"))))))
                   (catch Exception e (log/error (.getMessage e))))
              (recur))))

        (-> wtr (.println "- Run (quit) when you want to shutdown kikori.\n"))
        
        (loop [command-buffer ""]
          (let [line (str/trim (.readLine rdr (if (seq command-buffer)
                                                (str *ns* ". ")
                                                (str *ns* "> "))))
                buf (str command-buffer line)
                left-paren-count (count (filter #(= \( %) buf))
                right-paren-count (count (filter #(= \) %) buf))]
            (if (and (seq buf) (= left-paren-count right-paren-count))
              (do (try
                    (.add history buf)
                    (.save history)
                    (-> wtr (.println (pr-str (eval (read-string buf)))))
                    (catch Exception e (-> wtr (.println (.getMessage e)))))
                  (recur ""))
              (recur buf))))
        (catch UserInterruptException e (do (shutdown)
                                            (stop-web-server)))
        (catch EndOfFileException e (do (shutdown)
                                        (stop-web-server)))))))


(def options-schema
  [["-s" "--secrets <file>" "a file contains user/password for console login"]
   [nil "--help" "This help"]])


(defn -main [& args]
  (let [{:keys [options arguments summary] :as argv} (parse-opts args options-schema)]
    (if (:help options)
      (do
        (println "\nUsage: kikori shell <options>\n")
        (println summary "\n"))
      (try
        (cond
          (empty? args) (repl options)
          (seq arguments) (apply clojure.main/main arguments))
        (catch Throwable t (println (or (:cause (Throwable->map t)) (.getMessage t))))))))




;; @(http/post "http://192.168.11.2:9000/notify" {:form-params { :text "こんにちは" :sslengine (silvur.ssl/engine)}})







(comment
  (do (set-log-level! :warn) (load-module "bme280") (load-module "d6t44l") (load-module "adxl345") (load-module "gps") (defn boot! [] (on-platform (+device "hidraw0" (bind :GP0 :GPIO) (bind :GP1 :GPIO) (bind :GP2 :ADC2) (config :I2C) (config :UART {:path "/dev/ttyACM0"}) (place :I2C {:addr 10, :name "D6T44L0", :module "D6T44L"}) (place :I2C {:addr 83, :name "ADXL0", :module "ADXL345"}) (place :I2C {:addr 118, :name "BME0", :module "BME280"}) (place :I2C {:addr 119, :name "BME1", :module "BME280"}) (place :UART {:name "GPS0", :module "GPS"}) (place :GP2 {:name "ADC2"})) (+device "USB_04d8_00dd_" (bind :GP0 :GPIO) (bind :GP1 :GPIO) (bind :GP2 :ADC2) (config :I2C) (config :UART {:path "/dev/tty.usbmodem1421"}) (place :I2C {:addr 10, :name "D6T44L0", :module "D6T44L"}) (place :I2C {:addr 83, :name "ADXL0", :module "ADXL345"}) (place :I2C {:addr 118, :name "BME0", :module "BME280"}) (place :I2C {:addr 119, :name "BME1", :module "BME280"}) (place :UART {:name "GPS0", :module "GPS"}) (place :GP2 {:name "ADC2"})))) (start-web-server :ip "0.0.0.0" :port 3000) (boot!)))

;; DBus

(defn xtest []
  (let [dbus-busname "org.freedesktop.DBus"
        bluez-dbus-busname "org.bluez"
        bluez-device-interface "org.bluez.Device1"
        bluez-adapter-interface "org.bluez.Adapter1"
        bluez-gatt-interface "org.bluez.GattManager1"
        bluez-le-adv-interface "org.bluez.LEAdvertisingManager1"
        dbus-connection (DBusConnection/getConnection DBusConnection$DBusBusType/SYSTEM)
        dbus (.getRemoteObject dbus-connection dbus-busname "/org/freedesktop/DBus" DBus)
        bluez-dbus-busname+ (.GetNameOwner dbus bluez-dbus-busname)
        object-manager (.getRemoteObject dbus-connection bluez-dbus-busname "/" ObjectManager)
        bluez-managed-objects (.GetManagedObjects object-manager)
        adapter-path (first (keep (fn [x]
                                    (let [path (.getKey x)
                                          intfs (map #(.getKey %) (.getValue x))]
                                      (when (and ((set intfs) bluez-gatt-interface)
                                                 ((set intfs) bluez-le-adv-interface))
                                        (str path))))
                                  bluez-managed-objects))
        gatt-manager (.getRemoteObject dbus-connection bluez-dbus-busname adapter-path GattManager1)
        le-manager (.getRemoteObject dbus-connection bluez-dbus-busname adapter-path LEAdvertisingManager1)]
    le-manager
    ))


;; Introspection
;;  dbus-send --session --dest=org.freedesktop.DBus   --type=method_call --print-reply /org/freedesktop/DBus   org.freedesktop.DBus.Introspectable.Introspect 

(comment
  (+interface
  (config :UART {:path "/dev/ttyUSB0" :baud-rate 115200})
  (place :UART {:name "BU0" :module :BU.2JCIE :id "34:F6:4B:66:47:E7"})
  (place :USER {:name "OMRON0" :src "BU0" :module :VIF.2JCIE  :vid {"34:F6:4B:66:47:E7" :BU
	                                                            "34:F6:4B:66:47:E8" :BL
	                                                            "34:F6:4B:66:47:E9" :BL
	                                                            "34:F6:4B:66:47:EA" :BU}})))
        




