#!/usr/bin/env bash
# Compile main + test sources and run the unit tests. Requires JDK 21+.
set -euo pipefail
cd "$(dirname "$0")"
rm -rf out && mkdir -p out
javac -d out $(find src -name '*.java')
java -cp out qc.QcPipelineTest
