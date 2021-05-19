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


(ns kikori.device
  (:import [java.io ByteArrayOutputStream])
  (:require [silvur.datetime :refer [datetime]])
  (:require [kikori.chip.mcp2221a :as mc]
            [kikori.util :as util]
            [kikori.hid :refer :all]
            [kikori.hci :refer :all]
            [kikori.graphic :as g])
  (:require [clojure.core.async :as a :refer [chan <! >! put! >!! <!! go go-loop alts! close!
                                              poll!
                                              sliding-buffer thread timeout]])
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:require [taoensso.timbre :as log])
  (:import [org.freedesktop.dbus.exceptions DBusException DBusExecutionException]
           [com.github.hypfvieh.bluetooth DeviceManager]
           [com.github.hypfvieh.bluetooth.wrapper
            BluetoothAdapter BluetoothDevice BluetoothGattCharacteristic
            BluetoothGattService ]))

(defrecord GroovyIoT [product-id ;; product id
                      vendor-id ;; vendor id
                      device-id ;; device id
                      hid ;; HID device
                      port-out ;; channel: device -> host 
                      port-in  ;; channel: device <- host
                      sensors ;; bus that has sensors
                      uart ;; UART device
                      path ;; device path
                      GPIO1
                      GPIO2
                      GP0 GP1 GP2 GP3
                      lock])

(defrecord SystemInterface [device-id
                            ;;uart
                            ;;hid ;; Should use loopback Hid
                            ;;port-out ;; channel: device -> host 
                            ;;port-in  ;; channel: device <- host
                            sensors ;; bus that has sensors
                            lock])


(defrecord Sensor [name bus module])



(def ^:private device-mapper {[0x04d8 0x00dd] map->GroovyIoT})

(defonce ^:private PACKET-SIZE 64)

(defn padding [& [data]]
  (vec (take PACKET-SIZE (lazy-cat data (repeat :_)))))

(defprotocol Specs
  (specs [dev])
  (identitied? [dev])
  (pp [dev])
  (clear-queue [dev])
  (throw-context [dev msg]))
