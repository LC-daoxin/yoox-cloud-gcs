#!/usr/bin/env bash
set -euo pipefail
BASE="${1:-http://127.0.0.1:8080}"
CURRENT=$(curl -fsS "$BASE/maps/current.json")
VERSION=$(printf '%s' "$CURRENT" | sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
case "$VERSION" in ''|[!a-zA-Z0-9]*|*[!a-zA-Z0-9._-]*) printf 'Invalid map version\n' >&2; exit 1;; esac
ROOT="$BASE/maps/releases/$VERSION"
STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
STATUS=$(curl -fsS --range 0-126 -o "$STAGE/header" -w '%{http_code}' "$ROOT/china.pmtiles")
test "$STATUS" = 206
test "$(wc -c < "$STAGE/header" | tr -d ' ')" = 127
test "$(dd if="$STAGE/header" bs=1 count=7 2>/dev/null)" = PMTiles
curl -fsS "$ROOT/manifest.json" -o "$STAGE/manifest.json"
PROBE_OFFSET=$(sed -n 's/.*"rangeProbeOffset": *\([0-9]*\).*/\1/p' "$STAGE/manifest.json")
PROBE_LENGTH=$(sed -n 's/.*"rangeProbeLength": *\([0-9]*\).*/\1/p' "$STAGE/manifest.json")
PROBE_SHA=$(sed -n 's/.*"rangeProbeSha256": *"\([0-9a-f]*\)".*/\1/p' "$STAGE/manifest.json")
test -n "$PROBE_OFFSET" && test "$PROBE_LENGTH" = 127 && test "${#PROBE_SHA}" = 64
STATUS=$(curl -fsS --range "$PROBE_OFFSET-$((PROBE_OFFSET + PROBE_LENGTH - 1))" \
  -o "$STAGE/probe" -w '%{http_code}' "$ROOT/china.pmtiles")
test "$STATUS" = 206
if command -v sha256sum >/dev/null; then
  ACTUAL_SHA=$(sha256sum "$STAGE/probe" | cut -d ' ' -f 1)
else
  ACTUAL_SHA=$(shasum -a 256 "$STAGE/probe" | cut -d ' ' -f 1)
fi
test "$ACTUAL_SHA" = "$PROBE_SHA" || { printf 'Map byte-range checksum mismatch\n' >&2; exit 1; }
for asset in style.json sprites/v4/dark.json sprites/v4/dark.png \
  sprites/v4/dark@2x.json sprites/v4/dark@2x.png \
  fonts/Noto%20Sans%20Regular/0-255.pbf; do
  curl -fsS "$ROOT/$asset" -o "$STAGE/asset"
  test -s "$STAGE/asset"
done
curl -fsS --range 0-3 "$ROOT/fonts/NotoSansCJKsc-Regular.otf" -o "$STAGE/font"
test "$(cat "$STAGE/font")" = OTTO
test "$(curl -sS -o /dev/null -w '%{http_code}' "$ROOT/not-installed.pbf")" = 404
printf 'YOOX offline map smoke passed: %s (%s)\n' "$BASE" "$VERSION"
