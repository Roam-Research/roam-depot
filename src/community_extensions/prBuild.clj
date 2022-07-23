(ns community-extensions.prBuild
  (:require
   [clojure.string :as str]
   [cheshire.core :as json]
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

(defn diff [{pr "--pr"}]
  (let [branch (str "pr-" pr)
        _      (core/sh "git" "fetch" "origin" (str "pull/" pr "/head:" branch))]
    (for [[mode path] (->> (core/sh "git" "diff" "--name-status" "--merge-base" "remotes/origin/main" branch)
                           (str/split-lines)
                           (map #(str/split % #"\t")))
          :when       (str/starts-with? path "extensions/")
          :let        [[_ user repo] (re-matches #"extensions/([^/]+)/([^/]+)\.json" path)
                       ext-id        (str user "+" repo)
                       file          (io/file path)
                       data          (when (.exists file)
                                       (json/parse-string (slurp file)))]]
      [mode ext-id data])))

(defn -main [& {:as args-map}]
  (prn "args" args-map)
  (let [dif (diff args-map)]
    (prn dif)
    (doseq [[mode ext-id data] dif]
      (prn mode ext-id data)
      #_
      (case mode
        "A" (build ext-id data)
        "M" (build ext-id data)
        nil)))
  (shutdown-agents))
