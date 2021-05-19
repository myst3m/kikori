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


(ns kikori.facade
  (:refer-clojure :exclude [read])  
  (:import [purejavahidapi PureJavaHidApi HidDeviceInfo HidDevice InputReportListener])
  (:require [kikori.core :refer :all]
            [kikori.device :as dev]
            [kikori.util :as util]
            [kikori.module :refer :all])

  (:require [clojure.core.async :as a :refer [chan <! >! put! >!! <!! go go-loop alts! close!
                                              poll!
                                              sliding-buffer thread timeout]])
  (:require [clojure.string :as str])
  (:require [taoensso.timbre :as log]))

(def ^:private ^:dynamic *i2c-default-edge* (atom nil))

(defn set-default-i2c-edge! [addr]
  (if-let [edge (first (filter #(= addr (:addr %)) (sensors)))]
    (reset! *i2c-default-edge* edge)
    (log/error "Not identified specified I2C edge with addr")))

(defn i2c-write 
  ([name data]
   (let [edge (first (filter #(and (= name (:name %))
                                   (= :I2C (:bus %))) (sensors)))
         dev (devices (:device-id edge))]
     (if (and edge dev)
       (dev/i2c-write! dev {:addr (:addr edge) :data data})
       (log/error "Not found specified edge or device")))))

(defn i2c-read
  ([name]
   (first (i2c-read name 1)))
  ([name size]
   (let [edge (first (filter #(and (= name (:name %))
                                   (= :I2C (:bus %))) (sensors)))
         dev (devices (:device-id edge))]
     (if (and edge dev)
       (map #(bit-and 0xff %) (dev/i2c-read dev {:addr (:addr edge) :size size}))
       (log/error "Not found specified edge or device")))))


(defn gpio-write [name value]
  (let [edge (sensors name)
        pin (:bus edge)]
    (if-let [dev (devices (:device-id edge))]
      (dev/gpio-write! dev {:pin pin :value value })
      (log/warn "Not found device"))))

(defn gpio-read
  ([name]
   (gpio-read name 1))
  
  ([name size]
   (let [edge (sensors name)
         pin (:bus edge)]
     (if-let [dev (devices (:device-id edge))]
       (dev/gpio-read dev {:pin pin :count size})
       (log/warn "Not found device")))))


(defn list-sensors []
  (map :name (sensors)))

(defn read-sensors [& ids]
  (read {:op :sensors :ids ids}))


(defn- ir-put! []
  (put! (:host-out (sensors "GP")) "I,0\r"))

(defn- ir-poll! []
  (str/join (map char (take-while #(not= % nil) (lazy-seq (map (fn [_] (poll! (:host-in (sensors "GP")))) (repeat 0)))))))
