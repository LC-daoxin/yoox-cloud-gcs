#!/usr/bin/env bash
# 构建包含全部 arm64 Docker 镜像和部署配置的 Jetson Nano 离线包。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="$ROOT_DIR/.env.nano1"
ENV_TEMPLATE="$ROOT_DIR/.env.example"
OUT_DIR="$ROOT_DIR/offline/dist"
VERSION=""
MAP_DIR="$ROOT_DIR/maps"
STREAM=false
SKIP_BUILD=false

usage() {
    cat <<'EOF'
用法: scripts/build-nano-offline-bundle.sh [选项]

  --version VERSION        镜像/部署版本；默认 nano-YYYYMMDD-<git短号>
  --env-file FILE          Compose 构建配置；默认 .env.nano1
  --env-template FILE      写入包内的部署环境配置；默认 .env.example
  --output-dir DIR         输出目录；默认 offline/dist
  --map-dir DIR            已生成的完整全国地图目录；默认 maps（必需）
  --stream                 将完整归档输出到 stdout；进度输出到 stderr
  --skip-build             使用已导入的镜像；不构建、不拉取，缺少任一镜像即失败

若希望解压后只输入 Nano IP 即可部署，可显式传入已填写好密钥的文件：
  --env-template .env.nano1
EOF
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --version) VERSION="${2:-}"; shift 2 ;;
        --stream) STREAM=true; shift ;;
        --skip-build) SKIP_BUILD=true; shift ;;
        --map-dir) MAP_DIR="$(cd "${2:-}" && pwd)"; shift 2 ;;
        --env-file) ENV_FILE="$(cd "$(dirname "${2:-}")" && pwd)/$(basename "${2:-}")"; shift 2 ;;
        --env-template) ENV_TEMPLATE="$(cd "$(dirname "${2:-}")" && pwd)/$(basename "${2:-}")"; shift 2 ;;
        --output-dir) OUT_DIR="$(mkdir -p "${2:-}" && cd "${2:-}" && pwd)"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) printf '未知参数: %s\n' "$1" >&2; usage >&2; exit 1 ;;
    esac
done

if $STREAM; then
    exec 3>&1
    exec 1>&2
fi

# Never produce a nominally offline bundle without the complete national map.
bash "$ROOT_DIR/scripts/install-offline-map.sh" "$MAP_DIR" "$MAP_DIR" --verify-only
MAP_VERSION=$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$MAP_DIR/current.json")

command -v docker >/dev/null 2>&1 || { printf '未安装 docker。\n' >&2; exit 1; }
docker compose version >/dev/null 2>&1 || { printf '未安装 Docker Compose v2。\n' >&2; exit 1; }
docker info >/dev/null 2>&1 || { printf 'Docker 守护进程未运行。\n' >&2; exit 1; }
[ -f "$ENV_FILE" ] || { printf 'Compose 环境文件不存在: %s\n' "$ENV_FILE" >&2; exit 1; }
[ -f "$ENV_TEMPLATE" ] || { printf '部署环境模板不存在: %s\n' "$ENV_TEMPLATE" >&2; exit 1; }

SERVER_ARCH=$(docker info --format '{{.Architecture}}')
case "$SERVER_ARCH" in
    arm64|aarch64) ;;
    *) printf 'Docker 服务端架构必须是 arm64/aarch64，当前为 %s。\n' "$SERVER_ARCH" >&2; exit 1 ;;
esac

if [ -z "$VERSION" ]; then
    VERSION="nano-$(date +%Y%m%d)-$(git -C "$ROOT_DIR" rev-parse --short HEAD)"
fi
case "$VERSION" in
    *[!A-Za-z0-9_.-]*|'') printf '版本号包含 Docker tag 不支持的字符: %s\n' "$VERSION" >&2; exit 1 ;;
esac

export YOOX_VERSION="$VERSION"
export COMPOSE_BAKE=false

printf '\n[1/7] 同步离线部署配置\n'
bash "$ROOT_DIR/scripts/sync-offline.sh"

printf '\n[2/7] 校验 Compose 配置\n'
docker compose --env-file "$ENV_FILE" -f "$ROOT_DIR/compose.yml" --profile '*' config --quiet

printf '\n[3/7] 构建当前业务镜像 (%s)\n' "$VERSION"
if $SKIP_BUILD; then
    printf '  使用已导入镜像；跳过构建及联网拉取。\n'
else
    docker compose --env-file "$ENV_FILE" -f "$ROOT_DIR/compose.yml" build api web api-portal mediamtx
fi

IMAGE_MANIFEST=$(mktemp)
STAGE_DIR=$(mktemp -d)
cleanup() {
    rm -f "$IMAGE_MANIFEST"
    rm -rf "$STAGE_DIR"
}
trap cleanup EXIT INT TERM

docker compose --env-file "$ENV_FILE" -f "$ROOT_DIR/compose.yml" --profile '*' config --images \
    | sed '/^[[:space:]]*$/d' \
    | sort -u > "$IMAGE_MANIFEST"

