"""
demo_12_drc.py —— DRC 指令飞行控制模式

流程：
  1. drc/connect  → 获取 DRC 专用 MQTT 凭证（broker地址/账号/token）
  2. drc/enter    → 让设备进入 DRC 模式，返回 pub/sub Topic
     pub = thing/product/{sn}/drc/down（云→机）
     sub = thing/product/{sn}/drc/up  （机→云）
  3. 等待 CONNACK + SUBACK，立即发送心跳与连续两帧零杆探针；校验通过后
     才允许向 pubTopic 发送非零摇杆，DRC 应急指令按现场预案单独确认
  4. drc/exit     → 退出 DRC 模式

DRC 下行指令（thing/product/{sn}/drc/down）：
  | method                | data                          | 说明                        |
  |-----------------------|-------------------------------|-----------------------------|
  | heart_beat            | {seq, timestamp}              | 心跳，必须 1 秒一次         |
  | drone_control         | {seq,x,y,h,w,freq,delay_time} | 摇杆飞行控制                |
  | drone_emergency_stop  | {}                            | 急停（立即刹停并悬停）      |

DRC 上行消息（thing/product/{sn}/drc/up）：
  | method        | 说明                                              |
  |---------------|---------------------------------------------------|
  | heart_beat    | 原样回显下发的 timestamp，差值即往返时延（ping）   |
  | hsi_info_push | 避障信息，上下左右前后共 16 路雷达距离（hsi）      |

事件通知（thing/product/{sn}/events）：
  joystick_invalid_notify —— Joystick 失效，drone_control 不再生效，无法手动操控
  reason: 0=遥控器失联 1=低电量返航 2=低电量降落 3=靠近限飞区 4=遥控器夺权

前提：
  - 无人机已在空中（elevation > 0），已获取飞行控制权
  - paho-mqtt 已安装：pip install paho-mqtt

运行：
    python3 demo_12_drc.py
"""
import json
import math
import time
import uuid
import threading
from dataclasses import dataclass
from urllib.parse import urlparse

import paho.mqtt.client as mqtt
from config import (DOCK_SN, WORKSPACE_ID,
                    MQTT_HOST, MQTT_PORT, MQTT_USERNAME, MQTT_PASSWORD)
from demo_common import (
    DemoApiError,
    DemoError,
    api_call,
    login,
    print_error_and_hint,
    require_config,
)


# joystick_invalid_notify 的 reason 取值
JOYSTICK_INVALID_REASON = {
    0: "遥控器失联",
    1: "低电量返航",
    2: "低电量降落",
    3: "靠近限飞区",
    4: "遥控器夺权（如 B 控触发返航）",
}


@dataclass(frozen=True)
class PendingControlProbe:
    generation: int
    request_id: str
    seq: int
    handshake_step: int
    published_at_ms: int


def start_side_watcher():
    """
    连主 MQTT Broker 旁路监听设备事件 Topic。

      thing/product/{sn}/events
        joystick_invalid_notify —— Joystick 失效通知。
        一旦收到，drone_control 将不再生效，无法手动操控。

    注意：events 消息带 need_reply=1，由服务端负责回 events_reply，
    本脚本只旁路观察，不要重复回复。
    """
    events_topic = f"thing/product/{DOCK_SN}/events"

    def _on_connect(client, userdata, flags, reason_code, properties):
        if reason_code == 0:
            client.subscribe(events_topic)
            print(f"[✓] 已订阅事件 Topic: {events_topic}")
        else:
            print(f"[!] 旁路监听连接失败 {reason_code}（不影响指令下发）")

    def _on_message(client, userdata, msg):
        try:
            payload = json.loads(msg.payload)
            method = payload.get("method", "")
            data = payload.get("data", {}) or {}

            if method == "joystick_invalid_notify":
                reason = data.get("reason")
                desc = JOYSTICK_INVALID_REASON.get(reason, "未知原因")
                print(f"\n[!!] Joystick 已失效: reason={reason} {desc}")
                print("     drone_control 不再生效，手动操控不可用。")
                print("     处理完原因后需重新 drc/enter 才能恢复。")
                return

        except Exception:
            pass

    client = mqtt.Client(
        callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
        client_id=f"demo12_watch_{int(time.time())}",
    )
    if MQTT_USERNAME:
        client.username_pw_set(MQTT_USERNAME, MQTT_PASSWORD)
    client.on_connect = _on_connect
    client.on_message = _on_message
    try:
        client.connect(MQTT_HOST, MQTT_PORT, keepalive=60)
        client.loop_start()
        return client
    except Exception as e:
        print(f"[!] 旁路监听启动失败: {e}（不影响指令下发）")
        return None


