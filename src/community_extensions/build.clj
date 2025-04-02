(ns community-extensions.build
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [community-extensions.core :as core]))

(def args-map
  (apply array-map *command-line-args*))

(defn build [ext-id data]
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
            (core/sh "cp" (str subdir "/README.md") ".")))))))

(defn -main [& {:as args-map}]
  (doseq [[mode ext-id data] (core/diff args-map)]
    (case mode
      "A" (build ext-id data)
      "M" (build ext-id data)
      nil))
  (shutdown-agents))
