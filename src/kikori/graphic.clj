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

(ns kikori.graphic
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:require [taoensso.timbre :as log])
  (:require [mikera.image.core :refer :all :exclude [write]]
            [mikera.image.colours :refer [rand-colour]]
            [mikera.image.filters :as fil]
            [hiccup.core :refer [html]]
            [clojure.data.codec.base64 :as b64])
  (:import [org.apache.batik.transcoder TranscoderInput TranscoderOutput]
           [org.apache.batik.transcoder.image ImageTranscoder PNGTranscoder]
           [org.apache.batik.transcoder.wmf.tosvg WMFTranscoder]
           [java.io ByteArrayOutputStream]
           [javax.imageio ImageIO]
           [java.awt Rectangle Dimension Font Color]
           [java.nio.file Files]))



(def ^:dynamic *width* 128)
(def ^:dynamic *height* 128)

(defn image [w h]
  (new-image w h))


(defn image->rgb565 [^java.awt.image.BufferedImage buffered-image & [fname]]
  (let [h (.getHeight buffered-image)
        w (.getWidth buffered-image)
        raw (apply concat (for [y (range 0 *width*)
                                x (range 0 *height*)]
                            
                            (if (and (< x w) (< y h))
                              (let [rgb (.getRGB buffered-image x y)
                                    a (bit-and (bit-shift-right rgb 24) 0xff)
                                    r (int (bit-and (bit-shift-right rgb 16) 0xff))
                                    g (int (bit-and (bit-shift-right rgb 8) 0xff))
                                    b (int (bit-and rgb 0xff))
                                    bin (bit-and 0xffff (bit-or (bit-shift-left (bit-shift-right r 3) 11)
                                                                (bit-shift-left (bit-shift-right g 2) 5)
                                                                (bit-shift-right b 3)))]
                                [(bit-and 0x00ff bin) (bit-shift-right (bit-and 0xff00 bin) 8)])
                              [0 0])))]
    (if fname
      (with-open [s (io/output-stream fname)] (.write s (byte-array raw)))
      (byte-array raw))))


(defn svg->image [& [byte-data]]
  ;; data: byte array or vector
  (if-not (seq byte-data)
    (image *width* *height*)
    (with-open [is (io/input-stream byte-data)
                os (ByteArrayOutputStream.)]
      (let [tis (TranscoderInput. is)
            tos (TranscoderOutput. os)
        
            rect (Rectangle. 0 0 *width* *height*)
            t (doto (PNGTranscoder. )
                (.addTranscodingHint PNGTranscoder/KEY_WIDTH (float (.-width rect)))
                (.addTranscodingHint PNGTranscoder/KEY_HEIGHT (float (.-width rect))))]
        (.transcode t tis tos)
        (with-open [png (-> os (.toByteArray) (io/input-stream ))]
          (ImageIO/read png))))))

(defn image->png [buffered-image]
  (with-open [os (ByteArrayOutputStream.)]
    (ImageIO/write buffered-image "PNG" os)
    (.toByteArray os)))

(defn image->jpg [buffered-image]
  (with-open [os (ByteArrayOutputStream.)]
    (ImageIO/write buffered-image "JPG" os)
    (.toByteArray os)))



(defn bytes->svg [byte-data content-type]
  (with-open [is (io/input-stream byte-data)
              os (ByteArrayOutputStream.)]
    (html [:svg
           {"xmlns:dc" "http://purl.org/dc/elements/1.1/"
            "xmlns:cc" "http://creativecommons.org/ns#"
            "xmlns:rdf" "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
            "xmlns:svg" "http://www.w3.org/2000/svg"
            "xmlns" "http://www.w3.org/2000/svg"
            "xmlns:xlink" "http://www.w3.org/1999/xlink"}
           [:image
            {"width" *width*
             "height" *height*
             "xlink:href" (str "data:" (or content-type "image") ";base64,"
                               (str/join (map char (b64/encode byte-data))))}]])))


(defn bytes->base64 [byte-data]
  (str/join (map char (b64/encode byte-data))))



(defn save-image [img path & {:keys [type ]}]
  (ImageIO/write img (or type "PNG") (io/file path)))

(defn dimention [width height]
  (Dimension. width height))

