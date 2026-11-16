#!/usr/bin/env bash
set -euo pipefail
base="${BASE_URL:-http://127.0.0.1:8080}"
base="${base%/}"
token="$(bash "$(dirname "$0")/token.sh")"
work=$(mktemp -d)
cleanup() {
  if [[ -n "${version:-}" ]]; then
    curl -fsS --connect-timeout 2 --max-time 5 "$base/api/v1/policies/$id" \
      -X DELETE -H "Authorization: Bearer $token" -H "If-Match: $version" > /dev/null \
      || echo "Could not disable test policy $id; disable it before retrying." >&2
  fi
  rm -rf "$work"
}
trap cleanup EXIT
request() {
  local expected="$1" path="$2"
  shift 2
  local status
  status=$(curl -sS --connect-timeout 5 --max-time 30 -D "$work/headers" -o "$work/body" \
    -w '%{http_code}' "$base$path" -H "Authorization: Bearer $token" "$@")
  if [[ "$status" != "$expected" ]]; then
    echo "FAIL $path: expected HTTP $expected, got $status" >&2
    cat "$work/body" >&2
    exit 1
  fi
  echo "PASS $path: HTTP $status"
}
# Wait for liveness, not readiness: an empty policy catalog is not ready yet.
for attempt in {1..60}; do
  if curl -fsS --connect-timeout 1 --max-time 2 "$base/actuator/health/liveness" > /dev/null 2>&1; then
    break
  fi
  if [[ "$attempt" == 60 ]]; then
    echo "Application did not become live at $base; check docker compose logs app" >&2
    exit 1
  fi
  sleep 1
done
request 200 /api/v1/policies
# A unique rule and policy give every run fresh quota without resetting Redis.
id="smoke-$(python3 -c 'import uuid; print(uuid.uuid4().hex)')"
request 201 /api/v1/policies -H 'Content-Type: application/json' --data "{\"id\":\"$id\",\"ruleName\":\"$id\",\"dimension\":\"USER\",\"tenant\":\"*\",\"tier\":\"*\",\"endpoint\":\"/protected/demo\",\"capacity\":2,\"refillPerSecond\":0.001,\"failureMode\":\"CLOSED\",\"lease\":false,\"enabled\":true,\"version\":0}"
version=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["version"])' < "$work/body")
request 200 /actuator/health/readiness
for expected in 200 200 429; do
  request "$expected" /protected/demo
done
if ! tr -d '\r' < "$work/headers" | grep -Eiq '^Retry-After: [1-9][0-9]*$'; then
  echo 'FAIL: throttled response has no positive Retry-After header' >&2
  exit 1
fi
request 200 "/api/v1/policies/$id" -X DELETE -H "If-Match: $version"
version=""
echo 'Smoke test passed: 200, 200, 429 with Retry-After; test policy disabled.'
