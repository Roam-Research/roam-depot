(ns community-extensions.build
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [community-extensions.core :as core]))

(def args-map
  (apply array-map *command-line-args*))

(def docs-file-pattern
  "Files that do not affect the code users run: markdown and the images
   typically embedded in READMEs."
  #"(?i).*\.(md|markdown|png|jpe?g|gif|svg|webp)$")

(defn changed-files
  "Files changed in the extension repo (already cloned at `dir`) between
   `old-commit` and `new-commit`. Returns nil if the diff cannot be computed,
   e.g. the old commit is not reachable because the repo moved or was
   force-pushed."
  [dir old-commit new-commit]
  (try
    (shell/with-sh-dir dir
      (->> (core/sh "git" "diff" "--name-only" "--no-renames" old-commit new-commit)
        (str/split-lines)
        (remove str/blank?)
        (vec)))
    (catch Exception e
      (println "[ warn ] could not diff" old-commit ".." new-commit ":" (ex-message e))
      nil)))

(defn code-changed?
  "True unless we can positively show that only docs files changed between
   the previously published source and the new one. `files` is the list of
   changed paths between the commits, or nil when the diff could not be
   computed. Defaults to true (counts as a real update) whenever we cannot
   tell."
  [old-data data files]
  (let [old-commit (get old-data "source_commit")
        new-commit (get data "source_commit")]
    (cond
      (nil? old-commit)                                   true
      ;; a different build root selects different files even at the same commit
      (not= (get old-data "source_subdir")
            (get data "source_subdir"))                   true
      (= old-commit new-commit)                           false
      (nil? files)                                        true
      (empty? files)                                      false
      :else (not (every? #(re-matches docs-file-pattern %) files)))))

(defn write-build-info!
  "Writes checkout/<ext-id>/build-info.json, consumed by publish to decide
   whether to advance `code_updated`. Written after build.sh so that an
   extension's own build cannot override it."
  [dir old-data data]
  (let [old-commit (get old-data "source_commit")
        new-commit (get data "source_commit")
        files      (when (and old-commit (not= old-commit new-commit))
                     (changed-files dir old-commit new-commit))
        changed?   (code-changed? old-data data files)
        info       {"code_changed"  changed?
                    "old_commit"    old-commit
                    "new_commit"    new-commit
                    "changed_files" files}]
    (println (str "[ code ] " (if changed? "changed" "unchanged (docs/metadata only)")))
    (spit (io/file dir "build-info.json") (json/generate-string info {:pretty true}))))

(defn build
  ([ext-id data]
   (build ext-id data nil))
  ([ext-id data old-data]
   (let [dir     (io/file "checkout" ext-id)
         exists? (.exists dir)
         {repo "source_repo"
          commit "source_commit"
          subdir "source_subdir"} data]
     (when-not exists?
       (.mkdirs dir)
       (core/sh "git" "clone" repo (str "checkout/" ext-id)))
     (shell/with-sh-dir dir
       (when exists?
         (core/sh "git" "fetch" "--all"))
       (core/sh "git" "-c" "advice.detachedHead=false" "checkout" commit))
     ;; if there is a sub dir, run build inside of sub directory
     (let [sub-dir-file (if (string? subdir)
                          (io/file dir subdir)
                          dir)]
       (shell/with-sh-dir sub-dir-file
         (when (.exists (io/file sub-dir-file "build.sh"))
           (core/sh "chmod" "+x" "build.sh")
           (core/sh "./build.sh")))
       (when (string? subdir)
         ;; copy all required files to the root because build actions require them there
         (shell/with-sh-dir dir
           (when (.exists (io/file sub-dir-file "extension.js"))
             (core/sh "cp" (str subdir "/extension.js") "."))
           (when (.exists (io/file sub-dir-file "extension.css"))
             (core/sh "cp" (str subdir "/extension.css") "."))
           (when (.exists (io/file sub-dir-file "CHANGELOG.md"))
             (core/sh "cp" (str subdir "/CHANGELOG.md") "."))
           (when (.exists (io/file sub-dir-file "README.md"))
             (core/sh "cp" (str subdir "/README.md") ".")))))
     (write-build-info! dir old-data data))))

(defn -main [& {:as args-map}]
  (doseq [[mode ext-id data old-data] (core/diff args-map)]
    (case mode
      "A" (build ext-id data nil)
      "M" (build ext-id data old-data)
      nil))
  (shutdown-agents))
