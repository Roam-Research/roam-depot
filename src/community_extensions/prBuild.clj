(ns community-extensions.prBuild
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [community-extensions.core :as core]))

(def args-map
  (apply array-map *command-line-args*))

(defn build [ext-id data]
  (let [dir     (io/file "checkout" ext-id)
        exists? (.exists dir)
        {repo "source_repo"
         commit "source_commit"} data]
    (when-not exists?
      (.mkdirs dir)
      (core/sh "git" "clone" repo (str "checkout/" ext-id)))
    (shell/with-sh-dir dir
      (when exists?
        (core/sh "git" "fetch" "--all"))
      (core/sh "git" "-c" "advice.detachedHead=false" "checkout" commit)
      (when (.exists (io/file dir "build.sh"))
        (core/sh "chmod" "+x" "build.sh")
        (core/sh "./build.sh")))))

(defn -main [& {:as args-map}]
  (println args-map)
  #_
  (doseq [[mode ext-id data] (core/diff args-map)]
    (case mode
      "A" (build ext-id data)
      "M" (build ext-id data)
      nil))
  (shutdown-agents))
