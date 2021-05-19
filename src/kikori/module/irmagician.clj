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

;; Example to call
;; Assume there is a file named bulb_B.json in /tmp
;; 
;; (write (conj (sensors "irm") {:data-path "/tmp" :op "bulb_B"}))

(ns kikori.module.irmagician
  (:refer-clojure :exclude [read write])
  (:require [kikori.core :refer :all]
            [kikori.module :refer (defmodule)])
  (:require [irmagician.core :as i]
            [irmagician.port :as p]))

(refer-kikori)


(defmodule IrMagician
  (init [{:keys [uart name data-path] :as m}]
        (log/debug "IrMagician" uart)
        (i/set-default-ir-port!  (p/map->AsyncPort uart))
        m)
  
  (write [{:keys [data-path op] :as sensor}]
         (log/debug sensor)
         (try
           (let [file (str data-path "/" op ".json")]
             (log/debug "IR data file:" file)
             (if (.exists (io/as-file file))
               (do (i/ir-restore file)
                   (i/ir-play))
               (log/info "No data file: " file)))
           (catch Throwable e (log/error (.getMessage e)))))

  (close [sensor]
         (log/debug "IrMagician module close")
         (i/set-default-ir-port! nil))) 
