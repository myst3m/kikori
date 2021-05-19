(ns kikori.interface-test
  (:refer-clojure :exclude [read])
  (:require [clojure.core.async :refer [chan poll! put! >!! <!! timeout]])
  (:require [gniazdo.core :as ws])
  (:require [clojure.test :refer :all]
            [clojure.data.json :as json]
            [kikori.core :refer :all]
            [kikori.module :refer :all]
            [kikori.device :as dev]
            [kikori.operation :refer :all]
            [kikori.util :as util]
            [kikori.server :as srv]
            [kikori.facade :refer :all])
  (:require [org.httpkit.client :as http]
            [taoensso.timbre :as log]))


(defn config-user-test []
  (on-platform
   (+interface
    (place :USER {:name "TICK0" :module :Tick}))
   (+device :hidraw0
            (config :system {:PCB "1.0.0" :power :5.0})
            (config :I2C)
            (config :UART {:path "/dev/ttyACM0"})
            (config :ADC {:vrm :2.048})
            (bind :GP0 :GPIO)
            (bind :GP1 :GPIO)
            (bind :GP2 :ADC2)
            (bind :GP3 :GPIO)
            (place :GP0 {:name "LED0"})
            (place :GP2 {:name "ADC2"})
            (place :I2C {:addr 0x76 :name "BME0" :module :BME280})
            (place :I2C {:addr 0x77 :name "BME1" :module :BME280})
            (place :I2C {:addr 0x0a :name "D6T44L0" :module :D6T44L})            
            (place :UART {:name "GPS0" :module :GPS}))))

(def ^:dynamic *ws* (atom nil))
(def ws-data-ch (chan 64))

(defn connect []
  (reset! *ws* (ws/connect "ws://localhost:3000/ws"
                 :on-connect (fn [sess]
                               (println "Established connection"))
                 :on-receive (fn [text]
                               (put! ws-data-ch (json/read-str text)))
                 :on-error (fn [ex]
                             (println "Error: Connection Disconnected"))
                 :on-close (fn [code msg]
                             (reset! *ws* nil)
                             (println "Closed connection")))))

(use-fixtures :once (fn [f]
                      (util/set-log-level! :warn)
                      (load-modules)
                      (srv/start-web-server)
                      (connect)
                      (doall (config-user-test))
                      (f)
                      (shutdown)
                      (srv/stop-web-server)))

(deftest websocket-test
  (testing "Tick"
    (testing "start"
      (ws/send-msg @*ws* (str {:op :listen :v ["TICK0"]}))
      (<!! ws-data-ch) ;; {"result" "success", "msg" "Success"}

      (let [{:strs [TICK0] :as x} (<!! ws-data-ch)]
        (is (map? x))
        (is (or (= 0 TICK0)
                (= 1 TICK0)))
        (let [z (<!! ws-data-ch)]
          (is (map? z))
          (is (if (= 0 TICK0)
                (= 1 (get z "TICK0"))
                (= 0 (get z "TICK0")))))))
    (testing "stop"
      (ws/send-msg @*ws* (str {:op :stop}))
      (<!! (timeout 3000)) ;; Wait for a while
      (doall
       (take-while (fn [_] (not (nil? (poll!  ws-data-ch)))) (repeat 0))) ;; pull garbage
      (<!! (timeout 3000)) ;; Wait for a while
      (let [x (poll!  ws-data-ch)]
        (is (nil? x))))))

(deftest call-test
  (let [{:strs [BME1 ADC2 D6T44L0 GPS0]} (read-sensors)
        {:keys [pressure temperature humidity]} BME1
        {:keys [PTAT PX]} D6T44L0
        {:keys [raw]} GPS0]
    (testing "ADC2"
      (is  (number? (first ADC2))))
    (testing "BME1"
      (is  (number? pressure))
      (is  (number? temperature))
      (is  (number? humidity)))
    (testing "D6T44L0"
      (is (number? PTAT))
      (is (every? number? PX)))
    (testing "GPS0"
      (is (string? raw)))))

(deftest http-get-test
  (testing "HTTP GET"
    (testing "scan"
      (is (= 200 (:status @(http/get "http://localhost:3000/scan")))))
    (testing "sensor"
      (let [body (:body @(http/get "http://localhost:3000/sensor"
                                   {:query-params {:ids ["GPS0" "BME1" "ADC2" "D6T44L0"]}}))
            {:keys [BME1 ADC2 D6T44L0 GPS0]} (json/read-str body :key-fn keyword)
            {:keys [pressure temperature humidity]} BME1
            {:keys [PTAT PX]} D6T44L0
            {:keys [raw]} GPS0]
        (testing "ADC2"
          (is  (number? (first ADC2))))
        (testing "BME1"
          (is  (number? pressure))
          (is  (number? temperature))
          (is  (number? humidity)))
        (testing "D6T44L0"
          (is (number? PTAT))
          (is (every? number? PX)))
        (testing "GPS0"
          (is (string? raw)))))
    
    (testing "read"
      (let [body (:body @(http/get "http://localhost:3000/read"
                                   {:query-params {:target "ADC2"
                                                   :sampling 100
                                                   :type "raw"}}))
            {:keys [target value]} (json/read-str body :key-fn keyword)]
        
        (testing "ADC2"
          (is  (sequential? value))
          (is  (= 100 (count value))))))
    (testing "write"
      (let [target "LED0"
            {:keys [bus]} (sensors target)
            body (:body @(http/get "http://localhost:3000/write"
                                   {:query-params {:target target
                                                   :value "1"}}))]
        (if (*d)
          (let [{{:keys [value]} bus} (dev/gpio-current-settings (*d))]
            (testing target
              (is  (= 1 value))))
          false)))))
