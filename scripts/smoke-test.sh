#!/bin/sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ENV_FILE=${1:-"$ROOT_DIR/.env"}
test -f "$ENV_FILE" || {
  printf 'Missing %s\n' "$ENV_FILE" >&2
  exit 1
}

HTTP_PORT=$(sed -n 's/^YOOX_HTTP_PORT=//p' "$ENV_FILE" | tail -n 1)
HTTP_PORT=${HTTP_PORT:-8080}
BASE_URL="http://127.0.0.1:$HTTP_PORT"
PILOT_PORT=$(sed -n 's/^YOOX_PILOT_PORT=//p' "$ENV_FILE" | tail -n 1)
PILOT_PORT=${PILOT_PORT:-9000}
PILOT_BASE_URL="http://127.0.0.1:$PILOT_PORT"

curl -fsS "$BASE_URL/healthz" >/dev/null
curl -fsS "$BASE_URL/actuator/health" | grep -q '"status":"UP"'
curl -fsS "$BASE_URL/v3/api-docs" | grep -q '"openapi"'
test "$(curl -sS -o /dev/null -w '%{http_code}' "$PILOT_BASE_URL/manage/api/v1/login")" = "200"
test "$(curl -sS -o /dev/null -w '%{http_code}' "$PILOT_BASE_URL/api/v1/ws")" = "400"
printf 'YOOX Cloud GCS HTTP smoke test passed: %s\n' "$BASE_URL"
printf 'YOOX Pilot gateway smoke test passed: %s\n' "$PILOT_BASE_URL"
