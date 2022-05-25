#! bb

(ns publish
  (:require
    [babashka.curl :as curl]
    [babashka.fs :as fs]
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.java.shell :as shell]
    [clojure.string :as str])
  (:import
    [java.io File]))

(def root
  (-> *file* fs/canonicalize fs/parent fs/parent fs/file))

(alter-var-root #'shell/*sh-dir* (constantly root))

(def db-url
  "https://firescript-577a2.firebaseio.com/")

(defn sh [& cmd]
  (apply println "[ sh ]" cmd)
  (let [{:keys [out err exit]} (apply shell/sh cmd)]
    (when-not (str/blank? err)
      (binding [*out* *err*]
        (println err)))
    (if (= 0 exit)
      (str/trim out)
      (throw (Exception. (str "Command '" (str/join " " cmd) "' failed with exit code " exit))))))

(def token
  (sh "/bin/bash" (str (fs/file root "script" "token.sh"))))

(def diff
  (apply sh "git" "diff" "--name-status" *command-line-args*))

(defn request [method url & [body]]
  (println "[" method "]" url)
  (let [f       (case method
                 :get curl/get
                 :put curl/put
                 :post curl/post)
        url'    (str url "?auth=" token)
        headers {"Content-Type" "application/json; charset=utf-8"
                 "Accept" "application/json"}
        body'   (some-> body json/generate-string)]
    (some->
      (f url' (cond-> {:headers headers}
                body' (assoc :body body')))
      (:body)
      (json/parse-string true))))

(defn publish [id data]
  (let [{:keys [version] :as resp} (request :get (str db-url "extensions/" id ".json"))
        version' (-> (or version "0") parse-long inc str)]
    (when-not (fs/exists? (fs/file root "checkout"))
      (sh "git" "clone" (:source_repo data) "checkout"))
    (shell/with-sh-dir (fs/file root "checkout")
      (sh "git" "-c" "advice.detachedHead=false" "checkout" (:source_commit data)))
    (let [url       (str db-url "extension_files/" id "+" version' ".json")
          readme    (fs/file root "checkout" "README.md")
          changelog (fs/file root "checkout" "CHANGELOG.md")
          js        (fs/file root "checkout" "extension.js")
          css       (fs/file root "checkout" "extension.css")]
      (request :put url
        (cond-> {}
          (fs/exists? readme)
          (assoc :readme_md (slurp readme))
          
          (fs/exists? changelog)
          (assoc :changelog_md (slurp changelog))
          
          (fs/exists? js)
          (assoc :extension_js (slurp js))
          
          (fs/exists? css)
          (assoc :extension_css (slurp css)))))
    (let [url   (str db-url "extension_versions/" id "+" version' ".json")
          data' (assoc data
                  :extension id
                  :version version')]
      (request :put url data'))
    (let [url   (str db-url "extensions/" id ".json")
          data' (assoc data
                  :version version')]
      (request :put url data'))))

(defn -main []
  (doseq [[mode path] (->> diff
                        (str/split-lines)
                        (map #(str/split % #"\t")))
          :when (str/starts-with? path "extensions/")
          :let [[_ user repo] (re-matches #"extensions/([^/]+)/([^/]+)\.json" path)
                id   (str user "+" repo)
                data (-> (fs/file root path)
                       (slurp)
                       (json/parse-string true))]]
    (cond
      (= "A" mode) (publish id data)
      (= "M" mode) (publish id data)
      (str/starts-with? "R" mode) :nop)))

(-main)