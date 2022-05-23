(ns publish
  (:require
    [babashka.curl :as curl]
    [babashka.fs :as fs]
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]))

(def root
  (-> *file* fs/canonicalize fs/parent fs/parent))

(def db-url
  "https://firescript-577a2.firebaseio.com/")

(defn publish-added [id data]
  (let [data' (assoc data
                "version" "1")]
    (curl/put (str db-url "extensions/" id ".json") {:body data'}))
  (let [data' (assoc data
                "extension" id
                "version" "1")]
    (curl/put (str db-url "extension_versions/" id "+1.json") {:body data'})))

(doseq [[mode path] (->> *in*
                      (io/reader)
                      (line-seq)
                      (map #(str/split % #"\t")))
        :when (str/starts-with? path "extensions/")
        :let [[_ user repo] (re-matches #"extensions/([^/]+)/([^/]+)\.json" path)
              id   (str user "+" repo)
              data (-> (fs/file root path)
                     (slurp)
                     (json/parse-string))]]
  (case mode
    "A" (publish-added id data)
    nil))