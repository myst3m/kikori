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

(ns kikori.hid
  ;; For Common
  (:import [com.sun.jna FromNativeContext Structure Pointer IntegerType Memory Native]
           [purejavahidapi PureJavaHidApi])
  ;; For Linux
  (:import [purejavahidapi.linux
            CLibrary UdevLibrary CLibrary$pollfd
            UdevLibrary$hidraw_report_descriptor HIDRAW])

  ;; For Windows
  (:import [purejavahidapi.windows
            HidDeviceInfo
            WinDef HidLibrary$HIDP_CAPS WinDef$HANDLE
            WinDef$OVERLAPPED Kernel32Library WindowsBackend])


  (:import [java.util Properties])
  
  (:require [kikori.util :as util]
            [kikori.jna :refer [struct->native-buf pointer]])
  (:import [kikori.jna Pollfd])
  
  (:require [clojure.core.async :as a :refer [chan <! >! put! >!! <!! 
                                              poll! go-loop alts! close!
                                              sliding-buffer thread timeout]]
            [clojure.string :as str]
            [clojure.java.io :as io])
  (:require [taoensso.timbre :as log]))

(defrecord GeneralHid [port])
(defrecord LoopbackHid [port])




(defmulti enumerate-devices (fn [os & _] os))
(defmethod enumerate-devices :default [os & _]
  (log/error "Not yet supported: " os))


(defmulti usb-register-event-listner (fn [os & _] os))
(defmethod usb-register-event-listner :default [os  & _]
  (log/info "USB event detection is not yet supported on " os))

(defprotocol Hid
  (hid-open? [hid])
  (hid-open [hid])
  (hid-close [hid])
  (hid-write! [hid data])
  (hid-read [hid])
  (hid-listen [hid callback-fn]))


(load "hid/linux")
(load "hid/windows")
(load "hid/macosx")

(extend-protocol Hid

  ;; Default behavior (mainly for MacOSX)
  purejavahidapi.HidDevice
  (hid-open [hid]
    hid)
  (hid-close [hid]
    (try
      (log/debug "HID close")
      (.close hid)
      (catch java.lang.IllegalStateException e (log/warn (.getMessage e)))))
  (hid-write! [hid data]
    (.setOutputReport hid 0 (byte-array data)  (count data)))
  
  (hid-listen [hid callback-fn]
    (when hid
      (.setInputReportListener hid (proxy [purejavahidapi.InputReportListener] []
                                     (onInputReport [source id data len]
                                       (log/debug "Receive data:" (util/hex (first data)))
                                       (log/debug "Length:" len)				   
                                       (callback-fn {:source source :id id :data data :length len}))))))

  GeneralHid
  (hid-open? [hid]
    ;; return if it is open or not
    )
  (hid-open [hid]
    ;; return opend device
    hid)
  (hid-read [hid]
    ;; Read
    )
  (hid-write! [hid data]
    ;; Write
    )
  (hid-listen [hid callback-fn]
    ;; use listen interface defined on each Hid class
    ;; or start go-loop
    (go-loop []
      (let [buffer (byte-array 64)
            result (try
                     (hid-read hid)
                     (catch Exception e nil))]
        (if-not result
          (log/debug "Close HID go-loop reader")
          (do (callback-fn {:data buffer :length (count buffer)})
              (recur))))))
  (hid-close [hid]
    ;; close hid device
    )
  
  LoopbackHid
  (hid-open [hid]
    hid)
  (hid-close [hid])  
  (hid-write! [hid data]
    (put! (:port hid) data))
  (hid-listen [hid callback-fn]
    (go-loop []
      (when-let [data (<! (:port hid))]
        (callback-fn {:data data :length (count data)})
        (recur)))))


