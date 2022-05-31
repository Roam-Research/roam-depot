(ns community-extensions.core
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.java.shell :as shell]
    [clojure.string :as str]))

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
  (let [{sha-from "--from"
         sha-to   "--to"} args-map]
    (for [[mode path] (->> (sh "git" "diff" "--name-status" (or sha-from "HEAD~1") (or sha-to "HEAD"))
                        (str/split-lines)
                        (map #(str/split % #"\t")))
          :when (str/starts-with? path "extensions/")
          :let [[_ user repo] (re-matches #"extensions/([^/]+)/([^/]+)\.json" path)
                ext-id        (str user "+" repo)
                file          (io/file path)
                data          (when (.exists file)
                                (json/parse-string (slurp file)))]]
      [mode ext-id data])))