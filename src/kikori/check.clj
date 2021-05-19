(ns kikori.check
  (:refer-clojure :exclude [read])
  (:gen-class)
  (:require [clojure.tools.cli :refer [parse-opts]]
            [clojure.string :as str]
            [clojure.java.io :as io])
  (:require [kikori.core :refer [enumerate]]))

(def ^:private ^:dynamic *os*)

(def ^:private options-schema
  [[nil "--help" "This help"]])

(defn- usage [summary]
  (println "\nUsage: kikori check <options>\n")
  (println summary "\n")  )

(defn- commands []
  (filter #(not= '-main %) (keys (ns-publics *ns*))))

(defn formatter [subject m]
  (str/join "\n"
            (cons subject
                  (map (fn [[k v]]
                         (format "  %-30s %s" k (or (seq v) "-")))
                       m))))

(defn- scan-linux []
  (with-open [rdr (io/reader "/etc/modules")
              fb-rdr (io/reader "/etc/modprobe.d/fbtft.conf")]
    (println)
    (println  (formatter "Common:" {:hidraw (keep #(re-find #"hidraw[0-9]+" (.getName %)) (file-seq (io/as-file "/dev")))
                                    :uart (keep #(re-find #"ttyACM[0-9]+" (.getName %)) (file-seq (io/as-file "/dev")))}))
    (println)
    (println (formatter "LCD:" {:frame-buffer (keep #(re-find #"fb[0-9]+" (.getName %)) (file-seq (io/as-file "/dev")))
                                :modules (filter #(re-find #"spi-bcm2835|fbtft" %) (line-seq rdr))
                                :config (->> (line-seq fb-rdr)
                                             (map str/trim)
                                             (filter #(and (re-find #"options" %)
                                                           (not (re-find #"^[ \t]*#" %)))))}))
    (println)))

(defn scan-mac []
  (println  (formatter "Common:" {:hidraw (filter #(re-find #"hidraw" (.getPath %)) (file-seq (io/as-file "/dev")))
                                  :uart (filter #(re-find #"usbmodem" (.getPath %)) (file-seq (io/as-file "/dev")))})))
(defn scan-windows [])

(defn scan []
  (condp #(re-find (re-pattern %1) %2) (str/lower-case (System/getProperty "os.name"))
    "linux" (scan-linux)
    "mac" (scan-mac)
    "windows" (scan-windows)
    
    (println "No support")
    
    )
  )


(defn -main [& args]
  (binding [*os* (System/getProperty "os.name")]
    (let [{:keys [options arguments summary] :as argv} (parse-opts args options-schema)]
      (if (:help options)
        (usage summary)
        (try
          (scan)
          (catch Throwable t (println (or (:cause (Throwable->map t)) (.getMessage t)))))))))