(defn draw-image! [{screen :screen path :path data :data url :url degree :rotate :as m}]
  (with-open [fb (if (and path (.exists (io/file path) ))
                   (io/output-stream path)
                   (ByteArrayOutputStream.))]
    (log/debug "data:" (class data) (string? data))
    (log/debug "url:" url)
    (let [image-or-stream (or data url (image *width* *height*))
          screen (or screen (atom nil))
          buffered-image (cond 
                           ;; URL
                           url (-> image-or-stream (io/input-stream) (ImageIO/read))
                           ;; Handled as SVG
                           (string? data) (-> image-or-stream (.getBytes) (svg->image))
                           ;; Handled as Image as PNG/JPEG/BMP
                           (instance? java.io.BufferedInputStream image-or-stream) (ImageIO/read image-or-stream)
                           ;; Handled as BufferedImage
                           (instance? java.awt.image.BufferedImage image-or-stream) image-or-stream)]

      (if buffered-image
        (let [img (cond-> buffered-image
                    true (resize *width* *height*)
                    degree (rotate (Long/parseLong (str degree))))
              raw (image->rgb565 img)]
          (.write fb raw 0 (count raw))
          (reset! screen (image->png img))
          img)
        (log/error "Invalid type: " image-or-stream)))))


(defn direct-sample []
  (let [img (new-image *width* *height*)
        logo (load-image (svg->image (.getBytes (slurp (io/resource "logo.svg")))))
        g (.createGraphics img)]

    (draw-image! {:path "/dev/fb1" :screen (atom nil) :data img})
    (doto g
      (.drawImage ((fil/contrast 0.8) logo) nil 0 0)
      (.setFont (Font. "Dialog" Font/PLAIN 12))
      (.setColor (Color. 0 192 255))
      (.drawString "Groovy-IoT" 10 30)
      (.setFont (Font. "Dialog" Font/PLAIN 10))      
      (.drawString "by 大宮技研合同会社" 10 50))
    (draw-image! {:path "/dev/fb1" :screen (atom nil) :data img})))

;; (defn render [& [src-url] ]
;;   (let [doc-src (DefaultDocumentSource. (or src-url "http://www.omiya-giken.com"))
;;         parser (DefaultDOMSource. doc-src)
;;         doc (.parse parser)
;;         window (Dimension. 128 128)
;;         media (doto (MediaSpec. "screen")
;;                 (.setDimensions (.-width window) (.-height window) )
;;                 (.setDeviceDimensions (.-width window) (.-height window) ))
;;         url (-> doc-src (.getURL))
;;         dom-analyzer (doto (DOMAnalyzer. doc url)
;;                        (.setMediaSpec media)
;;                        (.attributesToStyles)
;;                        (.addStyleSheet nil (CSSNorm/stdStyleSheet) DOMAnalyzer$Origin/AGENT)
;;                        (.addStyleSheet nil (CSSNorm/userStyleSheet) DOMAnalyzer$Origin/AGENT)
;;                        (.addStyleSheet nil (CSSNorm/formsStyleSheet) DOMAnalyzer$Origin/AGENT)
;;                        (.getStyleSheets))
;;         canvas (doto (BrowserCanvas. (-> dom-analyzer (.getRoot)) dom-analyzer url)
;;                  (.setAutoMediaUpdate false))
;;         canvas-config (doto (-> canvas (.getConfig))
;; ;;                        (.setClipViewport true)
;;                         (.setLoadImages true)
;;                         (.setLoadBackgroundImages true)
;;                         )]
;;     (-> canvas (.createLayout window))
;;     (-> canvas (.getImage))))







;; How to request by curl

;; Send SVG data directly and draw 
;;  curl  -X POST http://192.168.11.3:3000/write --data-urlencode 'target=LCD0' --data-urlencode "data=$(cat lion.svg)"


;; Assume :view is not set in interface. Default Renderer is used.
;; curl -X POST http://192.168.11.2:3000/write --data-urlencode 'target=LCD0' --data-urlencode "url=https://1.bp.blogspot.com/-TJMjezZzMmo/XBRfBTQnZdI/AAAAAAABQ1w/pGtOUseQF78niug7oF2mPH6UH3aeNI8jQCLcBGAs/s800/gengou_happyou_blank.png"

;; Assume :view options is set as "BME280"
;; curl  -X POST http://192.168.11.3:3000/write --data-urlencode 'target=LCD0' --data-urlencode "data={:temperature 102.0 :pressure 2}"


(defn svg [width height hiccup-tree]
  (html
   [:svg (conj {:viewBox (str/join " " [0 0  width height])}
               {"xmlns:dc" "http://purl.org/dc/elements/1.1/"
                "xmlns:cc" "http://creativecommons.org/ns#"
                "xmlns:rdf" "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                "xmlns:svg" "http://www.w3.org/2000/svg"
                "xmlns" "http://www.w3.org/2000/svg"
                "xmlns:xlink" "http://www.w3.org/1999/xlink"})
    hiccup-tree]))