printf '\n[4/7] 拉取全部第三方 arm64 镜像\n'
while IFS= read -r image; do
    if $SKIP_BUILD; then
        docker image inspect "$image" >/dev/null 2>&1 || {
            printf '离线组装缺少镜像: %s\n' "$image" >&2; exit 1;
        }
        continue
    fi
    case "$image" in
        yoox/*) ;;
        *)
            if docker image inspect "$image" >/dev/null 2>&1; then
                printf '  [=] 使用本地镜像 %s\n' "$image"
            else
                docker pull --platform linux/arm64 "$image"
            fi
            ;;
    esac
done < "$IMAGE_MANIFEST"

printf '\n[5/7] 校验镜像架构\n'
while IFS= read -r image; do
    arch=$(docker image inspect --format '{{.Architecture}}' "$image")
    case "$arch" in
        arm64|aarch64) printf '  [✓] %-65s %s\n' "$image" "$arch" ;;
        *) printf '镜像架构错误: %s -> %s\n' "$image" "$arch" >&2; exit 1 ;;
    esac
done < "$IMAGE_MANIFEST"

case "$VERSION" in
    nano-*) PACKAGE_NAME="yoox-cloud-gcs-$VERSION" ;;
    *) PACKAGE_NAME="yoox-cloud-gcs-nano-$VERSION" ;;
esac
PACKAGE_DIR="$STAGE_DIR/$PACKAGE_NAME"
mkdir -p "$PACKAGE_DIR/images" "$OUT_DIR"
mkdir -p "$PACKAGE_DIR/maps/releases"
cp "$MAP_DIR/current.json" "$PACKAGE_DIR/maps/current.json"
# Avoid a second multi-GB staging copy; tar follows only this controlled link.
ln -s "$MAP_DIR/releases/$MAP_VERSION" "$PACKAGE_DIR/maps/releases/$MAP_VERSION"
cp -R "$ROOT_DIR/offline/nano-deploy/deploy" "$PACKAGE_DIR/deploy"
cp "$ROOT_DIR/offline/nano-deploy/install.sh" "$PACKAGE_DIR/install.sh"
cp "$ROOT_DIR/offline/nano-deploy/README.md" "$PACKAGE_DIR/README.md"
cp "$ROOT_DIR/docs/13-Nano快速部署.md" "$PACKAGE_DIR/DEPLOYMENT.md"
cp "$ROOT_DIR/docs/18-Windows10-11本地构建与部署.md" "$PACKAGE_DIR/WINDOWS-DEPLOYMENT.md"
cp "$ROOT_DIR/docs/20-全国离线地图与高德切换.md" "$PACKAGE_DIR/OFFLINE-MAPS.md"
cp "$ENV_TEMPLATE" "$PACKAGE_DIR/.env.template"
cp "$IMAGE_MANIFEST" "$PACKAGE_DIR/IMAGE-MANIFEST.txt"
printf '%s\n' "$VERSION" > "$PACKAGE_DIR/VERSION"
chmod 700 "$PACKAGE_DIR/install.sh"
chmod 600 "$PACKAGE_DIR/.env.template"

printf '\n[6/7] 导出 %s 个镜像\n' "$(wc -l < "$IMAGE_MANIFEST" | tr -d ' ')"
# shellcheck disable=SC2046
docker save $(cat "$IMAGE_MANIFEST") | gzip -1 > "$PACKAGE_DIR/images/yoox-cloud-gcs-$VERSION.tar.gz"

OUTPUT_FILE="$OUT_DIR/$PACKAGE_NAME.tar.gz"
printf '\n[7/7] 生成单文件部署包\n'
TAR_METADATA_FLAGS=(--no-xattrs)
if [ "$(uname -s)" = Darwin ]; then
    TAR_METADATA_FLAGS+=(--no-fflags)
fi
if $STREAM; then
    COPYFILE_DISABLE=1 tar "${TAR_METADATA_FLAGS[@]}" -h -C "$STAGE_DIR" -czf - "$PACKAGE_NAME" >&3
    printf '完整离线包已输出到 stdout：%s.tar.gz\n' "$PACKAGE_NAME"
    exit 0
fi
COPYFILE_DISABLE=1 tar "${TAR_METADATA_FLAGS[@]}" -h -C "$STAGE_DIR" -czf "$OUTPUT_FILE.partial" "$PACKAGE_NAME"
mv "$OUTPUT_FILE.partial" "$OUTPUT_FILE"
chmod 600 "$OUTPUT_FILE"
(cd "$OUT_DIR" && shasum -a 256 "$(basename "$OUTPUT_FILE")" > "$(basename "$OUTPUT_FILE").sha256")

printf '\n完成:\n  %s\n' "$OUTPUT_FILE"
printf 'Nano 使用:\n  tar xzf %s\n  cd %s\n  bash install.sh <Nano局域网IP>\n' \
    "$(basename "$OUTPUT_FILE")" "$PACKAGE_NAME"
