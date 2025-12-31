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

;; pr should be fetched already
#_
(defn try-fetch-pr [pr branch]
  ;; Try to fetch, but don't fail if branch already exists (e.g. from publish step)
  (let [{:keys [exit]} (shell/sh "git" "fetch" "origin" (str "pull/" pr "/head:" branch))]
    (when (zero? exit)
      (println "[ fetch ] Fetched PR" pr "to" branch))))

(defn -main [& {pr "--pr"
                token "--token"
                status "--status"
                :as args-map}]
  (println "=== comment.clj v2 - no fetch ===")
  (let [branch  (str "pr-" pr)
        #_       (try-fetch-pr pr branch)
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
        status-msg (case status
                     "success" "✅ **Publish succeeded** - Extension is available for testing.\n\n"
                     "failure" "❌ **Publish failed** - Check the workflow logs for details.\n\n"
                     "cancelled" "⚠️ **Publish was cancelled**\n\n"
                     "skipped" "⏭️ **Publish was skipped**\n\n"
                     "")
        message (str/join "\n"
                          (concat
                           [status-msg
                            "Here's your link to the diff:\n"]
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
