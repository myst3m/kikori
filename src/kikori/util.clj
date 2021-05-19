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


(ns kikori.util
  (:import [java.net InetAddress])
  (:require [clojure.string :as str]
            [clojure.core.async :as a :refer [sliding-buffer put! chan go-loop alts! poll!]])
  (:require [taoensso.timbre :as log]
            [taoensso.timbre.appenders.core :as appenders]))

;; For log
(defn set-log-output!
  ([]
   (log/merge-config! {:appenders {:spit nil
                                   :println {:enabled? true}}}))
  ([fname & {:keys [console?] :or {console? false}}]
   (log/merge-config! {:appenders {:spit (appenders/spit-appender {:fname fname})
                                  :println {:enabled? (true? console?)}}})))

(defn current-log-cofig []
  log/*config*)

(defn set-log-level! [level]
  (if (#{:error :warn :info :debug :trace} level)
    (log/set-level! level)
    (log/error "Invalid Log level" level)))


(defn clear [dev]
  (loop []
    (when-let [garbage (poll! (:port-out dev))]
      (log/debug garbage)
      (recur))))

(defn hex [x]
  (cond
    (number? x) (symbol (str "0x" (Long/toHexString (bit-and 0xff x))))
    (sequential? x) (mapv hex x)
    :else x))

(defn keyword->number [x]
  (try
    (Float/parseFloat (name x))
    (catch Exception e (log/error (.getMessage e)))))

(def ^:private ctrl (chan))
(def trace-channel (chan (sliding-buffer 32)))
(def ^:private ^:dynamic *tracing-running* (atom false))

(defn tracing! []
  (if @*tracing-running*
    (put! ctrl :quit)
    (do (set-log-level! :trace)
        (log/info "Tracer start")
        (reset! *tracing-running* true)
        (go-loop []
          (let [[data ch] (alts! [trace-channel ctrl])]
            (condp = data
              :quit (do (set-log-level! :info)
                        (reset! *tracing-running* false)
                        (log/info "Tracer quit"))
              (do (log/trace data)
                  (recur))))))))


(defn hostname []
  (first (str/split (str (InetAddress/getLocalHost)) #"/")))

(defn print* [& [text]]
  (print text)
  (flush))


(def ^:dynamic *confirm-on-boot* (atom false))

(defn confirm [dev specs]
  (if-not  @*confirm-on-boot*
    ;; Auto boot. Return true without user interruption
    true
    (do
      (println "============ Device Configuration ============")
      (println)
      (-> dev
          (update-in [:product-id] hex)
          (update-in [:vendor-id] hex)
          (select-keys (vec (flatten [[:system :product-id :vendor-id]
                                      (sort (:pins specs))
                                      [:uart :sensors]])))
          (as-> x
              (assoc x :sensors (->> (vals (:sensors x))
                                     (map #(dissoc % :initialized? :device-id))
                                     (reduce (fn [r {:keys [name addr] :as sensor}]
                                               (assoc r
                                                      (symbol name)
                                                      (if addr
                                                        (assoc sensor
                                                               :addr (hex addr))
                                                        sensor)))
                                             {}))))
          (->> (reduce (fn [r [k v]] (if v (conj r {k v}) r)) {}))
          (clojure.pprint/pprint))
      
      (println)
      (println "==============================================")
      (println)
      (loop []
        (print*  "Configure the device with above ? (Yes [Y] or No [N]) ")
        (let [user-input (read-line)
              y-or-n (str/trim (str/lower-case  user-input))]
          (cond 
            (#{"yes" "y"} y-or-n) true
            (#{"no" "n"} y-or-n) false
            :else (recur)))))))

(defn confirm!
  ([]
   (confirm! true))
  ([v]
   (reset! *confirm-on-boot* v)))


(defn os []
  (let [os (str/lower-case (System/getProperty "os.name"))
        os (cond 
             (re-find #"linux" os) :linux
             (re-find #"mac" os) :macosx
             (re-find #"win" os) :windows
             :else :out-of-support)]
    (log/debug "OS identified:" os)
    os))

(defn arch []
  (let [arch (str/lower-case (System/getProperty "os.arch"))]
    (log/debug "Arch  identified:" arch)
    (keyword arch)))



(defn- gen-crc16-table []
  (loop [i 0
         j 0
         c 0
         table []]
    (cond
      (and (= i 255) (= j 8)) table
      (< j 8) (recur i (inc j) (if (= 1 (bit-and c 1))
                                 (bit-xor 0xa001 (bit-shift-right c 1))
                                 (bit-shift-right c 1))
                     table)
      (= j 8) (recur (inc i) 0 (inc i) (conj table c)))))

(defn crc16 [& data]
  (let [table (gen-crc16-table)]
    (loop [bs (mapcat  #(if (string? %)
                          (.getBytes %)
                          %)
                       data)
           crc 0xffff]
      (if-not (seq bs) 
        crc
        (recur (rest bs) (bit-xor (nth table (bit-and 0xff (bit-xor crc (first bs))))
                                  (bit-shift-right crc 8)))))))


(defn little-endian [bs & [n]]
  (cond
    (sequential? bs) (reverse bs)
    (number? bs) (loop [r '()
                        x bs]
                   (if (= 0 x)
                     (if n
                       (take n (lazy-cat (reverse r) (repeat 0)))
                       (reverse r))
                     (recur (conj r (bit-and x 0xff)) (bit-shift-right x 8))))))


(defn bytes->long [xs & {:keys [endian] :or {endian :little}}]
  (reduce (fn [r x]
            (+ x (bit-shift-left r 8)))
          0
          (if (= endian :little)
            (reverse xs)
            xs)))
