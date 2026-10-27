#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 1 ] || [[ "$1" != rtsp://* ]]; then
  echo "Usage: bash scripts/play-vlc-rtsp-mac.sh rtsp://host:8554/path-normalized" >&2
  exit 2
fi

VLC_BIN="${YOOX_VLC_BIN:-/Applications/VLC.app/Contents/MacOS/VLC}"
if [ ! -x "$VLC_BIN" ]; then
  echo "VLC is not installed at ${VLC_BIN}" >&2
  exit 1
fi

# Quit an existing VLC instance before launching to avoid parallel players.
# Apply these options to this playback invocation, not unrelated global prefs.
exec "$VLC_BIN" --no-rtsp-tcp --network-caching=50 --clock-jitter=0 "$1"
