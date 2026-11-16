#!/usr/bin/env bash
set -euo pipefail
: "${TOKENS_FILE:?Set TOKENS_FILE to a JSON array of trusted issuer tokens}"
mkdir -p benchmarks/results
for rate in 10000 100000 500000 1000000 5000000 10000000; do
 for distribution in uniform hot zipf; do
  k6 run -e RATE="$rate" -e DISTRIBUTION="$distribution" -e TOKENS_FILE="$TOKENS_FILE" -e RESULT="benchmarks/results/${rate}-${distribution}.json" benchmarks/k6/evaluate.js
 done
done
