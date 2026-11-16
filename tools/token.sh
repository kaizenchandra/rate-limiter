#!/usr/bin/env bash
# Local Keycloak service account. Override these values for another client.
set -euo pipefail
url="${KEYCLOAK_URL:-http://localhost:9000}"
url="${url%/}/realms/rate-limiter/protocol/openid-connect/token"
# Do not pipe a failed HTTP response into the JSON parser.
if ! response=$(curl -fsS --connect-timeout 5 --max-time 30 "$url" \
  --data-urlencode 'grant_type=client_credentials' \
  --data-urlencode "client_id=${KEYCLOAK_CLIENT_ID:-rate-limiter-demo}" \
  --data-urlencode "client_secret=${KEYCLOAK_CLIENT_SECRET:-local-demo-secret}"); then
  echo "Token request failed at $url. Check KEYCLOAK_URL and client credentials." >&2
  echo "For the local stack run: docker compose up --build -d --remove-orphans --wait" >&2
  echo "An obsolete issuer container on port 9000 returns 404 for Keycloak routes." >&2
  exit 1
fi
python3 -c '
import json, sys
try:
    token = json.load(sys.stdin)["access_token"]
    if not isinstance(token, str) or not token:
        raise ValueError("empty or invalid access_token")
except (ValueError, KeyError, TypeError):
    sys.exit("Token response did not contain a valid access_token")
print(token)
' <<< "$response"