(extend-protocol Specs
  SystemInterface
  (specs [dev]
    {:capabilities {:SPI :UART}
     :bus #{:UART :SPI}})
  
  GroovyIoT
  (clear-queue [dev]
    (log/info "Clearing Queue")
    (dorun (take-while (fn [id]
                         (let [x (poll! (:port-out dev))]
                           (log/info "Clear out-channel: [" id "]: " x)
                           (some? x)))
                       (iterate inc 0)))
    (dorun (take-while (fn [id]
                         (let [x (poll! (:port-in dev))]
                           (log/info "Clear in-channel: [" id "]: " x)
                           (some? x)))
                       (iterate inc 0))))
  
  (identitied? [dev]
    (some? (re-find (re-pattern "MCP2221") (:product-string dev))))
  (specs [dev]
    ;; Added groves
    (-> (mc/specs)
        (assoc :groves #{:GPIO1 :GPIO2 :I2C :UART})
        (update-in [:capabilities] conj (condp = (-> dev :system :PCB)
                                          "1.0.0" {:GPIO1 #{:GPIO :ADC :CLKR}
                                                   :GPIO2 #{:GPIO :ADC :DAC}}
                                          {:GPIO1 #{:GPIO :ADC :CLKR}
                                           :GPIO2 #{:GPIO :ADC :DAC}}))
        (assoc-in [:bus]  #{:I2C :GPIO :UART :GP0 :GP1 :FB :CAMERA :USER})))
  (pp [dev]
    (-> dev
        (update :set-sram-settings util/hex)
        (update :status-set-parameters util/hex)))
  (throw-context [dev msg]
    (throw (ex-info msg (dissoc dev :set-sram-settings :status-set-parameters)))))


(defprotocol Circuit
  (reset-chip [dev])
  (pin-scan [dev])
  (pin-bind [dev pin fn-id opts])
  (pin-index [dev pin])
  (parse [dev message]))

(extend-protocol Circuit
  GroovyIoT
  (reset-chip [dev]
    (mc/reset-chip dev))
  (pin-index [dev pin]
    (when ((-> (specs dev) :pins) pin)
      (Long/parseLong (str/replace (name pin) #"GP([0-9]+)$" "$1"))))
  (pin-bind [dev grove fn-id opts]
    (if (fn-id (-> (specs dev) :capabilities grove))
      (let [conf  (cond
                    ;; If specified grove is pin as :GP0, GP1, GP2 and GP3
                    (grove (-> (specs dev) :pins)) {grove fn-id}
                    ;; If specified grove is as :GPIO1, GPIO2 and fn-id is :GPIO
                    (= [:GPIO1 :GPIO] [grove fn-id]) {:GP0 :GPIO :GP1 :GPIO}
                    (= [:GPIO2 :GPIO] [grove fn-id]) {:GP2 :GPIO :GP3 :GPIO}
                    (= [:GPIO1 :ADC] [grove fn-id]) {:GP0 :GPIO :GP1 :ADC1}
                    (= [:GPIO2 :ADC] [grove fn-id]) {:GP2 :ADC2 :GP3 :ADC3}
                    (= [:GPIO1 :CLKR] [grove fn-id]) {:GP0 :GPIO :GP1 :CLKR}
                    (= [:GPIO2 :DAC] [grove fn-id]) {:GP2 :DAC1 :GP3 :DAC2})]

        (reduce (fn [r [pin chip-fn-id]]
                  (if-let [config (mc/pin-mappings pin chip-fn-id)]
                    (let [{sram-settings :set-sram-settings} r]
                      (-> r
                          (assoc pin chip-fn-id)
                          (assoc :set-sram-settings
                                 (reduce (fn [xr [idx fn-or-const]]
                                           (assoc-in xr
                                                     [idx]
                                                     (if (fn? fn-or-const)
                                                       (fn-or-const (assoc opts :system (specs dev)))
                                                       fn-or-const)))
                                         (vec (padding sram-settings))
                                         config))))
                    r))
                dev
                conf))
      (do (log/warn grove "could not be assigned to" fn-id)
          (assoc dev grove :NOT-CAPABLE))))
  
  (pin-scan [dev]
    (let [{:keys [response]} (mc/get-sram-settings dev)
          spec (specs dev)
          kvs  (map (fn [pin x]
                      (vector pin (bit-and (nth response x) 2r00000111)))
                    (sort (:pins spec)) [22 23 24 25])]
      ;; PIN
      (reduce (fn [r [k v]]
                (assoc r k (get-in spec [:designation k v])))
              dev kvs)))
  (parse [dev message]
    (mc/parse* message)))


(defprotocol SRAM
  (sram-config [dev m])
  (sram-write! [dev])
  (sram-read [dev])
  (sram-build-frame [dev]))

(extend-protocol SRAM
  GroovyIoT
  (sram-config [dev {:keys [idx fn v]}]
    (let [{sram :set-sram-settings} dev]
      (-> dev
          (update :set-sram-settings padding)
          (update-in [:set-sram-settings idx] (or fn identity))
          (assoc-in [:set-sram-settings idx] (or v identity)))))
  (sram-write! [{data :set-sram-settings develop? :develop? :as dev}]
    (when (and data (not develop?))
      (mc/set-sram-settings dev data))
    dev)
  (sram-read [dev]
    (mc/get-sram-settings dev))
  (sram-build-frame [{:keys [develop?] :as dev}]
    " Read SRAM and Build byte sequence to configure"
    (let [sram (:response (mc/get-sram-settings dev))
          {sram-settings :set-sram-settings} dev]
      (log/debug (seq sram))
      (if develop?
        dev
        (assoc dev :set-sram-settings (->> sram-settings
                                           (map-indexed
                                            (fn [i v]
                                              (if (= v :_)
                                                (cond
                                                  (<= 1 i 6) 0x00
                                                  (= i 7) 0x00
                                                  (<= 8 i 11) (nth sram (+ i 22)) ;; index 22 in SRAM => GP0
                                                  (<= 12 i) 0x00
                                                  :else v)
                                                v)))
                                           (rest)
                                           (vec)))))))

(defprotocol DAC
  (dac-config [dev m])
  (dac-write! [dev m]))
(extend-protocol DAC
  GroovyIoT
  (dac-config [dev {vrm :vrm value :value :as m :or {vrm :VDD}}]
    (let [sv (-> dev :system :power)
          v (-> (specs dev) :VRM vrm)]
      (log/debug "DAC:" sv vrm sv v)
      (try
        (if (or (= :VDD vrm) (< (util/keyword->number vrm) (util/keyword->number sv)))
          (sram-config dev {:idx 3 :v v})
          (throw (ex-info "VRM should be lower than system power" {})))
        (catch Throwable e (do (log/error (.getMessage e))
                               (log/warn "DAC: Specified VRM not configurable:" vrm)
                               (log/warn "DAC: Available VRM:" (keys ((specs dev) :VRM)) "<" sv)
                               (throw-context dev (str "DAC: Invalid Parameters: " m)))))))
  (dac-write! [dev {:keys [value] :as m}]
    (log/debug "DAC write:" value)
    (try
      (let [v (if (number? value) value (read-string value))]
        (-> dev
            (sram-config {:idx 4 :v (bit-or 0x80 (bit-and 2r00011111 v))})
            (sram-build-frame)
            (sram-write!)))
      (catch Throwable e (do
                           (log/error (.getMessage e))
                           (log/warn "DAC: Required :value")
                           (throw-context dev (str "DAC: Invalid Parameter " m)))))))

;; GPIO
(defprotocol GPIO
  (gpio-read [dev m])
  (gpio-write! [dev m])
  (gpio-current-settings [dev])
  (gpio-set-mode! [dev pin mode]) ;; mode: :IN / :OUT
  (gpio-out [dev pin value])
  (gpio-in [dev pin]))

(extend-protocol GPIO
  GroovyIoT
  (gpio-current-settings [dev]
    (mc/parse* (mc/get-gpio-values dev)))

  (gpio-set-mode! [dev pin mode]
    (log/debug "Pin Mode:" pin mode)
    (try
      (if-let [pin-idx (pin-index dev pin)]
        (if-let [direction (-> (specs dev) :GPIO :direction mode)]
          (let [code (vec (take 4 (repeat (take 4 (repeat 0)))))
                result (mc/parse* (mc/set-gpio-output-values dev
                                                             (flatten
                                                              [0x00
                                                               (assoc-in code
                                                                         [pin-idx]
                                                                         ;; 0: alter output
                                                                         ;; 1: output value
                                                                         ;; 2: alter direction
                                                                         ;; 3: direction
                                                                         [0x00 0x00 0x01 direction])])))]
            (log/debug "Pin Index:" pin-idx)
            (log/debug "Direction:" direction)
            (log/debug "Result:" result)
            (log/debug "Return:" (pin result))
            (pin result))
          (do (log/warn "Given mode is invalid: " mode)
              (log/warn "Available modes: " (-> (specs dev) :GPIO :direction))
              (throw-context dev (str "Specified pin not available: " mode))))
        (throw (ex-info "Specified pin not available" {:pin pin})))
      (catch Throwable e (do (log/error (.getMessage e) (ex-data e))
                             (throw-context dev "Invalid Parameters")))))
  
  (gpio-write! [dev {:keys [pin value] :as m}]
    (log/debug "gpio-write!: pin value:" pin value)
    (try
      (if-let [pin-idx (pin-index dev pin)]
        (let [code (vec (take 4 (repeat (take 4 (repeat 0)))))]
          ((mc/parse* (mc/set-gpio-output-values dev (flatten
                                                      [0x00
                                                       (assoc-in code
                                                                 [pin-idx]
                                                                 [0x01 value 0x01 0x00])])))
           pin))
        (throw (ex-info "Specified pin not available" {:pin pin})))
      (catch Throwable e (do (log/error (.getMessage e) (ex-data e))
                             (throw-context dev "Invalid Parameters")))))
  
  (gpio-read [dev {n :count pin :pin bus :bus :or {n 1}}]
    (log/debug "Pin/Bus" pin bus)
    (let [pin (or pin bus)]
      (if-let [pin-idx (pin-index dev pin)]
        (do 
          (log/debug "Current:" (mc/get-gpio-values dev))
          (map (fn [x]
                 (let [{:keys [response] :as r} (mc/get-gpio-values dev)]
                   (nth response (+ 2 (* 2 pin-idx)))))
               (range n)))
        (log/warn "Pin not available:" pin))))

  ;; Syntax sugar 
  (gpio-out [dev pin-id value]
    (gpio-write! dev {:pin pin-id :value value}))
  
  (gpio-in [dev pin]
    (first (gpio-read dev {:pin pin :count 1}))))


;; I2C
(defprotocol I2C
  (i2c-config [dev m])
  (i2c-commit! [dev])
  (i2c-read [dev m])
  (i2c-write! [dev m])
  (i2c-cancel [dev])
  (i2c-state [dev])
  (i2c-scan [dev area])
  (i2c-ping [dev addr])
  (i2c-bind-sensors [dev]))
(extend-protocol I2C
  GroovyIoT
  (i2c-scan [dev area]
    (filter (fn [addr]
              (let [result (= (-> (mc/state-codes) :i2c :data-ready)
                              (do
                                (mc/i2c-read-data dev addr 1)
                                ;; the 8th byte of data has state machine state
                                (nth (:response (mc/status-set-parameters dev [])) 8)))]
                (mc/status-set-parameters dev [0x00 0x10])
                result))
            area))
  (i2c-ping [dev addr]
    (try
      (i2c-read dev {:addr addr :size 1})
      (catch Exception e nil)))
  (i2c-bind-sensors [dev]
    (reduce (fn [r {:keys [addr bus name] :as sensor}]
              (cond
                (not= bus :I2C) (assoc-in r [:sensors name] sensor)
                (and (= bus :I2C) (seq (i2c-ping dev addr))) (do (log/info "Found I2C device: " (util/hex addr))
                                                                 (assoc-in r [:sensors name] sensor))
                :else r))
            (assoc dev :sensors {})
            (vals (:sensors dev))))
  (i2c-cancel [dev]
    (mc/status-set-parameters dev [0x00 0x10]))
  (i2c-state [dev]
    (:i2c-state-machine (mc/parse* (mc/status-set-parameters dev []))))
  (i2c-write! [dev {:keys [addr data no-stop? repeated?]}]
    (when-not addr (throw (ex-info "No I2C address" {:reason "No address specified"})))
    (let [result (cond
                   no-stop? (mc/i2c-write-no-stop dev addr data)
                   repeated? (mc/i2c-write-repeated dev addr data)
                   :else (mc/i2c-write-data dev addr data))]
      (log/debug "I2C write:" result)
      (seq (:response result))))

  (i2c-read [dev {:keys [addr size repeated?] :or {size 60}}]
    (try
      (when-not addr (throw (ex-info "No I2C address" {:reason "No address specified"})))
      (let [{:keys [response] :as result} (if repeated?
                                            (mc/i2c-read-repeated dev addr size)
                                            (mc/i2c-read-data dev addr size))]
        (log/debug "I2C Read response:" (seq response))
        
        (if-not (mc/success? response)
          (do (i2c-cancel dev)
              (log/debug "I2C Read failed. "))
          
          (let [ready? #(and (= %1 (-> (mc/state-codes) :i2c :data-ready))
                             (= (count %2) size))
                remained? #(and (= %1 (-> (mc/state-codes) :i2c :data-remained)))
                need-retry? #(and (= %1 (-> (mc/state-codes) :i2c :need-retry)))
                completed? #(and (= %1 (-> (mc/state-codes) :i2c :idle))
                                  (= (count %2) size))]
            
            (loop [retry 1
                   raw (mc/get-i2c-data dev)
                   combined-data []]
              
              
              (let [{state :i2c-state-machine received :size data :data} (mc/parse* raw)
                    temporary-data (concat combined-data (take received data))]
                (log/debug "I2C State:" state)
                (log/debug "Retry count:" retry)
                (log/debug "Get I2C Data response: " (seq data))
                (log/debug "Combined data: " (seq temporary-data))
                (cond
                  (ready? state temporary-data) (seq (take size temporary-data))
                  (completed? state temporary-data) (seq (take size temporary-data))
                  
                  (and (> 0 retry)
                       (not (remained? state))) (do (i2c-cancel dev)
                                                    (log/debug "Data not ready:" raw))

                  (need-retry? state) (do
                                        (log/debug "need-retry?")
                                        (recur retry
                                               (mc/get-i2c-data dev)
                                               combined-data))
                  (remained? state) (do
                                      (log/debug "remained")
                                      (recur retry
                                             (mc/get-i2c-data dev)
                                             temporary-data))
                  :else (do
                          (log/debug "else")
                          (recur (dec retry)
                                 (mc/get-i2c-data dev)
                                 combined-data))))))))
      
      (catch Throwable e (do (i2c-cancel dev)
                             (throw e)))))





  (i2c-config [dev {:keys [speed] :or {speed 100000}}]
    (log/debug "I2C speed will be" speed)
    (let [{params :status-set-parameters} dev
          code (vec (map-indexed (fn [i v]
                                   (if (= v :_)
                                     (cond
                                       (= i 1) 0x00
                                       (= i 2) 0x20
                                       (= i 3) (int (- (/ 12000000 speed) 3))
                                       :else 0x00)
                                     v))
                                 (butlast (padding params))))]
      (assoc dev :status-set-parameters code)))
  

  (i2c-commit! [dev]
    (let [{code :status-set-parameters} dev]
      (if code
        (let [{state :i2c-state-machine} (mc/parse* (mc/status-set-parameters dev code))]
          (when-not (= 0 state)
            (do (log/warn "I2C Config: state:" (util/hex state))
                (i2c-cancel dev)))
          (assoc dev :status-set-parameters code))
        (do (log/info "I2C no need to configure")
            dev)))))




(defprotocol CLKR
  (clkr-config [dev m])
  (clkr-write! [dev m]))
(extend-protocol CLKR
  GroovyIoT
  (clkr-config [dev {:keys [duty divider frequency] :as m}]
    (let [{sram :set-sram-settings} dev
          sp (specs dev)
          {duties :duty} sp
          {dividers :divider} sp
          {freqs :frequency} sp
          dty (duties duty)
          div (dividers divider)
          freq (freqs frequency)]
      
      (log/debug "CLKR: Duty: " duty)
      (log/debug "CLKR: Divider: " (or divider "no specified"))
      (log/debug "CLKR: Frequency: " (or frequency "no specified"))
      (try
        (if (and dty (or div freq))
          (sram-config dev {:idx 2 :v (bit-or 0x80
                                              (bit-and 2r00011000 dty)
                                              (bit-and 2r00000111 (or div freq)))})
          (throw (ex-info "CLKR: No defined duty and divider/frequency" {:duty dty
                                                                         :divider div
                                                                         :frequency freq})))
        (catch Throwable e (do
                             (log/error (.getMessage e) (ex-data e))
                             (log/warn "CLKR: Available Duties:" (keys duties))
                             (log/warn "CLKR: Available Dividers:" (keys dividers))
                             (log/warn "CLKR: Available Frequency:" (keys freqs))
                             (throw-context dev (str "CLKR: Invalid parameters:" m) )
                             ;;:_
                             )))))
  (clkr-write! [dev {:keys [duty divider frequency] :as m}]
    (-> dev
        (clkr-config m)
        (sram-build-frame)
        (sram-write!))))




(defprotocol FB
  (fb-config! [dev m])
  (fb-clear! [dev])
  (fb-read [dev])
  (fb-write! [dev m]))


(extend-protocol FB
  SystemInterface
  (fb-config! [dev m]
    (assoc dev :frame-buffer (assoc m :screen (atom nil))))
  (fb-clear! [dev]
    (fb-write! dev {}))
  (fb-read [{{:keys [path screen]} :frame-buffer}]
    {:html? true :body (if @screen
                         (g/bytes->svg @screen "image/png")
                         "<html><body>No screen data</body></html>")})
  
  (fb-write! [dev {:keys [data url] :as m}]
    ;; m has keys: data, url, rotate
    (let [{s :screen p :path w :width h :height :as fb}  (-> dev :frame-buffer)]
      (binding [g/*width* (or w 128)
                g/*height* (or h 128)]
        ;; Data is assumed as
        ;; text: SVG
        ;; input-stream: PNG
        ;; byte-array: PNG binary array
        
        (g/draw-image! (assoc m :path p :screen s))))))

;; UART
(defprotocol UART
  (uart-config! [dev m])
  (uart-read [dev])
  (uart-write! [dev data])
  (uart-close [dev])
  (uart-get-serial-port-params [dev])
  (uart-set-serial-port-params! [dev {:as m}]))

(extend-protocol UART
  SystemInterface
  (uart-config! [dev {:keys [path baud-rate data-bits stop-bits parity immediate?]
                      :or {baud-rate 9600}
                      :as m}]
    (let [tty-path (if (or (nil? path) (= :auto (keyword (name path))))
                     (-> dev :hid :relatives :tty first)
                        path)]
      (log/debug "UART path:" tty-path)
      
      (if tty-path
        (assoc dev :uart (try
                           (when (mc/uart-open? (:uart dev))
                             (mc/uart-close (:uart dev)))
                           (let [uart (mc/uart-open tty-path)
                                 raw-port (-> uart :port :raw-port)
                                 opts {:baud-rate (or baud-rate (.getBaudRate raw-port))
                                       :stop-bits (or data-bits (.getStopBits raw-port))
                                       :parity (or stop-bits (.getParity raw-port))
                                       :data-bits (or parity (.getDataBits raw-port))}]
                             (log/debug "UART Options:" raw-port opts)
                             (.setSerialPortParams raw-port
                                                   (:baud-rate opts)
                                                   (:data-bits opts)
                                                   (:stop-bits opts)
                                                   (:parity opts))
                             
                             uart)
                           (catch Throwable e (do (log/warn "It might be caused by no access right of serial device:" (.getMessage e))
                                                  dev))))
        (do (log/warn "UART device could not be found")
            dev))))

  (uart-read [dev]
    (mc/uart-read (:uart dev)))

  (uart-write! [dev data]
    (log/debug data)
    (mc/uart-write (:uart dev) data))
  
  (uart-close [dev]
    (try
      (mc/uart-close (:uart dev))
      (assoc dev :uart nil)
      (log/debug "UART Closed")
      (catch Exception e (log/error (.getMessage e)))))

  (uart-get-serial-port-params [dev]
    (when-let [port (-> dev :uart :port :raw-port)]
      {:baud-rate (.getBaudRate port)
       :stop-bits (.getStopBits port)
       :parity (.getParity port)
       :data-bits (.getDataBits port)}))
  
  (uart-set-serial-port-params! [dev m]
    (when-let [port (-> dev :uart :port :raw-port)]
      (let [g (uart-get-serial-port-params dev)
            {:keys [baud-rate data-bits stop-bits parity]} (conj g m)]
        (.setSerialPortParams port baud-rate data-bits stop-bits parity))))



  
  GroovyIoT
  (uart-config! [dev {:keys [path baud-rate data-bits stop-bits parity immediate?]
                      :or {baud-rate 9600}
                      :as m}]
    (let [tty-path (if (or (nil? path) (= :auto path))
                     (-> dev :hid :relatives :tty first)
                        path)]
      (log/debug "UART path:" tty-path)
      
      (if tty-path
        (assoc dev :uart (try
                           (when (mc/uart-open? (:uart dev))
                             (log/debug "UART is open, therefore once close :" (:uart dev))
                             (mc/uart-close (:uart dev)))
                           (let [uart (mc/uart-open tty-path)
                                 raw-port (-> uart :port :raw-port)
                                 opts {:baud-rate (or baud-rate (.getBaudRate raw-port))
                                       :stop-bits (or data-bits (.getStopBits raw-port))
                                       :parity (or stop-bits (.getParity raw-port))
                                       :data-bits (or parity (.getDataBits raw-port))}]
                             (log/debug "UART Options:" raw-port opts)
                             (.setSerialPortParams raw-port
                                                   (:baud-rate opts)
                                                   (:data-bits opts)
                                                   (:stop-bits opts)
                                                   (:parity opts))
                             
                             uart)
                           (catch Throwable e (do (log/warn "It might be caused by no access right of serial device:" (.getMessage e))
                                                  dev))))
        (do (log/warn "UART device could not be found")
            dev))))

  (uart-read [dev]
    (mc/uart-read (:uart dev)))
  
  (uart-write! [dev data]
    (log/debug data)
    (mc/uart-write (:uart dev) data))
  
  (uart-close [dev]
    (try
      (mc/uart-close (:uart dev))
      (assoc dev :uart nil)
      (log/debug "UART Closed")
      (catch Exception e (log/error (.getMessage e)))))
  (uart-get-serial-port-params [dev]
    (when-let [port (-> dev :uart :port :raw-port)]
      {:baud-rate (.getBaudRate port)
       :stop-bits (.getStopBits port)
       :parity (.getParity port)
       :data-bits (.getDataBits port)}))
  (uart-set-serial-port-params! [dev m]
    (when-let [port (-> dev :uart :port :raw-port)]
      (let [g (uart-get-serial-port-params dev)
            {:keys [baud-rate data-bits stop-bits parity]} (conj g m)]
        (.setSerialPortParams port baud-rate data-bits stop-bits parity)))))




(defn open [dev]
  (assoc dev
         :hid (hid-open (.hid dev))
         :port-out  (chan 32
                          (map (fn [xs]
                                 (put! util/trace-channel [:h<==d (seq (map #(util/hex (bit-and 0xff %)) xs))])
                                 
                                 xs)))
         :port-in (chan 32
                        (map (fn [ws]
                               (put! util/trace-channel [:h==>d (let [{:keys [data]} ws]
                                                                  (if (keyword? data)
                                                                    data
                                                                    (seq (map #(util/hex (bit-and 0xff %)) data))))])
                               ws)))))



;; ADC
(defprotocol ADC
  (adc-config [dev m])
  (adc-read [dev m]))
(extend-protocol ADC
  GroovyIoT
  (adc-config [dev {vrm :vrm :as m :or {vrm :VDD}}]
    (log/debug "ADC VRM:" vrm)
    (sram-config dev {:idx 5
                      :v (let [sv (-> dev :system :power)
                               v (-> (specs dev) :VRM vrm)]
                           
                           (log/debug "ADC:" sv vrm sv v)
                           
                           (if (and sv v
                                    (or (= vrm :VDD)
                                        (< (util/keyword->number vrm) (util/keyword->number sv))))
                             v
                             (do
                               (log/warn "ADC: Configured VRM:" v)
                               (log/warn "ADC: Maybe you did't specifiy system power:" sv)
                               (log/warn "ADC: Available system powers are" (-> (specs dev) :system :power))
                               (log/warn "ADC: Available VRM:" (keys ((specs dev) :VRM)) "<" sv)
                               (throw-context dev (str "Check if ADC and system power in config are valid"))
                               ;;:_
                               )))}))
  (adc-read [dev {n :count direct? :direct? :or {n 1}}]
    (if direct?
      (loop [data []]
        (dotimes [i n]
          (hid-write! (.hid dev) (mc/opcode [:status-set-parameters])))
        (let [ret (take n (map (fn [x]
                                 (poll! (:port-out dev)))
                               (repeat 0)))]
          (log/debug "adc-read:" ret)
          (if (<= n (count data))
            (let [r (take n data)]
              {:ADC1 (map first r)
               :ADC2 (map second r)
               :ADC3 (map last r)})
            (recur (concat data (map (fn [response]
                                       (let [data (map #(bit-and 0xff %) response)]
                                         (let []
                                           [(bit-or (nth data 50)
                                                    (bit-shift-left (nth data 51) 8))
                                            (bit-or (nth data 52)
                                                    (bit-shift-left (nth data 53) 8))
                                            (bit-or (nth data 54)
                                                    (bit-shift-left (nth data 55) 8))])))
                                     ret))))))

      (loop [i n
             r []]
        (if (= 0 i)
          {:ADC1 (map first r)
           :ADC2 (map second r)
           :ADC3 (map last r)}
          (let [{:keys [response]} (mc/status-set-parameters dev [])]
            (log/debug "adc-read:" response)
            (when (and (sequential? response) (<= 56 (count response)))
              (let [data (map #(bit-and 0xff %) response)]
                (recur
                 (dec i)
                 (conj r
                       [(bit-or (nth data 50)
                                (bit-shift-left (nth data 51) 8))
                        (bit-or (nth data 52)
                                (bit-shift-left (nth data 53) 8))
                        (bit-or (nth data 54)
                                (bit-shift-left (nth data 55) 8))]))))))))))

(defn close [dev]
  (when dev
    (when (:hid dev) (hid-close (.hid dev)))
    (when (:uart dev) (uart-close dev))
    (when (:port-in dev) (put! (:port-in dev) {:data :close}))
    (when (:port-out dev) (close! (:port-out dev)))
    (when (:port-in dev) (close! (:port-in dev)))))

(defn listen [dev]
  (let [callback (fn [{:keys [data length]}]
                   (when-let [port (:port-out dev)]
                     ;; Need to change to vec or non-chunked seq to fix data as a block,
                     ;; otherwize it is delivered as chunked sequence
                     ;; That means poll! has a chance to get only 1 datum in this block.
                     (put! port (vec (take length data)))))
          hid (if (map? (.hid dev))
                (assoc (.hid dev) :callback callback)
                (.hid dev))]
    (go-loop []
      (let [xdata (<! (:port-in dev))]
        (when xdata
          (let [{:keys [join? data]} xdata]
            (when-not (= :close data)
              (try
                (hid-write! hid data)
                (catch Exception e (do (log/error (.getMessage e))
                                       ;; To avoid block
                                       (when-let [port (:port-out dev)]
                                         (put! port [0])))))
              (recur))))))
    (hid-listen (.hid dev) callback)
    dev))



(defprotocol Setup
  (ready! [dev]))
(extend-protocol Setup
  SystemInterface
  (ready! [dev]
    dev
    ;; (uart-config! dev (assoc (:uart dev) :immediate? true))
    )
  GroovyIoT
  (ready! [dev]
    (if (:hid dev)
      (let [d (-> dev open)]
        (if (:hid d)
          (cond-> d
            true (listen)
            (map? (:uart dev)) (uart-config! (assoc (:uart dev) :immediate? true))
            :alway (-> (sram-build-frame)
                       (sram-write!)
                       (pin-scan)
                       (i2c-commit!)
                       (i2c-bind-sensors)))
          (throw (ex-info "Device open failed" dev))))
      (throw (ex-info "No hid in this device" dev)))))





(defn map->device [m]
  (let [di (if (map? m) m (bean m))
        {s :productString v :vendorId
         p :productId d :deviceId
         path :path} di
        init-fn (device-mapper [v p])]
    (when init-fn
      (init-fn {:vendor-id v
                :product-id p
                :device-id d
                :product-string s
                :hid m
                :os (util/os)
                :arch (util/arch)
                :path path
                :sensors {}
                :lock (Object.)}))))

(defn enumerate [& {:keys [with-loopback?]}] 
  (->> (enumerate-devices (util/os))
       (keep map->device)
       ;; :index is added on linux by using last item of :path, but other platform is not.
       ;; Therefore :index is added if m does not have
       (map-indexed (fn [idx m] (if (:index m) m (assoc m :index idx))))))
