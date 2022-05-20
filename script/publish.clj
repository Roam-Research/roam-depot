(ns publish
  (:require
    [babashka.fs :as fs]
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]))

(def root
  (-> *file* fs/canonicalize fs/parent fs/parent))

(doseq [[mode path] (->> *in*
                      (io/reader)
                      (line-seq)
                      (map #(str/split % #"\t")))
        :when (str/starts-with? path "extensions/")
        :when (#{"A" "M"} mode)
        :let [mode ({"A" :added "M" :modified} mode)
              file (fs/absolutize (fs/file root path))]]
  (println mode
    (json/parse-string (slurp (fs/file file)) true)))


  
