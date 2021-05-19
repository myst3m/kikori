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

;; This is just for testing of 20 Sep. 2018
;; 

(ns kikori.module.terminal
  (:refer-clojure :exclude [read write])
  (:require [kikori.core :refer :all]
            [kikori.module :refer (defmodule)]
            [clojure.core.async :refer (alts! put! chan poll! go-loop)]
            [taoensso.timbre :as log])
  
  )

(def terminal-ctrl (chan))

(defn terminal-bridge [dev-in dev-out host-in host-out]
  (loop []
    (when-let [garbage (poll! terminal-ctrl)]
      (recur)))
  (go-loop []
    (let [[x ch] (alts! [dev-in host-out terminal-ctrl])]
      (log/error "loop:" host-out)
      (when x
        (log/debug x (class x))
        (condp = ch
          dev-in (do (log/debug "dev-in" x) (put! host-in x) (recur))
          host-out (do (log/debug "host-out" x) (put! dev-out x) (recur))
          terminal-ctrl (log/debug "Close")
          (recur))))))



(defmodule Terminal
  (config [dev op & [{:keys [host-in host-out name uart]}]]
          (log/info "Configuring Terminal: " name)
          (let [{:keys [in out]} uart]
            (log/error host-out)
            (if (and in out host-in host-out)
              (terminal-bridge in out host-in host-out)
              (log/info "Terminal is not available")))
          dev)
  (close [{:keys [name uart]}]
         (put! terminal-ctrl :close)
         (log/info "Terminal Closed: " name)))
