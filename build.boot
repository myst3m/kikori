(def project 'kikori)
(def version "1.7.1-SNAPSHOT")

(set-env! :resource-paths #{"resources" "src"}
          :asset-paths #{"docs" "assets"}
          :source-paths   #{"test"}
          :dependencies   '[[org.clojure/clojure "1.10.1"]
                            [org.clojure/core.specs.alpha "0.2.44"]
                            [org.clojure/data.json "0.2.6"]
                            [org.clojure/core.async "0.4.490"]
                            [org.clojure/tools.cli "0.4.1"]
                            [net.java.dev.jna/jna "4.5.2"]
                            [com.taoensso/timbre "5.1.0"]
                            [theorems/silvur "1.9.10"]
                            
                            [http-kit "2.5.0"]
                            [hiccup "1.0.5"]
                            [instaparse "1.4.10"]
                            [purejavahidapi "0.0.2"]
                            [irmagician "0.8.5"]
                            [nrepl "0.8.2"]
                            [cider/cider-nrepl "0.25.3"]
                            
                            [org.jline/jline "3.9.0"]
                            [net.mikera/imagez "0.12.0"]
                            [net.sf.cssbox/cssbox "4.14"]
                            
                            [org.clojure/data.codec "0.1.1"]
                            [org.apache.xmlgraphics/batik-all "1.10"]
                            [com.github.hypfvieh/bluez-dbus "0.1.1"]
                            [metosin/reitit "0.5.11"]
                            
                            [adzerk/bootlaces "0.1.13" :scope "test"]
                            [adzerk/boot-test "RELEASE" :scope "test"]
                            [adzerk/boot-jar2bin "1.1.1" :scope "test"]
                            [boot-codox "0.10.4" :scope "test"]
                            [radicalzephyr/boot-dpkg "0.1.0" :scope "test"]
                            
                            [incanter/incanter-core "1.9.3" :scope "test"]
                            [incanter/incanter-charts "1.9.3" :scope "test"]])




(require '[adzerk.boot-test :refer :all]
         '[adzerk.bootlaces :refer :all]
         '[adzerk.boot-jar2bin :refer :all]
         '[radicalzephyr.boot-dpkg :refer :all]
         '[clojure.java.io :as io]
         '[codox.boot :refer [codox]])

(bootlaces! version)

(task-options!
 codox {:name (str project)
        :version version
        :source-paths ["src"]
        :output-path "api"}
 
 pom {:project     project
      :version     version
      :description "A clojure library to control Groovy-IOT from Omiya Giken LLC."
      :url         "https://gitlab.com/myst3m/kikori"
      :scm         {:url "https://gitlab.com/myst3m/kikori"}
      :license     {"Eclipse Public License"
                    "http://www.eclipse.org/legal/epl-v10.html"}}
 aot {:namespace #{'kikori.core}}
 dpkg {:package "kikori"
       :version version
       :section "java"
       :priority "optional"
       :architecture "all"
       :maintainer "Tsutomu Miyashita"
       :description "This is a bridge software between sensors on GPIO/I2C and Web"}
 bin {:output-dir "./"})

(defn gen-systemd-conf []
  (let [cmd (str "/usr/bin/java -cp "
                 "/usr/share/java/kikori-" version ".jar "
                 "kikori.server "
                 "/etc/kikori/system.conf")]
    (str
     "
[Unit]
  Description=Kikori instance
  Documentation=https://gitlab.com/myst3m/kikori
  
[Service]
  EnvironmentFile=/etc/kikori/system.conf
  User=kikori
  Group=kikori
  Type=simple
  Restart=on-failure
  WorkingDirectory=/run/kikori
  RuntimeDirectory=kikori 
  RuntimeDirectoryMode=0755
  ExecStart=" cmd
 "

[Install]
  WantedBy=multi-user.target
")))

(defn gen-hid-rule []
  (str "
#HIDAPI/hidraw
KERNEL==\"hidraw*\", ATTRS{busnum}==\"1\", ATTRS{idVendor}==\"04d8\", ATTRS{idProduct}==\"00dd\", MODE=\"0666\"
"))

(deftask deb []
  (spit "assets/CHANGELOG.md" (slurp (io/file "CHANGELOG.md")))
  (spit "assets/LICENSE" (slurp (io/file "LICENSE")))
  (spit "assets/kikori.sh" (slurp (io/file "kikori")))
  (spit "assets/system.conf" (slurp (io/file "examples/conf/system.conf")))
  (spit "assets/kikori.service" (gen-systemd-conf))
  (spit "assets/99-hid.rules" (gen-hid-rule))
  (spit "assets/system.clj" (slurp (io/file "examples/reference.clj")))
  (comp (pom)
        (aot :namespace #{'kikori.util
                          'kikori.chip.mcp2221a
                          'kikori.hid
                          'kikori.device
                          'kikori.interop
                          'kikori.core
                          'kikori.operation
                          'kikori.module
                          'kikori.server
                          'kikori.parser
                          'kikori.facade
                          'kikori.shell})
        (uber)
        (jar :main 'kikori.server)
        (sift :include #{#"\.jar$" #"\.conf$" #"^[a-zA-Z0-9-_]+\.md$" #"^LICENSE" #"^kikori\.sh$"
                         #"DEBIAN/.*" #"system\.conf" #"system\.clj" #"99-hid.rules"
                         #"kikori\.service"}
              :move {#"(kikori-.*\.jar)" "usr/share/java/$1"
                     #"(system.conf)$" "etc/kikori/system.conf"
                     #"([a-z0-9-_]+\.md)$" "usr/share/doc/kikori/tutorial/$1"
                     #"system.clj" "usr/share/doc/kikori/clj/system.clj"
                     #"kikori.service" "usr/lib/systemd/system/kikori.service"
                     #"99-hid.rules" "etc/udev/rules.d/99-hid.rules"
                     #"CHANGELOG.md" "usr/share/doc/kikori/CHANGELOG.md"
                     #"LICENSE" "usr/share/doc/kikori/LICENSE"
                     #"kikori.sh" "usr/bin/kikori"})
        (dpkg)
        (target)))

(deftask build
  "Build and install the project locally."
  []
  
  (comp (aot :namespace #{'kikori.util
                          'kikori.chip.mcp2221a
                          'kikori.hid
                          'kikori.device
                          'kikori.interop
                          'kikori.core
                          'kikori.operation
                          'kikori.module
                          'kikori.server
                          'kikori.parser
                          'kikori.facade
                          'kikori.shell})
        (pom)
        (uber)
        (jar :main 'kikori.shell)
        (codox)
        (target)))

(require '[adzerk.boot-test :refer [test]])


;; To use a device file created by udev, it could be better to add the following rule to avoid
;; permission error of the device file

;; /etc/udev/rules.d/99-hid.rules

;; #HIDAPI/hidraw
;; KERNEL=="hidraw*", ATTRS{busnum}=="1", ATTRS{idVendor}=="04d8", ATTRS{idProduct}=="00dd", MODE="0666"

;; java -Daether.checksums.forSignature=true -Djava.net.preferIPv4Stack=true -Dboot.app.path=/home/myst/bin/boot-clj -jar /home/myst/bin/boot-clj build-jar push-release
