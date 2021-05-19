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


(ns kikori.command
  (:gen-class)
  (:import [java.io File])
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:require [clojure.tools.cli :refer [parse-opts]])
  (:require [kikori.core :refer [open shutdown ready! config enumerate]]
            [kikori.util :refer [set-log-level!]]
            [kikori.device :as dev])
  (:require [silvur.http :as http])
  (:require [taoensso.timbre :as log]))

(def ^:private repository "https://theorems.co/kikori/")
(defonce ^:private local-repository ".m2")
(defn list+ []
  (let [files (read-string (slurp (:body @(http/get (str repository "modules.edn")))))]
    
    (doseq [{:keys [file desc version]} files]
      (println (format "%-20s %-10s %-20s" (str/replace file #".jar" "") version desc)))))


(defn install+ [module-name {:keys [target]}]
  (let [path (str target "/" module-name)]
    (io/make-parents path)
    (with-open [out (clojure.java.io/output-stream path)]
      (let [body (:body @(http/get (str repository "/" module-name)))]
        (println (str repository "/" module-name))
        (println body)
        (.write out (.bytes body))))))

(defn- find-acm-device [path]
  (when-let [usb-path-number  (condp = (System/getProperty "os.arch")
                                "amd64" 8
                                "arm" 10
                                nil)]
    (let [paths (str/split path #"/")
          root (take usb-path-number paths)
          q (str/join (butlast (last root)))

          search-root (str/join"/"  (butlast root))]
      (->> (io/file search-root)
           (.list)
           (filter #(re-find (re-pattern q) %))
           (sort)
           (map #(io/file (str search-root "/" % "/tty")))
           (mapcat #(.list %))
           (first)))))


(defn scan+ [& {:keys [template?]}]
  (set-log-level! :warn)
  (let [devs (for [d (map open (enumerate "Microchip"))]
               (let [{:keys [path device-id] :as dev} (-> d (config :I2C) (ready!))
                     acm (when (= "Linux" (System/getProperty "os.name"))
                           (find-acm-device path))
                     hid (last (str/split path #"/"))]
                 (log/debug path device-id)
                 {:device-id device-id
                  :hid hid
                  :acm acm
                  :i2c (map #(format "0x%x" %) (dev/i2c-scan dev  (range 0x00 0x80)))}))]
    (shutdown)
    (doseq [{:keys [device-id hid acm i2c]} (sort-by :hid devs)]
      (println device-id
               ":"
               hid
               ":"
               (or acm "-")
               ":"
               i2c))))

;; (def options-schema
;;   [["-d" "--target DIRECTORY" "Target directory."
;;     :default (str (System/getProperty "user.home") "/.kikori/modules")]])

;; (defn -main [& args]
;;   (let [{:keys [options arguments summary]} (parse-opts args options-schema)]
;;     (condp = (keyword (first arguments))
;;       :list (list+)
;;       :install (install+ (second arguments) options)
;;       :scan (scan+)
;;       (println summary))))
