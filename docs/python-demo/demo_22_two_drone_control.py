"""双机一键起飞与分别指点飞行的安全编排 Demo。

该脚本只控制两个明确配置的遥控器/机巢网关。它一次登录，在每个阶段先校验
两台设备和已有点飞状态，再并发抢权、并发下发一次命令，并按网关 SN 与任务
ID 分别恢复结果。任何超时或未知结果都不会自动重发，也不会继续下一阶段。

飞行 API 中使用的是网关 SN，不是飞机 SN；飞机 SN 只用于核对拓扑，防止把
命令发给接错飞机的遥控器。
"""
from __future__ import annotations

import argparse
import math
import os
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from typing import Any

from config import HTTP_CONTROL_TIMEOUT, POINT_FLIGHT_WAIT_SECONDS, WORKSPACE_ID
from demo_common import (
    DemoApiError,
    DemoConfigError,
    DemoError,
    api_call,
    fetch_gateway_devices,
    get_point_flight_state,
    login,
    point_flight_is_terminal,
    point_flight_task_id,
    print_error_and_hint,
    require_config,
    validate_target,
)


FAILED_STATUSES = {
    "wayline_failed",
    "command_failed",
    "cancel_failed",
    "wayline_cancel",
    "cancel_confirmed",
}
PRINT_LOCK = threading.Lock()


@dataclass(frozen=True)
class AircraftIdentity:
    number: int
    gateway_sn: str
    aircraft_sn: str

    @property
    def label(self) -> str:
        return f"飞机{self.number}({self.aircraft_sn})"


@dataclass(frozen=True)
class FlightPlan:
    identity: AircraftIdentity
    latitude: float
    longitude: float
    height: float
    speed: int


@dataclass(frozen=True)
class CommandOutcome:
    identity: AircraftIdentity
    kind: str
    result: str  # success / failed / unknown
    message: str
    state: dict[str, Any] | None = None

    @property
    def succeeded(self) -> bool:
        return self.result == "success"


def say(message: str) -> None:
    with PRINT_LOCK:
        print(message, flush=True)


def env_text(name: str) -> str:
    return os.getenv(name, "").strip()


def env_float(name: str) -> float | None:
    raw = env_text(name)
    if not raw:
        return None
    try:
        return float(raw)
    except ValueError as exc:
        raise DemoConfigError(f"环境变量 {name} 必须是数字") from exc


def load_identities() -> list[AircraftIdentity]:
    require_config(YOOX_WORKSPACE_ID=WORKSPACE_ID)
    identities = [
        AircraftIdentity(
            number=index,
            gateway_sn=env_text(f"YOOX_DUAL_GATEWAY_SN_{index}"),
            aircraft_sn=env_text(f"YOOX_DUAL_AIRCRAFT_SN_{index}"),
        )
        for index in (1, 2)
    ]
    for identity in identities:
        require_config(
            **{
                f"YOOX_DUAL_GATEWAY_SN_{identity.number}": identity.gateway_sn,
                f"YOOX_DUAL_AIRCRAFT_SN_{identity.number}": identity.aircraft_sn,
            }
        )
    if identities[0].gateway_sn == identities[1].gateway_sn:
        raise DemoConfigError("两台飞机不能配置相同的网关 SN")
    if identities[0].aircraft_sn == identities[1].aircraft_sn:
        raise DemoConfigError("两台飞机不能配置相同的飞机 SN")
    return identities


def shared_speed() -> int:
    speed = env_float("YOOX_DUAL_MAX_SPEED")
    speed = 5.0 if speed is None else speed
    if not speed.is_integer() or not 1 <= speed <= 15:
        raise DemoConfigError("YOOX_DUAL_MAX_SPEED 必须是 1–15 m/s 的整数")
    return int(speed)


