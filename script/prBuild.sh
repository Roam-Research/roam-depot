#!/bin/bash
set -o errexit -o nounset -o pipefail
cd "`dirname $0`/.."

echo $@

clojure -M -m community-extensions.prBuild $@
