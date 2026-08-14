"""
demo_18_continuous_landing.py -- DRC 持续下降，直到飞机进入待机状态。

该 Demo 模拟驾驶舱持续按住 Z：在当前 DRC 会话内以 10 Hz 发送
``drone_control``，固定向量为
``x=0, y=0, h=-speed, w=0``。默认 speed=4 m/s，与驾驶舱标准档一致。

安全闭环：
  1. 复用 demo_12 的当前会话双帧零杆探针，未就绪时禁止非零控制。
  2. 下降帧必须在当前 MQTT generation、当前 drc/up Topic 上收到严格关联的
     tid/bid 和显式 result=0；若固件正确回显 output.seq，也会一并核对。
  3. 首个成功 ACK 后 3 秒内，WebSocket OSD 必须出现负垂速或高度下降。
  4. mode_code=0（待机/已落地）时自动停止；断链、遥测过期、ACK 超时、
     joystick 失效、控制权变化或最长运行时间到达时立即停止并发送零杆量。

运行：
    ./run.sh demo_18_continuous_landing.py
    ./run.sh demo_18_continuous_landing.py --speed 1.0 --max-seconds 180

不要同时打开驾驶舱 DRC、demo_12 或另一个控制端。必须有现场飞手和可用的
实体遥控器；本脚本不能替代飞控自身的触地保护。
"""
from __future__ import annotations

import argparse
import json
import math
import threading
import time
import uuid
from dataclasses import dataclass
from urllib.parse import quote

import websocket

from config import DOCK_SN, DRONE_SN, WORKSPACE_ID, WS_URL
from demo_12_drc import DrcSession, drc_connect, drc_enter, drc_exit
from demo_common import (
    DemoError,
    login,
    print_error_and_hint,
    require_config,
    seize_flight_authority,
)


CONTROL_PERIOD_SECONDS = 0.1
CONTROL_ACK_TIMEOUT_SECONDS = 2.0
MOVEMENT_TIMEOUT_SECONDS = 3.0
OSD_STALE_SECONDS = 3.0
SCHEDULER_STALL_SECONDS = 0.35
MIN_DESCENT_SPEED = 0.3
MAX_DESCENT_SPEED = 4.0
MODE_LABELS = {
    0: "待机",
    3: "手动飞行",
    16: "虚拟摇杆",
    17: "指令飞行",
}


def finite_number(value) -> float | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


def exact_int(value) -> int | None:
    number = finite_number(value)
    if number is None or not number.is_integer():
        return None
    return int(number)


@dataclass(frozen=True)
class OsdSnapshot:
    received_at: float
    mode_code: int | None
    altitude: float | None
    vertical_speed: float | None


