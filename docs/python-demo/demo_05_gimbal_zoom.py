"""抢占负载控制权并设置相机变焦倍率。"""
from __future__ import annotations

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


ZOOM_FACTOR = 5.0
CAMERA_TYPE = "zoom"  # zoom 或 ir


def main() -> int:
    require_config(YOOX_DOCK_SN=DOCK_SN, YOOX_PAYLOAD_INDEX=PAYLOAD_INDEX)
    if CAMERA_TYPE not in {"zoom", "ir"}:
        raise DemoConfigError("CAMERA_TYPE 只能是 zoom 或 ir")
    maximum = 160 if CAMERA_TYPE == "zoom" else 16
    if not 1 <= float(ZOOM_FACTOR) <= maximum:
        raise DemoConfigError(
            f"CAMERA_TYPE={CAMERA_TYPE} 时 ZOOM_FACTOR 必须在 1.0–{maximum}.0"
        )

    token = login()
    seize_payload_authority(token, PAYLOAD_INDEX)
    print(f"[*] 设置 {CAMERA_TYPE} 变焦倍率为 {ZOOM_FACTOR}x")
    send_payload_command(
        token,
        "camera_focal_length_set",
        {
            "payload_index": PAYLOAD_INDEX,
            "zoom_factor": float(ZOOM_FACTOR),
            "camera_type": CAMERA_TYPE,
        },
    )
    print("[✓] 变焦指令调用成功；实际镜头状态以 OSD 上报为准")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except DemoError as exc:
        print_error_and_hint(exc)
        raise SystemExit(1)
