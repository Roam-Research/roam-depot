(ns build
  "clojure -M script/build.clj [--from <sha>] [--to <sha>]"
  (:refer-clojure :exclude [ref])
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.java.shell :as shell]
    [clojure.string :as str])
  (:import
    [java.io File FileInputStream]
    [java.nio.file Files Path]))

(def args-map
  (apply array-map *command-line-args*))

(def sha-from
  (get args-map "--from" "HEAD~1"))

(def sha-to
  (get args-map "--to" "HEAD"))

(def root
  (-> *file* io/file .getParentFile .getParentFile))

(alter-var-root #'shell/*sh-dir*
  (constantly root))

(defn sh [& cmd]
  (apply println "[ sh ]" cmd)
  (let [{:keys [out err exit]} (apply shell/sh cmd)]
    (when-not (str/blank? err)
      (binding [*out* *err*]
        (println err)))
    (if (= 0 exit)
      (str/trim out)
      (throw (Exception. (str "Command '" (str/join " " cmd) "' failed with exit code " exit))))))

(defn build [ext-id data]
  (let [dir (io/file root "checkout" ext-id)
        {repo "source_repo"
         commit "source_commit"} data]
    (when-not (.exists dir)
      (.mkdirs dir)
      (sh "git" "clone" repo (str "checkout/" ext-id)))
    (shell/with-sh-dir dir
      (sh "git" "-c" "advice.detachedHead=false" "checkout" commit))))

(defn -main [& args]
  (doseq [[mode path] (->> (sh "git" "diff" "--name-status" sha-from sha-to)
                        (str/split-lines)
                        (map #(str/split % #"\t")))
          :when (str/starts-with? path "extensions/")
          :let [[_ user repo] (re-matches #"extensions/([^/]+)/([^/]+)\.json" path)
                ext-id   (str user "+" repo)
                data (-> (io/file root path)
                       (slurp)
                       (json/parse-string))]]
    (cond
      (= "A" mode) (build ext-id data)
      (= "M" mode) (build ext-id data)
      (str/starts-with? "R" mode) :nop))
    
  (shutdown-agents))

(-main)