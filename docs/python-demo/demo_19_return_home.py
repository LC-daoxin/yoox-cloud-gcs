"""一键返航 / 取消返航：显式抢权、单次下发、OSD 旁路监视与模式确认。

与 Web 控制台一致：先 ``flight_authority_grab``，再调用
``POST /control/api/v1/devices/{sn}/jobs/return_home``（或
``return_home_cancel``）。HTTP ``code=0`` 只代表设备接受调用，不代表已返航或
落地；脚本会通过 MQTT 旁路监视飞机 OSD 的 ``mode_code``：

- 一键返航：期望 mode_code 进入 ``9``（RETURN_AUTO），落地后回到 ``0``；
- 取消返航：期望 mode_code 离开 ``9`` 转为悬停（``3`` MANUAL 等）。

旁路还会打印 ``events`` 中的 ``return_home_info``（如固件上报）。
客户端超时绝不自动重发；先核对 mode_code 与现场状态。

运行：
    ./run.sh demo_19_return_home.py rth     # 一键返航
    ./run.sh demo_19_return_home.py cancel  # 取消返航（原地悬停）
    ./run.sh demo_19_return_home.py watch   # 只监视当前模式，不下发指令
"""
from __future__ import annotations

import argparse
import json
import threading
import time

import paho.mqtt.client as mqtt

from config import DOCK_SN, DRONE_SN, MQTT_HOST, MQTT_PASSWORD, MQTT_PORT, MQTT_USERNAME
from demo_common import (
    DemoApiError,
    DemoError,
    api_call,
    login,
    print_error_and_hint,
    require_config,
    seize_flight_authority,
)

# 飞机模式码（DroneModeCodeEnum），仅用于本 Demo 的可读输出。
MODE_NAMES = {
    0: "IDLE(待机)",
    1: "起飞前检查",
    2: "起飞完成",
    3: "MANUAL(手动)",
    4: "自动起飞",
    5: "WAYLINE(航线)",
    9: "RETURN_AUTO(自动返航)",
    10: "自动降落",
    11: "LANDING_MODE_11",
    16: "DRC(虚拟摇杆)",
    37: "指点飞行",
    39: "KML 航线",
}

RETURN_MODE = 9
WATCH_DEFAULT_SECONDS = 300