class AircraftOsdMonitor:
    """通过平台 WebSocket 监控目标飞机 OSD 和控制失效事件。"""

    def __init__(self, token: str) -> None:
        separator = "&" if "?" in WS_URL else "?"
        self.url = f"{WS_URL}{separator}x-auth-token={quote(token, safe='')}"
        self._lock = threading.RLock()
        self._opened = threading.Event()
        self._updated = threading.Event()
        self._stopping = False
        self._landing_active = False
        self._snapshot: OsdSnapshot | None = None
        self._failure = ""
        self._socket = websocket.WebSocketApp(
            self.url,
            on_open=self._on_open,
            on_message=self._on_message,
            on_error=self._on_error,
            on_close=self._on_close,
        )
        self._thread = threading.Thread(
            target=self._socket.run_forever,
            name="continuous-landing-osd",
            daemon=True,
        )

    def start(self) -> bool:
        print(f"[*] 连接 OSD WebSocket: {WS_URL}（token 不回显）")
        self._thread.start()
        if not self._opened.wait(8):
            print("[✗] 8 秒内未连接平台 WebSocket")
            return False
        if not self._updated.wait(12):
            print(f"[✗] 12 秒内未收到目标飞机 {DRONE_SN} 的 device_osd")
            return False
        return True

    def stop(self) -> None:
        self._stopping = True
        self._landing_active = False
        try:
            self._socket.close()
        except (OSError, RuntimeError, TypeError, ValueError):
            pass
        if self._thread.is_alive() and self._thread is not threading.current_thread():
            self._thread.join(timeout=1.0)

    def set_landing_active(self, active: bool) -> None:
        with self._lock:
            self._landing_active = active
            if active:
                self._failure = ""

    def snapshot(self) -> OsdSnapshot | None:
        with self._lock:
            return self._snapshot

    def failure(self) -> str:
        with self._lock:
            return self._failure

    def _fail(self, message: str) -> None:
        with self._lock:
            if self._landing_active and not self._failure:
                self._failure = message

    def _on_open(self, ws) -> None:
        print("[✓] OSD WebSocket 已连接")
        self._opened.set()

    def _on_message(self, ws, raw_message) -> None:
        try:
            message = json.loads(raw_message)
            if not isinstance(message, dict):
                return
            biz_code = str(message.get("biz_code") or "")
            data = message.get("data")
            data = data if isinstance(data, dict) else {}
            host = data.get("host")
            host = host if isinstance(host, dict) else {}
            event_sn = str(data.get("sn") or host.get("sn") or "")

            if biz_code == "device_osd" and event_sn == DRONE_SN:
                altitude = finite_number(host.get("height"))
                if altitude is None:
                    altitude = finite_number(host.get("elevation"))
                with self._lock:
                    previous = self._snapshot
                    mode_code = exact_int(host.get("mode_code"))
                    vertical_speed = finite_number(host.get("vertical_speed"))
                    snapshot = OsdSnapshot(
                        received_at=time.monotonic(),
                        mode_code=(
                            mode_code
                            if mode_code is not None
                            else previous.mode_code if previous else None
                        ),
                        altitude=(
                            altitude
                            if altitude is not None
                            else previous.altitude if previous else None
                        ),
                        vertical_speed=(
                            vertical_speed
                            if vertical_speed is not None
                            else previous.vertical_speed if previous else None
                        ),
                    )
                    self._snapshot = snapshot
                self._updated.set()
                return

            if biz_code == "joystick_invalid_notify":
                reason = data.get("reason")
                self._fail(f"Joystick 已失效（reason={reason}）")
            elif biz_code == "device_offline" and event_sn in {DOCK_SN, DRONE_SN}:
                self._fail(f"设备离线（sn={event_sn}）")
            elif biz_code == "control_source_change":
                source = str(data.get("control_source") or data.get("controlSource") or "")
                if exact_int(data.get("type")) == 1 and source.upper() == "B":
                    self._fail("飞行控制权已转移到 B 控")
        except (json.JSONDecodeError, TypeError, ValueError):
            return

    def _on_error(self, ws, error) -> None:
        self._fail(f"OSD WebSocket 错误：{error}")

    def _on_close(self, ws, close_status_code, close_message) -> None:
        if not self._stopping:
            self._fail(
                f"OSD WebSocket 已断开（{close_status_code} {close_message or ''}）"
            )


@dataclass(frozen=True)
class PendingLandingFrame:
    generation: int
    request_id: str
    seq: int
    published_at_ms: int