def load_takeoff_plans(identities: list[AircraftIdentity]) -> list[FlightPlan]:
    height = env_float("YOOX_DUAL_TAKEOFF_HEIGHT")
    speed = shared_speed()
    plans: list[FlightPlan] = []
    for identity in identities:
        lat_name = f"YOOX_DUAL_TAKEOFF_LATITUDE_{identity.number}"
        lon_name = f"YOOX_DUAL_TAKEOFF_LONGITUDE_{identity.number}"
        latitude = env_float(lat_name)
        longitude = env_float(lon_name)
        require_config(**{lat_name: latitude, lon_name: longitude})
        require_config(YOOX_DUAL_TAKEOFF_HEIGHT=height)
        lat, lon, checked_height, checked_speed = validate_target(
            latitude, longitude, height, speed
        )
        plans.append(
            FlightPlan(identity, lat, lon, checked_height, int(checked_speed))
        )
    validate_plan_separation(plans, "起飞点")
    return plans


def load_flyto_plans(identities: list[AircraftIdentity]) -> list[FlightPlan]:
    speed = shared_speed()
    plans: list[FlightPlan] = []
    for identity in identities:
        lat_name = f"YOOX_DUAL_FLYTO_LATITUDE_{identity.number}"
        lon_name = f"YOOX_DUAL_FLYTO_LONGITUDE_{identity.number}"
        height_name = f"YOOX_DUAL_FLYTO_HEIGHT_{identity.number}"
        latitude = env_float(lat_name)
        longitude = env_float(lon_name)
        height = env_float(height_name)
        require_config(**{lat_name: latitude, lon_name: longitude, height_name: height})
        lat, lon, checked_height, checked_speed = validate_target(
            latitude, longitude, height, speed, integer_speed=True
        )
        plans.append(
            FlightPlan(identity, lat, lon, checked_height, int(checked_speed))
        )
    validate_plan_separation(plans, "FlyTo 目标")
    return plans


def distance_metres(first: FlightPlan, second: FlightPlan) -> float:
    radius = 6_371_000.0
    lat1 = math.radians(first.latitude)
    lat2 = math.radians(second.latitude)
    d_lat = lat2 - lat1
    d_lon = math.radians(second.longitude - first.longitude)
    value = (
        math.sin(d_lat / 2) ** 2
        + math.cos(lat1) * math.cos(lat2) * math.sin(d_lon / 2) ** 2
    )
    value = min(1.0, max(0.0, value))
    return radius * 2 * math.atan2(math.sqrt(value), math.sqrt(1 - value))


def validate_plan_separation(plans: list[FlightPlan], label: str) -> None:
    separation = distance_metres(plans[0], plans[1])
    if separation < 10:
        raise DemoConfigError(
            f"两台飞机的{label}仅相距约 {separation:.1f} m；为降低冲突风险，至少配置 10 m 间隔"
        )


def child_status(device: dict[str, Any]) -> bool | None:
    child = device.get("children")
    return child.get("status") if isinstance(child, dict) else None


def verify_online_topology(
    token: str, identities: list[AircraftIdentity]
) -> None:
    devices = {str(item.get("device_sn") or ""): item for item in fetch_gateway_devices(token)}
    errors: list[str] = []
    for identity in identities:
        device = devices.get(identity.gateway_sn)
        if not device:
            errors.append(f"{identity.label}: 工作空间中不存在网关 {identity.gateway_sn}")
            continue
        actual_aircraft = str(
            device.get("child_device_sn") or device.get("aircraft_sn") or ""
        )
        if actual_aircraft != identity.aircraft_sn:
            errors.append(
                f"{identity.label}: 网关当前子飞机是 {actual_aircraft or '(无)'}，与配置不一致"
            )
        if device.get("status") is not True:
            errors.append(f"{identity.label}: 网关不在线")
        if child_status(device) is not True:
            errors.append(f"{identity.label}: 子飞机不在线或在线状态未知")
    if errors:
        raise DemoError("双机拓扑预检失败：\n  - " + "\n  - ".join(errors))


def state_summary(state: dict[str, Any] | None) -> str:
    if not state:
        return "无点飞任务"
    return (
        f"kind={state.get('kind', '?')} status={state.get('status', '?')} "
        f"active={state.get('active', '?')} task_id={point_flight_task_id(state) or '?'}"
    )