class DroneOsdWatcher:
    """旁路订阅飞机 OSD 与 events，实时打印返航相关状态。"""

    def __init__(self) -> None:
        self.topic_osd = (
            f"thing/product/{DRONE_SN}/osd" if DRONE_SN else "thing/product/+/osd"
        )
        self.topic_events = f"thing/product/{DOCK_SN}/events"
        self.connected = threading.Event()
        self.subscribed = threading.Event()
        self.mode_code: int | None = None
        self.mode_updated_at = 0.0
        self.saw_return_auto = threading.Event()
        self._stopping = False
        self.client = mqtt.Client(
            callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
            client_id=f"demo19_rth_{int(time.time())}",
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
            print(f"[!] MQTT 旁路监听启动失败: {exc}；仅按 HTTP 结果与现场观察")
            return False
        if not self.connected.wait(5) or not self.subscribed.wait(5):
            print("[!] MQTT 5 秒内未连接或订阅；仅按 HTTP 结果与现场观察")
            return False
        return True

    def stop(self) -> None:
        self._stopping = True
        try:
            self.client.loop_stop()
            self.client.disconnect()
        except Exception:
            pass

    def _on_connect(self, client, userdata, flags, reason_code, properties) -> None:
        if reason_code == 0:
            self.connected.set()
            client.subscribe(self.topic_osd, qos=0)
            client.subscribe(self.topic_events, qos=0)
        else:
            print(f"[!] MQTT 连接被拒绝: {reason_code}")

    def _on_subscribe(self, client, userdata, mid, reason_code_list, properties) -> None:
        self.subscribed.set()
        print(f"[✓] 已订阅 OSD({self.topic_osd}) 与 events")

    def _on_disconnect(self, client, userdata, disconnect_flags, reason_code, properties) -> None:
        if not self._stopping and reason_code != 0:
            print(f"[!] MQTT 监听断开: {reason_code}")

    def _on_message(self, client, userdata, message) -> None:
        try:
            payload = json.loads(message.payload.decode("utf-8"))
        except Exception:
            return
        method = payload.get("method")
        topic = message.topic
        if method == "return_home_info":
            data = payload.get("data") or {}
            print(f"[MQTT返航] return_home_info: {json.dumps(data, ensure_ascii=False)}")
            return
        if topic.endswith("/osd"):
            data = payload.get("data") or {}
            mode = data.get("mode_code")
            if mode is None:
                return
            if self.mode_code != mode:
                self.mode_updated_at = time.monotonic()
                label = MODE_NAMES.get(mode, f"未知({mode})")
                gps = data.get("position_state") or {}
                print(
                    f"[OSD] mode_code={mode} {label}  "
                    f"高度={data.get('height')}m 返航距离={data.get('home_distance')}m "
                    f"GPS={gps.get('gps_number', 0)}卫星"
                )
                if mode == RETURN_MODE:
                    self.saw_return_auto.set()
            self.mode_code = mode


def _describe_mode(mode: int | None) -> str:
    if mode is None:
        return "未收到 OSD"
    return MODE_NAMES.get(mode, f"未知({mode})")


def _send_return_job(token: str, method: str, label: str) -> None:
    seize_flight_authority(token)
    api_call(
        token,
        "POST",
        f"/control/api/v1/devices/{DOCK_SN}/jobs/{method}",
        action=label,
        timeout=25,
    )
    print(f"[✓] {label}指令已被设备接受（result=0），但**不代表已返航或悬停**")


def rth(args: argparse.Namespace) -> int:
    if input("[!] 一键返航将真实改变航迹（飞回返航点）。输入 YES 确认: ").strip() != "YES":
        print("[*] 已取消")
        return 0
    watcher = DroneOsdWatcher()
    watcher_started = watcher.start()
    try:
        _send_return_job(token_login(), "return_home", "一键返航")
        if not watcher_started:
            print("[!] 无 MQTT 旁路：请通过 Web 控制台/现场确认返航进度")
            return 0
        _wait_modes(watcher, args.wait, expect_return=True)
        return 0
    finally:
        watcher.stop()


def cancel(args: argparse.Namespace) -> int:
    if input("[!] 取消返航：飞机将退出返航并原地悬停。输入 YES 确认: ").strip() != "YES":
        print("[*] 已取消")
        return 0
    watcher = DroneOsdWatcher()
    watcher_started = watcher.start()
    try:
        _send_return_job(token_login(), "return_home_cancel", "取消返航")
        if not watcher_started:
            print("[!] 无 MQTT 旁路：请通过 Web 控制台/现场确认已悬停")
            return 0
        _wait_modes(watcher, args.wait, expect_return=False)
        return 0
    finally:
        watcher.stop()


def watch(args: argparse.Namespace) -> int:
    watcher = DroneOsdWatcher()
    if not watcher.start():
        return 1
    print(f"[*] 监视飞机模式（最长 {args.wait}s；Ctrl+C 退出）")
    try:
        deadline = time.monotonic() + args.wait
        while time.monotonic() < deadline:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\n[*] 已停止监视")
    finally:
        watcher.stop()
    return 0


def _wait_modes(watcher: DroneOsdWatcher, timeout: int, *, expect_return: bool) -> None:
    """监视 mode_code 变化直到确认或超时；超时只提示，不重发。"""
    goal = "确认 mode_code=9(RETURN_AUTO)，落地后回到 0" if expect_return else \
        "确认 mode_code 离开 9，转为悬停"
    print(f"[*] {goal}（最长 {timeout}s）")
    deadline = time.monotonic() + timeout
    try:
        while time.monotonic() < deadline:
            time.sleep(0.5)
            if expect_return:
                if watcher.saw_return_auto.is_set() and watcher.mode_code == 0:
                    print("[✓] 已观测到返航完成（mode_code 回到 0/待机），请以现场为准")
                    return
                if watcher.saw_return_auto.is_set():
                    print(f"[OSD] 返航进行中，当前 mode={_describe_mode(watcher.mode_code)}")
                    # 每 10 秒打印一次进度，避免刷屏。
                    time.sleep(10)
            else:
                if watcher.mode_code is not None and watcher.mode_code != RETURN_MODE \
                        and watcher.mode_updated_at > 0:
                    print(f"[✓] 已退出返航模式，当前 mode={_describe_mode(watcher.mode_code)}")
                    return
    except KeyboardInterrupt:
        print("\n[!] 已停止监视；指令可能仍在执行，继续观察现场")
    print("[!] 等待超时；这不等于失败，请核对 mode_code/现场，勿重复下发")


_TOKEN: str | None = None


def token_login() -> str:
    global _TOKEN
    if _TOKEN is None:
        _TOKEN = login()
    return _TOKEN


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="YOOX 一键返航 / 取消返航 Demo")
    parser.add_argument("action", nargs="?", choices=("rth", "cancel", "watch"))
    parser.add_argument("--wait", type=int, default=WATCH_DEFAULT_SECONDS,
                        help="模式确认最长等待秒数（默认 300）")
    return parser


def main() -> int:
    args = build_parser().parse_args()
    require_config(YOOX_DOCK_SN=DOCK_SN)
    if not DRONE_SN or DRONE_SN.upper() == "YOUR_DRONE_SN":
        print("[!] 未配置 YOOX_DRONE_SN，将通配订阅所有 OSD（多机时请配置）")
    action = args.action or input("输入 rth / cancel / watch / q: ").strip().lower()
    if action == "q":
        return 0
    if action == "rth":
        return rth(args)
    if action == "cancel":
        return cancel(args)
    if action == "watch":
        return watch(args)
    print("[✗] 无效操作")
    return 2


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except DemoError as exc:
        print_error_and_hint(exc)
        raise SystemExit(1)