def drc_connect(token) -> dict:
    """步骤1：获取 DRC 专用 MQTT 连接凭证"""
    body = {"client_id": "", "expire_sec": 3600}
    result = api_call(
        token, "POST",
        f"/control/api/v1/workspaces/{WORKSPACE_ID}/drc/connect",
        action="获取 DRC MQTT 凭证", json_body=body, timeout=15,
    )
    broker = result.get("data") or {}
    if not isinstance(broker, dict) or not broker.get("client_id"):
        raise DemoApiError("获取 DRC MQTT 凭证", "响应缺少 client_id")
    print("[✓] DRC MQTT 凭证获取成功")
    print(f"    broker  : {broker.get('address')}")
    print(f"    clientId: {broker.get('client_id')}")
    return broker


def drc_enter(token, client_id: str) -> dict:
    """步骤2：让设备进入 DRC 模式，获取 pub/sub Topic"""
    body = {"dock_sn": DOCK_SN, "client_id": client_id}
    result = api_call(
        token, "POST",
        f"/control/api/v1/workspaces/{WORKSPACE_ID}/drc/enter",
        action="进入 DRC 模式", json_body=body, timeout=25,
    )
    acl = result.get("data") or {}
    if not isinstance(acl, dict) or not acl.get("pub") or not acl.get("sub"):
        raise DemoApiError("进入 DRC 模式", "响应缺少 pub/sub ACL")
    print("[✓] 已进入 DRC 模式")
    print(f"    pubTopic: {acl.get('pub', [])}")
    print(f"    subTopic: {acl.get('sub', [])}")
    return acl


def drc_exit(token, client_id: str):
    """步骤4：退出 DRC 模式"""
    body = {"dock_sn": DOCK_SN, "client_id": client_id}
    api_call(
        token, "POST",
        f"/control/api/v1/workspaces/{WORKSPACE_ID}/drc/exit",
        action="退出 DRC 模式", json_body=body, timeout=20,
    )
    print("[✓] 已退出 DRC 模式")


def build_drc_msg(method: str, data: dict = None, request_id: str = None):
    """构造 DRC MQTT 消息，并返回消息体及可用于匹配 ACK 的 request_id。"""
    request_id = request_id or uuid.uuid4().hex[:16]
    msg = {
        "tid": request_id,
        "bid": request_id,
        "timestamp": int(time.time() * 1000),
        "method": method,
        "data": data or {}
    }
    return json.dumps(msg), request_id


