"""Python Demo 共用的配置校验、HTTP 调用、状态恢复与错误诊断。"""
from __future__ import annotations

import json
import time
from dataclasses import dataclass
from typing import Any, Iterable

import requests

from config import (
    BASE_URL,
    DOCK_SN,
    HTTP_CONNECT_TIMEOUT,
    HTTP_CONTROL_TIMEOUT,
    PILOT_BASE_URL,
    PILOT_FLAG,
    WEB_FLAG,
    WEB_PASSWORD,
    WEB_USERNAME,
    WORKSPACE_ID,
)


_NO_BODY = object()
_SESSION = requests.Session()

POINT_FLIGHT_TERMINAL_STATUSES = {
    "task_finish",
    "wayline_cancel",
    "wayline_failed",
    "wayline_ok",
    "command_failed",
    "cancel_confirmed",
}


class DemoError(RuntimeError):
    """可直接展示给 Demo 操作者的错误。"""


class DemoConfigError(DemoError):
    """配置缺失或格式无效。"""


@dataclass
class DemoApiError(DemoError):
    """HTTP/API 调用失败。

    ``ambiguous`` 为 True 表示客户端没有拿到可信响应。对 POST/PUT/DELETE
    来说，服务端或设备可能已经执行，调用方不得自动重发动作指令。
    """

    action: str
    message: str
    ambiguous: bool = False
    status_code: int | None = None
    api_code: Any = None

    def __str__(self) -> str:
        suffix = "（执行结果未知，禁止直接重发）" if self.ambiguous else ""
        return f"{self.action}失败: {self.message}{suffix}"


def is_placeholder(value: Any) -> bool:
    text = "" if value is None else str(value).strip()
    upper = text.upper()
    return (
        not text
        or upper.startswith("YOUR_")
        or upper in {"CHANGE_ME", "CHANGEME", "REPLACE_ME"}
    )


def require_config(**values: Any) -> None:
    """确保环境变量已配置，错误中只显示变量名，不回显敏感值。"""
    missing = [name for name, value in values.items() if is_placeholder(value)]
    if missing:
        joined = ", ".join(missing)
        raise DemoConfigError(
            f"缺少配置: {joined}。复制 .env.example 为 .env，填写后用 ./run.sh 运行"
        )


def validate_target(
    latitude: Any,
    longitude: Any,
    height: Any,
    speed: Any,
    *,
    integer_speed: bool = False,
) -> tuple[float, float, float, float]:
    """校验点飞/起飞目标，返回标准化数值。"""
    if any(value is None for value in (latitude, longitude, height, speed)):
        raise DemoConfigError(
            "缺少目标参数；请设置 YOOX_TARGET_LATITUDE/LONGITUDE/HEIGHT，"
            "或传入 --lat/--lon/--height"
        )
    try:
        lat, lon, target_height, max_speed = map(
            float, (latitude, longitude, height, speed)
        )
    except (TypeError, ValueError) as exc:
        raise DemoConfigError("目标经纬度、高度和速度必须是数字") from exc
    if not -90 <= lat <= 90 or not -180 <= lon <= 180 or (lat == 0 and lon == 0):
        raise DemoConfigError("目标必须是有效的非零 WGS84 经纬度")
    if not 2 <= target_height <= 1500:
        raise DemoConfigError("目标相对高度必须在 2–1500 m")
    if not 1 <= max_speed <= 15:
        raise DemoConfigError("最大速度必须在 1–15 m/s")
    if integer_speed and not max_speed.is_integer():
        raise DemoConfigError("FlyTo 的 max_speed 必须是 1–15 m/s 的整数")
    return lat, lon, target_height, max_speed


def _response_message(response: requests.Response, payload: Any = None) -> str:
    if isinstance(payload, dict):
        message = payload.get("message") or payload.get("msg")
        if message:
            return str(message)
    text = (response.text or "").strip().replace("\n", " ")
    return text[:300] or response.reason or "服务端未返回错误详情"


