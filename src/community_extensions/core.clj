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

(def safe-id-pattern
  "Allowlist regex for ext-id to prevent path/namespace injection.
   Note: Firebase Realtime Database keys cannot contain . $ # [ ] /
   so we only allow alphanumeric, underscore, hyphen, and plus."
  #"^[A-Za-z0-9_+-]+$")

(defn validate-ext-id! [ext-id]
  (when-not (re-matches safe-id-pattern ext-id)
    (throw (ex-info (str "Invalid ext-id (contains disallowed characters): " ext-id)
                    {:ext-id ext-id})))
  ext-id)

(defn sh [& cmd]
  (apply println "[ sh ]" cmd)
  (let [{:keys [out err exit]} (apply shell/sh cmd)]
    (when-not (str/blank? err)
      (binding [*out* *err*]
        (println err)))
    (if (= 0 exit)
      (str/trim out)
      (throw (Exception. (str "Command '" (str/join " " cmd) "' failed with exit code " exit))))))

(defn git-show-json
  "Reads and parses `path` as it was at git revision `rev`.
   Returns nil if the file did not exist at that revision."
  [rev path]
  (try
    (json/parse-string (sh "git" "show" (str rev ":" path)))
    (catch Exception _
      nil)))

(defn diff
  "Returns a seq of [mode ext-id data old-data] for every extension JSON
   touched by the diff. `data` is the new content (nil when deleted),
   `old-data` is the previous content (only populated for modifications)."
  [args-map]
  (let [[diff old-rev]
        (if-some [pr (get args-map "--pr")]
          (let [branch (str "pr-" pr)]
            (sh "git" "fetch" "origin" (str "pull/" pr "/head:" branch))
            (sh "git" "checkout" branch)
            [(sh "git" "diff" "--name-status" "--merge-base" "remotes/origin/main" branch)
             "remotes/origin/main"])
          (let [{sha-from "--from"
                 sha-to   "--to"
                 :or {sha-from "HEAD~1"
                      sha-to   "HEAD"}} args-map]
            [(sh "git" "diff" "--name-status" sha-from sha-to)
             sha-from]))]
    (for [[mode path] (->> diff
                        (str/split-lines)
                        (map #(str/split % #"\t")))
          :when       (str/starts-with? path "extensions/")
          :let        [[_ user repo] (re-matches #"extensions/([^/]+)/([^/]+)\.json" path)
                       ext-id        (validate-ext-id! (str user "+" repo))
                       file          (io/file path)
                       data          (when (.exists file)
                                       (json/parse-string (slurp file)))
                       old-data      (when (= mode "M")
                                       (git-show-json old-rev path))]]
      [mode ext-id data old-data])))

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
