#!/usr/bin/env bash
# YOOX Cloud GCS x86_64 离线一键安装脚本
#
# 用法：
#   1. 把整个 解压后的离线包 目录复制到 U 盘
#   2. 目标 Linux/WSL2 上挂载 U 盘，cd 到本目录
#   3. bash install.sh <本机局域网IP> [--update] [--with-turn] [--with-monitoring]
#      例如：bash install.sh 172.20.10.4
#
# 更新模式（保留 .env，只更新配置文件和镜像）：
#   bash install.sh 172.20.10.4 --update
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TARGET_DIR="$HOME/yoox-cloud-gcs"
UPDATE_MODE=false
COMPOSE_PROFILES=""

if [ -z "${1:-}" ] || echo "${1:-}" | grep -q '^-'; then
    printf '用法: bash install.sh <本机局域网IP> [--update] [--with-turn] [--with-monitoring]\n'
    printf '示例: bash install.sh 172.20.10.4\n'
    exit 1
fi
NODE_IP="$1"
shift
for arg in "$@"; do
    case "$arg" in
        --update) UPDATE_MODE=true ;;
        --with-turn) COMPOSE_PROFILES="${COMPOSE_PROFILES:+$COMPOSE_PROFILES,}turn" ;;
        --with-monitoring) COMPOSE_PROFILES="${COMPOSE_PROFILES:+$COMPOSE_PROFILES,}monitoring" ;;
        *) printf '[✗] 未知参数: %s\n' "$arg"; exit 1 ;;
    esac
done

