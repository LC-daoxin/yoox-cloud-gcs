#!/usr/bin/env bash
# YOOX Cloud GCS Nano 离线一键安装脚本
#
# 用法：
#   1. 把整个 nano-deploy/ 目录复制到 U 盘
#   2. Nano 上插入 U 盘，cd 到本目录
#   3. bash install.sh <本机局域网IP>
#      例如：bash install.sh 172.20.10.4
#
# 更新模式（保留 .env，只更新配置文件和镜像）：
#   bash install.sh 172.20.10.4 --update
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TARGET_DIR="$HOME/yoox-cloud-gcs"
UPDATE_MODE=false

if [ -z "${1:-}" ] || echo "${1:-}" | grep -q '^-'; then
    printf '用法: bash install.sh <本机局域网IP> [--update]\n'
    printf '示例: bash install.sh 172.20.10.4\n'
    exit 1
fi
NODE_IP="$1"
[ "${2:-}" = "--update" ] && UPDATE_MODE=true

# ── 1. 预检 ───────────────────────────────────────────────────────
printf '\n=== [1/6] 预检 ===\n'
[ "$(uname -m)" = "aarch64" ] || { printf '[✗] 非 aarch64 系统，脚本中止\n'; exit 1; }
command -v docker >/dev/null 2>&1 || { printf '[✗] 未安装 docker\n'; exit 1; }
docker compose version >/dev/null 2>&1 || { printf '[✗] 未安装 docker compose 插件\n'; exit 1; }
docker info >/dev/null 2>&1 || { printf '[✗] Docker 守护进程未运行\n'; exit 1; }
ls "$SCRIPT_DIR"/images/*.tar.gz >/dev/null 2>&1 || { printf '[✗] images/ 下没有镜像包（*.tar.gz）\n'; exit 1; }
[ -f "$SCRIPT_DIR/deploy/compose.yml" ] || { printf '[✗] 缺少 deploy/compose.yml\n'; exit 1; }

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

# 验证自定义应用镜像已存在
APP_TARBALL=$(ls "$SCRIPT_DIR"/images/yoox-cloud-gcs-*.tar.gz 2>/dev/null | grep -v thirdparty | head -1 || true)
if [ -n "$APP_TARBALL" ]; then
    YOOX_VERSION=$(basename "$APP_TARBALL" .tar.gz | sed 's/^yoox-cloud-gcs-//')
else
    YOOX_VERSION="local"
fi

# ── 3. 准备部署目录 ───────────────────────────────────────────────
printf '\n=== [3/6] 准备部署目录 %s ===\n' "$TARGET_DIR"
mkdir -p "$TARGET_DIR"

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
    printf '[✓] 配置文件已更新，.env 未修改\n'
    # 更新 YOOX_VERSION（如有变化）
    if [ "$YOOX_VERSION" != "local" ]; then
        sed -i "s|^YOOX_VERSION=.*|YOOX_VERSION=$YOOX_VERSION|" "$TARGET_DIR/.env" 2>/dev/null || \
            printf 'YOOX_VERSION=%s\n' "$YOOX_VERSION" >> "$TARGET_DIR/.env"
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

    # 检查是否仍有未替换的占位符
    if grep -Eq '=(.*change_me|replace_with)' "$TARGET_DIR/.env"; then
        printf '\n[!] .env 中仍有未填写的变量（change_me / replace_with），请编辑后再继续：\n'
        grep -E '=(.*change_me|replace_with)' "$TARGET_DIR/.env" | sed 's/=.*//' | sed 's/^/    /'
        printf '\n    编辑命令: nano %s/.env\n' "$TARGET_DIR"
        printf '    编辑完成后重新运行: bash %s %s\n' "$0" "$NODE_IP"
        exit 1
    fi
fi

# ── 4. 校验配置 ───────────────────────────────────────────────────
printf '\n=== [4/6] 校验配置 ===\n'
cd "$TARGET_DIR"
bash scripts/preflight.sh .env
printf '[✓] 配置有效\n'

# ── 5. 启动服务 ───────────────────────────────────────────────────
printf '\n=== [5/6] 启动服务（等待健康检查，最多 300 秒）===\n'
docker compose --env-file .env up -d --remove-orphans --wait --wait-timeout 300
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
