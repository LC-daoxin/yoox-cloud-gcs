#!/bin/sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ENV_FILE=${1:-"$ROOT_DIR/.env"}

fail() {
  printf 'ERROR: %s\n' "$1" >&2
  exit 1
}

command -v docker >/dev/null 2>&1 || fail "Docker is not installed."
docker compose version >/dev/null 2>&1 || fail "Docker Compose v2 is not available."
docker info >/dev/null 2>&1 || fail "Docker daemon is not running."
test -f "$ENV_FILE" || fail "Missing $ENV_FILE. Copy .env.example to .env first."
ENV_FILE="$(CDPATH= cd -- "$(dirname -- "$ENV_FILE")" && pwd)/$(basename -- "$ENV_FILE")"

UNFILLED_KEYS=$(awk '
  /^[[:space:]]*#/ { next }
  /^[[:space:]]*[A-Za-z_][A-Za-z0-9_]*=/ {
    separator = index($0, "=")
    if (substr($0, separator + 1) ~ /change_me|replace_with/)
      printf "  %d:%s\n", NR, substr($0, 1, separator - 1)
  }
' "$ENV_FILE")
if [ -n "$UNFILLED_KEYS" ]; then
  printf 'ERROR: 实际部署配置仍有未填写的占位值（change_me / replace_with）：%s\n' "$ENV_FILE" >&2
  printf '以下仅显示行号和变量名，不显示密码：\n%s\n' "$UNFILLED_KEYS" >&2
  printf '请备份后编辑这个文件：\n  nano "%s"\n' "$ENV_FILE" >&2
  printf '离线安装发现已有 .env 时会保留它；仅修改安装包的 .env.template 后重跑不会同步到已有 .env。\n' >&2
  printf '已有服务或数据卷请沿用原凭据，不要用新模板整份覆盖部署配置。\n' >&2
  printf '修改后重新校验：\n  bash "%s/scripts/preflight.sh" "%s"\n' "$ROOT_DIR" "$ENV_FILE" >&2
  exit 1
fi

env_value() {
  sed -n "s/^$1=//p" "$ENV_FILE" | tail -n 1 | sed "s/^['\"]//; s/['\"]$//"
}

MQTT_USERNAME=$(env_value YOOX_MQTT_USERNAME)
MQTT_PASSWORD=$(env_value YOOX_MQTT_PASSWORD)
DEVICE_MQTT_USERNAME=$(env_value YOOX_DEVICE_MQTT_USERNAME)
DEVICE_MQTT_PASSWORD=$(env_value YOOX_DEVICE_MQTT_PASSWORD)
JWT_SECRET=$(env_value YOOX_JWT_SECRET)

test "$MQTT_USERNAME" = "yoox-cloud" ||
  fail "YOOX_MQTT_USERNAME must be yoox-cloud to match deploy/emqx/acl.conf."
test "$DEVICE_MQTT_USERNAME" = "pilot" ||
  fail "YOOX_DEVICE_MQTT_USERNAME must be pilot to match the Pilot default and deploy/emqx/acl.conf."
test "$MQTT_USERNAME" != "$DEVICE_MQTT_USERNAME" ||
  fail "Cloud and device MQTT usernames must be different."
test "$DEVICE_MQTT_PASSWORD" = "pilot123" ||
  fail "YOOX_DEVICE_MQTT_PASSWORD must be pilot123 for the requested initialization default."
test "${#JWT_SECRET}" -ge 32 ||
  fail "YOOX_JWT_SECRET must contain at least 32 characters."

grep -Fq '{username, "pilot"}' "$ROOT_DIR/deploy/emqx/acl.conf" ||
  fail "deploy/emqx/acl.conf must grant the pilot device account."

PUBLIC_HOST=$(sed -n 's/^YOOX_PUBLIC_HOST=//p' "$ENV_FILE" | tail -n 1)
case "$PUBLIC_HOST" in
  ""|127.0.0.1|localhost)
    printf 'WARN: YOOX_PUBLIC_HOST=%s is suitable only for local testing.\n' "${PUBLIC_HOST:-<empty>}" >&2
    ;;
esac

docker compose --env-file "$ENV_FILE" -f "$ROOT_DIR/compose.yml" config --quiet
printf 'YOOX Cloud GCS preflight passed.\n'
