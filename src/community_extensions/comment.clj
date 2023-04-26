(ns community-extensions.comment
  (:require
    [cheshire.core :as json]
    [clj-http.client :as http]
    [clojure.java.io :as io]
    [clojure.java.shell :as shell]
    [clojure.string :as str]
    [community-extensions.core :as core]))

(def args-map
  (apply array-map *command-line-args*))

(defn -main [& {pr "--pr"
                token "--token"
                :as args-map}]
  (let [branch  (str "pr-" pr)
        _       (core/sh "git" "fetch" "origin" (str "pull/" pr "/head:" branch))
        changes (vec
                 (for [[mode path] (->> (core/sh "git" "diff" "--name-status" "--merge-base" "remotes/origin/main" branch)
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
                        [path (core/slurp-json path)]))
        message (str/join "\n"
                          (concat
                           ["Here’s your link to the diff:\n"]
                           (for [[mode path] changes
                                 :let [[_ user repo] (re-matches #"extensions/([^/]+)/([^/]+)\.json" path)
                                       pr-shorthand  (str user "+" repo "+" pr)
                                       pr-shorthand-msg (str " (PR-shorthand: `" pr-shorthand "`) ")
                                       commit-before (:source_commit (before path))
                                       commit-after  (:source_commit (after path))
                                       url (or (:source_url (before path))
                                               (:source_url (after path)))]
                                 :when (not= commit-before commit-after)]
                             (cond
                               (nil? commit-before)
                               (str "Added [" user "/" repo "](" url ") [" (subs commit-after 0 7) "](" url "/tree/" commit-after ")" pr-shorthand-msg)
                               
                               (nil? commit-after)
                               (str "Added [" user "/" repo "](" url ") [" (subs commit-before 0 7) "](" url "/tree/" commit-before ")" pr-shorthand-msg)
                               
                               :else
                               (str "Changed [" user "/" repo "](" url ") [" (subs commit-before 0 7) " → " (subs commit-after 0 7) "](" url "/compare/" commit-before ".." commit-after ")" pr-shorthand-msg)))))]
    (println message)
    (when token
      (http/post (str "https://api.github.com/repos/Roam-Research/roam-depot/issues/" pr "/comments")
                 {:headers {"Content-Type" "application/json"
                            "Accept" "application/vnd.github.v3+json"
                            "Authorization" (str "token " token)}
                  :body    (json/generate-string {:body message})}))
    (shutdown-agents)))
