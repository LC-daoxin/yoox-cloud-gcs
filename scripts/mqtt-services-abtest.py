#!/usr/bin/env python3
"""MQTT services 指令报文 A/B 测试工具（直连 EMQX，绕过云端业务层）。

``camera_look_at`` 在未提供自定义变体时，会自动生成以下 12 种组合：

* ``payload_index``：缺省 / 携带；
* ``locked``：缺省 / false / true；
* ``device_list``：缺省 / 携带。

工具可在同一 MQTT 连接内先发送 ``payload_authority_grab``，并默认在矩阵前后
发送零速度 ``camera_screen_drag`` 对照指令。只有这些对照都收到 method/tid/bid
匹配且 ``result=0`` 的回复，而目标 method 全部无回复时，才有依据怀疑当前固件
未实现该 method。

安全约束
--------
必须显式选择 ``--dry-run`` 或 ``--execute``。前者只打印报文，适合当前无设备的
离线检查；后者会向真实 MQTT Broker 发布，返回 result=0 时设备可能已经执行。
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shlex
import sys
import threading
import time
import uuid
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_ENV_FILES = (REPO_ROOT / ".env", REPO_ROOT / "docs/python-demo/.env")
RESERVED_TOP_LEVEL_FIELDS = {
    "tid", "bid", "timestamp", "method", "data", "device_list"
}
PLACEHOLDER_PREFIXES = ("YOUR_", "TEST_", "<")
PLACEHOLDER_MARKERS = ("XXX", "CHANGE_ME", "REPLACE_", "EXAMPLE")


def _parse_env_value(raw: str) -> str:
    """解析项目常见的 KEY=value；不执行变量展开或 shell 命令。"""
    raw = raw.strip()
    if not raw:
        return ""
    try:
        parts = shlex.split(raw, comments=True, posix=True)
    except ValueError as exc:
        raise ValueError(f"无法解析环境变量值 {raw!r}: {exc}") from exc
    return " ".join(parts)


def load_env_files(paths: list[Path], *, required: bool) -> list[Path]:
    """按顺序加载 env 文件；已有进程环境变量与先加载文件优先。"""
    loaded: list[Path] = []
    for path in paths:
        path = path.expanduser().resolve()
        if not path.exists():
            if required:
                raise FileNotFoundError(f"env 文件不存在: {path}")
            continue
        for line_number, line in enumerate(
            path.read_text(encoding="utf-8").splitlines(), 1
        ):
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                continue
            if stripped.startswith("export "):
                stripped = stripped[7:].lstrip()
            if "=" not in stripped:
                continue
            key, raw_value = stripped.split("=", 1)
            key = key.strip()
            if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key):
                raise ValueError(f"{path}:{line_number}: 非法环境变量名 {key!r}")
            os.environ.setdefault(key, _parse_env_value(raw_value))
        loaded.append(path)
    return loaded


def _first_env(*names: str, default: str = "") -> str:
    for name in names:
        value = os.getenv(name, "").strip()
        if value:
            return value
    return default


def _env_int(name: str, default: int) -> int:
    raw = os.getenv(name, "").strip()
    if not raw:
        return default
    try:
        return int(raw)
    except ValueError as exc:
        raise ValueError(f"环境变量 {name} 必须是整数，当前值为 {raw!r}") from exc


def _env_float(name: str) -> float | None:
    raw = os.getenv(name, "").strip()
    if not raw:
        return None
    try:
        return float(raw)
    except ValueError as exc:
        raise ValueError(f"环境变量 {name} 必须是数字，当前值为 {raw!r}") from exc


def parse_args(argv: list[str] | None = None) -> tuple[argparse.Namespace, list[Path]]:
    pre_parser = argparse.ArgumentParser(add_help=False)
    pre_parser.add_argument("--env-file", action="append", type=Path)
    pre_args, _ = pre_parser.parse_known_args(argv)
    explicit_env_files = pre_args.env_file or []
    loaded_env_files = load_env_files(
        explicit_env_files or list(DEFAULT_ENV_FILES),
        required=bool(explicit_env_files),
    )

    parser = argparse.ArgumentParser(
        description="直连 MQTT 的 services 指令 A/B 测试",
        parents=[pre_parser],
    )
    parser.add_argument(
        "--method", required=True,
        help="method 名；camera_look_at 默认生成 12 组字段矩阵",
    )
    parser.add_argument(
        "--variant", action="append", default=[],
        help=(
            "自定义变体 JSON，可多次传入。支持 data、device_list(bool)、"
            "no_device_list、topic(gateway/drone)、drone_topic、top_level"
        ),
    )
    parser.add_argument(
        "--topic-mode", choices=("gateway", "drone", "both"), default="gateway",
        help="自动矩阵的下发 topic；both 会把目标矩阵扩为两倍",
    )
    parser.add_argument(
        "--case-id", action="append", default=[],
        help=(
            "只执行指定自动矩阵用例，可多次传入；前/后对照仍保留。"
            "例如 LA-G-P1-L0-D1。用于真机悬停时分阶段测试，避免直接执行 locked=true"
        ),
    )
    parser.add_argument(
        "--gateway",
        default=_first_env("YOOX_GATEWAY_SN", "YOOX_DOCK_SN"),
        help="网关/遥控器 SN（默认读取 YOOX_GATEWAY_SN 或 YOOX_DOCK_SN）",
    )
    parser.add_argument(
        "--drone", default=_first_env("YOOX_DRONE_SN"),
        help="无人机 SN（默认读取 YOOX_DRONE_SN）",
    )
    parser.add_argument(
        "--payload-index", default=_first_env("YOOX_PAYLOAD_INDEX"),
        help="负载索引，例如 10806-0-0（默认读取 YOOX_PAYLOAD_INDEX）",
    )
    parser.add_argument(
        "--latitude", type=float, default=_env_float("YOOX_TARGET_LATITUDE"),
        help="Look At 目标纬度（椭球坐标）",
    )
    parser.add_argument(
        "--longitude", type=float, default=_env_float("YOOX_TARGET_LONGITUDE"),
        help="Look At 目标经度（椭球坐标）",
    )
    parser.add_argument(
        "--height", type=float, default=_env_float("YOOX_TARGET_HEIGHT"),
        help="Look At 目标椭球高，单位米",
    )
    parser.add_argument(
        "--host", default=_first_env("YOOX_MQTT_HOST", default="127.0.0.1")
    )
    parser.add_argument(
        "--port", type=int, default=_env_int("YOOX_MQTT_PORT", 1883)
    )
    parser.add_argument(
        "--username", default=_first_env("YOOX_MQTT_USERNAME")
    )
    parser.add_argument(
        "--password", default=os.getenv("YOOX_MQTT_PASSWORD", "")
    )
    parser.add_argument(
        "--wait", type=float, default=4.0,
        help="每个变体等待关联回复的秒数（默认 4）",
    )
    parser.add_argument(
        "--connect-timeout", type=float, default=8.0,
        help="等待 MQTT CONNACK/SUBACK 的秒数（默认 8）",
    )
    parser.add_argument("--qos", type=int, choices=(0, 1), default=0)
    parser.add_argument(
        "--control-position", choices=("both", "before", "after", "none"),
        default="both", help="零速度 camera_screen_drag 对照指令位置（默认前后各一次）",
    )
    parser.add_argument(
        "--grab-payload-authority", action="store_true",
        help=(
            "在目标用例前先发送 payload_authority_grab 并等待关联回复；"
            "用于明确验证负载控制权"
        ),
    )
    parser.add_argument(
        "--no-control", action="store_true",
        help="兼容旧参数，等价于 --control-position none",
    )
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--dry-run", action="store_true", help="只生成并打印报文，不连接 MQTT")
    mode.add_argument("--execute", action="store_true", help="确认向真实 MQTT Broker 发布报文")
    parser.add_argument(
        "--output", type=Path,
        help="可选：把不含 SN/坐标/凭据的用例维度与结果保存为 JSON",
    )

    args = parser.parse_args(argv)
    if args.no_control:
        args.control_position = "none"
    validate_args(parser, args)
    return args, loaded_env_files


def validate_args(parser: argparse.ArgumentParser, args: argparse.Namespace) -> None:
    if args.wait <= 0:
        parser.error("--wait 必须大于 0")
    if args.connect_timeout <= 0:
        parser.error("--connect-timeout 必须大于 0")
    if not args.gateway:
        parser.error("缺少 --gateway（或 YOOX_DOCK_SN/YOOX_GATEWAY_SN）")
    if not args.drone:
        parser.error("缺少 --drone（或 YOOX_DRONE_SN）")

    automatic_look_at = args.method == "camera_look_at" and not args.variant
    needs_payload_index = (
        automatic_look_at
        or args.control_position != "none"
        or args.grab_payload_authority
    )
    if needs_payload_index and not args.payload_index:
        parser.error("Look At 矩阵/对照指令需要 --payload-index（或 YOOX_PAYLOAD_INDEX）")
    if args.payload_index and not re.fullmatch(r"\d+-\d+-\d+", args.payload_index):
        parser.error("--payload-index 格式应为 type-subtype-gimbalindex，例如 10806-0-0")

    if automatic_look_at:
        missing = [
            name for name in ("latitude", "longitude", "height")
            if getattr(args, name) is None
        ]
        if missing:
            parser.error("Look At 自动矩阵缺少: " + ", ".join(f"--{x}" for x in missing))
        if not -90 <= args.latitude <= 90:
            parser.error("--latitude 必须在 [-90, 90]")
        if not -180 <= args.longitude <= 180:
            parser.error("--longitude 必须在 [-180, 180]")
        if not 2 <= args.height <= 10000:
            parser.error("--height 必须在 [2, 10000] 米；这里要求椭球高，不是相对高度")

    if args.execute:
        for option, value in (("--gateway", args.gateway), ("--drone", args.drone)):
            upper_value = value.upper()
            if (
                upper_value.startswith(PLACEHOLDER_PREFIXES)
                or any(marker in upper_value for marker in PLACEHOLDER_MARKERS)
            ):
                parser.error(f"{option} 仍是测试占位值，拒绝真实发布")


def _topic_keys(topic_mode: str) -> tuple[str, ...]:
    if topic_mode == "both":
        return ("gateway", "drone")
    return (topic_mode,)


def build_look_at_cases(args: argparse.Namespace) -> list[dict[str, Any]]:
    cases: list[dict[str, Any]] = []
    for topic_key in _topic_keys(args.topic_mode):
        topic_code = "G" if topic_key == "gateway" else "D"
        for with_payload in (False, True):
            for locked_state in ("absent", False, True):
                for with_device_list in (False, True):
                    data: dict[str, Any] = {
                        "latitude": args.latitude,
                        "longitude": args.longitude,
                        "height": args.height,
                    }
                    if with_payload:
                        data["payload_index"] = args.payload_index
                    if locked_state != "absent":
                        data["locked"] = locked_state
                    locked_code = (
                        "LX" if locked_state == "absent"
                        else "L1" if locked_state else "L0"
                    )
                    case_id = (
                        f"LA-{topic_code}-P{int(with_payload)}-{locked_code}-"
                        f"D{int(with_device_list)}"
                    )
                    cases.append({
                        "id": case_id,
                        "kind": "target",
                        "label": (
                            f"Look At payload_index={'有' if with_payload else '无'}, "
                            f"locked={locked_state!s}, "
                            f"device_list={'有' if with_device_list else '无'}"
                        ),
                        "method": args.method,
                        "data": data,
                        "device_list": with_device_list,
                        "topic_key": topic_key,
                        "top_level": {},
                        "dimensions": {
                            "payload_index": with_payload,
                            "locked": locked_state,
                            "device_list": with_device_list,
                        },
                    })
    return cases


def build_basic_cases(args: argparse.Namespace) -> list[dict[str, Any]]:
    cases: list[dict[str, Any]] = []
    for topic_key in _topic_keys(args.topic_mode):
        for with_device_list in (False, True):
            cases.append({
                "id": f"B-{topic_key[0].upper()}-D{int(with_device_list)}",
                "kind": "target",
                "label": (
                    f"{args.method} data={{}}, "
                    f"device_list={'有' if with_device_list else '无'}"
                ),
                "method": args.method,
                "data": {},
                "device_list": with_device_list,
                "topic_key": topic_key,
                "top_level": {},
                "dimensions": {"device_list": with_device_list},
            })
    return cases


def build_custom_cases(args: argparse.Namespace) -> list[dict[str, Any]]:
    cases: list[dict[str, Any]] = []
    for index, raw in enumerate(args.variant, 1):
        try:
            spec = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise ValueError(f"变体 {index} 不是合法 JSON: {exc}") from exc
        if not isinstance(spec, dict):
            raise ValueError(f"变体 {index} 必须是 JSON 对象")
        if "device_list" in spec and not isinstance(spec["device_list"], bool):
            raise ValueError(f"变体 {index} 的 device_list 必须是 bool")
        with_device_list = spec.get(
            "device_list", not bool(spec.get("no_device_list", False))
        )
        topic_key = spec.get(
            "topic", "drone" if spec.get("drone_topic") else "gateway"
        )
        if topic_key not in ("gateway", "drone"):
            raise ValueError(f"变体 {index} 的 topic 只能是 gateway 或 drone")
        top_level = spec.get("top_level", {})
        if not isinstance(top_level, dict):
            raise ValueError(f"变体 {index} 的 top_level 必须是 JSON 对象")
        if "need_reply" in spec:
            top_level = {**top_level, "need_reply": spec["need_reply"]}
        conflict = RESERVED_TOP_LEVEL_FIELDS.intersection(top_level)
        if conflict:
            raise ValueError(
                f"变体 {index} 的 top_level 不得覆盖: {', '.join(sorted(conflict))}"
            )
        cases.append({
            "id": f"V{index:02d}",
            "kind": "target",
            "label": spec.get("label", f"自定义变体 {index}"),
            "method": args.method,
            "data": spec.get("data"),
            "device_list": with_device_list,
            "topic_key": topic_key,
            "top_level": top_level,
            "dimensions": {},
        })
    return cases


def build_control_case(args: argparse.Namespace, position: str) -> dict[str, Any]:
    return {
        "id": f"CTRL-{position.upper()}",
        "kind": "control",
        "label": f"对照({position}): camera_screen_drag 零速度",
        "method": "camera_screen_drag",
        "data": {
            "payload_index": args.payload_index,
            "locked": False,
            "pitch_speed": 0.0,
            "yaw_speed": 0.0,
        },
        "device_list": True,
        "topic_key": "gateway",
        "top_level": {},
        "dimensions": {},
    }


def build_authority_case(args: argparse.Namespace) -> dict[str, Any]:
    return {
        "id": "CTRL-AUTHORITY-GRAB",
        "kind": "control",
        "label": "对照(authority): payload_authority_grab",
        "method": "payload_authority_grab",
        "data": {"payload_index": args.payload_index},
        "device_list": True,
        "topic_key": "gateway",
        "top_level": {},
        "dimensions": {},
    }


def build_cases(args: argparse.Namespace) -> list[dict[str, Any]]:
    if args.variant:
        targets = build_custom_cases(args)
    elif args.method == "camera_look_at":
        targets = build_look_at_cases(args)
    else:
        targets = build_basic_cases(args)

    cases: list[dict[str, Any]] = []
    if args.grab_payload_authority:
        cases.append(build_authority_case(args))
    if args.control_position in ("before", "both"):
        cases.append(build_control_case(args, "before"))
    cases.extend(targets)
    if args.control_position in ("after", "both"):
        cases.append(build_control_case(args, "after"))
    return cases


def select_cases(args: argparse.Namespace,
                 cases: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """按 case ID 缩小目标矩阵；对照用例不受过滤影响。"""
    if not args.case_id:
        return cases
    requested = list(dict.fromkeys(args.case_id))
    available = {
        case["id"] for case in cases if case["kind"] == "target"
    }
    unknown = [case_id for case_id in requested if case_id not in available]
    if unknown:
        raise ValueError(
            "未知 --case-id: " + ", ".join(unknown)
            + "；可用目标用例: " + ", ".join(sorted(available))
        )
    selected = [
        case for case in cases
        if case["kind"] == "control" or case["id"] in requested
    ]
    print("分阶段选择目标用例: " + ", ".join(requested))
    return selected


def topic_for(case: dict[str, Any], gateway_sn: str, drone_sn: str) -> str:
    sn = gateway_sn if case["topic_key"] == "gateway" else drone_sn
    return f"thing/product/{sn}/services"


def make_payload(case: dict[str, Any], drone_sn: str) -> tuple[str, dict[str, Any]]:
    tid = str(uuid.uuid4())
    payload: dict[str, Any] = {
        "tid": tid,
        "bid": tid,
        "timestamp": int(time.time() * 1000),
        "method": case["method"],
        "data": case["data"],
    }
    if case["device_list"]:
        payload["device_list"] = [{"sn": drone_sn}]
    payload.update(case["top_level"])
    return tid, payload


def print_case(index: int, total: int, case: dict[str, Any], topic: str,
               payload: dict[str, Any]) -> None:
    print(f"\n>> [{index}/{total}] {case['id']} {case['label']}")
    print(f"   topic={topic}")
    print(f"   {json.dumps(payload, ensure_ascii=False, separators=(',', ':'))}")


def verify_look_at_coverage(args: argparse.Namespace,
                            cases: list[dict[str, Any]]) -> None:
    if args.method != "camera_look_at" or args.variant:
        return
    targets = [case for case in cases if case["kind"] == "target"]
    actual = {
        (
            case["topic_key"],
            case["dimensions"]["payload_index"],
            str(case["dimensions"]["locked"]),
            case["dimensions"]["device_list"],
        )
        for case in targets
    }
    expected = {
        (topic, with_payload, str(locked), with_device_list)
        for topic in _topic_keys(args.topic_mode)
        for with_payload in (False, True)
        for locked in ("absent", False, True)
        for with_device_list in (False, True)
    }
    if actual != expected:
        raise RuntimeError("Look At 字段矩阵覆盖校验失败")
    print(
        f"覆盖校验: {len(targets)} 个 Look At 用例 = "
        f"{len(_topic_keys(args.topic_mode))} topic × 2 payload_index × "
        "3 locked × 2 device_list"
    )


def response_result(body: dict[str, Any]) -> Any:
    data = body.get("data")
    return data.get("result") if isinstance(data, dict) else None


def write_output(args: argparse.Namespace, cases: list[dict[str, Any]],
                 results: list[dict[str, Any]]) -> None:
    if not args.output:
        return
    safe_cases = [
        {
            "id": case["id"],
            "kind": case["kind"],
            "method": case["method"],
            "topic": case["topic_key"],
            "device_list": case["device_list"],
            "data_fields": (
                sorted(case["data"])
                if isinstance(case["data"], dict) else None
            ),
            "dimensions": case["dimensions"],
        }
        for case in cases
    ]
    document = {
        "generated_at": int(time.time() * 1000),
        "mode": "execute" if args.execute else "dry-run",
        "method": args.method,
        "cases": safe_cases,
        "results": results,
    }
    args.output.expanduser().resolve().write_text(
        json.dumps(document, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"\n结果已写入 {args.output.expanduser().resolve()}")


def run_dry(args: argparse.Namespace, cases: list[dict[str, Any]]) -> int:
    print("DRY-RUN：不会连接 MQTT，也不会向设备下发指令。")
    results: list[dict[str, Any]] = []
    for index, case in enumerate(cases, 1):
        _, payload = make_payload(case, args.drone)
        topic = topic_for(case, args.gateway, args.drone)
        print_case(index, len(cases), case, topic, payload)
        results.append({"case_id": case["id"], "status": "dry-run"})
    print(f"\n离线生成完成：{len(cases)} 个报文，发布数为 0。")
    write_output(args, cases, results)
    return 0


def run_live(args: argparse.Namespace, cases: list[dict[str, Any]]) -> int:
    try:
        import paho.mqtt.client as mqtt
    except ImportError as exc:
        raise RuntimeError(
            "缺少 paho-mqtt；请使用 docs/python-demo/.venv/bin/python 或安装 "
            "docs/python-demo/requirements.txt"
        ) from exc

    reply_topics = sorted({
        f"thing/product/{args.gateway}/services_reply",
        f"thing/product/{args.drone}/services_reply",
    })
    gateway_reply_topic = f"thing/product/{args.gateway}/services_reply"
    connected = threading.Event()
    subscribed = threading.Event()
    reply_condition = threading.Condition()
    replies: list[dict[str, Any]] = []
    state: dict[str, Any] = {"connect_error": None, "subscribe_error": None}

    def on_connect(client: Any, _userdata: Any, _flags: Any,
                   reason_code: Any, _properties: Any) -> None:
        if reason_code != 0:
            state["connect_error"] = str(reason_code)
            connected.set()
            return
        connected.set()
        result, mid = client.subscribe([(topic, args.qos) for topic in reply_topics])
        if result != mqtt.MQTT_ERR_SUCCESS:
            state["subscribe_error"] = f"subscribe rc={result}"
            subscribed.set()
        else:
            state["subscribe_mid"] = mid

    def on_subscribe(_client: Any, _userdata: Any, mid: int,
                     reason_codes: Any, _properties: Any) -> None:
        if mid != state.get("subscribe_mid"):
            return
        failures = [str(code) for code in reason_codes if getattr(code, "is_failure", False)]
        if failures:
            state["subscribe_error"] = ", ".join(failures)
        subscribed.set()

    def on_message(_client: Any, _userdata: Any, msg: Any) -> None:
        received_at = time.monotonic()
        try:
            body = json.loads(msg.payload.decode("utf-8"))
            if not isinstance(body, dict):
                raise ValueError("JSON 顶层不是对象")
        except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as exc:
            print(f"  << [{msg.topic}] 无法解析的回复: {exc}")
            return
        entry = {"topic": msg.topic, "body": body, "received_at": received_at}
        with reply_condition:
            replies.append(entry)
            reply_condition.notify_all()
        print(
            f"  << [{msg.topic}] method={body.get('method')} "
            f"tid={body.get('tid')} bid={body.get('bid')} "
            f"result={response_result(body)}"
        )

    client = mqtt.Client(
        callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
        client_id=f"services-abtest-{uuid.uuid4().hex[:8]}",
        protocol=mqtt.MQTTv311,
    )
    if args.username or args.password:
        client.username_pw_set(args.username, args.password)
    client.on_connect = on_connect
    client.on_subscribe = on_subscribe
    client.on_message = on_message

    results: list[dict[str, Any]] = []
    try:
        print(f"连接 MQTT {args.host}:{args.port}，等待订阅确认…")
        client.connect(args.host, args.port, keepalive=30)
        client.loop_start()
        if not connected.wait(args.connect_timeout):
            raise RuntimeError("等待 MQTT CONNACK 超时，未发布任何测试报文")
        if state["connect_error"]:
            raise RuntimeError(f"MQTT 连接被拒绝: {state['connect_error']}")
        if not subscribed.wait(args.connect_timeout):
            raise RuntimeError("等待 services_reply SUBACK 超时，未发布任何测试报文")
        if state["subscribe_error"]:
            raise RuntimeError(f"services_reply 订阅失败: {state['subscribe_error']}")
        print("订阅已确认: " + ", ".join(reply_topics))

        for index, case in enumerate(cases, 1):
            tid, payload = make_payload(case, args.drone)
            expected_bid = payload["bid"]
            topic = topic_for(case, args.gateway, args.drone)
            print_case(index, len(cases), case, topic, payload)
            with reply_condition:
                before = len(replies)
            published_at = time.monotonic()
            info = client.publish(
                topic,
                json.dumps(payload, ensure_ascii=False, separators=(",", ":")),
                qos=args.qos,
            )
            if info.rc != mqtt.MQTT_ERR_SUCCESS:
                result = {
                    "case_id": case["id"], "status": "publish-error",
                    "detail": f"rc={info.rc}",
                }
                results.append(result)
                print(f"   ⚠️ 发布失败 rc={info.rc}")
                continue
            try:
                info.wait_for_publish(timeout=min(args.connect_timeout, 5.0))
            except RuntimeError as exc:
                results.append({
                    "case_id": case["id"], "status": "publish-error",
                    "detail": str(exc),
                })
                print(f"   ⚠️ MQTT 发布未完成: {exc}")
                continue

            deadline = time.monotonic() + args.wait
            related: list[dict[str, Any]] = []
            with reply_condition:
                while True:
                    related = [
                        entry for entry in replies[before:]
                        if entry["body"].get("tid") == tid
                    ]
                    exact = [
                        entry for entry in related
                        if entry["body"].get("bid") == expected_bid
                    ]
                    if exact:
                        break
                    remaining = deadline - time.monotonic()
                    if remaining <= 0:
                        break
                    reply_condition.wait(remaining)

            matched = [
                entry for entry in related
                if entry["body"].get("bid") == expected_bid
            ]
            if matched:
                entry = matched[0]
                body = entry["body"]
                latency_ms = round((entry["received_at"] - published_at) * 1000)
                method_matches = body.get("method") == case["method"]
                result_code = response_result(body)
                status = "reply" if method_matches else "reply-method-mismatch"
                results.append({
                    "case_id": case["id"], "status": status,
                    "result": result_code, "latency_ms": latency_ms,
                    "reply_topic": (
                        "gateway" if entry["topic"] == gateway_reply_topic else "drone"
                    ),
                    "reply_method": body.get("method"),
                })
                method_note = "" if method_matches else "，但 method 不匹配"
                print(
                    f"   ✅ 收到 tid/bid 匹配回复 result={result_code} "
                    f"latency={latency_ms}ms{method_note}"
                )
            elif related:
                actual_bids = [entry["body"].get("bid") for entry in related]
                results.append({
                    "case_id": case["id"], "status": "bid-mismatch",
                    "actual_bids": actual_bids,
                })
                print(
                    "   ⚠️ 收到同 tid 回复但 bid 缺失/不匹配；当前后端会拒绝关联，"
                    f"仍可能报 211001。actual_bid={actual_bids}"
                )
            else:
                results.append({"case_id": case["id"], "status": "no-reply"})
                print("   ❌ 等待期内无同 tid 回复")
    finally:
        try:
            client.disconnect()
        finally:
            client.loop_stop()

    print_summary(cases, results)
    write_output(args, cases, results)
    return 0


def print_summary(cases: list[dict[str, Any]],
                  results: list[dict[str, Any]]) -> None:
    by_id = {result["case_id"]: result for result in results}
    print("\n结果汇总")
    print("-" * 88)
    print(f"{'case':<22} {'kind':<8} {'status':<24} {'result':<10} {'latency':<10}")
    print("-" * 88)
    for case in cases:
        result = by_id.get(case["id"], {})
        latency = result.get("latency_ms")
        print(
            f"{case['id']:<22} {case['kind']:<8} "
            f"{result.get('status', 'missing'):<24} "
            f"{str(result.get('result', '-')):<10} "
            f"{(str(latency) + 'ms') if latency is not None else '-':<10}"
        )

    controls = [
        result for case in cases if case["kind"] == "control"
        for result in [by_id.get(case["id"], {})]
    ]
    targets = [
        result for case in cases if case["kind"] == "target"
        for result in [by_id.get(case["id"], {})]
    ]
    replied_statuses = {"reply"}
    controls_all_replied = bool(controls) and all(
        r.get("status") in replied_statuses and r.get("result") == 0
        for r in controls
    )
    target_replied = [r for r in targets if r.get("status") in replied_statuses]
    correlation_failures = [r for r in targets if r.get("status") == "bid-mismatch"]

    print("\n判定：")
    if not controls:
        print("- 未发送对照指令：只能记录变体现象，不能排除 MQTT 链路/权限问题。")
    elif not controls_all_replied:
        print("- 并非所有抢权/前后对照都有 method/tid/bid 匹配且 result=0 的回复。")
        print("- 链路/权限基线不成立，不能归因于字段或固件支持。")
    elif target_replied:
        accepted = ", ".join(result["case_id"] for result in target_replied)
        print(f"- 对照成立，目标 method 有 {len(target_replied)} 个变体收到回复: {accepted}")
        print("- 对比这些用例的 P/L/D 维度，确定固件实际要求的字段。")
    elif correlation_failures:
        print("- 设备对目标 method 有回复，但 bid 未正确回显；优先排查请求/回复关联规则。")
    else:
        print("- 对照成立，而目标 method 所有变体均零回复。")
        print("- 这强烈指向当前设备/RC 固件未实现该 method；最终结论需记录固件版本。")


def main(argv: list[str] | None = None) -> int:
    try:
        args, loaded_env_files = parse_args(argv)
        cases = build_cases(args)
        verify_look_at_coverage(args, cases)
        cases = select_cases(args, cases)
        if loaded_env_files:
            print("配置来源: " + ", ".join(str(path) for path in loaded_env_files))
        if args.dry_run:
            return run_dry(args, cases)
        return run_live(args, cases)
    except (FileNotFoundError, ValueError, RuntimeError, OSError) as exc:
        print(f"错误: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
