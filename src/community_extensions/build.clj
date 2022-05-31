(ns community-extensions.build
  (:require
    [clojure.java.io :as io]
    [clojure.java.shell :as shell]
    [clojure.string :as str]
    [community-extensions.core :as core]))

(def args-map
  (apply array-map *command-line-args*))

(defn build [ext-id data]
  (let [dir (io/file "checkout" ext-id)
        {repo "source_repo"
         commit "source_commit"} data]
    (when-not (.exists dir)
      (.mkdirs dir)
      (core/sh "git" "clone" repo (str "checkout/" ext-id)))
    (shell/with-sh-dir dir
      (core/sh "git" "-c" "advice.detachedHead=false" "checkout" commit))))

(defn -main [& {:as args-map}]
  (doseq [[mode ext-id data] (core/diff args-map)]
    (cond
      (= "A" mode) (build ext-id data)
      (= "M" mode) (build ext-id data)
      (str/starts-with? "R" mode) :nop))
  (shutdown-agents))