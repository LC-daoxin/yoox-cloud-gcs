#!/usr/bin/env bash
set -eu

PROJECT_DIR="${YOOX_PROJECT_DIR:-$(cd "$(dirname "$0")/.." && pwd)}"
ENV_FILE="${YOOX_ENV_FILE:-${PROJECT_DIR}/.env}"
SOURCE_PATH="${YOOX_RTSP_NORMALIZER_SOURCE_PATH:-${1:-}}"
OUTPUT_SUFFIX="${YOOX_RTSP_NORMALIZER_OUTPUT_SUFFIX:--normalized}"
TIMESTAMP_MODE="${YOOX_RTSP_NORMALIZER_TIMESTAMP_MODE:-wallclock}"

read_env_value() {
  key="$1"
  sed -n "s/^${key}=//p" "${ENV_FILE}" | tail -n 1 | tr -d '\r' | sed 's/^"//;s/"$//'
}

if [ -z "${SOURCE_PATH}" ]; then
  echo "YOOX_RTSP_NORMALIZER_SOURCE_PATH or the first argument is required" >&2
  exit 2
fi

if [[ ! "${SOURCE_PATH}" =~ ^[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*$ ]] ||
   [[ ! "${OUTPUT_SUFFIX}" =~ ^-[A-Za-z0-9_-]+$ ]]; then
  echo "Use a MediaMTX source path and a nonempty -suffix, not an RTSP URL" >&2
  exit 2
fi

case "${SOURCE_PATH}" in
  *"${OUTPUT_SUFFIX}")
    echo "Refusing to normalize an already normalized path: ${SOURCE_PATH}" >&2
    exit 2
    ;;
esac

INPUT_TIMESTAMP_ARGS=(-fflags nobuffer+genpts)
OUTPUT_TIMESTAMP_ARGS=(-copyts -start_at_zero)
case "${TIMESTAMP_MODE}" in
  wallclock)
    INPUT_TIMESTAMP_ARGS+=(-use_wallclock_as_timestamps 1)
    ;;
  rtp-microseconds)
    # Opt in only after inspecting ingress RTP headers. These publishers put
    # microsecond increments in H264/90000 RTP; rescale packet timestamps to
    # 90 kHz without decoding or changing the H.264 access units.
    OUTPUT_TIMESTAMP_ARGS+=(-bsf:v 'setts=pts=PTS*9/100:dts=DTS*9/100')
    ;;
  *)
    echo "Unsupported timestamp mode; use wallclock or rtp-microseconds" >&2
    exit 2
    ;;
esac

# FFmpeg 4.x calls the RTSP socket timeout stimeout. Newer releases use timeout.
RTSP_HELP="$(ffmpeg -hide_banner -h demuxer=rtsp 2>&1)"
if [[ "${RTSP_HELP}" == *"-stimeout "* ]]; then
  TIMEOUT_ARGS=(-stimeout 15000000)
else
  TIMEOUT_ARGS=(-timeout 15000000)
fi

RTSP_PORT="${YOOX_PUBLIC_RTSP_PORT:-$(read_env_value YOOX_PUBLIC_RTSP_PORT)}"
RTSP_USER="${YOOX_RTSP_USERNAME:-$(read_env_value YOOX_RTSP_USERNAME)}"
RTSP_PASSWORD="${YOOX_RTSP_PASSWORD:-$(read_env_value YOOX_RTSP_PASSWORD)}"
RTSP_PORT="${RTSP_PORT:-8554}"

if [ -z "${RTSP_USER}" ] || [ -z "${RTSP_PASSWORD}" ]; then
  echo "RTSP publisher credentials are missing from ${ENV_FILE}" >&2
  exit 2
fi

encode_uri_component() {
  python3 -c 'import sys; from urllib.parse import quote; print(quote(sys.stdin.read(), safe=""), end="")'
}
ENCODED_USER="$(printf '%s' "${RTSP_USER}" | encode_uri_component)"
ENCODED_PASSWORD="$(printf '%s' "${RTSP_PASSWORD}" | encode_uri_component)"
INPUT_URL="rtsp://127.0.0.1:${RTSP_PORT}/${SOURCE_PATH}"
OUTPUT_URL="rtsp://${ENCODED_USER}:${ENCODED_PASSWORD}@127.0.0.1:${RTSP_PORT}/${SOURCE_PATH}${OUTPUT_SUFFIX}"

echo "Normalizing ${INPUT_URL} -> ${SOURCE_PATH}${OUTPUT_SUFFIX} (${TIMESTAMP_MODE})"

exec ffmpeg \
  -hide_banner \
  -loglevel warning \
  -rtsp_transport tcp \
  "${TIMEOUT_ARGS[@]}" \
  "${INPUT_TIMESTAMP_ARGS[@]}" \
  -analyzeduration 0 \
  -i "${INPUT_URL}" \
  -map 0:v:0 \
  -c:v copy \
  -an \
  "${OUTPUT_TIMESTAMP_ARGS[@]}" \
  -muxdelay 0 \
  -muxpreload 0 \
  -f rtsp \
  -rtsp_transport tcp \
  "${OUTPUT_URL}"
