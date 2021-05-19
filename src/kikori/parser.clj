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


(ns kikori.parser
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]])
  (:require [instaparse.core :as i])
  (:require [taoensso.timbre :as log]))



(def ^:private parser
  (i/parser
   "S = (require-module | import-java-module | platform | webserver | influxdb | nrepl | log | confirm | console)+

    <require-module> =  <white*> <'module:'> <white*> modules
    <modules> = (module <white*> <comma*> <white*>)+ 

    <import-java-module> = <white*> <'java-module:'> <white*> java-modules
    <java-modules> = (java-class <space*> <comma*> <space*>)+ 

    module = #'[a-zA-Z0-9_-]+'
    java-class = #'[a-zA-Z0-9_.]+'

    platform = <white*>  <'platform:'> <white*> (interface|device)+
    device = <space*> <'device:'> <space*> (options | #'[a-zA-Z0-9_#%&\\\\]+') <white*> ( bind | config | place)* 
    interface = <space*> <'interface:'> <white*> (config | place)* 

    bind = <space*> <'bind:'> <space+> key <space+> key <white*>
    config = <space*> <'config:'> <space+> key <space*> options* <white*>
    place = <space*> <'place:'> <space+> key <space*> options* <white*>

    options = <'{'> option+ <'}'>
    option = <space*> key <space*> value <space*> <comma*>

    nrepl = <white*> <'nrepl:'> <white*> (ip | port | cider)+
    cider = <white*> <'cider:'> <white*> bool <white*>

    influxdb = <white*> <'influxdb:'> <white*> (ip | port | secrets | database | tags | source | interval)+
    secrets = <white*> <'secrets:'> <white*> #'[a-zA-Z0-9./_]+' <white*>
    database = <white*> <'database:'> <white*> #'[a-zA-Z0-9_]+' <white*>
    interval = <white*> <'interval:'> <white*> number <white*>
    tags = <space*> <'tags:'> <space*> options* <white*>

    source =  <white*> <'source:'> <white*> array
    array = <'['>  (<white*>(string | symbol) <white*> <comma*>)+ <']'> <white*>


    webserver = <white*> <'webserver:'> <white*> (ip | port)+


    ip = <space*> <'ip:'> <white*> #'[a-zA-Z0-9.]+' <white*>
    port = <space*> <'port:'> <white*> number <white*>





    <log> = <white*> <'log:'> <white*> (log-level | log-output)+ <white*>
    log-level = <white*> <'level:'> <white*> ('error' | 'warn' | 'info' | 'debug') <white*>
    log-output = <white*> <'output:'> <white*> path <white*>
    <path> = #'[/a-zA-Z0-9/@#:;$%^&!_-~`.]+'

    confirm = <white*> <'confirm:'> <white*> bool <white*>
        

    console = <white*> <'console:'> <white*> bool <white*>

    key = #'[a-zA-Z0-9_-]+'
    <value> = (number | string)+ 
    bool = 'true' | 'false'

    string = <'\"'> #'[a-zA-Z0-9/@#:;$%^&!_-~`.]*' <'\"'>

    symbol = #'[a-zA-Z0-9/@#:;$%^&!_-~`.]+'

    <crlf> = #'[\n]'
    <space> = #'[ \t]*'
    <white> = #'[\n \t]'
    <comma> = ','
    comment = #'#+'
    number = hex | decimal
    <hex> = #'(0x)[a-zA-Z0-9]+'
    <decimal> = #'[0-9]+' "))


(defn parse [text]
  (let [console? (atom false)
        log-output (atom nil)]
    (filter some? (i/transform {:S (fn [& xs] (concat (cons 'do xs) (list '(boot!)
                                                                          (when @log-output (list 'set-log-output! @log-output))
                                                                          (when @console?
                                                                            '(kikori.shell/repl)))))
                                :module (fn [& xs] (cons 'load-module xs))
                                :java-class (fn [& xs] (cons 'load-java-module xs))
                                :key (fn [x & _] (keyword x))
                                :number (fn [x & _] (read-string x))
                                :options (fn [& xs] (into {} (map vec xs)))
                                :option (fn [& xs]
                                          (let [[k v] xs]
                                            [k (cond
                                                 ;; device
                                                 (and (= k :path) (= v "auto")) (keyword v)
                                                 (and (= k :id) (= v "any")) (keyword v)
                                                 (= k :os) (keyword v)
                                                 ;; ADC
                                                 (= k :vrm) (keyword v)
                                                 ;; system
                                                 (= k :power) (keyword v)
                                                 ;; place
                                                 (= k :module) (keyword v)
                                                 ;; CLKR
                                                 (#{:duty :divider :frequency} k) (keyword v)
                                                 :else v)]))
                                :string (fn [& xs] (str/join xs))
                                :platform (fn [& xs] (cons 'on-platform xs))
                                :interface (fn [& xs] (cons '+interface xs))
                                :device (fn [& xs] (cons '+device xs))
                                :config (fn [& xs] (cons 'config xs))
                                :bind (fn [& xs] (cons 'bind xs))
                                :place (fn [& xs] (cons 'place xs))
                                :webserver (fn [& xs] (cons 'start-web-server (flatten xs)))
                                :array (fn [& xs] (vec xs))
                                :influxdb (fn [& xs] (cons 'start-influxdb-client (apply concat xs)))
                                :nrepl (fn [& xs] (cons 'start-nrepl-server (apply concat xs)))
                                :log-level (fn [& xs] (cons 'set-log-level! (map keyword xs)))
                                :path (fn [& xs] (str/join xs))
                                :log-output (fn [& xs] (when (first xs)
                                                         (reset! log-output (first xs))
                                                         nil))
                                :bool (fn [& xs] (read-string (first xs)))
                                :confirm (fn [& xs] (when (first xs)
                                                      (list 'confirm!)))
                                :console (fn [& xs] (when (first xs)
                                                      (reset! console? true)
                                                      nil))}
               
                               (parser (str/replace text #"#.*\n*" ""))))))




;;; Sample: conf

;;; #java-module: D6t44lJava
;;; 
;;; platform: 
;;;   device: hidraw0
;;;     bind: GP0 GPIO
;;;     bind: GP1 GPIO
;;;     bind: GP2 ADC2
;;;     config: I2C
;;;     config: UART {path "/dev/ttyACM0"}
;;;     place: I2C {addr 0x0a, name "D6T44L0", module "D6T44L"}        
;;;     place: I2C {addr 0x53, name "ADXL0", module "ADXL345"}
;;;     place: I2C {addr 0x76, name "BME0", module "BME280"}
;;;     place: I2C {addr 0x77, name "BME1", module "BME280"}    
;;;     place: UART {name "GPS0" module "GPS"}
;;;     place: GP2 {name "ADC2"}
;;;   device: hidraw1
;;;       place: GP2 {name "ADC2"}
;;;   
;;; ## Here is comment
;;; 
;;; #webserver:
;;; #    ip: "0.0.0.0"
;;; #    port: 3000
