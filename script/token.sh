#!/bin/bash
set -o errexit -o nounset -o pipefail
cd "`dirname $0`/.."

GOOGLE_APPLICATION_CREDENTIALS=`pwd`/firebase-key.json node script/token.js