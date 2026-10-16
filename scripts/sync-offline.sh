#!/usr/bin/env bash
# 将项目根目录的关键文件同步到离线包快照目录。
# 每次修改 compose.yml、scripts/、deploy/emqx/、deploy/mediamtx/ 或 sql/ 后运行。
set -eu

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT_DIR/offline/nano-deploy/deploy"

cp "$ROOT_DIR/compose.yml"                           "$DEST/compose.yml"
cp "$ROOT_DIR/compose.nano.yml"                      "$DEST/compose.nano.yml"
cp "$ROOT_DIR/scripts/preflight.sh"                  "$DEST/scripts/preflight.sh"
cp "$ROOT_DIR/scripts/smoke-test.sh"                 "$DEST/scripts/smoke-test.sh"
cp "$ROOT_DIR/deploy/emqx/acl.conf"                  "$DEST/deploy/emqx/acl.conf"
cp "$ROOT_DIR/deploy/emqx/base.hocon"                "$DEST/deploy/emqx/base.hocon"
cp "$ROOT_DIR/deploy/mediamtx/mediamtx.yml"          "$DEST/deploy/mediamtx/mediamtx.yml"
mkdir -p "$DEST/deploy/grafana/dashboards" \
         "$DEST/deploy/grafana/provisioning/dashboards" \
         "$DEST/deploy/grafana/provisioning/datasources" \
         "$DEST/deploy/loki" \
         "$DEST/deploy/prometheus" \
         "$DEST/deploy/promtail"
cp "$ROOT_DIR/deploy/grafana/dashboards/yoox-overview.json" \
   "$DEST/deploy/grafana/dashboards/yoox-overview.json"
cp "$ROOT_DIR/deploy/grafana/provisioning/dashboards/dashboards.yml" \
   "$DEST/deploy/grafana/provisioning/dashboards/dashboards.yml"
cp "$ROOT_DIR/deploy/grafana/provisioning/datasources/datasources.yml" \
   "$DEST/deploy/grafana/provisioning/datasources/datasources.yml"
cp "$ROOT_DIR/deploy/loki/loki.yml"                  "$DEST/deploy/loki/loki.yml"
cp "$ROOT_DIR/deploy/prometheus/prometheus.yml"      "$DEST/deploy/prometheus/prometheus.yml"
cp "$ROOT_DIR/deploy/promtail/promtail.yml"          "$DEST/deploy/promtail/promtail.yml"
cp "$ROOT_DIR/sql/cloud_api.sql"                     "$DEST/sql/cloud_api.sql"

printf 'offline/nano-deploy/deploy/ 同步完成。\n'
