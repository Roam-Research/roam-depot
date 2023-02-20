(ns community-extensions.core
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.java.shell :as shell]
    [clojure.string :as str])
  (:import
    [java.time ZonedDateTime ZoneId]
    [java.time.format DateTimeFormatter]))

(def mime-types
  {"md"  "text/markdown"
   "js"  "application/js"
   "css" "application/css"})

(defn sh [& cmd]
  (apply println "[ sh ]" cmd)
  (let [{:keys [out err exit]} (apply shell/sh cmd)]
    (when-not (str/blank? err)
      (binding [*out* *err*]
        (println err)))
    (if (= 0 exit)
      (str/trim out)
      (throw (Exception. (str "Command '" (str/join " " cmd) "' failed with exit code " exit))))))

(defn diff [args-map]
  (let [diff (if-some [pr (get args-map "--pr")]
               (let [branch (str "pr-" pr)]
                 (sh "git" "fetch" "origin" (str "pull/" pr "/head:" branch))
                 (sh "git" "checkout" branch)
                 (sh "git" "diff" "--name-status" "--merge-base" "remotes/origin/main" branch))
               (let [{sha-from "--from"
                      sha-to   "--to"
                      :or {sha-from "HEAD~1"
                           sha-to   "HEAD"}} args-map]
                 (sh "git" "diff" "--name-status" sha-from sha-to)))]
    (for [[mode path] (->> diff
                        (str/split-lines)
                        (map #(str/split % #"\t")))
          :when       (str/starts-with? path "extensions/")
          :let        [[_ user repo] (re-matches #"extensions/([^/]+)/([^/]+)\.json" path)
                       ext-id        (str user "+" repo)
                       file          (io/file path)
                       data          (when (.exists file)
                                       (json/parse-string (slurp file)))]]
      [mode ext-id data])))

(defn slurp-json [path]
  (-> path
    (slurp)
    (json/parse-string true)))

(defn timestamp
  ([]
   (timestamp (ZonedDateTime/now (ZoneId/of "UTC"))))
  ([^ZonedDateTime date]
   (let [date'  (.withZoneSameInstant date (java.time.ZoneId/of "UTC"))
         format (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ssX")]
     (.format format date'))))

(comment
  (timestamp))