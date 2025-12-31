(ns community-extensions.publish
  (:refer-clojure :exclude [ref])
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.java.shell :as shell]
    [clojure.string :as str]
    [community-extensions.core :as core])
  (:import
    [java.io ByteArrayInputStream File FileInputStream]
    [java.nio.charset StandardCharsets]
    [java.nio.file Files Path]
    [com.google.firebase.auth FirebaseAuth]
    [com.google.auth.oauth2 GoogleCredentials]
    [com.google.firebase FirebaseOptions FirebaseApp]
    [com.google.firebase.database DatabaseReference DatabaseReference$CompletionListener FirebaseDatabase ValueEventListener]
    [com.google.cloud.storage BlobId BlobInfo Blob$BlobSourceOption Bucket BucketInfo Storage Storage$BlobTargetOption StorageOptions]))

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

(defn upload [storage parent version-id & files]
  (vec
    (for [file files
          :when (.exists file)
          :let [_         (when (Files/isSymbolicLink (.toPath file))
                            (throw (ex-info (str "Refusing to read symlink: " file) {:file file})))
                name      (.getName file)
                [_ ext]   (re-matches #".*\.(\w+)" name)
                o         (str parent "/" version-id "/" name)
                id        (BlobId/of "firescript-577a2.appspot.com" o)
                mime      (core/mime-types ext)
                compress? (>= (.length file) 1024)
                info      (cond-> (BlobInfo/newBuilder id)
                            true      (.setContentType mime)
                            compress? (.setContentEncoding "gzip")
                            true      (.build))
                _         (when compress?
                            (core/sh "gzip" "--best" "--keep" (.getPath file)))
                path      (if compress?
                            (Path/of (str (.getPath file) ".gz") (make-array String 0))
                            (.toPath file))
                bytes     (Files/readAllBytes path)]]
      (do
        (println (str "[ upload ] " name " (" (.length file) " bytes) as " mime " (" (count bytes) " bytes)"))
        (.create storage info bytes (make-array Storage$BlobTargetOption 0))
        name))))

(defn publish
  "Uploads files to:

     extension_files/<ext-id>+<version>

   and metadata to:

     extensions/<ext-id>
     extensions_versions/<ext-id>+<version>"
  [db storage ext-id data]
  (let [version    (->> (ref-find (ref db "extension_versions") "extension" ext-id)
                     (vals)
                     (map #(get % "version"))
                     (map parse-long)
                     (reduce max 0))
        version'   (str (inc version))
        version-id (str ext-id "+" version')
        dir        (io/file "checkout" ext-id)
        files      (upload storage "extension_files" version-id
                     (io/file dir "README.md")
                     (io/file dir "CHANGELOG.md")
                     (io/file dir "extension.js")
                     (io/file dir "extension.css"))
        _          (when (empty? files)
                     (throw (ex-info (str "No files found for extension " ext-id) {})))
        now        (core/timestamp)
        data'      (assoc data
                     "extension" ext-id
                     "version"   version'
                     "files"     files
                     "updated"   now)
        data''     (assoc data' "created"
                     (if (= 0 version)
                       now
                       (ref-get (ref db "extensions" ext-id "created"))))]
    (ref-set! (ref db "extension_versions" version-id) data')
    (ref-set! (ref db "extensions" ext-id) data'')))

(defn publish-pr
  "Same as publish, but uploads files to:

     extension_prs/<ext-id>+<PR>

   and metadata to:

     extension_prs/<ext-id>+<PR>"
  [db storage ext-id pr data]
  (let [version-id (str ext-id "+" pr)
        dir        (io/file "checkout" ext-id)
        files      (upload storage "extension_prs" version-id
                     (io/file dir "README.md")
                     (io/file dir "CHANGELOG.md")
                     (io/file dir "extension.js")
                     (io/file dir "extension.css"))
        _          (when (empty? files)
                     (throw (ex-info (str "No files found for extension " ext-id) {})))
        now        (core/timestamp)
        data'      (assoc data
                     "extension" ext-id
                     "version"   pr
                     "files"     files
                     "updated"   now)]
    (ref-set! (ref db "extension_prs" version-id) data')))

(defn unpublish [db ext-id]
  (ref-remove! (ref db "extensions" ext-id)))

(defn credentials-from-env-or-file
  "Load Firebase credentials from FIREBASE_SERVICE_KEY env var (preferred)
   or fall back to --key file path. Using env var avoids writing secrets to disk."
  [args-map]
  (if-some [json (System/getenv "FIREBASE_SERVICE_KEY")]
    (GoogleCredentials/fromStream
      (ByteArrayInputStream. (.getBytes json StandardCharsets/UTF_8)))
    (when-some [key-path (get args-map "--key")]
      (GoogleCredentials/fromStream (io/input-stream key-path)))))

(defn -main [& args]
  (prn "PUBLISHING>>>>>>")
  (let [args-map    (apply array-map args)
        credentials (credentials-from-env-or-file args-map)]
    (when (nil? credentials)
      (println "Usage:")
      (println)
      (println "  Either set FIREBASE_SERVICE_KEY env var, or pass --key <path-to-key>")
      (println)
      (println "  clojure -M -m community-extensions.publish [--key <path-to-key>] ([--pr <pr>] | [--from <sha>] [--to <sha>])")
      (System/exit 1))
    (let [opts    (-> (FirebaseOptions/builder)
                    (.setCredentials credentials)
                    (.setDatabaseUrl "https://firescript-577a2.firebaseio.com/")
                    (.build))
          _app    (FirebaseApp/initializeApp opts)
          db      (FirebaseDatabase/getInstance)
          storage (-> (StorageOptions/newBuilder)
                    (.setCredentials credentials)
                    (.build)
                    (.getService))]
      (if-some [pr (get args-map "--pr")]
        (doseq [[mode ext-id data] (core/diff args-map)]
          (case mode
            "A" (publish-pr db storage ext-id pr data)
            "M" (publish-pr db storage ext-id pr data)
            nil))
        (doseq [[mode ext-id data] (core/diff args-map)]
          (case mode
            "A" (publish db storage ext-id data)
            "M" (publish db storage ext-id data)
            "D" (unpublish db ext-id)
            nil)))
      (shutdown-agents))))
