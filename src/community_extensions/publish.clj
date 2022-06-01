(ns community-extensions.publish
  (:refer-clojure :exclude [ref])
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.java.shell :as shell]
    [clojure.string :as str]
    [community-extensions.core :as core])
  (:import
    [java.io File FileInputStream]
    [java.nio.file Files Path]
    [com.google.firebase.auth FirebaseAuth]
    [com.google.auth.oauth2 GoogleCredentials]
    [com.google.firebase FirebaseOptions FirebaseApp]
    [com.google.firebase.database DatabaseReference DatabaseReference$CompletionListener FirebaseDatabase ValueEventListener]
    [com.google.cloud.storage BlobId BlobInfo Blob$BlobSourceOption Bucket BucketInfo Storage Storage$BlobTargetOption StorageOptions]))

(def mime-types
  {"md"  "text/markdown"
   "js"  "application/js"
   "css" "application/css"})

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

(defn ref-find [ref ^String key ^String value]
  (println "[ find ]" (str ref) key "=" value)
  (let [p (promise)]
    (.addListenerForSingleValueEvent (-> ref (.orderByChild key) (.equalTo value))
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

(defn ref-remove! [ref]
  (println "[ remove ]" (str ref))
  (let [p (promise)]
    (.removeValue ref
      (reify DatabaseReference$CompletionListener
        (onComplete [_ error ref]
          (deliver p error))))
    (when-some [error @p]
      (throw (ex-info (str "Failed to remove " ref " with: " error) {:error error})))))

(defn ref [db & path]
  (.getReference db (str/join "/" path)))

(defn upload [storage version-id & files]
  (vec
    (for [file files
          :when (.exists file)
          :let [path    (.toPath file)
                bytes   (Files/readAllBytes path)
                name    (str (.getFileName path))
                [_ ext] (re-matches #".*\.(\w+)" name)
                o       (str "extension_files/" version-id "/" name)
                id      (BlobId/of "firescript-577a2.appspot.com" o)
                mime    (mime-types ext)
                info    (-> (BlobInfo/newBuilder id)
                          (.setContentType mime)
                          (.build))]]
      (do
        (println "[ upload ]" (str path) "as" mime "of" (count bytes) "bytes")
        (.create storage info bytes (make-array Storage$BlobTargetOption 0))
        o))))

(defn publish [db storage ext-id data]
  (let [version    (->> (ref-find (ref db "extension_versions") "extension" ext-id)
                     (vals)
                     (map #(get % "version"))
                     (map parse-long)
                     (reduce max 0))
        version'   (str (inc version))
        version-id (str ext-id "+" version')
        dir        (io/file "checkout" ext-id)
        files      (upload storage version-id
                     (io/file dir "README.md")
                     (io/file dir "CHANGELOG.md")
                     (io/file dir "extension.js")
                     (io/file dir "extension.css"))
        data'      (assoc data
                     "extension" ext-id
                     "version"   version'
                     "files"     files)]
    (ref-set! (ref db "extension_versions" version-id) data')
    (ref-set! (ref db "extensions" ext-id) data')))

(defn unpublish [db ext-id]
  (ref-remove! (ref db "extensions" ext-id)))

(defn -main [& args]
  (when (or (empty? args) (odd? (count args)))
    (println "Usage:")
    (println)
    (println "  clojure -M -m community-extensions.publish --key <path-to-key> [--from <sha>] [--to <sha>]")
    (System/exit 1))
  
  (let [args-map    (apply array-map args)
        credentials (GoogleCredentials/fromStream (io/input-stream (get args-map "--key")))
        opts        (-> (FirebaseOptions/builder)
                      (.setCredentials credentials)
                      (.setDatabaseUrl (str "https://firescript-577a2.firebaseio.com/"))
                      (.build))
        app         (FirebaseApp/initializeApp opts)
        db          (FirebaseDatabase/getInstance)
        storage     (-> (StorageOptions/newBuilder)
                      (.setCredentials credentials)
                      (.build)
                      (.getService))]
    (doseq [[mode ext-id data] (core/diff args-map)]
      (case mode
        "A" (publish db storage ext-id data)
        "M" (publish db storage ext-id data)
        "D" (unpublish db ext-id)
        nil))  
    (shutdown-agents)))
