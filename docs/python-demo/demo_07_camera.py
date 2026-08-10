"""拍照、开始录像或停止录像；每一步确认成功后才继续下一步。"""
from __future__ import annotations

import sys

from config import DOCK_SN, PAYLOAD_INDEX
from demo_common import (
    DemoConfigError,
    DemoError,
    login,
    print_error_and_hint,
    require_config,
    seize_payload_authority,
    send_payload_command,
)


def send(token: str, command: str, extra: dict | None = None) -> None:
    data = {"payload_index": PAYLOAD_INDEX}
    if extra:
        data.update(extra)
    print(f"[*] 发送 {command}")
    send_payload_command(token, command, data)
    print(f"[✓] {command} 调用成功；实际相机状态以 OSD 上报为准")


def main() -> int:
    require_config(YOOX_DOCK_SN=DOCK_SN, YOOX_PAYLOAD_INDEX=PAYLOAD_INDEX)
    action = sys.argv[1] if len(sys.argv) > 1 else "photo"
    if action not in {"photo", "rec_start", "rec_stop"}:
        raise DemoConfigError("用法: demo_07_camera.py [photo|rec_start|rec_stop]")

    token = login()
    seize_payload_authority(token, PAYLOAD_INDEX)
    if action == "photo":
        send(token, "camera_mode_switch", {"camera_mode": 0})
        send(token, "camera_photo_take")
    elif action == "rec_start":
        send(token, "camera_mode_switch", {"camera_mode": 1})
        send(token, "camera_recording_start")
    else:
        send(token, "camera_recording_stop")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except DemoError as exc:
        print_error_and_hint(exc)
        raise SystemExit(1)
