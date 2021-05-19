(ns kikori.module.camera
  (:refer-clojure :exclude [read write])
  (:require [kikori.core :refer :all]
            [kikori.module :refer (defmodule)])
  (:require [opencv4.core :as cv]
            [opencv4.utils :as u]
            [opencv4.video :as v]
            [opencv4.dnn :as dnn]
            [opencv4.process :as p]
            [clojure.string :as str]
            [kikori.device]
            [kikori.graphic :as g]
            )
  (:import [org.opencv.core MatOfByte Size Mat Scalar MatOfFloat]
           [org.opencv.imgcodecs Imgcodecs]))


(refer-kikori)


(defn gen-camera-device
  ([]
   (v/capture-device 0))
  ([{:keys [index encode width height]
     :or {index 0 encode "MJPG" width 640 height 480}}]
   (log/info "Camera :" index encode width height)
   (let [[c0 c1 c2 c3] (seq encode)]
     (doto ^org.opencv.videoio.VideoCapture (v/capture-device index)
       (.set v/CAP_PROP_FOURCC (org.opencv.videoio.VideoWriter/fourcc c0 c1 c2 c3))
       (.set v/CAP_PROP_FRAME_WIDTH width)
       (.set v/CAP_PROP_FRAME_HEIGHT height)
       (.set v/CAP_PROP_BUFFERSIZE 1)))))



(defn decode-fourcc [^org.opencv.videoio.VideoCapture c]
  (let [v (.get c v/CAP_PROP_FOURCC)]
    (str/join (map #(char (bit-and 0xff (bit-shift-right (int v) (* % 8)))) (range 4)))))

(def cameras (volatile! {}))
(def recording (volatile! #{}))
(def workspaces (volatile! {}))
(def frames (volatile! {}))

(defn mat->bytes [^org.opencv.core.Mat m]
  (let [buf (byte-array (* (.total m) (.channels m)))]
    (.get m 0 0 buf)
    buf))

(defn mat->jpg [src]
  (let [matOfBytes (MatOfByte.)]
    (Imgcodecs/imencode ".jpg" src matOfBytes)
    (.toArray matOfBytes)))


(defmodule CAMERA
  (init [{:keys [name index store encode width height] :as m}]
        (log/info "Initialize " name)
        (let [cam ^org.opencv.videoio.VideoCapture (gen-camera-device m)]
          (vswap! workspaces assoc name (cv/new-mat))
          (vswap! cameras assoc name cam)
          (log/info (@cameras name))
          (log/info (decode-fourcc cam))
          (assoc m
                 :width (.get cam v/CAP_PROP_FRAME_WIDTH)
                 :height (.get cam v/CAP_PROP_FRAME_HEIGHT) 
                 :fps (.get cam v/CAP_PROP_FPS)
                 :encode (decode-fourcc cam))))

  (read [{:keys [name] :as cam}]
        ;; (get @frames name)
        (let [cap ^org.opencv.videoio.VideoCapture (@cameras name)]
          (try
            (when-let [ws (@workspaces name)] 
              (.read cap ws)
              (mat->jpg ws))
            (catch Exception e (log/warn (.getMessage e))))))
  
  (write [{:keys [op name index] :as cam}]
         (condp = op
           :record (do (log/info "Record" name)
                       (log/info (@cameras name))
                       (when-not (@cameras name)
                         (init cam))
                       (when-not (@recording name)
                         (vswap! recording conj name)))
           :stop (let [phy ^org.opencv.videoio.VideoCapture (@cameras name)]
                   (.release phy)
                   (vswap! cameras dissoc name)
                   (vswap! recording disj name))
           (log/warn "No supported operation" name op )))

  (close [{:keys [name] :as cam}]
         (vswap! workspaces dissoc name)
         (vswap! frames dissoc name)
         (when-let [phy ^org.opencv.videoio.VideoCapture (@cameras name)] 
           (log/debug "Release Camera " name)
           (.release phy))))



(defn snap []
  (let [c (gen-camera-device)
        m (cv/new-mat)]
    (.read c m)
    (.release c)
    (u/imshow m)
    m))

(defn detect [net img-mat]
  (let [
        ;; net (dnn/read-net-from-onnx "resnet50-v1-7.onnx")
        ;; (dnn/read-net-from-caffe "mobilenet_v2_deploy.prototxt" "mobilenet_v2.caffemodel")
        ]

    (let [
          blob (dnn/blob-from-image img-mat 0 (Size. 416 416))
          z (cv/new-mat)
          _ (cv/normalize blob z)
          
          _ (.setInput net z)
          output (.forward net)
          buf (cv/new-mat)
          ]
      blob
      (cv/convert-to output buf 128)
      (u/mat-to-bytes buf)
      )))
