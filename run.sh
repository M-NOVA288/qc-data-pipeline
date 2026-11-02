#!/usr/bin/env bash
# Compile and run the pipeline on the sample data. Requires JDK 21+.
set -euo pipefail
cd "$(dirname "$0")"
rm -rf out && mkdir -p out
javac -d out $(find src/main -name '*.java')
java -cp out qc.QcPipeline "${1:-data/technician_log.csv}" "${2:-data/spec_limits.csv}" "${3:-output}"