# ── 1. 预检 ───────────────────────────────────────────────────────
printf '\n=== [1/6] 预检 ===\n'
[ "$(uname -s)" = "Linux" ] && [ "$(uname -m)" = "x86_64" ] || { printf '[✗] 请在 Linux x86_64 或 Windows x64 的 WSL2 中运行\n'; exit 1; }
command -v docker >/dev/null 2>&1 || { printf '[✗] 未安装 docker\n'; exit 1; }
docker compose version >/dev/null 2>&1 || { printf '[✗] 未安装 docker compose 插件\n'; exit 1; }
docker info >/dev/null 2>&1 || { printf '[✗] Docker 守护进程未运行\n'; exit 1; }
DOCKER_PLATFORM=$(docker info --format '{{.OSType}}/{{.Architecture}}')
case "$DOCKER_PLATFORM" in linux/x86_64|linux/amd64) ;; *) printf '[✗] Docker 必须运行 Linux amd64 容器，当前为 %s\n' "$DOCKER_PLATFORM"; exit 1;; esac
[ -f "$SCRIPT_DIR/IMAGE-MANIFEST.txt" ] || { printf '[✗] 缺少镜像清单\n'; exit 1; }
ls "$SCRIPT_DIR"/images/*.tar.gz >/dev/null 2>&1 || { printf '[✗] images/ 下没有镜像包（*.tar.gz）\n'; exit 1; }
[ -f "$SCRIPT_DIR/deploy/compose.yml" ] || { printf '[✗] 缺少 deploy/compose.yml\n'; exit 1; }
bash "$SCRIPT_DIR/deploy/scripts/install-offline-map.sh" "$SCRIPT_DIR/maps" "$SCRIPT_DIR/maps" --verify-only

IP_MATCHED=$(ip addr 2>/dev/null | grep -c "inet $NODE_IP/" || true)
if [ "$IP_MATCHED" = "0" ]; then
    printf '[!] 警告: %s 不是本机当前网卡 IP，请确认后再继续\n' "$NODE_IP"
    printf '    本机当前 IP: %s\n' "$(hostname -I 2>/dev/null | tr ' ' '\n' | head -5 | tr '\n' ' ')"
fi
printf '[✓] 预检通过\n'

# ── 2. 加载镜像 ───────────────────────────────────────────────────
printf '\n=== [2/6] 加载镜像（可能需要几分钟）===\n'
for f in "$SCRIPT_DIR"/images/*.tar.gz; do
    printf '加载: %s\n' "$(basename "$f")"
    gunzip -c "$f" | docker load
done
printf '[✓] 镜像加载完成\n'

# 验证包内声明的全部镜像已存在
if [ -f "$SCRIPT_DIR/IMAGE-MANIFEST.txt" ]; then
    while IFS= read -r image; do
        [ -n "$image" ] || continue
        [ "$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "$image")" = "linux/amd64" ] || {
            printf '[✗] 镜像加载后不存在或不是 linux/amd64: %s\n' "$image"
            exit 1
        }
    done < "$SCRIPT_DIR/IMAGE-MANIFEST.txt"
    printf '[✓] 镜像清单验证通过\n'
fi

if [ -f "$SCRIPT_DIR/VERSION" ]; then
    YOOX_VERSION=$(sed -n '1p' "$SCRIPT_DIR/VERSION")
else
    YOOX_VERSION="local"
fi

# ── 3. 准备部署目录 ───────────────────────────────────────────────
printf '\n=== [3/6] 准备部署目录 %s ===\n' "$TARGET_DIR"
mkdir -p "$TARGET_DIR"

if $UPDATE_MODE && [ ! -f "$TARGET_DIR/.env" ]; then
    printf '[✗] --update 需要已有 %s/.env\n' "$TARGET_DIR"
    exit 1
fi
if $UPDATE_MODE || [ -e "$TARGET_DIR/.env" ]; then
    if ! $UPDATE_MODE; then
        printf '[!] %s/.env 已存在，切换为更新模式（仅更新配置文件和镜像）\n' "$TARGET_DIR"
    fi
    # 更新模式：只更新配置文件，不覆盖 .env
    cp -r \
        "$SCRIPT_DIR/deploy/compose.yml" \
        "$SCRIPT_DIR/deploy/scripts" \
        "$SCRIPT_DIR/deploy/deploy" \
        "$SCRIPT_DIR/deploy/sql" \
        "$TARGET_DIR/"
    chmod +x "$TARGET_DIR/scripts/preflight.sh" "$TARGET_DIR/scripts/smoke-test.sh" 2>/dev/null || true
    printf '[✓] 配置文件已更新；保留部署配置 %s/.env（仅镜像版本会更新）\n' "$TARGET_DIR"
    printf '[i] 已有部署不会重新读取 %s/.env.template，也不会自动覆盖 YOOX_PUBLIC_HOST；请直接编辑部署配置。\n' "$SCRIPT_DIR"
    # 更新 YOOX_VERSION（如有变化）
    if [ "$YOOX_VERSION" != "local" ]; then
        if grep -q '^YOOX_VERSION=' "$TARGET_DIR/.env"; then
            sed -i "s|^YOOX_VERSION=.*|YOOX_VERSION=$YOOX_VERSION|" "$TARGET_DIR/.env"
        else
            printf 'YOOX_VERSION=%s\n' "$YOOX_VERSION" >> "$TARGET_DIR/.env"
        fi
        printf '[✓] YOOX_VERSION 已更新为 %s\n' "$YOOX_VERSION"
    fi
else
    # 全新安装
    cp -r "$SCRIPT_DIR/deploy/." "$TARGET_DIR/"
    chmod +x "$TARGET_DIR/scripts/preflight.sh" "$TARGET_DIR/scripts/smoke-test.sh" 2>/dev/null || true

    # 优先使用 .env.template（含预设密码），不存在则回退到 .env.example
    TEMPLATE="$SCRIPT_DIR/.env.template"
    [ -f "$TEMPLATE" ] || TEMPLATE="$SCRIPT_DIR/.env.example"
    cp "$TEMPLATE" "$TARGET_DIR/.env"

    # 填入节点 IP 和镜像版本
    sed -i "s|^YOOX_PUBLIC_HOST=.*|YOOX_PUBLIC_HOST=$NODE_IP|" "$TARGET_DIR/.env"
    # 删除已有 YOOX_VERSION 行后追加（避免重复）
    sed -i '/^YOOX_VERSION=/d' "$TARGET_DIR/.env"
    printf 'YOOX_VERSION=%s\n' "$YOOX_VERSION" >> "$TARGET_DIR/.env"
    chmod 600 "$TARGET_DIR/.env"
    printf '[✓] 已生成 %s/.env（YOOX_PUBLIC_HOST=%s, YOOX_VERSION=%s）\n' \
        "$TARGET_DIR" "$NODE_IP" "$YOOX_VERSION"
fi

# ── 4. 校验配置 ───────────────────────────────────────────────────
printf '\n=== [4/6] 校验配置 ===\n'
printf '[i] 实际部署配置：%s/.env\n' "$TARGET_DIR"
cd "$TARGET_DIR"
MAP_DEST=$(sed -n 's/^YOOX_MAP_DATA_DIR=//p' .env | tail -n 1 | sed "s/^['\"]//; s/['\"]$//")
MAP_DEST=${MAP_DEST:-./maps}
case "$MAP_DEST" in /*) ;; *) MAP_DEST="$TARGET_DIR/$MAP_DEST";; esac
bash scripts/install-offline-map.sh "$SCRIPT_DIR/maps" "$MAP_DEST"
bash scripts/preflight.sh "$TARGET_DIR/.env"
printf '[✓] 配置有效\n'

# ── 5. 启动服务 ───────────────────────────────────────────────────
printf '\n=== [5/6] 启动服务（等待健康检查，最多 300 秒）===\n'
if [ -n "$COMPOSE_PROFILES" ]; then
    export COMPOSE_PROFILES
    printf '启用可选 profile: %s\n' "$COMPOSE_PROFILES"
fi
# 安装与更新均严格使用包内镜像，不联网拉取或构建。
docker compose --env-file .env up -d --no-build --pull never --remove-orphans --wait --wait-timeout 300
printf '[✓] 所有服务已启动\n'

# ── 6. 验证 ───────────────────────────────────────────────────────
printf '\n=== [6/6] 验证 ===\n'
docker compose --env-file .env ps
printf '\n'
bash scripts/smoke-test.sh .env

HTTP_PORT=$(sed -n 's/^YOOX_HTTP_PORT=//p' .env | tail -n 1)
HTTP_PORT=${HTTP_PORT:-8080}
PILOT_PORT=$(sed -n 's/^YOOX_PILOT_PORT=//p' .env | tail -n 1)
PILOT_PORT=${PILOT_PORT:-9000}
PORTAL_PORT=$(sed -n 's/^YOOX_API_PORTAL_PORT=//p' .env | tail -n 1)
PORTAL_PORT=${PORTAL_PORT:-8081}

printf '\n============================================\n'
printf ' 部署完成！\n'
printf ' Web 控制台 : http://%s:%s\n' "$NODE_IP" "$HTTP_PORT"
printf ' Pilot 网关 : http://%s:%s\n' "$NODE_IP" "$PILOT_PORT"
printf ' API 文档   : http://%s:%s\n' "$NODE_IP" "$PORTAL_PORT"
printf ' 初始账号   : admin / Yoox@123456（首次登录后请修改密码）\n'
printf '============================================\n'
