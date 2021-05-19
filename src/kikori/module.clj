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


(ns kikori.module
  (:refer-clojure :exclude [read])
  (:require [clojure.core.async :refer [chan poll! go go-loop alts! put!
                                        >! >!! <!! <!  alts!! timeout
                                        sliding-buffer mult tap untap
                                        close! pipe pipeline pipeline-async pipeline-blocking]]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:require [kikori.core :refer :all]
            [kikori.device :as dev]
            [kikori.util :as util]
            [kikori.interop :refer [invoke-edge-constructor]]
            [kikori.graphic :as g])
  (:require [taoensso.timbre :as log]))


(defmacro defmodule [module-name & body]
  (let [dispatch-key (keyword module-name)]
    (apply list
           'do
           (map (fn [[fname & opts]]
                  `(defmethod ~fname ~dispatch-key ~@opts))
                body))))

(defn get-method-in-class [cname mname]
  (->> (Class/forName cname)
       (.getMethods)
       (filter #(= mname (.getName %)))
       (first)))


(defmacro load-java-module [class-name]
  (let [sym (gensym)
        ms (->> (Class/forName class-name)
                (.getMethods)
                (filter #((set ["init" "read" "write" "close" "listen" "view"]) (.getName %)))
                (map (fn [m]
                       `(defmethod ~(symbol (.getName m)) ~(keyword class-name) [sensor-map#]
                          (.invoke
                           (get-method-in-class ~class-name ~(.getName m))
                           ~sym
                           (into-array Object [(invoke-edge-constructor
                                                (dev/map->Sensor sensor-map#))]))))))]
    `(let [~sym (.newInstance (Class/forName ~class-name))]
       ~@ms)))


(defn load-module [module-path & options]
  ;; Try require first
  (let [mp (str/replace module-path #"/" ".")]
    (loop [mps [mp
                (str "module." mp)
                (str "kikori.module." mp)]
           results []]
      (if-not (seq mps)
        (log/info "Driver not found:" module-path)
        (let [result (try
                       (apply require (symbol (first mps)) options)
                       (catch java.io.FileNotFoundException e false))]
          (if (nil? result)
            (log/info "Loaded:" (first mps))
            (recur (rest mps) (conj results result))))))))





(defn load-modules [& require-option]
  (doseq [m ["bme280" "gps" "d6t44l" "d7s" "adxl345" "irmagician" "tick" "2jcie"]]
    (apply load-module m require-option)))