class DrcSession:
    """封装 DRC MQTT 连接和基础控制指令"""

    HSI_WARN_M = 5.0         # 障碍物近于此距离时告警（米）
    HSI_WARN_INTERVAL = 2.0  # 告警限频，避免刷屏（秒）
    CONTROL_INTERVAL_SECONDS = 0.1  # freq=10 Hz
    PROBE_ACK_WINDOW_MS = 1500

    def __init__(self, broker: dict, acl: dict):
        self.broker = broker
        pub_topics = self._flatten_acl_topics(acl.get("pub"))
        self.sub_topics = self._flatten_acl_topics(acl.get("sub"))
        expected_down = f"thing/product/{DOCK_SN}/drc/down"
        self.pub_topic = (expected_down if expected_down in pub_topics else
                          next((topic for topic in pub_topics if topic.endswith("/drc/down")), None))
        expected_up = self.pub_topic[:-5] + "/up" if self.pub_topic else ""
        self.sub_topic = (expected_up if expected_up in self.sub_topics else
                          next((topic for topic in self.sub_topics if topic.endswith("/drc/up")), None))
        self.services_reply_topics = {
            topic for topic in self.sub_topics if topic.endswith("/services_reply")
        }
        if not self.pub_topic or not self.sub_topic:
            raise DemoError("DRC ACL 缺少可用的 drc/down 或对应 drc/up Topic")
        self.client = None
        self._running = True
        self.last_hsi = {}       # 最近一次 hsi_info_push 完整数据
        self.last_rtt_ms = None  # 最近一次心跳往返时延
        self._last_hsi_warn = 0.0
        self._connected = threading.Event()
        self._subscribed = threading.Event()
        self.controls_ready = threading.Event()
        self._state_lock = threading.RLock()
        self._generation = 0
        self._connected_at_ms = 0
        self._expected_sub_mid = None
        self._expected_sub_generation = 0
        self._pending_probes = {}
        self._probe_ack_stage = 0
        self._control_vector = None
        self._control_seq = -1
        self._heartbeat_seq = 0
        self._last_heartbeat_ack_seq = 0
        self._heartbeat_thread = None

    @staticmethod
    def _flatten_acl_topics(value) -> list[str]:
        topics = []
        stack = list(value) if isinstance(value, (list, tuple, set)) else [value]
        while stack:
            item = stack.pop(0)
            if isinstance(item, str) and item:
                topics.append(item)
            elif isinstance(item, (list, tuple, set)):
                stack[0:0] = list(item)
        return list(dict.fromkeys(topics))

    def connect(self) -> bool:
        address = str(self.broker.get("address") or "")
        try:
            parsed = urlparse(address if "://" in address else f"tcp://{address}")
            host = parsed.hostname
            default_ports = {"tcp": 1883, "mqtt": 1883, "ssl": 8883,
                             "tls": 8883, "mqtts": 8883, "ws": 80, "wss": 443}
            port = parsed.port or default_ports.get(parsed.scheme)
        except ValueError:
            print(f"[✗] 无法解析 DRC Broker 地址: {address!r}")
            return False
        if not host or port is None:
            print(f"[✗] 无法解析 DRC Broker 地址: {address!r}")
            return False
        transport = "websockets" if parsed.scheme in {"ws", "wss"} else "tcp"

        client_id = self.broker.get("client_id", f"drc_{int(time.time())}")
        username = self.broker.get("username", "")
        password = self.broker.get("password", "")  # JWT token

        self.client = mqtt.Client(
            callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
            client_id=client_id,
            transport=transport,
        )
        if transport == "websockets" and parsed.path:
            self.client.ws_set_options(path=parsed.path)
        if parsed.scheme in {"ssl", "tls", "mqtts", "wss"}:
            self.client.tls_set()
        if username:
            self.client.username_pw_set(username, password)

        self.client.on_connect = self._on_connect
        self.client.on_subscribe = self._on_subscribe
        self.client.on_disconnect = self._on_disconnect
        self.client.on_message = self._on_message

        try:
            self._connected.clear()
            self._subscribed.clear()
            self.controls_ready.clear()
            self.client.connect(host, port, keepalive=30)
            self.client.loop_start()
            if not self._connected.wait(8):
                print("[✗] DRC MQTT 8 秒内未完成连接，未启动心跳")
                self.disconnect()
                return False
            if not self._subscribed.wait(8):
                print("[✗] DRC MQTT 订阅未确认，未启动心跳")
                self.disconnect()
                return False
            if not self.controls_ready.wait(2):
                print("[!] MQTT 已连接，但尚未收到当前会话零杆探针 ACK；非零摇杆保持锁定")
            return True
        except Exception as e:
            print(f"[✗] DRC MQTT 连接失败: {e}")
            return False

    def _on_connect(self, client, userdata, flags, reason_code, properties):
        if reason_code == 0:
            with self._state_lock:
                self._generation += 1
                generation = self._generation
                self._connected_at_ms = int(time.time() * 1000)
                self._expected_sub_mid = None
                self._expected_sub_generation = generation
                self._pending_probes = {}
                self._probe_ack_stage = 0
                self._heartbeat_seq = 0
                self._last_heartbeat_ack_seq = 0
                self.controls_ready.clear()
                self._subscribed.clear()
            print(f"[✓] DRC MQTT 已连接（会话代次 {generation}）")
            self._connected.set()
            if self.sub_topics:
                result, mid = client.subscribe([(topic, 0) for topic in self.sub_topics])
                if result != mqtt.MQTT_ERR_SUCCESS:
                    print(f"[✗] DRC MQTT 订阅发送失败 rc={result}")
                    return
                with self._state_lock:
                    self._expected_sub_mid = mid
                print(f"    正在订阅: {self.sub_topics}")
            else:
                self._subscribed.set()
                self._bootstrap_generation(generation)
        else:
            print(f"[✗] DRC MQTT 连接失败: {reason_code}")

    def _on_subscribe(self, client, userdata, mid, reason_code_list, properties):
        if any(getattr(code, "is_failure", False) for code in reason_code_list):
            print(f"[✗] DRC MQTT SUBACK 拒绝订阅: {reason_code_list}")
            return
        with self._state_lock:
            if (client is not self.client or mid != self._expected_sub_mid or
                    self._expected_sub_generation != self._generation):
                print(f"[!] 忽略非当前会话的 SUBACK mid={mid}")
                return
            generation = self._generation
        self._subscribed.set()
        print(f"[✓] DRC MQTT 订阅已确认: {self.sub_topics}")
        # SUBACK 后立即发送心跳和零杆量探针，避免第一次进入 DRC 时因部分
        # RC 固件不回显 heart_beat 而一直等不到可控制状态。
        self._bootstrap_generation(generation)

    def _on_disconnect(self, client, userdata, disconnect_flags, reason_code, properties):
        with self._state_lock:
            if client is self.client:
                self._pending_probes = {}
                self._probe_ack_stage = 0
                self.controls_ready.clear()
                self._subscribed.clear()
        if self._running and reason_code != 0:
            print(f"[!] DRC MQTT 已断开 ({reason_code})，重连并通过新探针前禁止非零摇杆")

    def _bootstrap_generation(self, generation: int):
        with self._state_lock:
            if generation != self._generation or not self._subscribed.is_set():
                return
        self.heartbeat(force_probe=True)

    def _on_message(self, client, userdata, msg):
        try:
            if client is not self.client or msg.topic not in self.sub_topics:
                return
            payload = json.loads(msg.payload)
            if not isinstance(payload, dict):
                raise TypeError("消息根节点不是 JSON 对象")
            method = payload.get("method", "")
            data, output, result = self._normalise_response(payload)
            received_at_ms = int(time.time() * 1000)

            if msg.topic in self.services_reply_topics:
                flag = "✓" if result == 0 else "✗"
                print(f"\n[{flag}] services_reply {method} result={result}")
                return

            if msg.topic != self.sub_topic:
                return

            if method == "heart_beat":
                self._handle_heartbeat_ack(output, received_at_ms)
                return

            if method == "hsi_info_push":
                self._handle_hsi(data)
                return

            if method == "drone_control":
                self._handle_control_ack(payload, data, output, result, received_at_ms)
                return

            print(f"[DRC↑] {method}: {json.dumps(data, ensure_ascii=False)}")
        except (json.JSONDecodeError, UnicodeDecodeError, TypeError, ValueError) as exc:
            print(f"[!] 忽略无法解析的 DRC 上行消息: {exc}")

    @staticmethod
    def _normalise_response(payload: dict):
        """兼容 data/result/output 的常见嵌套形式，但不为缺失 result 伪造成功。"""
        container = payload.get("data")
        container = container if isinstance(container, dict) else {}
        nested_data = container.get("data")
        data = nested_data if isinstance(nested_data, dict) else container
        nested_output = container.get("output")
        if not isinstance(nested_output, dict):
            nested_output = data.get("output")
        output = nested_output if isinstance(nested_output, dict) else data

        marker = object()
        result = container.get("result", marker)
        if result is marker:
            result = data.get("result", marker)
        if result is marker:
            result = payload.get("result", marker)
        result = None if result is marker else DrcSession._exact_int(result)
        return data, output, result

    @staticmethod
    def _reply_ids(payload: dict):
        """递归收集回包携带的 tid/bid；存在时必须全部属于当前请求。"""
        reply_ids = []
        stack = [payload]
        while stack:
            value = stack.pop()
            if not isinstance(value, dict):
                continue
            for key, child in value.items():
                if key in {"tid", "bid"} and child is not None and child != "":
                    reply_ids.append(str(child))
                elif isinstance(child, dict):
                    stack.append(child)
        return reply_ids

    @staticmethod
    def _exact_int(value):
        if value is None or isinstance(value, bool):
            return None
        if isinstance(value, int):
            return value
        if isinstance(value, float):
            return int(value) if value.is_integer() else None
        if isinstance(value, str):
            try:
                return int(value.strip())
            except ValueError:
                return None
        return None

    def _handle_heartbeat_ack(self, output: dict, received_at_ms: int):
        sent_at = self._exact_int(output.get("timestamp"))
        seq = self._exact_int(output.get("seq"))
        with self._state_lock:
            if (sent_at is None or seq is None or sent_at < self._connected_at_ms or
                    seq <= self._last_heartbeat_ack_seq or
                    seq > self._heartbeat_seq):
                return
            self._last_heartbeat_ack_seq = seq
            self.last_rtt_ms = max(0, received_at_ms - sent_at)

    def _probe_matches_locked(self, probe: PendingControlProbe, payload: dict,
                              output: dict, received_at_ms: int):
        if (probe is None or probe.generation != self._generation or
                probe.published_at_ms < self._connected_at_ms or
                received_at_ms < probe.published_at_ms or
                received_at_ms - probe.published_at_ms > self.PROBE_ACK_WINDOW_MS):
            return False

        # DroneControlResponse 官方定义包含 output.seq。缺失或不精确匹配时
        # 宁可继续锁定，也不能让旧会话/旧命令的 ACK 解锁摇杆。
        if self._exact_int(output.get("seq")) != probe.seq:
            return False
        reply_ids = self._reply_ids(payload)
        if reply_ids and any(reply_id != probe.request_id for reply_id in reply_ids):
            return False
        return True

    def _handle_control_ack(self, payload: dict, data: dict, output: dict,
                            result, received_at_ms: int):
        if not self.controls_ready.is_set():
            with self._state_lock:
                seq = self._exact_int(output.get("seq"))
                probe = self._pending_probes.get(seq)
                if (probe is None or
                        not self._probe_matches_locked(probe, payload, output, received_at_ms)):
                    return
                if result != 0:
                    self._pending_probes = {}
                    self._probe_ack_stage = 0
                    ready = False
                elif probe.handshake_step != self._probe_ack_stage:
                    # MQTT 同一 Topic 正常有序；乱序/迟到的第二帧不能越过第一帧。
                    return
                elif probe.handshake_step == 0:
                    self._pending_probes.pop(seq, None)
                    self._probe_ack_stage = 1
                    return
                else:
                    self._pending_probes = {}
                    self._probe_ack_stage = 0
                    # 与 generation 更新共用同一把锁，重连清锁后旧回调不能再 set。
                    self.controls_ready.set()
                    ready = True
            if ready:
                print(f"[✓] 当前 DRC 会话控制链路已就绪"
                      f"（连续零杆探针末帧 seq={seq}）")
            else:
                shown = "缺失" if result is None else result
                print(f"[!] 零杆探针回包 result={shown}，保持摇杆锁定并等待重试")
            return

        if result not in {None, 0}:
            with self._state_lock:
                self.controls_ready.clear()
                self._pending_probes = {}
                self._probe_ack_stage = 0
            print(f"[!] 设备拒绝 drone_control（result={result}），已锁定非零摇杆并重新探测")
        else:
            print(f"[DRC↑] drone_control: {json.dumps(data, ensure_ascii=False)}")

    def _handle_hsi(self, data: dict):
        """避障信息上报：频率很高，只缓存快照，逼近障碍物时才限频告警"""
        self.last_hsi = data
        if not data.get("radar_enable"):
            return
        nearest = min(self._hsi_distances(data).values(), default=None)
        if nearest is None or nearest >= self.HSI_WARN_M:
            return
        now = time.time()
        if now - self._last_hsi_warn < self.HSI_WARN_INTERVAL:
            return
        self._last_hsi_warn = now
        print(f"\n[避障] 最近障碍物 {nearest:.2f} m，输入 hsi 查看各方向详情")

    @staticmethod
    def _hsi_distances(data: dict) -> dict:
        """取出所有 *_distance 字段（单位米）"""
        return {k: v for k, v in data.items()
                if (k.endswith("_distance") and not isinstance(v, bool)
                    and isinstance(v, (int, float)) and math.isfinite(v) and v >= 0)}

    def print_hsi(self):
        """打印最近一次避障快照，按方向分组"""
        if not self.last_hsi:
            print("  尚未收到 hsi_info_push（设备未上报或未进入 DRC 模式）")
            return
        d = self.last_hsi
        print(f"\n避障信息（radar_enable={'开' if d.get('radar_enable') else '关'}，单位米）")
        groups = [
            ("前 front", ["front1_distance", "front2_distance", "front3_distance", "front4_distance"]),
            ("后 rear",  ["rear1_distance", "rear2_distance", "rear3_distance", "rear4_distance"]),
            ("左 left",  ["left1_distance", "left2_distance", "left3_distance"]),
            ("右 right", ["right1_distance", "right2_distance", "right3_distance"]),
            ("上 up",    ["up_distance"]),
            ("下 down",  ["down_distance"]),
        ]
        for label, keys in groups:
            vals = [f"{d.get(k, 0):.2f}" for k in keys if k in d]
            print(f"  {label:10s} {'  '.join(vals) if vals else '无数据'}")
        print()

    def publish(self, method: str, data: dict = None,
                request_id: str = None, quiet: bool = False):
        if not self.pub_topic or not self.client or not self.client.is_connected():
            print(f"[!] DRC MQTT 未就绪，{method} 未发送")
            return None
        payload, request_id = build_drc_msg(method, data, request_id)
        info = self.client.publish(self.pub_topic, payload)
        if info.rc != mqtt.MQTT_ERR_SUCCESS:
            print(f"[!] {method} 发布失败 rc={info.rc}")
            return None
        if method != "heart_beat" and not quiet:
            print(f"[DRC↓] 发送 {method}")
        return request_id

    def heartbeat(self, force_probe: bool = False):
        """发送心跳；控制尚未就绪时同时发送当前会话双帧零杆量探针。"""
        sent_at_ms = int(time.time() * 1000)
        with self._state_lock:
            self._heartbeat_seq += 1
            seq = self._heartbeat_seq
        self.publish("heart_beat", {"seq": seq, "timestamp": sent_at_ms})
        if not self.controls_ready.is_set():
            self._publish_zero_probe_pair(force=force_probe)

    def _next_control_seq_locked(self, vector):
        """官方约束：杆量变化时 seq 从 0 开始，同一杆量逐帧递增。"""
        if vector != self._control_vector:
            self._control_vector = vector
            self._control_seq = 0
        else:
            self._control_seq += 1
        return self._control_seq

    def _publish_zero_probe_pair(self, force: bool = False):
        now_ms = int(time.time() * 1000)
        with self._state_lock:
            generation = self._generation
            if generation <= 0 or not self._subscribed.is_set():
                return False
            if not force and self._pending_probes:
                newest_at = max(probe.published_at_ms
                                for probe in self._pending_probes.values())
                if now_ms - newest_at <= self.PROBE_ACK_WINDOW_MS:
                    return False

            # 首轮全零杆量通常为 seq=0、1。若 ACK 丢失或设备拒绝，
            # 设备可能已消费旧帧，因此同一零向量必须继续递增而不能回放 0、1。
            self._probe_ack_stage = 0
            self._pending_probes = {}
            probes = []
            for handshake_step in range(2):
                seq = self._next_control_seq_locked((0, 0, 0, 0))
                request_id = uuid.uuid4().hex[:16]
                probe = PendingControlProbe(
                    generation, request_id, seq, handshake_step,
                    int(time.time() * 1000))
                self._pending_probes[seq] = probe
                probes.append(probe)

            # 持锁发布，ACK 回调只能在两帧都登记、发送后校验，且重连不能
            # 在两帧中间切换 generation。
            for index, probe in enumerate(probes):
                published = self.publish("drone_control", {
                    "seq": probe.seq,
                    "x": 0, "y": 0, "h": 0, "w": 0,
                    "freq": 10, "delay_time": 300,
                }, request_id=probe.request_id, quiet=True)
                if not published:
                    self._pending_probes = {}
                    self._probe_ack_stage = 0
                    return False
                if index == 0:
                    time.sleep(self.CONTROL_INTERVAL_SECONDS)
        print(f"[DRC↓] 已发送当前会话连续零杆探针 "
              f"seq={probes[0].seq}→{probes[1].seq}")
        return True

    def emergency_stop(self):
        """急停：无人机立即刹停并原地悬停，不降落"""
        if self.publish("drone_emergency_stop", {}):
            print("[!] 已发送急停指令 drone_emergency_stop（原地悬停）")

    def joystick(self, x: float = 0, y: float = 0, h: float = 0, w: float = 0):
        """
        飞行控制摇杆 drone_control
        x: 前后速度 m/s，范围 -17 ~ 17（正=前，负=后）
        y: 左右速度 m/s，范围 -17 ~ 17（正=右，负=左）
        h: 升降速度 m/s，范围 -4  ~ 5 （正=上，负=下）
        w: 偏航角速度 度/s，范围 -90 ~ 90（正=顺时针，负=逆时针）
        """
        if not (-17 <= x <= 17 and -17 <= y <= 17 and -4 <= h <= 5 and -90 <= w <= 90):
            print("[!] 摇杆值越界：x/y=-17~17, h=-4~5, w=-90~90，未发送")
            return False
        non_zero = any(value != 0 for value in (x, y, h, w))
        if non_zero and not self.controls_ready.is_set():
            print("[!] 当前 DRC 会话尚未通过零杆探针校验，非零摇杆未发送")
            return False
        if not non_zero and not self.controls_ready.is_set():
            self._publish_zero_probe_pair()
            print("[!] 悬停输入仅作为零杆探针发送；收到当前会话成功 ACK 前仍保持锁定")
            return False
        vector = (x, y, h, w)
        with self._state_lock:
            # 就绪检查、seq 计算和 publish 原子化，重连不能夹在检查与下发之间。
            if not self.controls_ready.is_set():
                print("[!] DRC 会话刚刚重连，非零摇杆未发送")
                return False
            seq = self._next_control_seq_locked(vector)
            return bool(self.publish("drone_control", {
                "seq": seq,
                "x": x, "y": y, "h": h, "w": w,
                "freq": 10, "delay_time": 300,
            }))

    def start_heartbeat_loop(self):
        """后台线程每秒发送心跳"""
        if self._heartbeat_thread and self._heartbeat_thread.is_alive():
            return

        def _loop():
            while self._running:
                self.heartbeat()
                time.sleep(1)
        self._heartbeat_thread = threading.Thread(target=_loop, daemon=True)
        self._heartbeat_thread.start()

    def disconnect(self):
        self._running = False
        if (self._heartbeat_thread and self._heartbeat_thread.is_alive()
                and self._heartbeat_thread is not threading.current_thread()):
            try:
                self._heartbeat_thread.join(timeout=1.2)
            except RuntimeError as exc:
                print(f"[!] 等待 DRC 心跳线程退出失败: {exc}")
        self.controls_ready.clear()
        publish_info = None
        final_seq = None
        try:
            with self._state_lock:
                self._pending_probes = {}
                self._probe_ack_stage = 0
                if (self.client and self.pub_topic and self.client.is_connected()):
                    final_seq = self._next_control_seq_locked((0, 0, 0, 0))
                    payload, _ = build_drc_msg("drone_control", {
                        "seq": final_seq,
                        "x": 0, "y": 0, "h": 0, "w": 0,
                        "freq": 10, "delay_time": 300,
                    })
                    publish_info = self.client.publish(self.pub_topic, payload)
        except (OSError, RuntimeError, TypeError, ValueError) as exc:
            print(f"[!] 退出前最终零杆量发布失败: {exc}")
        if publish_info is not None and publish_info.rc == mqtt.MQTT_ERR_SUCCESS:
            try:
                publish_info.wait_for_publish(timeout=0.5)
                if publish_info.is_published():
                    print(f"[DRC↓] 退出前已发送最终零杆量 seq={final_seq}")
                else:
                    print("[!] 最终零杆量未在 0.5 秒内确认交给 MQTT 传输层")
            except (RuntimeError, TypeError, ValueError, TimeoutError):
                print("[!] 最终零杆量未在 0.5 秒内确认交给 MQTT 传输层")
        if self.client:
            try:
                self.client.disconnect()
            except (OSError, RuntimeError, TypeError, ValueError) as exc:
                print(f"[!] DRC MQTT disconnect 失败: {exc}")
            try:
                self.client.loop_stop()
            except (OSError, RuntimeError, TypeError, ValueError) as exc:
                print(f"[!] DRC MQTT loop_stop 失败: {exc}")