class ContinuousLandingSession(DrcSession):
    """在 DrcSession 上增加严格下降 ACK 关联和失联停止状态。"""

    def __init__(self, broker: dict, acl: dict) -> None:
        super().__init__(broker, acl)
        self._landing_active = False
        self._landing_pending: dict[int, PendingLandingFrame] = {}
        self._landing_failure = ""
        self._first_landing_ack_at: float | None = None
        self._last_landing_ack_at: float | None = None
        self._seq_compatibility_warned = False

    def begin_landing(self) -> None:
        with self._state_lock:
            self._landing_active = True
            self._landing_pending = {}
            self._landing_failure = ""
            self._first_landing_ack_at = None
            self._last_landing_ack_at = None
            self._seq_compatibility_warned = False

    def end_landing(self) -> None:
        with self._state_lock:
            self._landing_active = False
            self._landing_pending = {}

    def landing_status(self) -> tuple[str, float | None, float | None]:
        with self._state_lock:
            return (
                self._landing_failure,
                self._first_landing_ack_at,
                self._last_landing_ack_at,
            )

    def send_descent(self, speed: float) -> bool:
        vector = (0, 0, -speed, 0)
        now_ms = int(time.time() * 1000)
        with self._state_lock:
            if not self._landing_active:
                return False
            if not self.controls_ready.is_set():
                self._landing_failure = "DRC 控制链路已锁定"
                return False
            if not self.client or not self.client.is_connected():
                self._landing_failure = "DRC MQTT 已断开"
                return False
            generation = self._generation
            seq = self._next_control_seq_locked(vector)
            request_id = uuid.uuid4().hex[:16]
            frame = PendingLandingFrame(generation, request_id, seq, now_ms)
            self._landing_pending[seq] = frame
            self._landing_pending = {
                pending_seq: pending
                for pending_seq, pending in self._landing_pending.items()
                if now_ms - pending.published_at_ms <= 3_000
            }
            published = self.publish(
                "drone_control",
                {
                    "seq": seq,
                    "x": 0,
                    "y": 0,
                    "h": -speed,
                    "w": 0,
                    "freq": 10,
                    "delay_time": 300,
                },
                request_id=request_id,
                quiet=True,
            )
            if not published:
                self._landing_pending.pop(seq, None)
                self._landing_failure = "下降控制帧发布失败"
                return False
        return True

    def publish_zero(self) -> None:
        """不依赖 controls_ready，尽力立即发送一帧零杆量。"""
        with self._state_lock:
            if not self.client or not self.client.is_connected():
                print("[!] DRC MQTT 已断开，无法立即发送零杆量")
                return
            seq = self._next_control_seq_locked((0, 0, 0, 0))
            request_id = self.publish(
                "drone_control",
                {
                    "seq": seq,
                    "x": 0,
                    "y": 0,
                    "h": 0,
                    "w": 0,
                    "freq": 10,
                    "delay_time": 300,
                },
                quiet=True,
            )
        if request_id:
            print(f"[DRC↓] 持续降落停止，已发送零杆量 seq={seq}")

    def _handle_control_ack(
        self,
        payload: dict,
        data: dict,
        output: dict,
        result,
        received_at_ms: int,
    ) -> None:
        reply_ids = list(dict.fromkeys(self._reply_ids(payload)))

        # 部分 EVO RC 固件会正确回显 tid/bid，却把 output.seq 固定回成 0。
        # 零杆探针优先使用强随机 request ID 严格关联；完全无 ID 时才回退到
        # demo_12 基类的 output.seq 严格校验，绝不只凭 result=0 解锁。
        if not self.controls_ready.is_set():
            if reply_ids:
                with self._state_lock:
                    matching = [
                        probe
                        for probe in self._pending_probes.values()
                        if all(reply_id == probe.request_id for reply_id in reply_ids)
                    ]
                    if len(matching) != 1:
                        return
                    probe = matching[0]
                    if (
                        probe.generation != self._generation
                        or received_at_ms < probe.published_at_ms
                        or received_at_ms - probe.published_at_ms > self.PROBE_ACK_WINDOW_MS
                    ):
                        return
                    if result != 0:
                        shown = "缺失" if result is None else result
                        self._pending_probes = {}
                        self._probe_ack_stage = 0
                        print(
                            f"[!] 零杆探针回包 result={shown}，保持非零控制锁定"
                        )
                        return
                    if probe.handshake_step != self._probe_ack_stage:
                        return
                    reported_seq = self._exact_int(output.get("seq"))
                    if (
                        reported_seq != probe.seq
                        and not self._seq_compatibility_warned
                    ):
                        self._seq_compatibility_warned = True
                        print(
                            "[!] RC 回显的 output.seq 与下发值不一致；"
                            "本会话改用严格 tid/bid 关联 ACK"
                        )
                    if probe.handshake_step == 0:
                        self._pending_probes.pop(probe.seq, None)
                        self._probe_ack_stage = 1
                        return
                    self._pending_probes = {}
                    self._probe_ack_stage = 0
                    self.controls_ready.set()
                print(
                    "[✓] 当前 DRC 会话控制链路已就绪"
                    f"（严格 tid/bid 双帧零杆探针末帧 seq={probe.seq}）"
                )
                return
            super()._handle_control_ack(payload, data, output, result, received_at_ms)
            return

        with self._state_lock:
            matching = [
                frame
                for frame in self._landing_pending.values()
                if reply_ids
                and all(reply_id == frame.request_id for reply_id in reply_ids)
            ]
            frame = matching[0] if len(matching) == 1 else None
            if not self._landing_active or frame is None:
                super()._handle_control_ack(payload, data, output, result, received_at_ms)
                return
            if (
                frame.generation != self._generation
                or received_at_ms < frame.published_at_ms
                or received_at_ms - frame.published_at_ms > 2_000
            ):
                return
            if result != 0:
                shown = "缺失" if result is None else result
                self._landing_failure = (
                    f"设备拒绝下降控制帧（seq={frame.seq}, result={shown}）"
                )
                self.controls_ready.clear()
                return
            reported_seq = self._exact_int(output.get("seq"))
            if reported_seq != frame.seq and not self._seq_compatibility_warned:
                self._seq_compatibility_warned = True
                print(
                    "[!] RC 回显的 output.seq 与下发值不一致；"
                    "下降 ACK 继续按严格 tid/bid 关联"
                )
            acknowledged_at = time.monotonic()
            if self._first_landing_ack_at is None:
                self._first_landing_ack_at = acknowledged_at
                print(
                    f"[✓] 首个下降控制帧已获严格关联 ACK "
                    f"seq={frame.seq} tid={frame.request_id}"
                )
            self._last_landing_ack_at = acknowledged_at
            self._landing_pending.pop(frame.seq, None)

    def _on_disconnect(
        self, client, userdata, disconnect_flags, reason_code, properties
    ) -> None:
        with self._state_lock:
            if self._landing_active and not self._landing_failure:
                self._landing_failure = f"DRC MQTT 断开（{reason_code}）"
        super()._on_disconnect(
            client, userdata, disconnect_flags, reason_code, properties
        )

    def _on_connect(self, client, userdata, flags, reason_code, properties) -> None:
        with self._state_lock:
            if self._landing_active and not self._landing_failure:
                self._landing_failure = "DRC MQTT 发生重连，旧持续降落会话已失效"
        super()._on_connect(client, userdata, flags, reason_code, properties)


