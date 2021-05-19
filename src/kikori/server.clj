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


(ns kikori.server
  (:gen-class)
  (:refer-clojure :exclude [read])
  (:require [clojure.spec.alpha :as s])
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.xml :as xml]
            [clojure.stacktrace :as trace]
            [clojure.core.async :as a
             :refer [<!! <! put! go-loop chan close! pipe alts! timeout >!! sliding-buffer]])
  (:require [org.httpkit.server :refer [open? with-channel on-close
                                        on-receive websocket? send! run-server]]
            [org.httpkit.client :as http])
  (:require [reitit.core :refer :all]
            [reitit.http :as rh]
            [reitit.interceptor.sieppari]
            [reitit.http.interceptors.muuntaja :as im]
            [reitit.http.interceptors.parameters :as ip]
            [reitit.ring :as ring])
  (:require [clojure.tools.cli :refer [parse-opts]])
  (:require [clojure.data.json :as json])
  (:require [kikori.core :refer :all]
            [kikori.module :refer :all]
            [kikori.util :as util]
            [kikori.device :as dev]
            [kikori.facade :as facade]
            [kikori.parser :as p])
  
  (:require [silvur.datetime :refer :all]
            [silvur.util :refer (map->json json->map)])
  (:require [taoensso.timbre :as log])
  (:require [nrepl.server :as nrepl]
            [nrepl.transport :as transport])
  (:import [java.io ByteArrayInputStream]))


(defonce ^:private server (atom nil))
(defonce ^:private clients (atom {}))

(def ^:private ^:dynamic *workers* (atom {}))


