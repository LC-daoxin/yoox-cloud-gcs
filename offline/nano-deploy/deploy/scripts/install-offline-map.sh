#!/usr/bin/env bash
# Local files only. Safe to run on an air-gapped target; also used by packaging.
set -euo pipefail
SOURCE="${1:?Usage: install-offline-map.sh SOURCE_MAPS TARGET_MAPS [--verify-only]}"
TARGET="${2:?Missing target maps directory}"
MODE="${3:-}"
VERSION=$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$SOURCE/current.json")
case "$VERSION" in ''|[!a-zA-Z0-9]*|*[!a-zA-Z0-9._-]*) printf 'Invalid offline map version\n' >&2; exit 1;; esac
RELEASE="$SOURCE/releases/$VERSION"
for required in china.pmtiles style.json manifest.json coverage.geojson NOTICE.txt SHA256SUMS \
  fonts/NotoSansCJKsc-Regular.otf fonts/Noto-CJK-LICENSE.txt \
  sprites/v4/dark.json sprites/v4/dark.png sprites/v4/dark@2x.json sprites/v4/dark@2x.png; do
  test -s "$RELEASE/$required" || { printf 'Missing map resource: %s\n' "$required" >&2; exit 1; }
done
test "$(dd if="$RELEASE/china.pmtiles" bs=1 count=7 2>/dev/null)" = PMTiles || {
  printf 'Invalid PMTiles archive\n' >&2; exit 1;
}
test "$(dd if="$RELEASE/fonts/NotoSansCJKsc-Regular.otf" bs=1 count=4 2>/dev/null)" = OTTO || {
  printf 'Invalid Chinese font\n' >&2; exit 1;
}
# Checksum paths must stay within the immutable release directory.
if ! LC_ALL=C awk '
  !/^[0-9a-f]+  / || length($1) != 64 { bad=1 }
  { p=substr($0,67); if (p ~ /^\// || p ~ /(^|\/)\.\.(\/|$)/) bad=1 }
  END { exit bad }' "$RELEASE/SHA256SUMS"; then
  printf 'Invalid map checksum manifest\n' >&2; exit 1
fi
verify() {
  if command -v sha256sum >/dev/null; then
    (cd "$1" && sha256sum -c --quiet SHA256SUMS)
  else
    (cd "$1" && shasum -a 256 -c --quiet SHA256SUMS)
  fi
}
verify "$RELEASE"
test "$MODE" != "--verify-only" || { printf 'Offline map verified: %s\n' "$VERSION"; exit 0; }
mkdir -p "$TARGET/releases"
chmod a+rx "$TARGET" "$TARGET/releases"
DEST="$TARGET/releases/$VERSION"
if [ -d "$DEST" ]; then
  cmp "$RELEASE/SHA256SUMS" "$DEST/SHA256SUMS" >/dev/null || {
    printf 'Existing map version has different contents; choose a new version.\n' >&2; exit 1;
  }
  verify "$DEST"
else
  BYTES=$(du -sk "$RELEASE" | awk '{print $1}')
  AVAILABLE=$(df -Pk "$TARGET" | awk 'END {print $4}')
  test "$AVAILABLE" -gt "$((BYTES + 262144))" || { printf 'Insufficient map disk space\n' >&2; exit 1; }
  STAGE=$(mktemp -d "$TARGET/releases/.install-$VERSION.XXXXXX")
  trap 'test -z "${STAGE:-}" || rm -rf "$STAGE"' EXIT
  cp -R "$RELEASE/." "$STAGE/"
  verify "$STAGE"
  mv "$STAGE" "$DEST"
  STAGE=''
fi
# mktemp creates mode 0700, which nginx's unprivileged worker cannot traverse.
find "$DEST" -type d -exec chmod 755 {} +
find "$DEST" -type f -exec chmod 644 {} +
# Retain old immutable releases. Only the small pointer is replaced atomically.
cp "$SOURCE/current.json" "$TARGET/current.json.tmp"
chmod 644 "$TARGET/current.json.tmp"
mv "$TARGET/current.json.tmp" "$TARGET/current.json"
printf 'Offline map installed: %s\n' "$VERSION"
