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

(defmethod enumerate-devices :windows [os]
  (PureJavaHidApi/enumerateDevices))


(defrecord WindowsHidDevice [rx tx ctrl])

(extend-protocol Hid
  purejavahidapi.windows.HidDeviceInfo
  (hid-open [hid-info]
    (log/debug "Windowshiddevice: open")
    (let [rx (Kernel32Library/CreateFile (.getPath hid-info)
                                         Kernel32Library/GENERIC_READ
                                         (bit-or Kernel32Library/FILE_SHARE_READ
                                                 Kernel32Library/FILE_SHARE_WRITE)
                                         nil
                                         Kernel32Library/OPEN_EXISTING
                                         0 ;;Kernel32Library/FILE_FLAG_OVERLAPPED
                                         nil)
          tx (Kernel32Library/CreateFile (.getPath hid-info)
                                         Kernel32Library/GENERIC_WRITE
                                         (bit-or Kernel32Library/FILE_SHARE_READ
                                                 Kernel32Library/FILE_SHARE_WRITE)
                                         nil
                                         Kernel32Library/OPEN_EXISTING
                                         0 ;;Kernel32Library/FILE_FLAG_OVERLAPPED
                                         nil)]
      (if-not (or (= rx WinDef/INVALID_HANDLE_VALUE)
                  (= tx WinDef/INVALID_HANDLE_VALUE))
        (map->WindowsHidDevice {:rx rx
                         :tx tx
                         :ctrl (chan)})
        (log/error "Open failed. Check device permissions and whether device is connected."))))
  
  WindowsHidDevice
  (hid-open [hid]
    hid)
  (hid-close [hid]
    (try
      (log/debug "HID close")
      (Kernel32Library/CloseHandle (:tx hid))
      (log/debug "HID tx close")
      ;; Since ReadFile does not accept Close, ctrl msg is implemented
      (>!! (:ctrl hid) :close)
      (Kernel32Library/CloseHandle (:rx hid))
      (log/debug "HID rx close")
      (catch java.lang.IllegalStateException e (log/warn (.getMessage e)))))
  (hid-write! [hid data]
    (log/debug "WindowsHiddevice: write!:" (seq data))
    (let [buf (doto (Memory. 65)
                (.clear)
                (.write 0 (byte-array (concat [0] data)) 0 (inc (count data))))]
      (>!! (:ctrl hid) :sent)
      (Kernel32Library/WriteFile (:tx hid) buf 65 nil nil)))
  (hid-read [hid]
    (log/debug "WindowsHiddevice: read")
    (let [buf (doto (Memory. 65)
                (.clear))
          number-of-read (int-array [-1])]
      (Kernel32Library/ReadFile (:rx hid)  buf 65 number-of-read nil)
      (seq (-> buf (.getByteArray 0 (first number-of-read)) rest))))
  
  (hid-listen [hid callback-fn]
    (go-loop []
      (log/debug "Listen: loop")
      (let [msg (<!! (:ctrl hid))]
        (if (= :close msg)
          :close
          (let [buffer (hid-read hid)]
            (callback-fn {:data buffer :length (count buffer)})
            (recur)))))))