def api_call(
    token: str | None,
    method: str,
    path: str,
    *,
    action: str,
    json_body: Any = _NO_BODY,
    params: dict[str, Any] | None = None,
    timeout: float | None = None,
    allow_api_error: bool = False,
    base_url: str | None = None,
    files: Any = None,
) -> dict[str, Any]:
    """调用平台 API，不对动作请求进行隐式重试。

    传入 ``files`` 时改用 multipart/form-data 上传（如 KMZ 航线），requests
    会自动设置边界与 Content-Type，此时不应再传 ``json_body``。
    """
    method = method.upper()
    mutating = method in {"POST", "PUT", "PATCH", "DELETE"}
    headers = {"x-auth-token": token} if token else {}
    kwargs: dict[str, Any] = {
        "headers": headers,
        "params": params,
        "timeout": (HTTP_CONNECT_TIMEOUT, timeout or HTTP_CONTROL_TIMEOUT),
    }
    if json_body is not _NO_BODY:
        kwargs["json"] = json_body
    if files is not None:
        kwargs["files"] = files

    try:
        response = _SESSION.request(
            method, f"{(base_url or BASE_URL).rstrip('/')}{path}", **kwargs
        )
    except requests.RequestException as exc:
        raise DemoApiError(
            action,
            f"{exc.__class__.__name__}: {exc}",
            ambiguous=mutating,
        ) from exc

    try:
        payload = response.json()
    except (ValueError, json.JSONDecodeError) as exc:
        ambiguous = mutating and (
            response.status_code == 408
            or response.status_code >= 500
            or 200 <= response.status_code < 300
        )
        raise DemoApiError(
            action,
            f"HTTP {response.status_code} 但响应不是 JSON: {_response_message(response)}",
            ambiguous=ambiguous,
            status_code=response.status_code,
        ) from exc

    if not response.ok:
        # 408 或任意 5xx 都可能发生在后端/设备已接收动作之后。
        # 飞行类变更必须先查状态，不能因为不是 502/503/504 就盲目重发。
        ambiguous = mutating and (
            response.status_code == 408 or response.status_code >= 500
        )
        raise DemoApiError(
            action,
            f"HTTP {response.status_code}: {_response_message(response, payload)}",
            ambiguous=ambiguous,
            status_code=response.status_code,
            api_code=payload.get("code") if isinstance(payload, dict) else None,
        )
    if not isinstance(payload, dict):
        raise DemoApiError(
            action,
            "响应 JSON 不是对象",
            ambiguous=mutating,
            status_code=response.status_code,
        )
    if payload.get("code") != 0 and not allow_api_error:
        api_message = _response_message(response, payload)
        message_lower = api_message.lower()
        api_code = payload.get("code")
        ambiguous = mutating and (
            "211001" in str(api_code)
            or "211001" in api_message
            or "no message reply" in message_lower
            or "status is unknown" in message_lower
            or "reply was empty" in message_lower
        )
        raise DemoApiError(
            action,
            api_message,
            ambiguous=ambiguous,
            status_code=response.status_code,
            api_code=api_code,
        )
    return payload


def login_account(username: str, password: str, flag: int, label: str = "登录") -> dict[str, Any]:
    require_config(USERNAME=username, PASSWORD=password)
    payload = api_call(
        None,
        "POST",
        "/manage/api/v1/login",
        action=label,
        json_body={
            "username": username,
            "password": password,
            "flag": flag,
        },
        timeout=10,
        base_url=PILOT_BASE_URL if flag == PILOT_FLAG else BASE_URL,
    )
    data = payload.get("data") or {}
    if not isinstance(data, dict):
        raise DemoApiError(label, "成功响应中的 data 不是对象")
    return data


def login() -> str:
    data = login_account(WEB_USERNAME, WEB_PASSWORD, WEB_FLAG)
    token = data.get("access_token")
    if not token:
        raise DemoApiError("登录", "成功响应中缺少 access_token")
    return str(token)


def seize_flight_authority(token: str, sn: str = DOCK_SN) -> None:
    api_call(
        token,
        "POST",
        f"/control/api/v1/devices/{sn}/authority/flight",
        action="抢占飞行控制权",
        timeout=HTTP_CONTROL_TIMEOUT,
    )
    print("[✓] 已抢占飞行控制权 (flight_authority_grab)")


def seize_payload_authority(token: str, payload_index: str, sn: str = DOCK_SN) -> None:
    require_config(YOOX_PAYLOAD_INDEX=payload_index)
    api_call(
        token,
        "POST",
        f"/control/api/v1/devices/{sn}/authority/payload",
        action="抢占负载控制权",
        json_body={"payload_index": payload_index},
        timeout=HTTP_CONTROL_TIMEOUT,
    )
    print("[✓] 已获取负载控制权")


def send_payload_command(
    token: str,
    command: str,
    data: dict[str, Any],
    *,
    sn: str = DOCK_SN,
    timeout: float | None = None,
) -> dict[str, Any]:
    return api_call(
        token,
        "POST",
        f"/control/api/v1/devices/{sn}/payload/commands",
        action=f"负载指令 {command}",
        json_body={"cmd": command, "data": data},
        timeout=timeout or HTTP_CONTROL_TIMEOUT,
    )


