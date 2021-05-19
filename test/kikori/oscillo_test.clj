(ns kikori.oscillo-test
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

(defn config-user-test []
  (on-platform
   (+device :hidraw
            (config :system {:PCB "1.0.0" :power :5.0})
            ;; (config :ADC {:vrm :2.048})
            (config :DAC {:vrm :VDD})
            (bind :GP0 :GPIO)
            (bind :GP1 :CLKR)
            (bind :GP2 :DAC1)
            (bind :GP3 :GPIO)
            (place :GP1 {:name "clkr"}))))

(deftest clkr-test
  (testing "CLKR config"
    (-> (*d) (bind :GP1 :CLKR) (ready!))
    (is (= :CLKR (:GP1 (*d)))))
  (testing "CLKR write"
    ;; :duty (:0 :25 :50 :75)
    ;; :divider (:2 :4 :8 :16 :32 :64 :128)
    ;; :frequency (:24M :12M :6M :3M :1.5M :750K :375K)
    (dev/clkr-write! (*d) {:duty :0 :divider :2})
    (let [{{:keys [duty divider]} :clock-output-divider} (dev/parse (*d) (dev/sram-read (*d)))]
      (is (= duty 0))
      (is (= divider 1)))))

(deftest dac-test
  (testing "CLKR write"
    ;; :value [0 31]
    (dev/dac-write! (*d) {:value 10})
    ;; (let [{{:keys [duty divider]} :clock-output-divider} (dev/parse (*d) (dev/sram-read (*d)))]
    ;;   (is (= duty 0))
    ;;   (is (= divider 1)))
    ))