def main() -> int:
    require_config(YOOX_DOCK_SN=DOCK_SN, YOOX_WORKSPACE_ID=WORKSPACE_ID)
    print(f"[*] 设备 SN: {DOCK_SN}")
    print(f"[*] Workspace: {WORKSPACE_ID}\n")
    print("[!] 警告：DRC 模式会让无人机进入云端直接飞行控制，确认无人机已在空中后继续\n")

    token = login()

    if input("[!!] 确认进入 DRC 并开启云端摇杆控制？输入 YES: ").strip() != "YES":
        print("[*] 已取消进入 DRC")
        return 0

    # 步骤 1：获取 DRC MQTT 凭证
    broker = drc_connect(token)
    client_id = broker.get("client_id", "")
    session = None
    watch_client = None
    enter_attempted = False
    try:
        # 步骤 2：设备进入 DRC 模式。即使响应丢失，finally 也会尝试退出。
        enter_attempted = True
        acl = drc_enter(token, client_id)

        # 步骤 3：等 CONNACK + SUBACK 后立即发心跳及 seq=0→1 双帧零杆探针。
        session = DrcSession(broker, acl)
        if not session.connect():
            return 1
        session.start_heartbeat_loop()
        watch_client = start_side_watcher()

        print("\nDRC 飞行指令（速度单位 m/s，偏航单位 度/s）：")
        print("  fwd/back/left/right/up/down/yaw <n>；hover 悬停；ping 查看心跳；q 退出")
        print("  stop 急停悬停\n")

        while True:
            cmd = input("DRC指令: ").strip().lower().split()
            if not cmd:
                continue
            action = cmd[0]
            if action == "q":
                break
            if action == "hsi":
                session.print_hsi()
            elif action == "ping":
                rtt = session.last_rtt_ms
                print(f"  心跳往返时延: {rtt} ms" if rtt is not None else "  尚未收到心跳回包")
                print("  控制链路: 已就绪" if session.controls_ready.is_set()
                      else "  控制链路: 锁定（等待当前会话双帧零杆 ACK）")
            elif action == "stop":
                session.emergency_stop()
            elif action == "hover":
                session.joystick()
            elif action in {"up", "down", "fwd", "back", "left", "right", "yaw"}:
                try:
                    val = float(cmd[1]) if len(cmd) > 1 else 3.0
                except ValueError:
                    print("  数值格式错误")
                    continue
                vectors = {
                    "up": {"h": val}, "down": {"h": -val},
                    "fwd": {"x": val}, "back": {"x": -val},
                    "left": {"y": -val}, "right": {"y": val},
                    "yaw": {"w": val},
                }
                session.joystick(**vectors[action])
            else:
                print("  未知指令")
    finally:
        try:
            if session:
                session.disconnect()
        except Exception as exc:
            print(f"[!] 本地 DRC MQTT 清理异常，将继续请求服务端退出: {exc}")
        finally:
            try:
                if watch_client:
                    try:
                        watch_client.loop_stop()
                    except Exception as exc:
                        print(f"[!] 旁路 MQTT loop_stop 失败: {exc}")
                    try:
                        watch_client.disconnect()
                    except Exception as exc:
                        print(f"[!] 旁路 MQTT disconnect 失败: {exc}")
            finally:
                if enter_attempted and client_id:
                    try:
                        drc_exit(token, client_id)
                    except DemoError as exc:
                        print_error_and_hint(exc)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except DemoError as exc:
        print_error_and_hint(exc)
        raise SystemExit(1)
