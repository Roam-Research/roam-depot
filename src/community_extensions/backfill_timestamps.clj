(ns community-extensions.backfill-timestamps
  "Parses git log and sets created/updated to extensions and extension_versions if missing"
  (:refer-clojure :exclude [ref])
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.java.shell :as shell]
    [clojure.string :as str]
    [community-extensions.core :as core])
  (:import
    [java.time ZonedDateTime]
    [java.time.format DateTimeFormatter]
    [java.io File FileInputStream]
    [java.nio.file Files Path]
    [com.google.firebase.auth FirebaseAuth]
    [com.google.auth.oauth2 GoogleCredentials]
    [com.google.firebase FirebaseOptions FirebaseApp]
    [com.google.firebase.database DatabaseReference DatabaseReference$CompletionListener FirebaseDatabase ValueEventListener]))

(defn ref [^FirebaseDatabase db & path]
  (.getReference db (str/join "/" path)))

(defn ref-get [^DatabaseReference ref]
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

(defn ref-set! [^DatabaseReference ref value]
  (if-some [old-value (ref-get ref)]
    (println "[ not set ]" (str ref) "=" old-value "to" value)
    (do
      (println "[ set ]" (str ref) "to" value)
      (let [p (promise)]
        (.setValue ref value
          (reify DatabaseReference$CompletionListener
            (onComplete [_ error ref]
              (deliver p error))))
        (when-some [error @p]
          (throw (ex-info (str "Failed to set " ref " with: " error) {:error error})))))))

(defn parse-commit [s]
  (let [[sha ts author subject] (str/split s #"\s+" 4)]
    {:sha     sha
     :instant (java.time.ZonedDateTime/parse ts DateTimeFormatter/ISO_OFFSET_DATE_TIME)
     :author  author
     :subject subject}))

(defn timestamps []
  (let [commits     (->> (core/sh "git" "log" "--first-parent" "--format=%H %aI %ae %s")
                      (str/split-lines)
                      (mapv parse-commit)
                      (reverse))
        *timestamps (volatile! {})]
    (doseq [[prev this] (map vector commits (next commits))
            [mode ext-id data] (core/diff {"--from" (:sha prev) "--to" (:sha this)})
            :when (not (#{"tonsky+roam_calculator" "tonsky+roam-calculator"} ext-id))
            :when (not= "D" mode)
            :let [[author name] (str/split ext-id #"\+" 2)
                  json          (core/sh "git" "show" (str (:sha this) ":extensions/" author "/" name ".json"))
                  commit        (-> json (json/parse-string true) :source_commit)
                  updated       (core/timestamp (:instant this))]]
      (vswap! *timestamps update ext-id update commit
        (fn [u]
          (if (pos? (compare updated u))
            updated
            u))))
    @*timestamps))
          
(defn db ^FirebaseDatabase []
  (FirebaseDatabase/getInstance))

(defn init [args-map]
  (let [credentials (GoogleCredentials/fromStream (io/input-stream (get args-map "--key")))
        opts        (-> (FirebaseOptions/builder)
                      (.setCredentials credentials)
                      (.setDatabaseUrl (str "https://firescript-577a2.firebaseio.com/"))
                      (.build))
        app         (FirebaseApp/initializeApp opts)]))

(defn commit->max-version [db ext-id current]
  (->>
    (for [version (range 1 (inc current))
          :let [data (ref-get (ref db (str "/extension_versions/" ext-id "+" version)))]]
      [version (get data "source_commit")])
    (reduce
      (fn [m [v c]]
        (cond
          (nil? (m c))
          (assoc m c v)
        
          (< (m c) v)
          (assoc m c v)
        
          :else
          m))
      {})
    (sort-by second)))
        
(comment
  (commit->max-version (db) "getmatterapp+matter" 10)
  (get (timestamps) "getmatterapp+matter"))

(defn update-db [timestamps db ref-set!]
  (doseq [[ext-id commit->updated] timestamps
          :let       [ext-data (ref-get (ref db (str "/extensions/" ext-id)))]
          :when      ext-data
          :let       [current         (parse-long (get ext-data "version"))
                      commit-versions (commit->max-version db ext-id current)
                      original        (reduce min (map second commit-versions))]
          [commit v] commit-versions
          :let       [updated (get commit->updated commit)]
          :when      updated]
    (ref-set! (ref db (str "/extension_versions/" ext-id "+" v "/updated")) updated)
    (when (= v original)
      (ref-set! (ref db (str "/extensions/" ext-id "/created")) updated))
    (when (= v current)
      (ref-set! (ref db (str "/extensions/" ext-id "/updated")) updated))))

(defn -main [& args]
  (init (apply array-map args))
  (update-db (timestamps) (db)))

(comment
  (defn safe-ref-set! [ref val]
    (when-not (ref-get ref)
      (println "  [ will set ]" (str ref) "to" val)))
  
  (init {"--key" "firescript-577a2-b042f73061e4.json"})
  (def times (timestamps))
  (times "8bitgentleman+self-destructing-blocks")
  (commit->max-version (db) "8bitgentleman+self-destructing-blocks" 3)
  (update-db (select-keys times ["8bitgentleman+self-destructing-blocks"]) (db) safe-ref-set!)
  (update-db times (db) safe-ref-set!)
  ; (update-db times (db) ref-set!)
  (-main "--key" "firescript-577a2-b042f73061e4.json")
  (ref-get (ref (db) "extensions/8bitgentleman+clear-sidebar/source_commit"))
  (ref-get (ref (db) "extensions/8bitgentleman+clear-sidebar")))
