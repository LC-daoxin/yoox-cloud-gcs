"""Publish a smaller H.264 RTSP stream; configuration comes from environment."""

import os
import re
import signal
import subprocess
import sys
from urllib.parse import quote


def positive_int(env, name, default, maximum):
    value = int(env.get(name, str(default)))
    if not 1 <= value <= maximum:
        raise ValueError(f"{name} must be between 1 and {maximum}")
    return value


def build_command(env):
    path = env.get("YOOX_TRANSCODE_SOURCE_PATH", "")
    if not re.fullmatch(r"[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*", path):
        raise ValueError("YOOX_TRANSCODE_SOURCE_PATH must be a MediaMTX path, not a URL")
    if path.endswith("-low"):
        raise ValueError("Use the original stream, not a -low stream")
    host = env.get("YOOX_TRANSCODE_RTSP_HOST", "mediamtx")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", host):
        raise ValueError("YOOX_TRANSCODE_RTSP_HOST must be an IPv4 address or hostname")
    port = positive_int(env, "YOOX_TRANSCODE_RTSP_PORT", 8554, 65535)
    width = positive_int(env, "YOOX_TRANSCODE_WIDTH", 1280, 3840)
    height = positive_int(env, "YOOX_TRANSCODE_HEIGHT", 720, 2160)
    fps = positive_int(env, "YOOX_TRANSCODE_FPS", 25, 60)
    bitrate = positive_int(env, "YOOX_TRANSCODE_BITRATE_KBPS", 1500, 20000)
    threads = positive_int(env, "YOOX_TRANSCODE_THREADS", 2, 32)
    if width < 2 or height < 2 or width % 2 or height % 2:
        raise ValueError("Output dimensions must be positive even numbers >= 2")
    username = env.get("YOOX_RTSP_USERNAME", "")
    password = env.get("YOOX_RTSP_PASSWORD", "")
    if not username or not password:
        raise ValueError("YOOX_RTSP_USERNAME and YOOX_RTSP_PASSWORD are required")
    source = f"rtsp://{host}:{port}/{path}"
    credentials = f"{quote(username, safe='')}:{quote(password, safe='')}"
    output = f"rtsp://{credentials}@{host}:{port}/{path}-low"
    filters = (
        f"fps={fps},scale=w='min(iw,{width})':h='min(ih,{height})':"
        "force_original_aspect_ratio=decrease:force_divisible_by=2,setsar=1"
    )
    return [
        "ffmpeg", "-hide_banner", "-nostdin", "-loglevel", "warning",
        "-rtsp_transport", "tcp", "-timeout", "15000000",
        "-threads", str(threads), "-i", source,
        "-map", "0:v:0", "-an", "-vf", filters,
        "-filter_threads", "1", "-c:v", "libx264", "-threads", str(threads),
        "-preset", "veryfast", "-tune", "zerolatency", "-pix_fmt", "yuv420p",
        "-profile:v", "main", "-b:v", f"{bitrate}k",
        "-maxrate", f"{bitrate * 4 // 3}k", "-bufsize", f"{bitrate * 2}k",
        "-g", str(fps), "-keyint_min", str(fps), "-sc_threshold", "0", "-bf", "0",
        "-f", "rtsp", "-rtsp_transport", "tcp", "-muxdelay", "0.1", output,
    ]


def main():
    try:
        command = build_command(os.environ)
    except ValueError as error:
        print(f"Invalid transcoder configuration: {error}", file=sys.stderr)
        return 2
    print(f"Transcoding {os.environ['YOOX_TRANSCODE_SOURCE_PATH']} to a -low stream", flush=True)
    process = subprocess.Popen(command, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)

    def stop(signum, _frame):
        if process.poll() is None:
            process.send_signal(signum)

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    # FFmpeg may include its output URL in an error. Do not log publish credentials.
    for line in process.stderr:
        line = re.sub(r"rtsp://[^\s/]*@", "rtsp://***@", line)
        print(line, end="", file=sys.stderr, flush=True)
    return process.wait()


if __name__ == "__main__":
    sys.exit(main())
