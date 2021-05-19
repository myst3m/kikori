(ns kikori.pin-test
  (:refer-clojure :exclude [read])
  (:require [clojure.test :refer :all]
            [clojure.data.json :as json]
            [kikori.core :refer :all]
            [kikori.module :refer :all]
            [kikori.device :as dev]
            [kikori.operation :refer :all]
            [kikori.util :as util]
            [kikori.server :as srv]
            )
  (:require [org.httpkit.client :as http]
            [taoensso.timbre :as log])
  (:require [incanter.core :as i]
            [incanter.charts :refer :all]))

(defn chart! [chart data]
  (let [mx (apply max data)
        mn (apply min data)
        scale (fn [x] (/ (* 2 (- x mn)) (- mx mn)) )
        scaled-data (map scale data)
        mean (/ (reduce + scaled-data) (count scaled-data))]
    (add-lines chart (take (count scaled-data) (iterate inc 0)) (map #(double (- % mean)) scaled-data))))


(defn adc-plot [hz sampling-ms & {:keys [data direct?]}]
  (let [xdata (or data (:ADC1 (dev/adc-read (*d) {:direct? direct? :count 100})))]
    (i/view (-> (xy-plot)
             ;;(function-plot (fn [t] (i/sin (* sampling-ms Math/PI 2 t (/ hz 1000)))) 0 100)
             (chart! xdata)))
    xdata))

(defn config-user-test []
  (on-platform
   (+device :hidraw
            (config :system {:PCB "1.0.0" :power :5.0})
            (config :ADC {:vrm :VDD})
            (bind :GP0 :GPIO)
            (bind :GP2 :ADC2)
            (place :GP0 {:name "GP0"})
            (place :GP2 {:name "ADC2"}))))

(use-fixtures :once (fn [f]
                      (util/set-log-level! :warn)
                      (srv/start-web-server)
                      (doall (config-user-test))
                      (f)
                      (srv/stop-web-server)
                      (shutdown)))

(deftest gpio-test
  (testing "GPIO out"
    (testing "normal out"
      (let [{:keys [alter-direction direction]} (dev/gpio-set-mode! (*d) :GP0 :OUT)]
        (is (= alter-direction 1))
        (is (= direction 0)))
      (let [{:keys [alter-output value]} (dev/gpio-out (*d) :GP0 1)]
        (is (= alter-output 1))
        (is (= value 1)))
      (let [value (dev/gpio-in (*d) :GP0)]
        (is (= 1 value))))
    (testing "HTTP write"
        (let [body (:body @(http/get "http://localhost:3000/write"
                                     {:query-params {:target "GP0"
                                                     :value 1}}))
              {:keys [target value result]} (json/read-str body :key-fn keyword)]
          (is  (= result "success")))))

  (testing "GPIO in"
    (testing "normal in"
      (let [{:keys [alter-direction direction]} (dev/gpio-set-mode! (*d) :GP0 :IN)]
        (is (= alter-direction 1))
        (is (= direction 1)))
      (let [v (dev/gpio-in (*d) :GP0)]
        (is (or (= v 0) (= v 1))))))

  (testing "GPIO block read"
    (testing "normal in"
      (let [{:keys [alter-direction direction]} (dev/gpio-set-mode! (*d) :GP0 :IN)]
        (is (= alter-direction 1))
        (is (= direction 1)))
      (let [vs (dev/gpio-read (*d) {:pin :GP0 :count 100})]
        (is (every? number? vs))))))

(deftest adc-test
  (testing "ADC read"
    (testing "normal read"
      (let [{data :ADC2} (dev/adc-read (*d) {:count 100})]
        (is (= 100 (count data)))
        (is (every? number? data))))
    (testing "direct read"
      (let [{data :ADC2} (dev/adc-read (*d) {:direct? true :count 100})]
        (is (= 100 (count data)))
        (is (every? number? data))))
    (testing "HTTP Get"
      (testing "Sampling 100"
        (let [body (:body @(http/get "http://localhost:3000/read"
                                     {:query-params {:target "ADC2"
                                                     :sampling 100
                                                     :type "raw"}}))
              {:keys [target value]} (json/read-str body :key-fn keyword)]
          (is  (sequential? value))
          (is  (= 100 (count value)))))
      (testing "No Option"
        (let [body (:body @(http/get "http://localhost:3000/read"
                                     {:query-params {:target "ADC2"}}))
              {:keys [target value]} (json/read-str body :key-fn keyword)]
          (is  (sequential? value))
          (is  (= 1 (count value))))))))








