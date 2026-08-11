#!/usr/bin/env bash
# 将项目根目录的关键文件同步到离线包快照目录。
# 每次修改 compose.yml、scripts/、deploy/emqx/、deploy/mediamtx/ 或 sql/ 后运行。
set -eu

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT_DIR/offline/nano-deploy/deploy"

cp "$ROOT_DIR/compose.yml"                           "$DEST/compose.yml"
cp "$ROOT_DIR/scripts/preflight.sh"                  "$DEST/scripts/preflight.sh"
cp "$ROOT_DIR/scripts/smoke-test.sh"                 "$DEST/scripts/smoke-test.sh"
cp "$ROOT_DIR/deploy/emqx/acl.conf"                  "$DEST/deploy/emqx/acl.conf"
cp "$ROOT_DIR/deploy/emqx/base.hocon"                "$DEST/deploy/emqx/base.hocon"
cp "$ROOT_DIR/deploy/mediamtx/mediamtx.yml"          "$DEST/deploy/mediamtx/mediamtx.yml"
cp "$ROOT_DIR/sql/cloud_api.sql"                     "$DEST/sql/cloud_api.sql"

printf 'offline/nano-deploy/deploy/ 同步完成。\n'
