"""指点飞行：抢权、单次下发、任务身份恢复、进度上报与安全停止。

当前服务端会为 RC 网关自动补 ``device_list``，并将点飞状态保存到
``GET .../jobs/point-flight/status``。动作请求超时后，本脚本只查询该状态，
绝不会自动重发 FlyTo。
"""
from __future__ import annotations

import argparse
import json
import threading
import time
from typing import Any

import paho.mqtt.client as mqtt

from config import (
    DOCK_SN,
    MQTT_HOST,
    MQTT_PASSWORD,
    MQTT_PORT,
    MQTT_USERNAME,
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


FLY_TO_STATUS = {
    "command_pending": "正在发送",
    "command_accepted": "设备已受理",
    "command_unknown": "结果待确认",
    "wayline_progress": "执行中",
    "wayline_ok": "执行成功",
    "wayline_failed": "执行失败",
    "wayline_cancel": "设备已取消",
    "cancel_requested": "正在取消",
    "cancel_unknown": "取消结果待确认",
    "cancel_confirmed": "停止指令已确认",
    "cancel_failed": "停止失败",
}
MQTT_TERMINAL = {"wayline_ok", "wayline_failed", "wayline_cancel", "task_finish"}


class FlyToEventWatcher:
    """旁路打印设备原始 ``fly_to_point_progress``，终态仍以任务 ID 过滤。"""

    def __init__(self) -> None:
        self.events_topic = f"thing/product/{DOCK_SN}/events"
        self.expected_task_id = ""
        self.terminal = threading.Event()
        self.connected = threading.Event()
        self.subscribed = threading.Event()
        self._stopping = False
        self._path_printed_for = ""
        self.client = mqtt.Client(
            callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
            client_id=f"demo08_flyto_{int(time.time())}",
        )
        if MQTT_USERNAME:
            self.client.username_pw_set(MQTT_USERNAME, MQTT_PASSWORD)
        self.client.on_connect = self._on_connect
        self.client.on_subscribe = self._on_subscribe
        self.client.on_disconnect = self._on_disconnect
        self.client.on_message = self._on_message

    def start(self) -> bool:
        try:
            self.client.connect(MQTT_HOST, MQTT_PORT, keepalive=60)
            self.client.loop_start()
        except Exception as exc:
            print(f"[!] MQTT 旁路监听启动失败: {exc}；将使用 HTTP 状态接口监控")
            return False
        if not self.connected.wait(5):
            print("[!] MQTT 5 秒内未完成连接；将使用 HTTP 状态接口监控")
            return False
        if not self.subscribed.wait(5):
            print("[!] MQTT 订阅未确认；将使用 HTTP 状态接口监控")
            return False
        return True

    def set_task_id(self, task_id: str) -> None:
        self.expected_task_id = task_id

    def _on_connect(self, client, userdata, flags, reason_code, properties) -> None:
        if reason_code == 0:
            self.connected.set()
            client.subscribe(self.events_topic, qos=0)
        else:
            print(f"[!] MQTT 连接被拒绝: {reason_code}")

    def _on_subscribe(self, client, userdata, mid, reason_code_list, properties) -> None:
        self.subscribed.set()
        print(f"[✓] 已订阅原始进度事件 {self.events_topic}")

    def _on_disconnect(self, client, userdata, disconnect_flags, reason_code, properties) -> None:
        if not self._stopping and reason_code != 0:
            print(f"[!] MQTT 监听断开: {reason_code}；HTTP 状态轮询仍会继续")

    def _on_message(self, client, userdata, message) -> None:
        try:
            payload = json.loads(message.payload.decode("utf-8"))
            if payload.get("method") != "fly_to_point_progress":
                return
            data = payload.get("data") or {}
            if isinstance(data.get("output"), dict):
                data = data["output"]
            task_id = str(data.get("fly_to_id") or "")
            # 不接纳任务身份未知或迟到的旧任务事件。
            if not self.expected_task_id or task_id != self.expected_task_id:
                return
            self._print_progress(data)
        except Exception as exc:
            print(f"[!] 无法解析进度事件: {exc}")

    def _print_progress(self, data: dict[str, Any]) -> None:
        status = str(data.get("status") or "")
        points = data.get("planned_path_points") or []
        if points and self._path_printed_for != self.expected_task_id:
            self._path_printed_for = self.expected_task_id
            print(f"[MQTT轨迹] 设备规划了 {len(points)} 个轨迹点")
            for index, point in enumerate(points[:3]):
                print(
                    f"    #{index} lat={point.get('latitude')} "
                    f"lon={point.get('longitude')} h={point.get('height')}"
                )
            if len(points) > 3:
                print(f"    ... 其余 {len(points) - 3} 个已省略")
        details = [FLY_TO_STATUS.get(status, status or "未知状态")]
        if data.get("remaining_distance") is not None:
            details.append(f"剩余 {data.get('remaining_distance')} m")
        if data.get("remaining_time") is not None:
            details.append(f"预计 {data.get('remaining_time')} s")
        print(f"[MQTT进度] {'  '.join(details)}  result={data.get('result', 0)}")
        if status in MQTT_TERMINAL:
            self.terminal.set()

    def stop(self) -> None:
        self._stopping = True
        try:
            self.client.loop_stop()
            self.client.disconnect()
        except Exception:
            pass


def _baseline(token: str) -> tuple[dict[str, Any] | None, int]:
    state = get_point_flight_state(token)
    return state, int((state or {}).get("updated_at") or 0)


def _reconcile_after_command(
    token: str,
    baseline_version: int,
    *,
    ambiguous_error: DemoApiError | None = None,
) -> dict[str, Any] | None:
    state = wait_for_new_point_flight_state(
        token, "flyto", baseline_version, timeout=15
    )
    if state:
        label = "[恢复]" if ambiguous_error else "[受理]"
        print_point_flight_state(state, label)
        return state
    if ambiguous_error:
        print("[!!] 未能从状态接口确认本次 FlyTo；不要再次执行 go。")
        print("     继续查看 OSD/地图；确认未在飞行后，才可人工决定下一步。")
    else:
        print("[!] 指令返回成功但暂未取得任务 ID；不要重复下发，稍后运行 status 查询。")
    return None


def go(token: str, args: argparse.Namespace) -> int:
    lat, lon, height, speed = validate_target(
        args.lat, args.lon, args.height, args.speed, integer_speed=True
    )
    state, baseline_version = _baseline(token)
    if state and state.get("active") is True:
        print_point_flight_state(state, "[拒绝]")
        print("[✗] 已有点飞任务或结果待确认。先 status；FlyTo 可用 stop，禁止重复 go。")
        return 2

    print(
        f"[*] FlyTo 目标 WGS84=({lat:.7f}, {lon:.7f})，"
        f"相对高度={height:.1f}m，最大速度={speed:g}m/s"
    )
    if input("[!] 将真实改变飞行航迹。确认现场安全后输入 YES: ").strip() != "YES":
        print("[*] 已取消")
        return 0

    watcher = FlyToEventWatcher()
    watcher.start()
    try:
        seize_flight_authority(token)
        body = {
            "max_speed": int(speed),
            "points": [{"latitude": lat, "longitude": lon, "height": height}],
        }
        ambiguous: DemoApiError | None = None
        try:
            api_call(
                token,
                "POST",
                f"/control/api/v1/devices/{DOCK_SN}/jobs/fly-to-point",
                action="下发 FlyTo",
                json_body=body,
            )
            print("[✓] FlyTo HTTP 调用已返回成功；等待设备进度，不代表已到达")
        except DemoApiError as exc:
            print_error_and_hint(exc)
            if not exc.ambiguous:
                try:
                    print_point_flight_state(get_point_flight_state(token), "[服务端状态]")
                except DemoApiError:
                    pass
                return 1
            ambiguous = exc

        current = _reconcile_after_command(
            token, baseline_version, ambiguous_error=ambiguous
        )
        if not current:
            return 2
        task_id = point_flight_task_id(current)
        watcher.set_task_id(task_id)
        if point_flight_is_terminal(current):
            return 0 if current.get("result", 0) == 0 else 1
        final = wait_for_point_flight_terminal(
            token,
            "flyto",
            task_id,
            timeout=args.wait,
            terminal_event=watcher.terminal,
        )
        if final and (
            final.get("result", 0) != 0
            or final.get("status") in {"wayline_failed", "command_failed"}
        ):
            return 1
        return 0
    finally:
        watcher.stop()


def stop(token: str) -> int:
    state, baseline_version = _baseline(token)
    if not state or state.get("active") is not True:
        print_point_flight_state(state)
        print("[*] 当前没有活动点飞任务，无需发送停止指令")
        return 0
    if state.get("kind") != "flyto":
        print_point_flight_state(state, "[拒绝]")
        print("[✗] 活动任务不是 FlyTo；该 DELETE 接口不能用来取消一键起飞")
        return 2
    task_id = point_flight_task_id(state)
    if input(f"[!] 确认停止 FlyTo {task_id}？输入 YES: ").strip() != "YES":
        print("[*] 已取消")
        return 0

    seize_flight_authority(token)
    try:
        api_call(
            token,
            "DELETE",
            f"/control/api/v1/devices/{DOCK_SN}/jobs/fly-to-point",
            action="停止 FlyTo",
        )
        print("[✓] 停止指令已受理；设备确认后应为 cancel_confirmed/wayline_cancel")
    except DemoApiError as exc:
        print_error_and_hint(exc)
        # 停止接口可人工重试，但仍先查询这一次是否已被设备确认。
        if not exc.ambiguous:
            return 1

    current = wait_for_new_point_flight_state(
        token, "flyto", baseline_version, timeout=10
    )
    print_point_flight_state(current or get_point_flight_state(token), "[停止状态]")
    if current and current.get("active") is True:
        print("[!] 停止尚未确认；继续观察 OSD。确认仍在 FlyTo 时可再次运行 stop。")
        return 2
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="YOOX 指点飞行安全 Demo")
    parser.add_argument("action", nargs="?", choices=("go", "stop", "status", "auth"))
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
    action = args.action or input("输入 go / stop / status / auth / q: ").strip().lower()
    if action == "q":
        return 0
    if action == "status":
        print_point_flight_state(get_point_flight_state(token))
        return 0
    if action == "auth":
        seize_flight_authority(token)
        return 0
    if action == "go":
        return go(token, args)
    if action == "stop":
        return stop(token)
    print("[✗] 无效操作")
    return 2


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except DemoError as exc:
        print_error_and_hint(exc)
        raise SystemExit(1)
