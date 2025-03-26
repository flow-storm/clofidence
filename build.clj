(ns build
  (:require [clojure.tools.build.api :as b]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]))

(def version (or (System/getenv "VERSION")
                 "0.4.1"))

(def target-dir "target")
(def class-dir (str target-dir "/classes"))

(defn clean [_]
  (b/delete {:path target-dir}))

(defn jar [_]
  (clean nil)
  (let [lib 'com.github.flow-storm/clofidence
        basis (b/create-basis {:project "deps.edn"})
        jar-file (format "%s/%s.jar" target-dir (name lib))
        src-dirs ["src"]]
    (b/write-pom {:class-dir class-dir
                  :lib lib
                  :version version
                  :basis basis
                  :src-dirs src-dirs
                  :pom-data [[:licenses
                              [:license
                               [:name "Unlicense"]
                               [:url "http://unlicense.org/"]]]]})

    (b/copy-dir {:src-dirs src-dirs
                 :target-dir class-dir})
    (b/jar {:class-dir class-dir
            :jar-file jar-file})))
