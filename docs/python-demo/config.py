"""
Python Demo 统一配置。

配置全部来自环境变量；推荐复制 ``.env.example`` 为 ``.env``，再通过
``./run.sh`` 启动。这里不保存服务器地址、账号密码、设备序列号或飞行坐标。
"""
from __future__ import annotations

import os


def _env_int(name: str, default: int) -> int:
    raw = os.getenv(name, "").strip()
    if not raw:
        return default
    try:
        return int(raw)
    except ValueError as exc:
        raise RuntimeError(f"环境变量 {name} 必须是整数，当前值为 {raw!r}") from exc


def _env_float(name: str, default: float | None = None) -> float | None:
    raw = os.getenv(name, "").strip()
    if not raw:
        return default
    try:
        return float(raw)
    except ValueError as exc:
        raise RuntimeError(f"环境变量 {name} 必须是数字，当前值为 {raw!r}") from exc


# Web/REST 与 Pilot 网关使用当前项目的两个默认入口：8080 和 9000。
# 反向代理/TLS 场景可分别覆盖完整 URL。
SERVER_IP = os.getenv("YOOX_SERVER_IP", "127.0.0.1").strip()
SERVER_PORT = _env_int("YOOX_SERVER_PORT", 8080)
BASE_URL = os.getenv(
    "YOOX_BASE_URL", f"http://{SERVER_IP}:{SERVER_PORT}"
).strip().rstrip("/")
PILOT_PORT = _env_int("YOOX_PILOT_PORT", 9000)
PILOT_BASE_URL = os.getenv(
    "YOOX_PILOT_BASE_URL", f"http://{SERVER_IP}:{PILOT_PORT}"
).strip().rstrip("/")

# Web 登录账号。密码没有可用默认值，运行前必须在 .env 中填写。
WEB_USERNAME = os.getenv("YOOX_WEB_USERNAME", "admin").strip()
WEB_PASSWORD = os.getenv("YOOX_WEB_PASSWORD", "change_me")
WEB_FLAG = _env_int("YOOX_WEB_FLAG", 1)

# Pilot 登录仅供相应 Demo 使用。
PILOT_USERNAME = os.getenv("YOOX_PILOT_USERNAME", "pilot").strip()
PILOT_PASSWORD = os.getenv("YOOX_PILOT_PASSWORD", "change_me")
PILOT_FLAG = _env_int("YOOX_PILOT_FLAG", 2)

# 主 MQTT Broker（OSD、事件和 services_reply 旁路监听）。
MQTT_HOST = os.getenv("YOOX_MQTT_HOST", SERVER_IP).strip()
MQTT_PORT = _env_int("YOOX_MQTT_PORT", 1883)
MQTT_WS_PORT = _env_int("YOOX_MQTT_WS_PORT", 9001)
MQTT_USERNAME = os.getenv("YOOX_MQTT_USERNAME", "").strip()
MQTT_PASSWORD = os.getenv("YOOX_MQTT_PASSWORD", "")

# Demo 只拼接不含发布凭据的 MediaMTX RTSP 播放路径；设备发布凭据由服务端管理。
RTSP_HOST = os.getenv("YOOX_RTSP_HOST", SERVER_IP).strip()
RTSP_PORT = _env_int("YOOX_RTSP_PORT", 8554)

# WebSocket 地址可单独覆盖（反向代理/TLS 场景常用 wss://）。
WS_URL = os.getenv(
    "YOOX_WS_URL", f"ws://{SERVER_IP}:{PILOT_PORT}/api/v1/ws"
).strip()

# 设备与工作空间。使用显眼占位符，防止样例误操作真实设备。
DOCK_SN = os.getenv("YOOX_DOCK_SN", "YOUR_DOCK_SN").strip()
DRONE_SN = os.getenv("YOOX_DRONE_SN", "YOUR_DRONE_SN").strip()
PAYLOAD_INDEX = os.getenv("YOOX_PAYLOAD_INDEX", "YOUR_PAYLOAD_INDEX").strip()
WORKSPACE_ID = os.getenv("YOOX_WORKSPACE_ID", "YOUR_WORKSPACE_ID").strip()

# 点飞/起飞目标。默认为空，必须在 .env 或命令行显式给出。
TARGET_LATITUDE = _env_float("YOOX_TARGET_LATITUDE")
TARGET_LONGITUDE = _env_float("YOOX_TARGET_LONGITUDE")
TARGET_HEIGHT = _env_float("YOOX_TARGET_HEIGHT")
TARGET_MAX_SPEED = _env_float("YOOX_TARGET_MAX_SPEED", 5.0)

# 控制接口超时只限制客户端等待时间，不代表设备没有执行指令。
HTTP_CONNECT_TIMEOUT = _env_float("YOOX_HTTP_CONNECT_TIMEOUT", 5.0) or 5.0
HTTP_CONTROL_TIMEOUT = _env_float("YOOX_HTTP_CONTROL_TIMEOUT", 20.0) or 20.0
POINT_FLIGHT_WAIT_SECONDS = _env_int("YOOX_POINT_FLIGHT_WAIT_SECONDS", 300)