def _unwrap_items(data: Any) -> list[dict[str, Any]]:
    if isinstance(data, list):
        return [item for item in data if isinstance(item, dict)]
    if isinstance(data, dict):
        for key in ("list", "records", "items"):
            value = data.get(key)
            if isinstance(value, list):
                return [item for item in value if isinstance(item, dict)]
    return []


def fetch_gateway_devices(token: str) -> list[dict[str, Any]]:
    """从服务端设备 API 获取网关及在线状态，不读取本机 Redis/Docker。"""
    if is_placeholder(WORKSPACE_ID):
        return []
    payload = api_call(
        token,
        "GET",
        f"/manage/api/v1/devices/{WORKSPACE_ID}/devices",
        action="查询设备列表",
        timeout=10,
    )
    return [
        item
        for item in _unwrap_items(payload.get("data"))
        if item.get("domain") in (2, 3)
    ]


def get_point_flight_state(token: str, sn: str = DOCK_SN) -> dict[str, Any] | None:
    payload = api_call(
        token,
        "GET",
        f"/control/api/v1/devices/{sn}/jobs/point-flight/status",
        action="查询点飞任务状态",
        timeout=10,
    )
    data = payload.get("data")
    return data if isinstance(data, dict) else None


def point_flight_task_id(state: dict[str, Any] | None) -> str:
    if not state:
        return ""
    return str(state.get("fly_to_id") or state.get("flight_id") or "")


def point_flight_is_terminal(state: dict[str, Any] | None) -> bool:
    if not state:
        return False
    status = str(state.get("status") or "")
    return status in POINT_FLIGHT_TERMINAL_STATUSES or state.get("active") is False


def print_point_flight_state(state: dict[str, Any] | None, prefix: str = "[状态]") -> None:
    if not state:
        print(f"{prefix} 服务端没有保存点飞任务")
        return
    task_id = point_flight_task_id(state) or "(身份尚未确认)"
    parts = [
        f"kind={state.get('kind', '?')}",
        f"status={state.get('status', '?')}",
        f"active={state.get('active', '?')}",
        f"task_id={task_id}",
    ]
    if state.get("remaining_distance") is not None:
        parts.append(f"remaining={state.get('remaining_distance')}m")
    if state.get("message"):
        parts.append(f"message={state.get('message')}")
    print(f"{prefix} " + "  ".join(parts))


def wait_for_new_point_flight_state(
    token: str,
    kind: str,
    baseline_updated_at: int = 0,
    *,
    sn: str = DOCK_SN,
    timeout: float = 12,
) -> dict[str, Any] | None:
    """在动作响应丢失时，从权威状态接口恢复本次任务身份。"""
    deadline = time.monotonic() + timeout
    last_error: DemoApiError | None = None
    while time.monotonic() < deadline:
        try:
            state = get_point_flight_state(token, sn)
            last_error = None
        except DemoApiError as exc:
            last_error = exc
            time.sleep(1)
            continue
        version = int((state or {}).get("updated_at") or 0)
        if (
            state
            and state.get("kind") == kind
            and point_flight_task_id(state)
            and version > baseline_updated_at
        ):
            return state
        time.sleep(0.75)
    if last_error:
        print(f"[!] 状态恢复期间查询失败: {last_error}")
    return None


def wait_for_point_flight_terminal(
    token: str,
    kind: str,
    task_id: str,
    *,
    sn: str = DOCK_SN,
    timeout: int = 300,
    terminal_event: Any = None,
) -> dict[str, Any] | None:
    """轮询服务端持久化状态；MQTT 不可达时仍能确认设备进度。"""
    print(f"[*] 等待任务终态（最长 {timeout}s；Ctrl+C 只停止监听，不会停止飞行）")
    deadline = time.monotonic() + timeout
    last_signature: tuple[Any, ...] | None = None
    consecutive_errors = 0
    try:
        while time.monotonic() < deadline:
            try:
                state = get_point_flight_state(token, sn)
                consecutive_errors = 0
            except DemoApiError as exc:
                consecutive_errors += 1
                if consecutive_errors in (1, 5):
                    print(f"[!] 进度状态查询失败，将继续监听: {exc}")
                time.sleep(1.5)
                continue
            if (
                state
                and state.get("kind") == kind
                and point_flight_task_id(state) == task_id
            ):
                signature = (
                    state.get("status"),
                    state.get("result"),
                    state.get("way_point_index"),
                    state.get("remaining_distance"),
                    state.get("remaining_time"),
                    state.get("active"),
                )
                if signature != last_signature:
                    print_point_flight_state(state, "[进度]")
                    last_signature = signature
                if point_flight_is_terminal(state):
                    return state
            # MQTT 终态只用于加速下一次权威查询，绝不直接决定成功/失败。
            mqtt_terminal_seen = terminal_event is not None and terminal_event.is_set()
            if mqtt_terminal_seen:
                terminal_event.clear()
            time.sleep(0.25 if mqtt_terminal_seen else 1.5)
    except KeyboardInterrupt:
        print("\n[!] 已停止本地监听；飞行任务仍可能继续。需要停止 FlyTo 时请运行 stop")
        return None
    print("[!] 等待超时；这不代表任务失败或停止，请继续查询 status/OSD，勿重复下发")
    return None


