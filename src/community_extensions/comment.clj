(ns community-extensions.comment
  (:require
    [clojure.java.io :as io]
    [clojure.java.shell :as shell]
    [clojure.string :as str]
    [community-extensions.core :as core]))

(def args-map
  (apply array-map *command-line-args*))

; (defn build [ext-id data]
;   (let [dir     (io/file "checkout" ext-id)
;         exists? (.exists dir)
;         {repo "source_repo"
;          commit "source_commit"} data]


(defn -main [& {pr "--pr" :as args-map}]
  (let [branch  (str "pr-" pr)
        _       (core/sh "git" "fetch" "origin" (str "pull/" pr "/head:" branch))
        changes (vec
                  (for [[mode path] (->> (core/sh "git" "diff" "--name-status" "remotes/origin/main" branch)
                                      (str/split-lines)
                                      (map #(str/split % #"\t")))
                        :when (str/starts-with? path "extensions/")]
                    [mode path]))
        _       (core/sh "git" "-c" "advice.detachedHead=false" "checkout" "remotes/origin/main")
        before  (into {}
                  (for [[mode path] changes
                        :when (.exists (io/file path))]
                    [path (core/slurp-json path)]))
        _       (core/sh "git" "checkout" branch)
        after   (into {}
                  (for [[mode path] changes
                        :when (.exists (io/file path))]
                    [path (core/slurp-json path)]))]
    (println
      (str/join "\n"
        (concat
          ["Here’s your link to the diff:"]
          (for [[mode path] changes
                :let [[_ user repo] (re-matches #"extensions/([^/]+)/([^/]+)\.json" path)
                      commit-before (:source_commit (before path))
                      commit-after  (:source_commit (after path))
                      url (or (:source_url (before path))
                            (:source_url (after path)))]
                :when (not= commit-before commit-after)]
            (cond
              (nil? commit-before)
              (str "Added [" user "/" repo "](" url ") [" (subs commit-after 0 7) "](" url "/tree/" commit-after ")")
            
              (nil? commit-after)
              (str "Added [" user "/" repo "](" url ") [" (subs commit-before 0 7) "](" url "/tree/" commit-before ")")
            
              :else
              (str "Changed [" user "/" repo "](" url ") [" (subs commit-before 0 7) " → " (subs commit-after 0 7) "](" url "/compare/" commit-before ".." commit-after ")"))))))
    (shutdown-agents)))