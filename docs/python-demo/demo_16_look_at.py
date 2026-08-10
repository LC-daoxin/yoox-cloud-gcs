"""负载 Look At：抢占负载控制权后指向显式 WGS84 目标。"""
from __future__ import annotations

import argparse

from config import (
    DOCK_SN,
    PAYLOAD_INDEX,
    TARGET_HEIGHT,
    TARGET_LATITUDE,
    TARGET_LONGITUDE,
)
from demo_common import (
    DemoConfigError,
    DemoError,
    login,
    print_error_and_hint,
    require_config,
    seize_payload_authority,
    send_payload_command,
)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="YOOX camera_look_at Demo")
    parser.add_argument("latitude", nargs="?", type=float, default=TARGET_LATITUDE)
    parser.add_argument("longitude", nargs="?", type=float, default=TARGET_LONGITUDE)
    parser.add_argument("height", nargs="?", type=float, default=TARGET_HEIGHT)
    parser.add_argument(
        "--locked",
        action="store_true",
        help="同时联动机身；默认 false，仅转云台，与当前 Web 控制台一致",
    )
    return parser


def main() -> int:
    args = build_parser().parse_args()
    require_config(YOOX_DOCK_SN=DOCK_SN, YOOX_PAYLOAD_INDEX=PAYLOAD_INDEX)
    if args.latitude is None or args.longitude is None or args.height is None:
        raise DemoConfigError(
            "请传入 latitude longitude height，或配置 YOOX_TARGET_LATITUDE/LONGITUDE/HEIGHT"
        )
    if not (
        -90 <= args.latitude <= 90
        and -180 <= args.longitude <= 180
        and not (args.latitude == 0 and args.longitude == 0)
        and 2 <= args.height <= 10000
    ):
        raise DemoConfigError("Look At 目标需为非零有效经纬度，高度范围 2–10000 m")

    token = login()
    seize_payload_authority(token, PAYLOAD_INDEX)
    print(
        f"[*] camera_look_at -> ({args.latitude:.6f}, "
        f"{args.longitude:.6f}, {args.height:.1f}m), locked={args.locked}"
    )
    send_payload_command(
        token,
        "camera_look_at",
        {
            "payload_index": PAYLOAD_INDEX,
            "locked": args.locked,
            "latitude": round(args.latitude, 6),
            "longitude": round(args.longitude, 6),
            "height": round(args.height, 1),
        },
    )
    print("[✓] Look At 调用成功；实际云台方向以 OSD/画面为准")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except DemoError as exc:
        print_error_and_hint(exc)
        raise SystemExit(1)
