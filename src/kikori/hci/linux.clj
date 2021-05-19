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

(in-ns 'kikori.hci)

(alias 'bt 'bluetooth)

(defonce BLE_SCAN_PASSIVE 0x00)  
(defonce BLE_SCAN_ACTIVE  0x01)
(defonce BLE_RANDOM_ADDRESS 0x01)
(defonce BLE_FILTER_ALLOW_ALL 0x00)

;; hci.h
(defonce SOL_HCI 0x00) 
(defonce HCI_FILTER 2)

(defonce HCI_EVENT_PKT		0x04)
(defonce EVT_LE_META_EVENT	0x3E)


;; --- hci.h ---
;; struct hci_filter {
;;    uint32_t type_mask;
;;    uint32_t event_mask[2];
;;    uint16_t opcode;
;; };





(defrecord LinuxHciDevice [handle])

(extend-protocol HCI
  LinuxHciDevice
  (hci-config! [dev m]
    (let [dev-id (bt/hci_get_route nil)
          sock (bt/hci_open_dev dev-id)]
      (if (or (< dev-id 0) (< sock 0))
        dev
        (assoc dev :handle sock))))

  (hci-close [{:keys [handle]}]
    (bt/hci_close_dev handle))
  )


(defn ble-set-scan-parameters [dev & {:keys [scan-type interval
                                              window own-type
                                              filter-policy timeout]
                                       :or {scan-type BLE_SCAN_ACTIVE
                                            interval 0x0010
                                            window 0x0010
                                            own-type BLE_RANDOM_ADDRESS
                                            filter-policy BLE_FILTER_ALLOW_ALL
                                            timeout 1000}}]
  (log/debug "scan-type:" scan-type)
  (log/debug "interval:" interval)
  (log/debug "window:" window)
  (log/debug "own-type:" own-type)
  (log/debug "filter-policy:" filter-policy)
  (log/debug "timeout:" timeout)
  
  (if-let [hnd (:handle dev)]
    (let [err (bt/hci_le_set_scan_parameters (int hnd)
                                             scan-type
                                             (bytes->long (little-endian interval))
                                             (bytes->long (little-endian window))
                                             own-type
                                             filter-policy
                                             timeout)]
      (if (<= 0 err)
        dev
        (do (log/error "Set scan parameters failed, maybe need to be a super user:" err)
            (throw (ex-info "Set scan parameters failed:" {:cause err})))))
    (log/error "Device not opened:" dev)))



(defn ble-set-scan-enable [dev & {:keys [enable filter-dup timeout]
                                     :or {enable 0x01     ;; Enable scan
                                          filter-dup 0x01 ;; Duplicate filter
                                          timeout 1000}}]

  (if-let [hnd (:handle dev)]
    (let [err (bt/hci_le_set_scan_enable hnd
                                         enable
                                         filter-dup
                                         timeout)]
      (if (<= 0 err)
        dev
        (do (log/error "Enable scan failed:" err)
            (throw (ex-info "Enable scan failed" {:cause err})))))
    (log/error "Device not opened:" dev)))


(defonce HCI_FLT_TYPE_BITS	31)
(defonce HCI_FLT_EVENT_BITS	63)

(defn hci-set-bit [nr addr]
  (bit-or (+ addr (bit-shift-right nr 5))
          (bit-shift-left 1 (bit-and nr 31))))

(defprotocol StructOperation
  (size-of [x])
  (allocate [x])
  (get-field [x n])
  (set-field! [x n v]))

(defmacro defstructure [name aligns]
  (let [buf 'buffer
        size (reduce + (map jna/size-map aligns))]
    `(do (defrecord ~name [])
         (extend-protocol StructOperation
           ~name
           (size-of [x#]
             ~size)
           (allocate [x#] (assoc x# :buffer (jna/make-cbuf ~size)))
           (get-field [x# n#]
             (let [offset# (if (<= n# 0)
                             0
                             (reduce + (map jna/size-map (take n# ~aligns))))
                   type# (nth ~aligns n#)
                   buf# (:buffer x#)]
               (.position buf# offset#)
               (condp = type#
                 :char (.getChar buf#)
                 :short (.getShort buf#)
                 :int (.getInt buf#)
                 :long (.getLong buf#)
                 :float (.getFloat buf#)
                 :double (.getDouble buf#))))
           (set-field! [x# n# v#]
             (let [offset# (if (<= n# 0)
                             0
                             (reduce + (map jna/size-map (take n# ~aligns))))
                   type# (nth ~aligns n#)
                   buf# (:buffer x#)]
               (.position buf# offset#)
               (condp = type#
                 :char (.putChar buf# v#)
                 :short (.putShort buf# v#)
                 :int (.putInt buf# v#)
                 :long (.putLong buf# v#)
                 :float (.putFloat buf# v#)
                 :double (.putDouble buf# v#))
               x#))))))

(defstructure HciFilter [:int :int :int :int])

(defn ble-set-sockopt [dev]
  (let [filt (allocate (HciFilter.))
        mem (:buffer filt)
        type-flag (bit-and HCI_FLT_TYPE_BITS HCI_EVENT_PKT)
        event-flag (bit-and HCI_FLT_EVENT_BITS EVT_LE_META_EVENT)]

    ;; filter set ptype : type-mask
    (.position mem (bit-shift-right type-flag 5))

    (let [m0 (.getInt mem)]
      (.position mem m0)
      (.putInt mem (bit-or m0 (bit-shift-left 1 (bit-and type-flag 31)))))

    ;; filter set for event : event-mask
    (.rewind mem)
    (.position mem (dec (+ (jna/size-map :int) (bit-shift-right event-flag 5))))

    (let [offset (jna/size-map :int)
          m0 (.getInt mem)]
      (.position mem (+ m0 offset))
      (.putInt mem (bit-or m0 (bit-shift-left 1 (bit-and event-flag 31)))))

    (let [nf (jna/pointer mem)]
      (c/setsockopt (:handle dev) SOL_HCI HCI_FILTER nf (size-of filt)))
    dev))

(defn lescan [dev]
  (let [own-type 0x00
        scan-type 0x00 ;; passive
        filter-type 0
        filter-policy 0x00
        interval (bytes->long (little-endian 0x0010 2) :endian :big)
        window (bytes->long (little-endian 0x0010 2) :endian :big)]
    (ble-set-scan-parameters dev :scan-type 0x00 :interval interval :window window)
    (ble-set-scan-enable dev )
    (ble-set-sockopt dev)))