def _diagnostic_hint(server_msg: str) -> str:
    text = (server_msg or "").lower()
    if "not registered" in text or "210001" in text:
        return "服务端没有该设备记录；核对工作空间和 YOOX_DOCK_SN。"
    if "211001" in text or "no message reply" in text:
        return (
            "设备没有回复 services_reply。确认网关与飞机在线、固件支持该指令；"
            "当前服务端会为 RC 网关自动补 device_list。动作结果可能未知，先查状态/OSD。"
        )
    if "offline" in text:
        return "网关或子设备离线；用 demo_02 查询服务端状态并检查设备 MQTT 心跳。"
    if "already active" in text or "awaiting confirmation" in text:
        return (
            "已有点飞任务或上次结果待确认。先查询 .../jobs/point-flight/status；"
            "仅 FlyTo 可用 DELETE .../jobs/fly-to-point 停止，勿重复起飞。"
        )
    if "current state" in text or "must be on the ground" in text:
        return (
            "设备模式不满足：一键起飞要求地面 IDLE；FlyTo 要求已在空中且为 MANUAL；"
            "返航要求飞机在线。先核对 OSD mode_code。"
        )
    if "incorrect status" in text and "cancel" in text:
        return (
            "仅待执行(1)、执行中(2)、已暂停(6)航线可取消；先刷新任务列表，"
            "终态任务(3/4/5)不能重复取消。"
        )
    if "timed out" in text or "timeout" in text:
        return "客户端超时不等于设备未执行。先查任务状态和 OSD，确认后再决定是否重发。"
    if "payload" in text and ("index" in text or "authority" in text):
        return "负载索引/控制权可能不匹配；用 demo_02 或 OSD 获取实际 payload_index。"
    return "查看服务端日志、设备 OSD 与 services_reply；动作请求没有可靠响应时不要盲目重发。"


def diagnose(
    token: str,
    action: str,
    server_msg: str,
    exit_on_error: bool = True,
) -> bool:
    """兼容旧 Demo 的诊断入口；在线状态仅从服务端 API 获取。"""
    print(f"\n[✗] {action}失败")
    print(f"    服务端返回: {server_msg or '(无详情)'}")
    print(f"    [处理] {_diagnostic_hint(server_msg)}")
    print(f"\n    [诊断] 当前 YOOX_DOCK_SN = {DOCK_SN}")

    try:
        devices = fetch_gateway_devices(token)
    except DemoApiError as exc:
        devices = []
        print(f"    [诊断] 无法查询服务端设备列表: {exc}")
    if devices:
        print(f"    [诊断] 工作空间 {WORKSPACE_ID} 的网关：")
        found = False
        for device in devices:
            sn = str(device.get("device_sn") or "")
            child = device.get("child_device_sn") or device.get("aircraft_sn") or "(无)"
            online = device.get("status") is True
            marker = " ← 当前使用" if sn == DOCK_SN else ""
            print(f"      - {sn}  子设备: {child}  [{'在线' if online else '离线/未知'}]{marker}")
            found = found or sn == DOCK_SN
        if not found:
            print("      当前 SN 不在列表中，请改用该工作空间内的网关 SN。")
    elif is_placeholder(WORKSPACE_ID):
        print("    [诊断] 未配置 YOOX_WORKSPACE_ID，无法列出设备。")

    if exit_on_error:
        raise SystemExit(1)
    return False


def print_error_and_hint(error: DemoError) -> None:
    print(f"[✗] {error}")
    if isinstance(error, DemoApiError):
        print(f"    [处理] {_diagnostic_hint(error.message)}")
        if error.ambiguous:
            print("    [安全] 此次调用可能已到达服务端/设备，先核对权威状态与 OSD。")


def choose(items: Iterable[dict[str, Any]], prompt: str, render) -> dict[str, Any] | None:
    """交互选择工具；空输入或越界返回 None。"""
    values = list(items)
    for index, item in enumerate(values):
        print(f"  {index}. {render(item)}")
    raw = input(prompt).strip()
    if not raw.isdigit() or int(raw) >= len(values):
        return None
    return values[int(raw)]
