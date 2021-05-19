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

(ns groovy-iot
  (:refer-clojure :exclude [read])
  (:require [kikori.core :as k]))

(k/refer-kikori)
(set-log-level! :warn)

(defn register-device []
  (with-first-device
    (config :I2C)
    (bind :GP0 :GPIO)
    (bind :GP1 :ADC1)
    (place :GP1 {:name "ADC1"})        
    (ready!)))

(register-device)
(srv/start-web-server :ip "0.0.0.0" :port 3000)
