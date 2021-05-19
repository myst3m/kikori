(ns kikori.hci
  (:require [kikori.util :as util]
            [kikori.jna :as jna]
            [kikori.util :refer [little-endian bytes->long]]
            [taoensso.timbre :as log]))




(defprotocol HCI
  (hci-config! [dev m])
  (hci-read [dev m])
  (hci-write! [dev m])
  (hci-close [dev]))

(load "hci/linux")


;; Omron 2ICE
;; BT: "C2:DF:85:89:AF:59"


