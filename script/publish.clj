(ns publish
  (:refer-clojure :exclude [ref])
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.java.shell :as shell]
    [clojure.string :as str])
  (:import
    [java.io File FileInputStream]
    [java.nio.file Files Path]
    [com.google.firebase.auth FirebaseAuth]
    [com.google.auth.oauth2 GoogleCredentials]
    [com.google.firebase FirebaseOptions FirebaseApp]
    [com.google.firebase.database DatabaseReference DatabaseReference$CompletionListener FirebaseDatabase ValueEventListener]
    [com.google.cloud.storage BlobId BlobInfo Blob$BlobSourceOption Bucket BucketInfo Storage Storage$BlobTargetOption StorageOptions]))

(when (or (zero? (count *command-line-args*))
        (odd? (count *command-line-args*)))
  (println "Usage:")
  (println)
  (println "  clojure -M script/publish.clj --key <path-to-key> [--from <sha>] [--to <sha>] [--realtime <name>] [--storage <name>]")
  (System/exit 1))

(def args-map
  (apply array-map *command-line-args*))

(def credentials
  (GoogleCredentials/fromStream (io/input-stream (get args-map "--key"))))

(def sha-from
  (get args-map "--from" "HEAD~1"))

(def sha-to
  (get args-map "--to" "HEAD"))

(def realtime-name
  (get args-map "--realtime" "firescript-577a2.firebaseio.com"))

(def storage-name
  (get args-map "--storage" "firescript-577a2.appspot.com"))

(def mime-types
  {"md"  "text/markdown"
   "js"  "application/js"
   "css" "application/css"})

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

(defn ref-get [ref]
  (println "[ get ]" (str ref))
  (let [p (promise)]
    (.addListenerForSingleValueEvent ref
      (reify ValueEventListener
        (onCancelled [_ error]
          (deliver p (.toException error)))
        (onDataChange [_ snapshot]
          (deliver p (.getValue snapshot)))))
    (if (instance? Throwable @p)
      (throw (ex-info (str "Failed to get " ref " with: " @p)))
      @p)))

(defn ref-set! [ref value]
  (println "[ set ]" (str ref))
  (let [p (promise)]
    (.setValue ref value
      (reify DatabaseReference$CompletionListener
        (onComplete [_ error ref]
          (deliver p error))))
    (when-some [error @p]
      (throw (ex-info (str "Failed to set " ref " with: " error) {:error error})))))

(defn ref [db & path]
  (.getReference db (str/join "/" path)))

(defn upload [version-id & files]
  (let [storage (-> (StorageOptions/newBuilder)
                  (.setCredentials credentials)
                  (.build)
                  (.getService))]
    (doseq [file files
            :when (.exists file)
            :let [path    (.toPath file)
                  bytes   (Files/readAllBytes path)
                  name    (str (.getFileName path))
                  [_ ext] (re-matches #".*\.(\w+)" name)
                  o       (str "extension_files/" version-id "/" name)
                  id      (BlobId/of storage-name o)
                  mime    (mime-types ext)
                  info    (-> (BlobInfo/newBuilder id)
                            (.setContentType mime)
                            (.build))]]
      (println "[ upload ]" (str path) "as" mime "of" (count bytes) "bytes")
      (.create storage info bytes (make-array Storage$BlobTargetOption 0)))))

(defn publish [db ext-id data]
  (let [{:strs [version]} (ref-get (ref db "extensions" ext-id))
        version'   (-> (or version "0") parse-long inc str)
        version-id (str ext-id "+" version')
        data'      (assoc data
                     "extension" ext-id
                     "version" version')
        dir        (io/file root "checkout" ext-id)]
    (upload version-id
      (io/file dir "README.md")
      (io/file dir "CHANGELOG.md")
      (io/file dir "extension.js")
      (io/file dir "extension.css"))
    (ref-set! (ref db "extension_versions" version-id) data')
    (ref-set! (ref db "extensions" ext-id) data')))

(defn -main [& args]
  (let [opts (-> (FirebaseOptions/builder)
               (.setCredentials credentials)
               (.setDatabaseUrl (str "https://" realtime-name "/"))
               (.build))
        app  (FirebaseApp/initializeApp opts)
        db   (FirebaseDatabase/getInstance)]
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
        (= "A" mode) (publish db ext-id data)
        (= "M" mode) (publish db ext-id data)
        (str/starts-with? "R" mode) :nop))
    
    (shutdown-agents)))

(-main)