def read_baselines(
    token: str, identities: list[AircraftIdentity]
) -> dict[str, int]:
    baselines: dict[str, int] = {}
    active: list[str] = []
    for identity in identities:
        state = get_point_flight_state(token, identity.gateway_sn)
        say(f"[{identity.label}] {state_summary(state)}")
        if state and state.get("active") is True:
            active.append(identity.label)
        baselines[identity.gateway_sn] = int((state or {}).get("updated_at") or 0)
    if active:
        raise DemoError(
            "以下设备仍有活动任务或结果待确认，拒绝下发新命令：" + "、".join(active)
        )
    return baselines


def seize_one(token: str, identity: AircraftIdentity) -> None:
    api_call(
        token,
        "POST",
        f"/control/api/v1/devices/{identity.gateway_sn}/authority/flight",
        action=f"{identity.label}抢占飞行控制权",
        timeout=HTTP_CONTROL_TIMEOUT,
    )
    say(f"[{identity.label}] 已抢占飞行控制权")


def seize_all(token: str, identities: list[AircraftIdentity]) -> None:
    failures: list[str] = []
    with ThreadPoolExecutor(max_workers=2, thread_name_prefix="dual-authority") as executor:
        futures = {executor.submit(seize_one, token, item): item for item in identities}
        for future in as_completed(futures):
            identity = futures[future]
            try:
                future.result()
            except Exception as exc:
                failures.append(f"{identity.label}: {exc}")
    if failures:
        raise DemoError(
            "至少一台飞机未能取得飞行控制权；尚未下发飞行动作：\n  - "
            + "\n  - ".join(failures)
        )


def wait_for_new_state(
    token: str,
    identity: AircraftIdentity,
    kind: str,
    baseline: int,
    timeout: float = 15,
) -> dict[str, Any] | None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            state = get_point_flight_state(token, identity.gateway_sn)
        except DemoApiError:
            time.sleep(1)
            continue
        updated_at = int((state or {}).get("updated_at") or 0)
        if (
            state
            and state.get("kind") == kind
            and point_flight_task_id(state)
            and updated_at > baseline
        ):
            return state
        time.sleep(0.75)
    return None


def wait_for_terminal(
    token: str,
    identity: AircraftIdentity,
    kind: str,
    task_id: str,
    timeout: int,
) -> dict[str, Any] | None:
    deadline = time.monotonic() + timeout
    last_summary = ""
    while time.monotonic() < deadline:
        try:
            state = get_point_flight_state(token, identity.gateway_sn)
        except DemoApiError as exc:
            say(f"[{identity.label}] 状态查询暂时失败，将继续监听: {exc}")
            time.sleep(1.5)
            continue
        if (
            state
            and state.get("kind") == kind
            and point_flight_task_id(state) == task_id
        ):
            summary = state_summary(state)
            if summary != last_summary:
                say(f"[{identity.label}] {summary}")
                last_summary = summary
            if point_flight_is_terminal(state):
                return state
        time.sleep(1.5)
    return None


def state_succeeded(state: dict[str, Any]) -> bool:
    status = str(state.get("status") or "")
    result = state.get("result")
    return status not in FAILED_STATUSES and result in (None, 0, "0")


