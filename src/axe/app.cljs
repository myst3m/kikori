(ns axe.app
  (:require-macros [cljs.core.async.macros :refer (go)])
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [cljs-http.client :as http]
            [cljs.core.async :refer (<! chan go timeout put! poll! >!)]
            [cljs.core.async.interop :refer-macros (<p!)]
            ["@tensorflow-models/coco-ssd" :as tfm]
            ["@tensorflow/tfjs-backend-cpu" :as cpu]
            ["@tensorflow/tfjs-backend-webgl" :as webgl])) 

(def camera (js/Image.))
(def canvas (atom nil) )
(def coco-ssd (.load tfm))

(defn start-camera [& [devid]]
  (set! (.-src camera) (str "/stream?id=" (or devid "CAM0"))))

(defn stop-camra []
  (set! (.-src camera) "#"))

(defn get-image-data [img]
  (let [cv (.createElement js/document "canvas")]
    (set! (.-width cv) (.-width img))
    (set! (.-height cv) (.-height img))
    (let [ctx ^js/CanvasRenderingContext2D (.getContext cv "2d")]
      (.drawImage ctx img 0 0)
      (.getImageData ctx 0 0 (.-width cv) (.-height cv) ))))


(defn draw-image
  ([]
   (draw-image @canvas camera))
  ([img]
   (draw-image @canvas img))
  ([cv img]
   (when cv
     (let [ctx ^js/CanvasRenderingContext2D (.getContext cv "2d")]
       (cond
         (instance? js/HTMLImageElement img) (do (.drawImage ctx img 0 0)
                                                 (.getImageData ctx 0 0 (.-width cv) (.-height cv)))
         (instance? js/ImageData img) (do (.putImageData ctx img 0 0) img))))))

(defn clear-boxes [cv]
  (let [ctx ^js/CanvasRenderingContext2D (.getContext cv "2d")]
    (.clearRect ctx 0 0 (.-width cv) (.-height cv))))

(defn draw-objects-bound-boxes [cv img boxes]
  (let [ctx ^js/CanvasRenderingContext2D (.getContext cv "2d")]
    (.clearRect ctx 0 0 (.-width cv) (.-height cv)) 
    (doseq [z (js->clj boxes :keywordize-keys true)
            :when (seq z)]
      (let [{:keys [class bbox score]} z
            color (if (= class "person")
                    "blue"
                    "green")]
        (.beginPath ctx)
        (.rect ctx (nth bbox 0) (nth bbox 1) (nth bbox 2) (nth bbox 3))
        (set! (.-lineWidth ctx) 2)
        (set! (.-strokeStyle ctx) color)
        (set! (.-font ctx) "32px serif")
        (set! (.-fillStyle ctx) color)
        (.stroke ctx)
        (.fillText ctx
                   (str class " " (/ (int (* 1000 score)) 1000))
                   (+ (first bbox) 5)
                   (+ (second  bbox) 25))))))


(def drawn-ch (chan))
(def toggle-inference (r/atom true))

(defn start-drawer-agent [cv img]
  (go
    (<! (timeout 1000))
    (set! (.-width cv) (.-width img))
    (set! (.-height cv) (.-height img))
    (loop []
        (<! (timeout 33))
        (if (= "#" (last (.-src camera)))
          (.log js/console "Drawer finished")
          (do (draw-image cv img)
              (>! drawn-ch @toggle-inference)
              (recur))))) )

(defn start-detect-agent [cv img]
  (go
    (<! (timeout 1000))
    (set! (.-width cv) (.-width img))
    (set! (.-height cv) (.-height img))
    (loop []
      (if (= "#" (last (.-src camera)))
        (.log js/console "Drawer finished")
        (do (if (<! drawn-ch)
              (let [model (<p! coco-ssd)
                    boxes (<p! (.detect model img))]
                (draw-objects-bound-boxes cv img boxes))
              (do (clear-boxes cv)
                  (<! (timeout 33))))
            (recur))))))




(defn camera-canvas [devid] 
  (r/create-class
   {:component-did-mount (fn [this]
                           (reset! canvas (rdom/dom-node this))
                           (start-camera)
                           (start-drawer-agent @canvas  camera)
                           ;;(set! (.-src img) (str "/stream?id=" devid))
                           )
    :component-did-update (fn [this old-argv] 
                            )
    :reagent-render (fn []
                      [:canvas#camera {:style { :z-index 0}}])}))


(def -detector-canvas (atom nil))

(defn detector-canvas [devid] 
  (r/create-class
   {:component-did-mount (fn [this]
                           (reset! -detector-canvas (rdom/dom-node this))
                           (start-detect-agent @-detector-canvas camera))
    :component-did-update (fn [this old-argv] 
                            )
    :reagent-render (fn []
                      [:canvas#detector {:style {:position "absolute" :z-index 1}}])}))


(defn toggle-button []
  (fn []
    [:button.button
     {:style {:position "absolute"
              :z-index 2
              :background-color "transparent"
              :border-radius "15px"
              :color "white"}
      :on-click (fn [e]
                  (swap! toggle-inference not))}
     (if @toggle-inference
       "Stop"
       "Detect")]))

(defn home []
  (let [devid (.-innerHTML (.getElementById js/document "device"))]
    (fn []
      [:div
       [:nav.panel
        [:p.panel-heading
         "Camera"]
        [:div.panel-block
         [camera-canvas devid]
         [detector-canvas devid]
         [toggle-button]
         ]
        ]])))

(defn ^:dev/after-load init []
  (rdom/render [home] (.getElementById js/document "app")))