def mode_text(mode_code: int | None) -> str:
    if mode_code is None:
        return "未知"
    return f"{MODE_LABELS.get(mode_code, '其他状态')}(mode={mode_code})"


def validate_args(args: argparse.Namespace) -> None:
    if not MIN_DESCENT_SPEED <= args.speed <= MAX_DESCENT_SPEED:
        raise DemoError(
            f"--speed 必须在 {MIN_DESCENT_SPEED}–{MAX_DESCENT_SPEED} m/s"
        )
    if not 10 <= args.max_seconds <= 600:
        raise DemoError("--max-seconds 必须在 10–600 秒")


def require_fresh_airborne_osd(monitor: AircraftOsdMonitor) -> OsdSnapshot:
    snapshot = monitor.snapshot()
    if snapshot is None or time.monotonic() - snapshot.received_at > OSD_STALE_SECONDS:
        raise DemoError("目标飞机 OSD 不新鲜，拒绝进入持续降落")
    if snapshot.mode_code == 0:
        raise DemoError("飞机已处于待机状态，不需要持续降落")
    return snapshot


def run_continuous_landing(
    session: ContinuousLandingSession,
    monitor: AircraftOsdMonitor,
    speed: float,
    max_seconds: int,
) -> tuple[bool, str]:
    initial = require_fresh_airborne_osd(monitor)
    initial_altitude = initial.altitude
    started_at = time.monotonic()
    next_publish_at = started_at
    movement_observed = False
    last_status_at = 0.0

    session.begin_landing()
    monitor.set_landing_active(True)
    print(
        f"[DRC↓] 开始持续下降：x=0 y=0 h={-speed:g} w=0，10 Hz；"
        "进入 mode=0 后自动停止"
    )

    while True:
        now = time.monotonic()
        snapshot = monitor.snapshot()
        osd_failure = monitor.failure()
        drc_failure, first_ack_at, last_ack_at = session.landing_status()

        if osd_failure:
            return False, osd_failure
        if drc_failure:
            return False, drc_failure
        if not session.controls_ready.is_set():
            return False, "DRC 控制链路失去就绪状态"
        if snapshot is None or now - snapshot.received_at > OSD_STALE_SECONDS:
            return False, "超过 3 秒未收到新鲜飞机 OSD"
        if snapshot.mode_code == 0:
            return True, "飞机已进入待机状态（mode=0），判定已经落地"
        if now - started_at >= max_seconds:
            return False, f"达到安全运行上限 {max_seconds} 秒"

        if now >= next_publish_at:
            if now - next_publish_at > SCHEDULER_STALL_SECONDS:
                return False, "控制循环卡顿超过 350 ms"
            if not session.send_descent(speed):
                failure, _, _ = session.landing_status()
                return False, failure or "下降控制帧未能发布"
            # 不补发因调度延迟错过的历史周期，避免恢复后出现超过 10 Hz 的突发包。
            next_publish_at = now + CONTROL_PERIOD_SECONDS

        if first_ack_at is None:
            if now - started_at >= CONTROL_ACK_TIMEOUT_SECONDS:
                return False, "2 秒内未收到严格关联的下降控制 ACK"
        else:
            if last_ack_at is None or now - last_ack_at >= CONTROL_ACK_TIMEOUT_SECONDS:
                return False, "连续 2 秒未收到当前下降控制 ACK"
            altitude_drop = (
                initial_altitude - snapshot.altitude
                if initial_altitude is not None and snapshot.altitude is not None
                else 0.0
            )
            descending = (
                snapshot.vertical_speed is not None
                and snapshot.vertical_speed < -0.15
            ) or altitude_drop >= 0.15
            if descending and not movement_observed:
                movement_observed = True
                print(
                    "[✓] OSD 已检测到飞机下降："
                    f"V={snapshot.vertical_speed} m/s，高度下降={altitude_drop:.2f} m"
                )
            if not movement_observed and now - first_ack_at >= MOVEMENT_TIMEOUT_SECONDS:
                return False, "设备返回 result=0，但 3 秒内 OSD 未检测到下降"

        if now - last_status_at >= 1.0:
            last_status_at = now
            print(
                f"[状态] {mode_text(snapshot.mode_code)} "
                f"高度={snapshot.altitude}m V={snapshot.vertical_speed}m/s "
                f"ACK={'正常' if last_ack_at is not None else '等待'}"
            )
        time.sleep(0.02)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="通过 DRC 以 10 Hz 持续发送下降杆量，进入待机后自动停止"
    )
    parser.add_argument(
        "--speed",
        type=float,
        default=4.0,
        help="正数下降速度，范围 0.3–4.0 m/s；实际下发 h=-speed（默认 4）",
    )
    parser.add_argument(
        "--max-seconds",
        type=int,
        default=300,
        help="最长持续发送时间，范围 10–600 秒（默认 300）",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    validate_args(args)
    require_config(
        YOOX_DOCK_SN=DOCK_SN,
        YOOX_DRONE_SN=DRONE_SN,
        YOOX_WORKSPACE_ID=WORKSPACE_ID,
    )
    print(f"[*] 网关/遥控器 SN: {DOCK_SN}")
    print(f"[*] 无人机 SN: {DRONE_SN}")
    print(f"[*] 下降控制量: h={-args.speed:g} m/s")
    print("[!] 不要同时运行驾驶舱 DRC 或其他 DRC Demo。现场飞手必须持有实体遥控器。\n")

    token = login()
    monitor = AircraftOsdMonitor(token)
    if not monitor.start():
        monitor.stop()
        return 1
    initial = require_fresh_airborne_osd(monitor)
    print(
        f"[✓] 当前 OSD: {mode_text(initial.mode_code)} "
        f"高度={initial.altitude}m V={initial.vertical_speed}m/s"
    )
    if initial.mode_code not in {16, 17}:
        print(
            "[!] 当前未上报虚拟摇杆/指令飞行 mode=16/17；部分 RC 固件在 "
            f"{mode_text(initial.mode_code)} 下仍接受 DRC，但若不运动将于 3 秒后归零。"
        )

    if input("[!!] 确认抢占飞行权并建立独占 DRC 会话？输入 YES: ").strip() != "YES":
        print("[*] 已取消")
        monitor.stop()
        return 0

    broker = None
    client_id = ""
    session: ContinuousLandingSession | None = None
    enter_attempted = False
    landing_started = False
    try:
        seize_flight_authority(token, DOCK_SN)
        broker = drc_connect(token)
        client_id = str(broker.get("client_id") or "")
        enter_attempted = True
        acl = drc_enter(token, client_id)
        session = ContinuousLandingSession(broker, acl)
        if not session.connect():
            return 1
        session.start_heartbeat_loop()
        if not session.controls_ready.wait(5):
            raise DemoError("当前 DRC 会话未通过双帧零杆探针，拒绝发送下降量")

        before_start = require_fresh_airborne_osd(monitor)
        print(
            f"[✓] DRC 控制链路就绪；当前 {mode_text(before_start.mode_code)}，"
            f"高度={before_start.altitude}m"
        )
        if input("[!!!] 确认开始持续下降？输入 LAND: ").strip() != "LAND":
            print("[*] 已取消持续降落")
            return 0

        landing_started = True
        try:
            landed, reason = run_continuous_landing(
                session, monitor, args.speed, args.max_seconds
            )
        except KeyboardInterrupt:
            landed, reason = False, "操作者按下 Ctrl+C"
        if landed:
            print(f"\n[✓] {reason}")
            return 0
        print(f"\n[✗] 持续降落已安全停止：{reason}")
        return 2
    finally:
        monitor.set_landing_active(False)
        if session:
            try:
                if landing_started:
                    session.end_landing()
                    session.publish_zero()
                    time.sleep(0.15)
                session.disconnect()
            except Exception as exc:
                print(f"[!] 本地 DRC 清理异常，将继续请求服务端退出：{exc}")
        if enter_attempted and client_id:
            try:
                drc_exit(token, client_id)
            except DemoError as exc:
                print_error_and_hint(exc)
        monitor.stop()


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except DemoError as exc:
        print_error_and_hint(exc)
        raise SystemExit(1)