def dispatch_one(
    token: str,
    plan: FlightPlan,
    kind: str,
    baseline: int,
    wait_seconds: int,
    start_barrier: threading.Barrier,
) -> CommandOutcome:
    identity = plan.identity
    if kind == "takeoff":
        path = f"/control/api/v1/devices/{identity.gateway_sn}/jobs/takeoff-to-point"
        body: dict[str, Any] = {
            "target_longitude": plan.longitude,
            "target_latitude": plan.latitude,
            "target_height": round(plan.height, 1),
            "max_speed": plan.speed,
        }
        action = f"{identity.label}一键起飞"
    else:
        path = f"/control/api/v1/devices/{identity.gateway_sn}/jobs/fly-to-point"
        body = {
            "max_speed": plan.speed,
            "points": [
                {
                    "latitude": plan.latitude,
                    "longitude": plan.longitude,
                    "height": plan.height,
                }
            ],
        }
        action = f"{identity.label}指点飞行"

    try:
        start_barrier.wait(timeout=10)
    except threading.BrokenBarrierError:
        return CommandOutcome(identity, kind, "failed", "双机并发屏障失败，未下发")

    ambiguous_error: DemoApiError | None = None
    try:
        api_call(token, "POST", path, action=action, json_body=body)
        say(f"[{identity.label}] {action} HTTP 已返回成功，等待任务状态")
    except DemoApiError as exc:
        say(f"[{identity.label}] {exc}")
        if not exc.ambiguous:
            return CommandOutcome(identity, kind, "failed", str(exc))
        ambiguous_error = exc

    current = wait_for_new_state(token, identity, kind, baseline)
    if not current:
        message = (
            "请求结果未知且未恢复任务 ID；禁止重发，请检查 OSD/状态"
            if ambiguous_error
            else "服务端返回成功但未恢复任务 ID；禁止重发，请稍后查询状态"
        )
        return CommandOutcome(identity, kind, "unknown", message)

    task_id = point_flight_task_id(current)
    say(f"[{identity.label}] 已恢复任务 {task_id}: {state_summary(current)}")
    final = current if point_flight_is_terminal(current) else wait_for_terminal(
        token, identity, kind, task_id, wait_seconds
    )
    if final is None:
        return CommandOutcome(
            identity, kind, "unknown", "等待任务终态超时；任务可能仍在执行"
        )
    if state_succeeded(final):
        return CommandOutcome(identity, kind, "success", "任务已进入成功终态", final)
    return CommandOutcome(
        identity, kind, "failed", f"任务终态失败: {state_summary(final)}", final
    )


def run_phase(
    token: str,
    plans: list[FlightPlan],
    kind: str,
    wait_seconds: int,
) -> list[CommandOutcome]:
    identities = [plan.identity for plan in plans]
    verify_online_topology(token, identities)
    baselines = read_baselines(token, identities)
    seize_all(token, identities)
    # 抢权成功后再取一次基线，覆盖抢权期间可能到达的旧状态更新。
    baselines = read_baselines(token, identities)

    barrier = threading.Barrier(len(plans))
    outcomes: list[CommandOutcome] = []
    with ThreadPoolExecutor(max_workers=2, thread_name_prefix=f"dual-{kind}") as executor:
        futures = {
            executor.submit(
                dispatch_one,
                token,
                plan,
                kind,
                baselines[plan.identity.gateway_sn],
                wait_seconds,
                barrier,
            ): plan.identity
            for plan in plans
        }
        for future in as_completed(futures):
            identity = futures[future]
            try:
                outcomes.append(future.result())
            except Exception as exc:  # 保留另一台的结果，不能让线程异常掩盖部分执行。
                outcomes.append(
                    CommandOutcome(identity, kind, "unknown", f"本地线程异常: {exc}")
                )
    outcomes.sort(key=lambda item: item.identity.number)
    print_outcomes(outcomes)
    return outcomes


def print_outcomes(outcomes: list[CommandOutcome]) -> None:
    say("\n阶段结果：")
    for outcome in outcomes:
        marker = {"success": "✓", "failed": "✗", "unknown": "?"}[outcome.result]
        say(f"  [{marker}] {outcome.identity.label}: {outcome.result} - {outcome.message}")


def show_plans(title: str, plans: list[FlightPlan]) -> None:
    say(f"\n{title}：")
    for plan in plans:
        say(
            f"  - {plan.identity.label} 网关={plan.identity.gateway_sn} "
            f"WGS84=({plan.latitude:.7f}, {plan.longitude:.7f}) "
            f"高度={plan.height:.1f}m 速度={plan.speed}m/s"
        )
    say(f"  - 两目标水平间隔约 {distance_metres(plans[0], plans[1]):.1f} m")


def confirm(prompt: str, phrase: str) -> bool:
    try:
        answer = input(f"{prompt}\n确认请输入 {phrase}: ").strip()
    except EOFError:
        return False
    if answer != phrase:
        say("[*] 输入不匹配，已取消；没有下发本阶段飞行动作")
        return False
    return True


