#!/usr/bin/env bash
set -eu

PROJECT_DIR="${YOOX_PROJECT_DIR:-$(cd "$(dirname "$0")/.." && pwd)}"
ENV_FILE="${YOOX_ENV_FILE:-${PROJECT_DIR}/.env}"
SOURCE_PATH="${YOOX_RTSP_NORMALIZER_SOURCE_PATH:-${1:-}}"
OUTPUT_SUFFIX="${YOOX_RTSP_NORMALIZER_OUTPUT_SUFFIX:--normalized}"

read_env_value() {
  key="$1"
  sed -n "s/^${key}=//p" "${ENV_FILE}" | tail -n 1 | tr -d '\r' | sed 's/^"//;s/"$//'
}

if [ -z "${SOURCE_PATH}" ]; then
  echo "YOOX_RTSP_NORMALIZER_SOURCE_PATH or the first argument is required" >&2
  exit 2
fi

case "${SOURCE_PATH}" in
  *-normalized)
    echo "Refusing to normalize an already normalized path: ${SOURCE_PATH}" >&2
    exit 2
    ;;
esac

RTSP_PORT="${YOOX_PUBLIC_RTSP_PORT:-$(read_env_value YOOX_PUBLIC_RTSP_PORT)}"
RTSP_USER="${YOOX_RTSP_USERNAME:-$(read_env_value YOOX_RTSP_USERNAME)}"
RTSP_PASSWORD="${YOOX_RTSP_PASSWORD:-$(read_env_value YOOX_RTSP_PASSWORD)}"
RTSP_PORT="${RTSP_PORT:-8554}"

if [ -z "${RTSP_USER}" ] || [ -z "${RTSP_PASSWORD}" ]; then
  echo "RTSP publisher credentials are missing from ${ENV_FILE}" >&2
  exit 2
fi

INPUT_URL="rtsp://127.0.0.1:${RTSP_PORT}/${SOURCE_PATH}"
OUTPUT_URL="rtsp://${RTSP_USER}:${RTSP_PASSWORD}@127.0.0.1:${RTSP_PORT}/${SOURCE_PATH}${OUTPUT_SUFFIX}"

echo "Normalizing ${INPUT_URL} -> ${SOURCE_PATH}${OUTPUT_SUFFIX}"

exec ffmpeg \
  -hide_banner \
  -loglevel warning \
  -rtsp_transport tcp \
  -use_wallclock_as_timestamps 1 \
  -fflags nobuffer+genpts \
  -analyzeduration 0 \
  -i "${INPUT_URL}" \
  -map 0:v:0 \
  -c:v copy \
  -an \
  -copyts \
  -start_at_zero \
  -muxdelay 0 \
  -muxpreload 0 \
  -f rtsp \
  -rtsp_transport tcp \
  "${OUTPUT_URL}"
