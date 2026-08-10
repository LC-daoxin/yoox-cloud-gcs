"""一键起飞：按当前 Web 控制台的最小请求体下发并恢复任务状态。

服务端负责补全 ``security_takeoff_height=20``、``rth_altitude=20`` 和
``rc_lost_action=RETURN_HOME``，并为 RC 网关补 ``device_list``。客户端超时
不代表起飞失败，本脚本会查询 point-flight 状态且不会自动重发。
"""
from __future__ import annotations

import argparse

from config import (
    DOCK_SN,
    POINT_FLIGHT_WAIT_SECONDS,
    TARGET_HEIGHT,
    TARGET_LATITUDE,
    TARGET_LONGITUDE,
    TARGET_MAX_SPEED,
)
from demo_common import (
    DemoApiError,
    DemoError,
    api_call,
    get_point_flight_state,
    login,
    point_flight_is_terminal,
    point_flight_task_id,
    print_error_and_hint,
    print_point_flight_state,
    require_config,
    seize_flight_authority,
    validate_target,
    wait_for_new_point_flight_state,
    wait_for_point_flight_terminal,
)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="YOOX 一键起飞安全 Demo")
    parser.add_argument("action", nargs="?", choices=("go", "status"), default="go")
    parser.add_argument("--lat", type=float, default=TARGET_LATITUDE)
    parser.add_argument("--lon", type=float, default=TARGET_LONGITUDE)
    parser.add_argument("--height", type=float, default=TARGET_HEIGHT)
    parser.add_argument("--speed", type=float, default=TARGET_MAX_SPEED)
    parser.add_argument("--wait", type=int, default=POINT_FLIGHT_WAIT_SECONDS)
    return parser


def main() -> int:
    args = build_parser().parse_args()
    require_config(YOOX_DOCK_SN=DOCK_SN)
    token = login()
    if args.action == "status":
        print_point_flight_state(get_point_flight_state(token))
        return 0

    lat, lon, height, speed = validate_target(
        args.lat, args.lon, args.height, args.speed
    )
    previous = get_point_flight_state(token)
    baseline_version = int((previous or {}).get("updated_at") or 0)
    if previous and previous.get("active") is True:
        print_point_flight_state(previous, "[拒绝]")
        print("[✗] 已有点飞任务或上次结果待确认，禁止重复起飞")
        return 2

    print(
        f"[*] 起飞目标 WGS84=({lat:.7f}, {lon:.7f})，"
        f"相对高度={height:.1f}m，最大速度={speed:g}m/s"
    )
    print("[!] 通常应使用飞机当前有效 GPS 经纬度，实现原地爬升到目标高度。")
    if input("[!!] 确认飞机在地面 IDLE、现场净空后输入 YES: ").strip() != "YES":
        print("[*] 已取消")
        return 0

    seize_flight_authority(token)
    ambiguous: DemoApiError | None = None
    try:
        api_call(
            token,
            "POST",
            f"/control/api/v1/devices/{DOCK_SN}/jobs/takeoff-to-point",
            action="下发一键起飞",
            # 与 CockpitView.oneKeyTakeoff 一致；安全默认值由后端统一补齐。
            json_body={
                "target_longitude": lon,
                "target_latitude": lat,
                "target_height": round(height, 1),
                "max_speed": speed,
            },
        )
        print("[✓] 一键起飞 HTTP 调用已返回成功；等待设备进度，不代表已起飞")
    except DemoApiError as exc:
        print_error_and_hint(exc)
        if not exc.ambiguous:
            try:
                print_point_flight_state(get_point_flight_state(token), "[服务端状态]")
            except DemoApiError:
                pass
            return 1
        ambiguous = exc

    current = wait_for_new_point_flight_state(
        token, "takeoff", baseline_version, timeout=15
    )
    if not current:
        if ambiguous:
            print("[!!] 请求结果未知且暂未恢复任务 ID；不要再次点击/运行起飞。")
            print("     观察 OSD 是否进入自动起飞/悬停，稍后运行本脚本 status。")
        else:
            print("[!] 返回成功但暂未取得任务 ID；不要重发，稍后运行 status。")
        return 2

    print_point_flight_state(current, "[恢复]" if ambiguous else "[受理]")
    task_id = point_flight_task_id(current)
    if point_flight_is_terminal(current):
        return 0 if current.get("result", 0) == 0 else 1

    final = wait_for_point_flight_terminal(
        token, "takeoff", task_id, timeout=args.wait
    )
    if final and (
        final.get("result", 0) != 0
        or final.get("status") in {"wayline_failed", "command_failed"}
    ):
        return 1
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except DemoError as exc:
        print_error_and_hint(exc)
        raise SystemExit(1)