def show_status(token: str, identities: list[AircraftIdentity]) -> int:
    try:
        verify_online_topology(token, identities)
    except DemoError as exc:
        # 状态查询也是动作结果未知后的恢复手段。即使设备已经离线，也要继续
        # 展示服务端最后保存的任务状态，不能让在线预检遮住恢复信息。
        say(f"[!] {exc}")
    failed = False
    for identity in identities:
        try:
            state = get_point_flight_state(token, identity.gateway_sn)
            say(f"[{identity.label}] 网关={identity.gateway_sn} {state_summary(state)}")
        except DemoApiError as exc:
            failed = True
            say(f"[{identity.label}] 状态查询失败: {exc}")
    return 1 if failed else 0


def result_code(outcomes: list[CommandOutcome]) -> int:
    if all(outcome.succeeded for outcome in outcomes):
        return 0
    if any(outcome.result == "unknown" for outcome in outcomes):
        return 2
    return 1


def run_takeoff(
    token: str,
    identities: list[AircraftIdentity],
    wait_seconds: int,
    plans: list[FlightPlan] | None = None,
) -> list[CommandOutcome] | None:
    plans = plans or load_takeoff_plans(identities)
    show_plans("双机起飞计划（起飞经纬度通常应为各自当前 GPS）", plans)
    if not confirm(
        "[!!] 确认两台飞机均在地面 IDLE、螺旋桨区域净空且现场飞手已就位。",
        "TAKEOFF TWO",
    ):
        return None
    return run_phase(token, plans, "takeoff", wait_seconds)


def run_flyto(
    token: str,
    identities: list[AircraftIdentity],
    wait_seconds: int,
    plans: list[FlightPlan] | None = None,
) -> list[CommandOutcome] | None:
    plans = plans or load_flyto_plans(identities)
    show_plans("双机 FlyTo 计划", plans)
    if not confirm(
        "[!!] 确认两机已在空中 MANUAL，完整航迹净空且目标之间不存在冲突。",
        "FLYTO TWO",
    ):
        return None
    return run_phase(token, plans, "flyto", wait_seconds)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="YOOX 双机安全起飞与分别指点飞行")
    parser.add_argument(
        "action",
        nargs="?",
        choices=("status", "takeoff", "flyto", "all"),
        default="status",
        help="默认 status；all 会在起飞全部成功后再次确认并执行 FlyTo",
    )
    parser.add_argument(
        "--wait",
        type=int,
        default=POINT_FLIGHT_WAIT_SECONDS,
        help="每阶段等待每台飞机终态的最长秒数",
    )
    return parser


def main() -> int:
    args = build_parser().parse_args()
    if args.wait < 1:
        raise DemoConfigError("--wait 必须大于 0")
    identities = load_identities()
    takeoff_plans = (
        load_takeoff_plans(identities)
        if args.action in ("takeoff", "all")
        else None
    )
    flyto_plans = (
        load_flyto_plans(identities)
        if args.action in ("flyto", "all")
        else None
    )
    token = login()
    say("[✓] Web 登录成功；开始核对双机拓扑")

    if args.action == "status":
        return show_status(token, identities)
    if args.action in ("takeoff", "all"):
        outcomes = run_takeoff(token, identities, args.wait, takeoff_plans)
        if outcomes is None:
            return 0
        code = result_code(outcomes)
        if code != 0 or args.action == "takeoff":
            if code != 0 and args.action == "all":
                say("[!!] 起飞未全部明确成功，安全停止后续 FlyTo 阶段")
            return code
    if args.action in ("flyto", "all"):
        outcomes = run_flyto(token, identities, args.wait, flyto_plans)
        return 0 if outcomes is None else result_code(outcomes)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except DemoError as exc:
        print_error_and_hint(exc)
        raise SystemExit(1)
    except KeyboardInterrupt:
        print("\n[!!] 已停止本地编排；已经受理的飞行动作可能仍在继续。")
        print("     立即分别核对两台飞机的 OSD/现场状态，必要时执行悬停或返航处置。")
        raise SystemExit(130)
