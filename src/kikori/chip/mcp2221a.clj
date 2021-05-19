(ns kikori.chip.mcp2221a
  (:require [irmagician.port :as port])
  (:require [clojure.string :as str]
            [clojure.core.async :as a :refer [chan <! >! put! >!! <!! go go-loop alts! close! poll! alts!! timeout]]
            [clojure.spec.alpha :as s])
  (:require [taoensso.timbre :as log]))

(defrecord MCP2221A [product-id ;; product id
                     vendor-id ;; vendor id
                     device-id ;; device id
                     hid ;; HID device
                     port-out ;; channel: device -> host 
                     port-in  ;; channel: device <- host
                     modules ;; bus that has actuators/sensors
                     uart ;; UART device
                     GP0 GP1 GP2 GP3])



(def ^:private command-codes
  {:status-set-parameters [0x10]
   :read-flash-data {:read-chip-settings [0xB0 0x00]
                     :read-gp-settings [0xB0 0x01]
                     :read-usb-manufacture [0xB0 0x02]
                     :read-usb-product [0xB0 0x03]                               
                     :read-usb-serial [0xB0 0x04]
                     :read-chip-factory [0xB0 0x05]}
   :set-gpio-output-values [0x50]
   :get-gpio-values [0x51]
   :set-sram-settings [0x60]
   :get-sram-settings [0x61]
   :reset-chip [0x70 0xAB 0xCD 0xEF]
   :i2c-write-data [0x90]
   :i2c-read-data [0x91]
   :i2c-write-repeated [0x92]
   :i2c-read-repeated [0x93]
   :i2c-write-no-stop [0x94]
   :get-i2c-data [0x40]})

(defn state-codes []
  {:i2c {:idle 0
         :address-not-found 37
         :need-retry 65
         :data-remained 84
         :data-ready 85
         :unknown 98
         :cancel-transfer-marked 16}})

(defn opcode [ks]
  (get-in command-codes ks))


(defn read-packet [{:keys [port-out] :as dev} op]
  (let [[result ch] (alts!! [port-out (timeout 30000)])]
    (log/debug "result:" result)
    (if (and result (= op [(bit-and 0xff (first result))]))
      result
      (do (log/debug "Response is not match for request or timeout:" op (or result "timeout"))
          (throw (ex-info "Response does not match or timeout:" {:request op :result result}))))))


(defn report [{:keys [port-in]}  data & _]
  (when port-in
    (>!! port-in {:data data})))


(defn reset-chip [dev]
  ;; Not work as of 2018 9 15
  (let [op (opcode [:reset-chip])]
    (report dev op)
    (read-packet dev op)))


(defn send-recv [dev op data  & _]
  (locking (:lock dev)
    (log/debug "op:" op "count:" (count data))
    (report dev (take 64 (concat op data)))
    {:response (read-packet dev op)
     :request op
     :data data}))

(defn status-set-parameters [dev data]
  (send-recv dev (opcode [:status-set-parameters]) data))


(defn set-gpio-output-values [dev code]
  (send-recv dev (opcode [:set-gpio-output-values]) code))


(defn get-i2c-data [dev]
  ;; 37: addr-not-found
  ;; 85: data-ready
  ;; 98: unknown
  (send-recv dev (opcode [:get-i2c-data]) []))

(defn get-gpio-values [dev]
  (send-recv dev (opcode [:get-gpio-values]) []))

(defn i2c-write-data [dev addr data]
  (send-recv dev (opcode [:i2c-write-data]) (concat
                                             [(bit-and (count data) 0x00ff)
                                              (bit-and (count data) 0xff00)
                                              (bit-and (bit-shift-left addr 1) 0xff)]
                                             data)))
(defn i2c-write-no-stop [dev addr data]
  (send-recv dev (opcode [:i2c-write-no-stop]) (concat
                                                [(bit-and (count data) 0x00ff)
                                                 (bit-and (count data) 0xff00)
                                                 (bit-and (bit-shift-left addr 1) 0xff)]
                                                data)))

(defn i2c-write-repeated [dev addr data]
  (send-recv dev (opcode [:i2c-write-repeated]) (concat
                                                 [(bit-and (count data) 0x00ff)
                                                  (bit-and (count data) 0xff00)
                                                  (bit-and (bit-shift-left addr 1) 0xff)]
                                                 data)))


(defn i2c-read-repeated [dev addr size]
  (send-recv dev (opcode [:i2c-read-repeated]) [(bit-and size 0x00ff)
                                                (bit-and size 0xff00)
                                                (bit-and (inc (bit-shift-left addr 1)) 0xff)]))

(defn i2c-read-data [dev addr size]
  (send-recv dev (opcode [:i2c-read-data]) [(bit-and size 0x00ff)
                                            (bit-and size 0xff00)
                                            (bit-and (inc (bit-shift-left addr 1)) 0xff)]))


;; Flash
(defn read-chip-settings [dev]
  (send-recv dev (opcode [:read-flash-data :read-chip-settings]) []))

