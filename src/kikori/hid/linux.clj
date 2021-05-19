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

(in-ns 'kikori.hid)

(defrecord LinuxHidDevice [handle])

(def POLLIN 0x0001)
(def O_RDWR 0x0002)

(defn- scan-tty  [usb-syspath vid pid]
  (let [udev (udev/udev_new)
        enumerate (udev/udev_enumerate_new udev)
        _ (udev/udev_enumerate_add_match_subsystem enumerate "tty")
        _ (udev/udev_enumerate_scan_devices enumerate)
        dev-entry (udev/udev_enumerate_get_list_entry enumerate)]
    (loop [d dev-entry
           res []]
      (if-not d
        (do
          (udev/udev_enumerate_unref enumerate)
          (udev/udev_unref udev)
          res)
        (let [path (udev/udev_list_entry_get_name d)
              tty-dev (udev/udev_device_new_from_syspath udev path)
              usb-dev (udev/udev_device_get_parent_with_subsystem_devtype tty-dev "usb" "usb_device")]
          
          (recur (udev/udev_list_entry_get_next d)
                 (if usb-dev
                   (let [sp (udev/udev_device_get_syspath usb-dev)
                         v (udev/udev_device_get_sysattr_value usb-dev "idVendor")
                         p (udev/udev_device_get_sysattr_value usb-dev "idProduct")]
                     (log/debug "Path" sp)
                     (log/debug "USB Syspath" usb-syspath)
                     (if (and (= usb-syspath sp) (= vid v) (= pid p))
                       (conj res (str "/dev/"(last (str/split path #"/"))))
                       res))
                   res)))))))

(defn device-attributes [dev]
  (let [usb-dev (udev/udev_device_get_parent_with_subsystem_devtype dev "usb" "usb_device")
        
        ;; Properties comes on adding only, thus attributes are used.
        path (udev/udev_device_get_syspath dev)
        usb-path (udev/udev_device_get_syspath usb-dev)
        vid (udev/udev_device_get_sysattr_value usb-dev "idVendor")
        pid (udev/udev_device_get_sysattr_value usb-dev "idProduct")
        product-string (udev/udev_device_get_sysattr_value usb-dev "product")
        _ (log/debug "Attributes:" path vid pid product-string)
        index (Long/parseLong (str (last path)))
        tty (scan-tty usb-path vid pid)]

    (try
      (map->LinuxHidDevice {:productString product-string
                            :vendorId (Long/parseLong vid 16)
                            :productId (Long/parseLong pid 16)
                            :deviceId path
                            :index index
                            :relatives {:tty tty}
                            :path path})
      (catch Exception e (do (log/error "Message:" (.getMessage e))
                             ;;(clojure.stacktrace/print-throwable e)
                             )))))


(defmethod enumerate-devices :linux  [os]
  (let [udev (udev/udev_new)
        enumerate (udev/udev_enumerate_new udev)
        _ (udev/udev_enumerate_add_match_subsystem enumerate "hidraw")
        _ (udev/udev_enumerate_scan_devices enumerate)
        dev-entry (udev/udev_enumerate_get_list_entry enumerate)]

    (loop [d dev-entry
           res []]
      (if-not d
        (do
          (udev/udev_enumerate_unref enumerate)
          (udev/udev_unref udev)
          res)
        (let [path (udev/udev_list_entry_get_name d)
              dev (udev/udev_device_new_from_syspath udev path)
              nd (udev/udev_list_entry_get_next d)
              _ (log/debug path dev)
              m (device-attributes dev)]
          (udev/udev_device_unref dev)
          
          (recur nd (conj res m)))))))


(extend-protocol Hid
  kikori.hid.LinuxHidDevice
  (hid-open [hid]
    (let [udev (udev/udev_new)
          path (-> udev
                   (udev/udev_device_new_from_syspath (:path hid))
                   (udev/udev_device_get_devnode))

          ;; The cast to int is required for ARM
          handle (c/open path (int O_RDWR))]

      (udev/udev_unref udev)

      (if (< handle 0)
        (do (log/error "Open Error:" handle)
            (log/error "Check device permissions and whether devices is connected"))
        ;; Success is 0
        (assoc hid :handle handle))))

  (hid-close [{:keys [handle] :as hid}]
    (try
      (log/debug "HID close")
      (c/close handle)
      (catch java.lang.IllegalStateException e (log/warn (.getMessage e)))))
  
  (hid-write! [{:keys [handle callback] :as hid} data]
    (log/debug "LinuxHiddevice: write!:" (seq data))
    (let [res (c/write handle (byte-array (cons 0 data)) (inc (count data)))]
      (log/debug "result:" res)
      (if (< 0 res)
        (let [buffer (hid-read hid)]
          (log/debug "Will deliver:" (seq buffer))
          (callback {:data buffer :length (count buffer)}))
        (log/error "Write error:" data))
      res))

  (hid-read [{:keys [handle] :as hid}]
    (log/debug "LinuxHiddevice: read")
    (let [buffer (byte-array 128)
          read-size (c/read handle buffer 128)]
      (log/debug "Received:" read-size (seq buffer))
      (take (dec read-size) (seq buffer))))
  
  (hid-listen [{:keys [handle] :as hid} callback-fn]
    ;; Nothing to do 
    ))

(defn udev-properties [syspath sub-system & [dev-type]]
  (let [udev (udev/udev_new)
        raw (udev/udev_device_new_from_syspath udev syspath)
        dev (udev/udev_device_get_parent_with_subsystem_devtype raw sub-system dev-type)]

    (loop [res {}
           entry (udev/udev_device_get_properties_list_entry dev)]
      (if-not entry
        (do (udev/udev_unref udev)
            (udev/udev_device_unref raw)
            ;;(udev/udev_device_unref dev)
            res)
        (recur (conj res {(udev/udev_list_entry_get_name entry)
                          (udev/udev_list_entry_get_value entry)})
               (udev/udev_list_entry_get_next entry))))))

(defn udev-attributes [syspath sub-system & [dev-type]]
  (let [udev (udev/udev_new)
        raw (udev/udev_device_new_from_syspath udev syspath)
        dev (udev/udev_device_get_parent_with_subsystem_devtype raw sub-system dev-type)]

    (loop [res {}
           entry (udev/udev_device_get_sysattr_list_entry dev)]
      (if-not entry
        (do (udev/udev_unref udev)
            (udev/udev_device_unref raw)
            ;;(udev/udev_device_unref dev)
            res)
        (recur (let [attr (udev/udev_list_entry_get_name entry)]
                 (conj res {attr
                            (udev/udev_device_get_sysattr_value dev attr)}))
               (udev/udev_list_entry_get_next entry))))))



(defmethod usb-register-event-listner :linux [os & args]
  (log/info "Register USB event detection: " os)
  (let [udev (udev/udev_new)
        monitor (udev/udev_monitor_new_from_netlink udev "udev")
        monitor-fd (udev/udev_monitor_get_fd monitor)
        ;;        fn-map (into {} (map vec (partition 2 args)))
        fn-map (into {} (map (fn [[k v]] [(name k) v]) (partition 2 args)))]
    
    (when (< (udev/udev_monitor_filter_add_match_subsystem_devtype monitor "hidraw" nil) 0)
      (throw (ex-info "udev_monitor_filter_add_match_subsystem_devtype failed")))
    (when (< (udev/udev_monitor_enable_receiving monitor) 0)
      (throw (ex-info "udev_monitor_enable_receiving failed")))
    (go-loop [ ;;pfds (.toArray (CLibrary$pollfd.) 1)
              ]
      
      ;;(set! (.-fd (first pfds)) monitor-fd)
      ;;(set! (.-events (first pfds)) POLLIN)

      (let [pfds (-> (kikori.jna/Pollfd. {:fd monitor-fd :events POLLIN :revents -1}
                                         [:int :short :short])
                     (struct->native-buf)
                     (pointer))
            res (c/poll pfds (int 1) (int -1))]
        
        (log/debug "pool result:" res)
        (if (>= 0 res)
          (log/warn "poll failed:" res)
          ;; Plug ON:  raw-dev, hid-dev
          ;; Plug OFF: raw-dev
          (let [hidraw-dev (udev/udev_monitor_receive_device monitor)
                action (udev/udev_device_get_action hidraw-dev)]

            (log/info "Action:" action)

            (try
              (let [f (fn-map action)
                    m (device-attributes hidraw-dev)]
                (when f (f m)))
              (catch Exception e (do (log/warn "Invalid response from device. Try to plug in again.")
                                     ;; (clojure.stacktrace/print-stack-trace e)
                                     )))
            (udev/udev_device_unref hidraw-dev)
            (recur)))))))

;; syspath "/sys/devices/pci0000:00/0000:00:14.0/usb1/1-6/1-6:1.2/0003:04D8:00DD.000F/hidraw/hidraw0"

;;  sudo bash -c 'echo "1-6" > /sys/bus/usb/drivers/usb/unbind
