#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
SOURCE="$WORK/source"
TARGET="$WORK/target"
make_release() {
  local version="$1"
  local release="$SOURCE/releases/$version"
  mkdir -p "$release/fonts" "$release/sprites/v4"
  printf 'PMTiles-test-fixture\n' > "$release/china.pmtiles"
  printf 'OTTO-test-font\n' > "$release/fonts/NotoSansCJKsc-Regular.otf"
  for file in style.json manifest.json coverage.geojson NOTICE.txt fonts/Noto-CJK-LICENSE.txt \
    sprites/v4/dark.json sprites/v4/dark.png sprites/v4/dark@2x.json sprites/v4/dark@2x.png; do
    printf 'test-%s\n' "$version" > "$release/$file"
  done
  (cd "$release" && find . -type f ! -name SHA256SUMS -print | LC_ALL=C sort |
    while IFS= read -r file; do shasum -a 256 "$file"; done > SHA256SUMS)
  printf '{"version":"%s"}\n' "$version" > "$SOURCE/current.json"
}
make_release v1
bash "$ROOT/scripts/install-offline-map.sh" "$SOURCE" "$TARGET" >/dev/null
cmp "$SOURCE/current.json" "$TARGET/current.json"
test "$(LC_ALL=C ls -ld "$TARGET/releases/v1" | awk '{print substr($1,8,3)}')" = 'r-x'
test "$(LC_ALL=C ls -l "$TARGET/releases/v1/china.pmtiles" | awk '{print substr($1,8,1)}')" = r
make_release v2
printf 'broken' >> "$SOURCE/releases/v2/fonts/NotoSansCJKsc-Regular.otf"
if bash "$ROOT/scripts/install-offline-map.sh" "$SOURCE" "$TARGET" >/dev/null 2>&1; then
  printf 'Corrupt package unexpectedly installed\n' >&2; exit 1
fi
grep -q '"v1"' "$TARGET/current.json"
test ! -e "$TARGET/releases/v2"
make_release v2
bash "$ROOT/scripts/install-offline-map.sh" "$SOURCE" "$TARGET" >/dev/null
grep -q '"v2"' "$TARGET/current.json"
test -f "$TARGET/releases/v1/china.pmtiles"
rm "$SOURCE/releases/v2/sprites/v4/dark.png"
if bash "$ROOT/scripts/install-offline-map.sh" "$SOURCE" "$TARGET" --verify-only >/dev/null 2>&1; then
  printf 'Incomplete package unexpectedly verified\n' >&2; exit 1
fi
grep -q '"v2"' "$TARGET/current.json"
if bash "$ROOT/scripts/build-nano-offline-bundle.sh" --stream --map-dir "$SOURCE" \
  > "$WORK/rejected.tar.gz" 2> "$WORK/rejected.log"; then
  printf 'Incomplete map unexpectedly accepted by bundle builder\n' >&2; exit 1
fi
test ! -s "$WORK/rejected.tar.gz"

# Offline assembly must fail on a missing image without building or pulling it.
make_release v2
mkdir -p "$WORK/packager/scripts" "$WORK/bin"
cp "$ROOT/scripts/build-nano-offline-bundle.sh" "$ROOT/scripts/install-offline-map.sh" "$WORK/packager/scripts/"
printf '#!/bin/sh\nexit 0\n' > "$WORK/packager/scripts/sync-offline.sh"
touch "$WORK/packager/.env.nano1" "$WORK/packager/.env.example"
cat > "$WORK/bin/docker" <<'SH'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$YOOX_TEST_DOCKER_LOG"
case "$*" in
  'info --format '*) printf 'arm64\n' ;;
  'compose '*config*--images) printf 'yoox/test:missing\n' ;;
  'image inspect '*) exit 1 ;;
esac
SH
chmod +x "$WORK/bin/docker"
if PATH="$WORK/bin:$PATH" YOOX_TEST_DOCKER_LOG="$WORK/docker.log" \
  bash "$WORK/packager/scripts/build-nano-offline-bundle.sh" \
    --stream --skip-build --version test --map-dir "$SOURCE" \
    > "$WORK/offline-rejected.tar.gz" 2> "$WORK/offline-rejected.log"; then
  printf 'Missing image unexpectedly accepted in offline assembly\n' >&2; exit 1
fi
grep -q '离线组装缺少镜像: yoox/test:missing' "$WORK/offline-rejected.log"
! grep -Eq '(^pull | build )' "$WORK/docker.log"
test ! -s "$WORK/offline-rejected.tar.gz"
printf 'Offline maps: corruption, missing assets, atomic update, nginx permissions, rollback, bundle guard and network-free assembly passed.\n'