(defn- scan []
  {:result :success :v (->> (devices)
                            (mapcat :sensors)
                            (map last)
                            (map #(select-keys % [:name :module :bus :async :id])))})


(defn scan-handler [req]
  {:status 200
   :body (scan)})

(defn sensors-handler [{:keys [params] :as req}]
  (log/debug "Params:" params)
  (let [{:keys [ids]} params
        data (apply facade/read-sensors (filter identity (flatten [ids])))
        ]
    (log/debug data)
    {:body (reduce (fn [r [k v]]
               (conj r {k (cond 
                            (associative? v) (assoc v :id (:id (sensors k)))
                            (seq? v) (seq v)
                            :else v)}))
             {}
             data)}
    ;; (json/write-str (reduce (fn [r [k v]]
    ;;                           (conj r {k (if (associative? v)
    ;;                                        (assoc v :id (:id (sensors k)))
    ;;                                        (seq v))}))
    ;;                         {}
    ;;                         data))
    ))


(s/def ::target string?)
(s/def :unq/params (s/keys :req-un [::target]))
(s/def :unq/request (s/keys :req-un [:unq/params]))
(s/fdef read-handler
  :args (s/cat :request :unq/request)
  :ret any?)

(defn read-handler [{{:keys [target type sampling direct command]
                      :as params
                      :or {sampling "1"}} :params :as req}]
  (log/debug "Request:" req)
  {:body (try
           (if-let [s (sensors target)]
             (let [n (Long/parseLong sampling)
                   data (-> (assoc s :count n :direct? direct :type type :command command)
                            (read)
                            (as-> x
                                (cond
                                  (associative? x) (assoc x :id (:id s))
                                  :else x)))]
               (log/debug "Result: " target ":" data)
               (cond
                 ;; For data
                 (and data (every? number? data)) (conj {:result "success"
                                                         :value (condp = (keyword type)
                                                                  :max (apply max data)
                                                                  :min (apply min data)
                                                                  :mean (double (/ (reduce + data) (count data)))
                                                                  :sd (let [mu (/ (reduce + data) (count data))]
                                                                        (Math/sqrt (reduce + (map (fn [x] (* (- x mu) (- x mu))) data))))
                                                                  :rms (let [mu (/ (reduce + data) (count data))]
                                                                         (Math/sqrt (/ (reduce + (map * data data)) (count data)))) 
                                                                  data)}
                                                        params)
                 
                 ;; For almost watching screen
                 (and (map? data) (:html? data)) (:body data)
                 (and (map? data) (:binary? data))  {:headers {"Content-Type" (or (:content-type data) "image")}
                                                     :body (ByteArrayInputStream. (:body data))}
                 (map? data) (assoc data :result "success")
                 (string? data) {:result "success" :msg data}
                 :else (conj {:result "error"
                              :msg "read function not supported due to the result has key/values. Use sensor operation from web for the target which returns key/values"}
                             params)
                 ))
             (conj {:result "error" :msg "No sensor found"} params)
             )
           (catch Throwable e (do (log/error e)
                                  {:result "error"
                                   :msg "Invalid parameters."})))})

(defn write-handler [{params :params :as req}]
  (log/debug "Request:" req )
  (let [{:keys [target data] :as params} params
        p (or (try
                ;; Check if data are valid SVG
                (xml/parse  (java.io.ByteArrayInputStream. (.getBytes data)))
                (assoc params :data data)
                (catch Exception e nil))
              (try
                (update params :data read-string)
                (catch Exception e nil))
              (try
                (update params :data json->map)
                (catch Exception e nil))
              params)]

    {:body (try
             (if-let [s (sensors target)]
               (let [result (write (conj s p))]
                 (cond
                   (and (map? result) (:html? result)) (:body result)
                   result (merge {:result "success" }
                                 (when-let [x (status s)] {:status x})
                                 params)
                   :else (merge {:result "error"
                                 :msg "Invalid parameters or could not find device"}
                                params)))
               {:result "error" :msg "No target found"})
             (catch Throwable e (do (log/warn e)
                                    {:result "error"
                                     :cause (.getMessage e)
                                     :msg "Invalid parameters"})))}))



(defn go-data-bridge [ch {:keys [name host-in] :as sensor}]
  (log/info "Start Listen Worker for" name)
  (if (seq (get-in @*workers* [:bridge name ch]))
    (log/info "Worker is already running for " name)
    (let [worker-in (chan (sliding-buffer 8))
          sensor (listen (assoc sensor :worker-in worker-in))]
      
      (swap! *workers* assoc-in [:bridge name ch] sensor)
      
      (go-loop []
        (let [c (@clients ch)]
          (cond (nil? (get-in @*workers* [:bridge name ch])) (log/info "Bridge worker stopped")
                (nil? c) (do 
                           (close sensor)
                           (swap! *workers* update-in [:bridge name] dissoc ch)
                           (log/info "Bridge worker stopped"))
                :else (let [data (<! worker-in)]
                        (if-not data
                          (log/info "host-in port has been closed. Quit worker")
                          (do (if (open? ch)
                                (send! ch (map->json {(keyword name) data}))
                                (log/info "Channel closed: " name))
                              (recur))))))))))


(defn go-data-provider [ch]
  (log/info "Start Sync worker for " ch)
  ;; Register this channel as a worker id
  
  (if (get-in @*workers* [:provider ch])
    (log/info "Running worker. Only parameters are changed.")
    (let [{:keys [listener]} (@clients ch)]
      (swap! *workers* assoc-in [:provider ch] listener)
      (go-loop []
        (let [{:keys [listener]} (@clients ch)]
          (cond (nil? (get-in @*workers* [:provider ch])) (log/info "Provider worker stopped")
                (empty? listener) (do (swap! *workers* update :provider dissoc ch)
                                      (log/info "Provider worker stopped"))
                (.isClosed ch) (log/info "Client closed channel.")
                
                
                :else (let [{:keys [interval]} (@clients ch)
                            data (reduce (fn [r sn]
                                           (assoc r (keyword (:name sn)) (read sn)))
                                         {}
                                         (filter (comp not :async) (map sensors listener)))]
                        (send! ch (map->json data))
                        (<! (timeout (or interval 1000)))
                        (recur))))))))



(defn handler [req]
  (with-channel req channel
    (on-close channel (fn [status]
                        (log/info "Connection closed.")
                        (swap! clients dissoc channel)))

    (when (websocket? channel)
      (swap! clients assoc channel {}))
    
    (on-receive channel (fn [data]
                          (log/debug data)
                          (try
                            (let [{:keys [op v interval]} (try
                                                            (json->map data)
                                                            (catch Exception e (read-string data)))

                                  
                                  result (condp = (keyword op)
                                           :scan (scan)
                                           :stop (do (swap! *workers* empty)
                                                     {:result :success :msg "Worker stopped"})
                                           :close (.onClose channel 0)
                                           :listen (let [sensor-names (filter seq (distinct (flatten [v])))
                                                         {bridge-targets true provider-listner false} (group-by :async (map sensors sensor-names))]
                                                     
                                                     (swap! clients update channel conj {:listener (keep :name provider-listner) :interval interval})
                                                     
                                                     ;; Boot go routine for async sensors
                                                     (doseq [s (seq bridge-targets)]
                                                       (log/debug "Use listen interface:" (:name s))
                                                       (go-data-bridge channel s))
                                                     
                                                     ;; Boot go routines for non-async sensors that means it needs to be called read function
                                                     (go-data-provider channel)
                                                     
                                                     ;; (when-not running?
                                                     ;;   (log/info "Boot data provider")
                                                     ;;   (go-data-provider channel))
                                                     {:result :success :msg "Success"})
                                           {:result :error :msg "No operation"})]
                              (send! channel (map->json result)))
                            (catch Exception e (do (log/error (.getMessage e))
                                                   (send! channel (map->json {:result :error :msg "Illegal format"})))))))))

(defn go-stream-provider [cam]
  (log/info "Stream provider")
  (doseq [ch (get-in @*workers* [:stream cam])]
    ;; First data
    (send! ch {:headers {"Content-Type" "multipart/x-mixed-replace; boundary=FRAME"
                         "Cache-Control" "no-cache, no-store, max-age=0, must-revalidate"
                         "Pragma" "no-cache"
                         ;; "Connection" "close"
                         "Transfer-Encoding" "none"}
               
               ;; :body (cam/snap)
               } false))
  (go-loop []
    (let [st (datetime*)]
      (if-let [jpg (read cam)]
        (do
          (doseq [ch (get-in @*workers* [:stream cam])]
            (with-open [is (io/input-stream jpg)]
              (send! ch (str "--FRAME\n"
                             "Content-Type: image/jpg\n"
                             "Content-Length: " (count jpg) "\n\n")
                     false)
              (send! ch is false)
              (send! ch "\n" false)))
          (let [wait (- (quot 1000 (:fps cam)) (- (datetime*) st))]
            (when (< 0 wait)
              (<! (timeout wait)))))
        (Thread/sleep 100)))
    (if-not (seq (get-in @*workers* [:stream cam]))
      (log/info "Stream provider for" (:name cam) "closed")
      (recur))))


(defn stream-handler [{:keys [query-params] :as req}]
  (let [{:strs [id] :or {id "CAM0"}} query-params]
    (log/debug query-params)
    
    (if-let [cam (sensors id)]
      (do
        (when-not (get-in @*workers* [:stream cam])
          (swap! *workers* assoc-in [:stream cam] #{}))
        
        (with-channel req channel
          (on-close channel (fn [status]
                              (log/info "Connection closed " status)
                              (swap! *workers* update-in [:stream cam] disj channel)
                              (when-not (seq (get-in @*workers*[:stream cam]))
                                (write (assoc cam :op :stop)))
                              ;;(swap! clients dissoc channel)
                              ))
          ;; Send header
          (write (assoc cam :op :record))
          (send! channel {
                          :headers {"Content-Type" "multipart/x-mixed-replace; boundary=FRAME"
                                    "Cache-Control" "no-cache, no-store, max-age=0, must-revalidate"
                                    "Pragma" "no-cache"
                                    "Connection" "close"
                                    "Transfer-Encoding" "none"}
                          ;; :body (cam/snap)
                          } false)
          
          ;; Run if no client is watching this cam
          (when-not (seq (get-in @*workers* [:stream cam]))
            (log/info "Start stream provider for" (:name cam))
            (go-stream-provider cam))

          ;; Add channel 
          (swap! *workers* update-in [:stream cam] conj channel)))
      {:status 401 :body (str "Device not found. " id)})))

(def reitit-routes
  (rh/ring-handler
   (rh/router ["" 
               ["/scan" {:get scan-handler}]
               ["/sensor" {:get sensors-handler}]
               ["/read" {:get read-handler}]
               ["/write" {:get write-handler
                          :post write-handler}]
               ["/ws" {:get handler}]
               ["/camera" {:get (fn [{:keys [params]}]
                                  (let [{:strs [id] :or {id "CAM0"}} params]
                                    {:body (hiccup.core/html
                                            [:html
                                             [:head
                                              [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
                                              [:link {:href "https://cdn.jsdelivr.net/npm/bulma@0.9.1/css/bulma.min.css" :rel "stylesheet"}]]
                                             [:body
                                              [:div#app]
                                              [:div#device {:style "display:none;"} id]
                                              ;; [:script {:src "https://cdn.jsdelivr.net/npm/@tensorflow/tfjs"}]
                                              ;; [:script {:src "https://cdn.jsdelivr.net/npm/@tensorflow-models/coco-ssd"}]
                                              
                                              [:script {:type "text/javascript" :src "js/main.js"}
                                               
                                               ]]])}))}]
               ["/stream" {:get stream-handler}]
               ["/js/*" {:get (fn [req]
                                (let [path (subs (:uri req) 1)]
                                  (try
                                    {:status 200 :body (slurp (io/resource path))}
                                    (catch Exception e {:status 500 :body (str "Not found " path)}))))}]]
              ;; This instance must be specified, otherwise jsonize does not work.
              {:data {:muuntaja muuntaja.core/instance
                      :interceptors [(ip/parameters-interceptor)
                                     (im/format-negotiate-interceptor)
                                     (im/format-request-interceptor)
                                     (im/format-response-interceptor)]}})
   {:executor reitit.interceptor.sieppari/executor}))

;; (defroutes all-routes
;;   (GET "/scan" [] scan-handler)
;;   (GET "/sensor" [] sensors-handler)
;;   (GET "/read" [] read-handler)
;;   (GET "/write" [] write-handler)
;;   (POST "/write" [] write-handler)
;;   (GET "/ws" [] handler)
;;   (GET "/camera" [] (fn [req]
;;                       (log/debug "Request come:")
;;                       (hiccup.core/html
;;                        [:html
;;                         [:body
;;                          [:img#cam {:src "http://localhost:3000/stream?id=CAM0"}]
;;                          [:script {:type "text/javascript" :src "js/app.js"}

;;                           ]]])))
;;   (GET "/stream" [] stream-handler)
;;   (not-found "<p>Page not found</p>"))

(defn start-web-server [& {:keys [develop? ip port root] :or {ip "0.0.0.0" port 3000 root "."}}]
  (reset! server (run-server #'reitit-routes
                             ;; (cond-> #'all-routes
                             ;;   develop? (wrap-file ".")
                             ;;   develop? (wrap-content-type {:mime-types {"md" "text/markdown"}})
                             ;;   :always (-> (wrap-keyword-params)
                             ;;               (wrap-params)
                             ;;               (wrap-session)))
                             
                             {:ip ip :port port}))
  ;; (reset! server (run-server (cond-> #'all-routes
  ;;                              develop? (wrap-file ".")
  ;;                              develop? (wrap-content-type {:mime-types {"md" "text/markdown"}})
  ;;                              :always (-> (wrap-keyword-params)
  ;;                                          (wrap-params)
  ;;                                          (wrap-session)))
  
  ;;                            {:ip ip :port port}))
  
  (println "Booted Web server on " ip ":" port))

(defn stop-web-server []
  (when @server (@server :timeout 100))
  (reset! server nil))


(defn start-nrepl-server [& {:keys [ip port cider] :or {ip "0.0.0.0" port 7888}}]
  (letfn [(nrepl-handler []
            (require 'cider.nrepl)
            (ns-resolve 'cider.nrepl 'cider-nrepl-handler))]

    (if cider
      (nrepl/start-server :port port :bind ip :handler (nrepl-handler))
      (nrepl/start-server :port port :bind ip
                          :transport-fn transport/tty
                          :greeting-fn (fn [transport]
                                         (transport/send transport
                                                         {:out (str "\nWelcome to Kikori nREPL !\n\n"
                                                                    "user=> ")})
                                         )))
    (println (str "Booted nREPL server on " ip  ":" port (when cider (str " with cider option"))))))

(defn sensor->points [targets tags]
  (binding [*tz* UTC]
    (vec (reduce (fn [r [k vs]]
                   (if ((set (map keyword targets)) (keyword k))
                     (let [n (name k)
                           ts (conj {"name" n} tags)]
                       (cond
                         (seq? vs) (cons {:measurement n
                                          :tags ts
                                          :fields {"value" (float (first vs))}
                                          :timestamp (datetime*)} r)
                         (map? vs) (concat r (map (fn [[measurement value]]
                                                    {:measurement (name measurement)
                                                     :tags ts
                                                     :fields {"value" (float value)}
                                                     :timestamp (datetime*)})
                                                  vs))))
                     r))
                 '()
                 (apply facade/read-sensors targets)))))


(defn- reference []
  (with-first-device
    ;; system
    (config :system {:PCB "1.0.0" :power :5.0})
    
    ;; ADC
    (config :ADC {:vrm :VDD})
    (bind :GP0 :GPIO)
    (bind :GP2 :ADC2)
    (place :GP0 {:name "GP0"})
    (place :GP2 {:name "ADC2"})
    
    ;; Declare to use I2C and UART

    (config :I2C)
    ;; If no address on I2C, sensor info will be removed.
    (place :I2C {:addr 0x76 :name "BME0" :module :BME280})
    (place :I2C {:addr 0x77 :name "BME1" :module :BME280})
    (place :I2C {:addr 0x0a :name "D6T44L0" :module :D6T44L})    

    ;; If no :module is specified, it is queried by the second arity and called the read function defined on that.
    (config :UART {:path "/dev/ttyACM0" :baud-rate 9600})
    ;; Will be called read function  by :module    
    (place :UART {:name "GPS0" :module :GPS})
    (ready!)))

(def options-schema
  [["-s" "--secrets <file>" "a file contains user/password for console login"]
   [nil "--help" "This help"]])


(defn -main [& args]
  (try
    (let [{:keys [options arguments summary] :as argv} (parse-opts args options-schema)
          {:keys [nrepl-host nrepl-port cider help]} options
          config (first arguments)]

      (binding [*ns* (create-ns 'kikori.shell)]
        (kikori.core/refer-kikori)
        (util/set-log-level! :warn)
        
        (if help
          (do
            (println "\nUsage: kikori server <options>\n")
            (println summary "\n"))
          (if config
            (try
              (eval (p/parse (slurp config)))
              (catch Exception e (do (log/error (.getMessage e))
                                     (trace/print-stack-trace e))))
            (do (load-modules)
                (reference)
                (start-web-server :ip "0.0.0.0" :port 3000))))))

    (catch Throwable e (do (log/error (.getMessage e))
                           (trace/print-stack-trace e)
                           (stop-web-server)))))

