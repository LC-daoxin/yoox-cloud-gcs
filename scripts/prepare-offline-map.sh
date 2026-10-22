#!/usr/bin/env bash
# Connected build machine only. Target installations never download resources.
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="${YOOX_MAP_VERSION:-china-20261020-v1}"
SOURCE="${YOOX_MAP_SOURCE:-https://build.protomaps.com/20261020.pmtiles}"
ASSETS_COMMIT=028c18f713baecad011301ff7a69acc39bcc2ae7
CJK_COMMIT=f8d157532fbfaeda587e826d4cd5b21a49186f7c
PMTILES_BIN="${PMTILES_BIN:-pmtiles}"
MAP_ROOT="${YOOX_MAP_DATA_DIR:-$ROOT_DIR/maps}"
REGION="$ROOT_DIR/deploy/maps/china-coverage.geojson"
case "$VERSION" in ''|[!a-zA-Z0-9]*|*[!a-zA-Z0-9._-]*) printf 'Invalid map version\n' >&2; exit 1;; esac
command -v "$PMTILES_BIN" >/dev/null || { printf 'Install go-pmtiles v1.31.2 on the build machine first.\n' >&2; exit 1; }
command -v node >/dev/null
command -v unzip >/dev/null
command -v python3 >/dev/null
test -d "$ROOT_DIR/web-console/node_modules/@protomaps/basemaps"
mkdir -p "$MAP_ROOT/releases"
RELEASE="$MAP_ROOT/releases/$VERSION"
test ! -e "$RELEASE" || { printf 'Release already exists: %s\n' "$RELEASE" >&2; exit 1; }
STAGE="$MAP_ROOT/.preparing-$VERSION"
mkdir -p "$STAGE"
# Reuse a completed extraction after an asset-download failure, but never a
# partially written archive. Failed extracts remain .partial for diagnosis.
if [ ! -f "$STAGE/china.pmtiles" ]; then
  CACHE_KEY=$(printf '%s' "$SOURCE" | shasum -a 256 | cut -d ' ' -f 1)
  CACHE="$MAP_ROOT/.range-cache-$CACHE_KEY"
  PORT_FILE=$(mktemp)
  python3 "$ROOT_DIR/scripts/map-range-cache.py" --source "$SOURCE" --cache "$CACHE" \
    --port-file "$PORT_FILE" &
  CACHE_PID=$!
  trap 'kill "$CACHE_PID" 2>/dev/null || true; rm -f "$PORT_FILE"' EXIT
  for attempt in $(seq 1 100); do
    test -s "$PORT_FILE" && break
    kill -0 "$CACHE_PID"
    sleep 0.1
  done
  PORT=$(cat "$PORT_FILE")
  test -n "$PORT"
  EXTRACTED=false
  for attempt in 1 2 3; do
    if "$PMTILES_BIN" extract "http://127.0.0.1:$PORT/china.pmtiles" "$STAGE/china.pmtiles.partial" \
      --region="$REGION" --maxzoom=15 --download-threads="${YOOX_MAP_DOWNLOAD_THREADS:-16}" --overfetch=0.01; then
      EXTRACTED=true
      break
    fi
    printf 'Extraction attempt %s failed; completed ranges are cached for retry.\n' "$attempt" >&2
  done
  "$EXTRACTED"
  kill "$CACHE_PID"
  wait "$CACHE_PID" || true
  rm -f "$PORT_FILE"
  trap - EXIT
  "$PMTILES_BIN" verify "$STAGE/china.pmtiles.partial"
  mv "$STAGE/china.pmtiles.partial" "$STAGE/china.pmtiles"
  rm -rf "$CACHE"
fi
ASSETS_ARCHIVE="${YOOX_MAP_ASSETS_ARCHIVE:-$MAP_ROOT/.assets-$ASSETS_COMMIT.zip}"
if [ ! -f "$ASSETS_ARCHIVE" ]; then
  curl -fL --retry 3 --connect-timeout 30 \
    "https://codeload.github.com/protomaps/basemaps-assets/zip/$ASSETS_COMMIT" \
    -o "$ASSETS_ARCHIVE.partial"
  mv "$ASSETS_ARCHIVE.partial" "$ASSETS_ARCHIVE"
fi
unzip -q -o "$ASSETS_ARCHIVE" -d "$STAGE/assets"
rm -rf "$STAGE/fonts" "$STAGE/sprites"
cp -R "$STAGE/assets/basemaps-assets-$ASSETS_COMMIT/fonts" "$STAGE/"
cp -R "$STAGE/assets/basemaps-assets-$ASSETS_COMMIT/sprites" "$STAGE/"
cp "$STAGE/assets/basemaps-assets-$ASSETS_COMMIT/scripts/LICENSE.md" "$STAGE/sprites/LICENSE.md"
# Upstream font stacks share PBFs through relative symlinks. Materialize them
# so the deliverable has ordinary files and also works on Windows filesystems.
python3 - "$STAGE" <<'PY'
from pathlib import Path
import sys
for name in ('fonts', 'sprites'):
    root = (Path(sys.argv[1]) / name).resolve()
    for filename in root.rglob('*'):
        if not filename.is_symlink():
            continue
        target = filename.resolve(strict=True)
        target.relative_to(root)
        if not target.is_file():
            raise RuntimeError(f'Unsupported resource link: {filename}')
        content = target.read_bytes()
        filename.unlink()
        filename.write_bytes(content)
PY
rm -r "$STAGE/assets"
if [ -n "${YOOX_MAP_CJK_FONT:-}" ]; then
  cp "$YOOX_MAP_CJK_FONT" "$STAGE/fonts/NotoSansCJKsc-Regular.otf"
else
  curl -fL --retry 3 --connect-timeout 30 \
    "https://raw.githubusercontent.com/notofonts/noto-cjk/$CJK_COMMIT/Sans/OTF/SimplifiedChinese/NotoSansCJKsc-Regular.otf" \
    -o "$STAGE/fonts/NotoSansCJKsc-Regular.otf"
fi
curl -fsSL --retry 3 --connect-timeout 30 \
  "https://raw.githubusercontent.com/notofonts/noto-cjk/$CJK_COMMIT/Sans/LICENSE" \
  -o "$STAGE/fonts/Noto-CJK-LICENSE.txt"
cp "$REGION" "$STAGE/coverage.geojson"
node "$ROOT_DIR/scripts/write-map-package.mjs" "$STAGE" "$VERSION" "$SOURCE" "$ASSETS_COMMIT"
mv "$STAGE" "$RELEASE"
chmod a+rx "$MAP_ROOT" "$MAP_ROOT/releases"
find "$RELEASE" -type d -exec chmod 755 {} +
find "$RELEASE" -type f -exec chmod 644 {} +
printf '{"version":"%s"}\n' "$VERSION" > "$MAP_ROOT/current.json.tmp"
chmod 644 "$MAP_ROOT/current.json.tmp"
mv "$MAP_ROOT/current.json.tmp" "$MAP_ROOT/current.json"
printf 'Offline map ready: %s\n' "$RELEASE"