(defn read-gp-settings [dev]
  (send-recv dev (opcode [:read-flash-data :read-gp-settings]) []))


(defn read-usb-serial [dev]
  (send-recv dev (opcode [:read-flash-data :read-usb-serial]) []))

(defn read-usb-manufacture [dev]
  (send-recv dev (opcode [:read-flash-data :read-usb-manufacture]) []))

(defn read-chip-factory [dev]
  (send-recv dev (opcode [:read-flash-data :read-chip-factory]) []))


(defn read-usb-product [dev]
  (send-recv dev (opcode [:read-flash-data :read-usb-product]) []))

;; Sram

(defn get-sram-settings [dev]
  (send-recv dev (opcode [:get-sram-settings]) []))

(defn set-sram-settings [dev data]
  (send-recv dev (opcode [:set-sram-settings]) data))

;; Spec
(defn specs []
  {:system {:power #{:3.3 :5.0}}
   :pins #{:GP0 :GP1 :GP2 :GP3}
   :designation {:GP0 {0 :GPIO
                       1 :SSPND
                       2 :LED-UART-RX}
                 :GP1 {0 :GPIO
                       1 :CLKR
                       2 :ADC1
                       3 :LED-UART-TX
                       4 :INTERRUPT-DETECTION}
                 :GP2 {0 :GPIO
                       1 :USBCFG
                       2 :ADC2
                       3 :DAC1}
                 :GP3 {0 :GPIO
                       1 :LED-I2C
                       2 :ADC3
                       3 :DAC2}}
   :capabilities {:GP0 #{:GPIO}
                  :GP1 #{:GPIO :ADC1 :CLKR}
                  :GP2 #{:GPIO :ADC2 :DAC1}
                  :GP3 #{:GPIO :ADC3 :DAC2}}
   :duty {:0 0x00
          :25 0x08
          :50 0x10
          :75 0x18}

   :frequency {:24M 0x01      ;; 24MHz
               :12M 0x02      ;; 12MHz
               :6M 0x03      ;;  6MHz
               :3M 0x04      ;;  3Mhz
               :1.5M 0x05     ;; 1.5Mhz
               :750K 0x06     ;; 750KHz
               :375K 0x07}    ;; 375KHz
   :divider {:2 0x01      ;; 24MHz
             :4 0x02      ;; 12MHz
             :8 0x03      ;;  6MHz
             :16 0x04     ;;  3Mhz
             :32 0x05     ;; 1.5Mhz
             :64 0x06     ;; 750KHz
             :128 0x07}   ;; 375KHz
   :VRM {:VDD 2r10000000
         :OFF 2r10000001
         :1.024 2r10000011
         :2.048 2r10000101
         :4.096 2r10000111}

   :GPIO {:direction {:OUT 0
                      :IN 1}}})

(defn pin-mappings [pin fn-id]
  ({[:GP0 :GPIO] [[7 0x80]
                  [8 0x00]]
    [:GP1 :GPIO] [[7 0x80]
                  [9 0x00]]
    [:GP2 :GPIO] [[7 0x80]
                  [10 0x00]]
    [:GP3 :GPIO] [[7 0x80]
                  [11 0x00]]
    [:GP1 :ADC1] [[7 0x80]
                  [9 0x02]]
    [:GP1 :CLKR] [[7 0x80]
                  [9 0x01]]
    [:GP2 :ADC2] [[7 0x80]
                  [10 0x02]]
    [:GP3 :ADC3] [[7 0x80]
                  [11 0x02]]
    [:GP2 :DAC1] [[7 0x80]
                  [10 0x03]]
    [:GP3 :DAC2] [[7 0x80]
                  [11 0x03]]}
   [pin fn-id]))


(defn pin? [dev pin-name]
  (contains? (:pins (specs)) pin-name))

(defn valid-assign? [dev pin fn-id]
  (fn-id (-> dev specs :capabilities pin)))



(defn uart-open [path]
  (port/open :path path))

(defn uart-open? [async-port]
  (port/open? async-port))

(defn uart-close [async-port]
  (when (uart-open? async-port)
    (port/close! async-port)))

(defn uart-read [port]
  (port/read-bytes port 1))

(defn uart-write [port data]
  (port/write port data))



(defmulti parse* (fn [{:keys [request response]}]
                   (or request nil)))

(defmethod parse* (opcode [:status-set-parameters]) [{:keys [response]}]
  {:error (nth response 1)
   :cancel-transfer (nth response 2)
   :i2c-state-machine (nth response 8)
   :lower-byte-of-transfer-length (nth response 9)
   :higher-byte-of-transfer-length (nth response 10)
   :current-i2c-speed-divider (nth response 14)
   :scl-line-value (nth response 22)
   :sda-line-value (nth response 23)
   :hardware-revision (str/join (map char (take 2 (drop 46 response))))
   :firmware-revision (str/join (map char (take 2 (drop 48 response))))
   :i2c-read-pending (nth response 25)
   :adc-data (mapv #(bit-and 0xff %)
                   [(bit-or (nth response 50)
                            (bit-shift-left (nth response 51) 8))
                    (bit-or (nth response 52)
                            (bit-shift-left (nth response 53) 8))
                    (bit-or (nth response 54)
                            (bit-shift-left (nth response 55) 8))])})

(defmethod parse* (opcode [:read-flash-data :read-gp-settings]) [{:keys [response]}]
  (let [[_ result _  _ gp0 gp1 gp2 gp3] response]
    (->> [gp0 gp1 gp2 gp3]
         (reduce (fn [r x]
                   (conj r {(keyword (str "GP" (count r)))
                            {:out (bit-and (bit-shift-right x 4) 2r00000001)
                             :direction (bit-and (bit-shift-right x 3) 2r00000001)
                             :designation (bit-and x 2r00000111)}}))
                 {}))))

(defmethod parse* (opcode [:read-flash-data :read-usb-manufacture]) [{:keys [response]}]
  (let [[_ result size+2 _ & rest] response]
    (->> rest
         (take (- size+2 2))
         (partition 2)
         (map (fn [[a b]]
                (char (+ (bit-shift-left b 17) a))))
         (str/join ))))

(defmethod parse* (opcode [:read-flash-data :read-usb-product]) [{:keys [response]}]
  (let [[_ result size+2 _ & rest] response]
    (->> rest
         (take (- size+2 2))
         (partition 2)
         (map (fn [[a b]]
                (char (+ (bit-shift-left b 17) a))))
         (str/join))))

(defmethod parse* (opcode [:read-flash-data :read-usb-serial]) [{:keys [response]}]
  (let [[_ result size+2 _ & rest] response]
    (->> rest
         (take (- size+2 2))
         (partition 2)
         (map (fn [[a b]]
                (char (+ (bit-shift-left b 17) a))))
         (str/join ))))

(defmethod parse* (opcode [:read-flash-data :read-chip-factory]) [{:keys [response]}]
  (let [[_ result _  _ & rest] response]
    ;; Likely the size is 8 bytes
    (->> rest
         (take 8)
         (str/join ))))

(defmethod parse* (opcode [:get-sram-settings]) [{:keys [response]}]
  {:clock-output-divider {:duty (bit-and (nth response 5) 2r000011000)
                          :divider (bit-and (nth response 5) 2r00000111)}
   :dac-reference {:voltage-option (bit-and (nth response 6) 2r11000000)
                   :option (bit-and (nth response 6) 2r0100000)
                   :power-up-value (bit-and (nth response 6) 2r00011111)}
   :gp0-settings (nth response 22)
   :gp1-settings (nth response 23)
   :gp2-settings (nth response 24)
   :gp3-settings (nth response 25)})

(defmethod parse* (opcode [:get-gpio-values]) [{:keys [response]}]
  {:GP0 {:value (nth response 2)
         :direction (nth response 3)}
   :GP1 {:value (nth response 4)
         :direction (nth response 5)}
   :GP2 {:value (nth response 6)
         :direction (nth response 7)}
   :GP3 {:value (nth response 8)
         :direction (nth response 9)}})

(defmethod parse* (opcode [:set-gpio-output-values]) [{:keys [response]}]
  {:GP0 {:alter-output (nth response 2)
         :value (nth response 3)
         :alter-direction (nth response 4)
         :direction (nth response 5)}
   :GP1 {:alter-output (nth response 6)
         :value (nth response 7)
         :alter-direction (nth response 8)
         :direction (nth response 9)}
   :GP2 {:alter-output (nth response 10)
         :value (nth response 11)
         :alter-direction (nth response 12)
         :direction (nth response 13)}
   :GP3 {:alter-output (nth response 14)
         :value (nth response 15)
         :alter-direction (nth response 16)
         :direction (nth response 17)}})


(defmethod parse* (opcode [:get-i2c-data]) [{:keys [response]}]
  {:error (nth response 1)
   :i2c-state-machine (nth response 2)
   :size (nth response 3)
   :data (drop 4 response)})

(defmethod parse* (opcode [:i2c-read-data]) [{:keys [response]}]
  {:error (nth response 1)
   :state (nth response 2)})

(defmethod parse* (opcode [:i2c-write-data]) [{:keys [response]}]
  {:error (nth response 1)
   :state (nth response 2)})

(defmethod parse* nil [{:keys [request]}]
  {:error 1
   :request request
   :msg "response is nothing"})

(defmethod parse* :default [{:keys [request]}]
  (str "No implement for [" (str/join " " (mapv #(format "0x%x" %) request)) "]"))

;; A function which convert a number to bit sequence
;; Ex. 18 => (0 0 0 1 0 0 1 0)


(defn success? [response]
  (and (= 0x00 (nth response 1))
       (not= 0x25 (nth response 2))))

