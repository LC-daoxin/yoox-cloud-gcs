"""读取当前两台在线飞机的实时位置，并发测试起飞 API（默认只预检）。

--execute 才发送动作；--props-removed 表示操作者确认两机均已拆桨，允许
近距离台架测试。客户端每机只 POST 一次；服务端 MQTT 重试策略不由本脚本控制。
报告中的时间差是客户端调用时间差，不是设备起飞时间差。
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import tempfile
import threading
import time

import websocket

from config import BASE_URL, WS_URL
import demo_common as common
from demo_22_two_drone_control import (
    AircraftIdentity, FlightPlan, read_baselines, seize_all, state_summary,
    validate_plan_separation, verify_online_topology, wait_for_new_state,
    wait_for_terminal,
)


def discover(token: str) -> list[AircraftIdentity]:
    devices = [
        d for d in common.fetch_gateway_devices(token)
        if d.get("status") is True
        and isinstance(d.get("children"), dict)
        and d["children"].get("status") is True
    ]
    if len(devices) != 2:
        raise common.DemoError(f"要求恰好两套网关和子飞机在线，当前为 {len(devices)} 套")
    identities = []
    for number, device in enumerate(sorted(devices, key=lambda d: d["device_sn"]), 1):
        gateway = str(device.get("device_sn") or "")
        aircraft = str(device.get("child_device_sn") or "")
        if not gateway or not aircraft or aircraft != device["children"].get("device_sn"):
            raise common.DemoError("网关与子飞机拓扑不完整或不一致")
        identities.append(AircraftIdentity(number, gateway, aircraft))
    if len({i.gateway_sn for i in identities}) != 2 or len({i.aircraft_sn for i in identities}) != 2:
        raise common.DemoError("两套设备的网关 SN 和飞机 SN 必须分别唯一")
    return identities


def read_osd(token: str, ws_url: str, identities: list[AircraftIdentity]) -> dict:
    """只接受本次连接收到的完整 OSD，不用历史缓存补 GPS 或 mode。"""
    wanted = {i.aircraft_sn for i in identities}
    latest = {}
    separator = "&" if "?" in ws_url else "?"
    connection = None
    try:
        connection = websocket.create_connection(
            f"{ws_url}{separator}x-auth-token={token}", timeout=3
        )
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            try:
                raw = connection.recv()
            except websocket.WebSocketTimeoutException:
                continue
            if not raw:
                break
            try:
                event = json.loads(raw)
            except ValueError:
                continue
            if not isinstance(event, dict) or event.get("biz_code") != "device_osd":
                continue
            data = event.get("data") or {}
            host = data.get("host") or {}
            sn = str(data.get("sn") or host.get("sn") or "")
            if sn not in wanted:
                continue
            latest[sn] = {
                "received_monotonic": time.monotonic(),
                **{k: host.get(k) for k in (
                    "mode_code", "latitude", "longitude", "height", "elevation",
                    "vertical_speed", "battery", "position_state",
                )},
            }
            if wanted <= latest.keys() and all(
                time.monotonic() - latest[sn]["received_monotonic"] < 3 for sn in wanted
            ):
                return latest
    except (OSError, websocket.WebSocketException):
        # 异常可能含带 Token 的 URL，不回显原始异常。
        raise common.DemoError("OSD WebSocket 连接失败或中断，请检查 --ws-url") from None
    finally:
        if connection is not None:
            connection.close()
    raise common.DemoError("15 秒内未收到两台飞机的新鲜完整 OSD，不下发起飞")


def make_plans(identities: list[AircraftIdentity], osd: dict, height: float) -> list[FlightPlan]:
    plans = []
    for identity in identities:
        state = osd[identity.aircraft_sn]
        if time.monotonic() - state["received_monotonic"] > 5:
            raise common.DemoError(f"{identity.label}: OSD 已过期")
        if type(state.get("mode_code")) is not int or state["mode_code"] != 0:
            raise common.DemoError(f"{identity.label}: 飞机不是地面 IDLE，拒绝起飞")
        lat, lon, checked_height, speed = common.validate_target(
            state.get("latitude"), state.get("longitude"), height, 1
        )
        plans.append(FlightPlan(identity, lat, lon, checked_height, int(speed)))
    return plans


def dispatch(token: str, plan: FlightPlan, baseline: int, barrier: threading.Barrier,
             wait_seconds: int, record: dict) -> None:
    try:
        barrier.wait(timeout=10)
    except threading.BrokenBarrierError:
        record.update(result="not_sent", error="并发屏障失败")
        return
    record["request_started_at"] = datetime.now(timezone.utc).isoformat()
    record["request_started_monotonic_ns"] = time.monotonic_ns()
    error = None
    try:
        response = common.api_call(
            token, "POST",
            f"/control/api/v1/devices/{plan.identity.gateway_sn}/jobs/takeoff-to-point",
            action=f"{plan.identity.label}起飞",
            json_body={"target_latitude": plan.latitude, "target_longitude": plan.longitude,
                       "target_height": plan.height, "max_speed": plan.speed},
        )
        record["api_response"] = response
        record["result"] = "accepted"
    except common.DemoApiError as exc:
        error = exc
        record.update(result="unknown" if exc.ambiguous else "rejected", error=str(exc))
    finally:
        record["request_elapsed_ms"] = round(
            (time.monotonic_ns() - record["request_started_monotonic_ns"]) / 1e6, 3
        )
    print(f"[{plan.identity.label}] API: {record['result']}", flush=True)
    # 包括明确拒绝在内，都尝试恢复这次命令的状态，绝不重发 POST。
    current = wait_for_new_state(token, plan.identity, "takeoff", baseline)
    record["state"] = current
    if current is None:
        if error is None or error.ambiguous:
            record["result"] = "unknown"
        return
    if not common.point_flight_is_terminal(current):
        final = wait_for_terminal(token, plan.identity, "takeoff",
                                  common.point_flight_task_id(current), wait_seconds)
        if final is None:
            record["result"] = "unknown"
            # 等待函数返回 None 时也保留最后一次可核对的状态，而非最初受理状态。
            latest = common.get_point_flight_state(token, plan.identity.gateway_sn)
            if (latest and common.point_flight_task_id(latest)
                    == common.point_flight_task_id(current)):
                record["state"] = latest
            return
        record["state"] = current = final
    # task_finish 可能只是地面待机触发的任务清理，不能视为起飞成功。
    status = current.get("status")
    if status == "wayline_ok" and current.get("result") in (0, "0"):
        record["result"] = "device_reported_success"
    elif status in {"wayline_failed", "command_failed", "wayline_cancel", "cancel_confirmed"}:
        record["result"] = "failed"
    elif error is None or error.ambiguous:
        record["result"] = "ended_unverified"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=BASE_URL)
    parser.add_argument("--ws-url", default=WS_URL)
    parser.add_argument("--height", type=float, default=2, help="目标相对高度，默认 2 m")
    parser.add_argument("--wait", type=int, default=30)
    parser.add_argument("--execute", action="store_true", help="明确授权本次并发起飞")
    parser.add_argument("--props-removed", action="store_true", help="确认两台均拆桨，仅做台架测试")
    parser.add_argument("--report", type=Path, help="新建 JSON 报告路径（不覆盖已有文件）")
    args = parser.parse_args()
    if args.wait < 1 or not math.isfinite(args.height) or not 2 <= args.height <= 1500:
        parser.error("wait 必须为正数，height 必须为 2–1500 m")
    common.BASE_URL = args.base_url.rstrip("/")
    if args.report:
        report_file = args.report.open("x", encoding="utf-8")
    else:
        report_file = tempfile.NamedTemporaryFile(mode="w", suffix=".json",
                                                prefix="dual-takeoff-", delete=False)
    print(f"报告: {report_file.name}", flush=True)
    report = {"base_url": common.BASE_URL, "bench_props_removed": args.props_removed,
              "execute": args.execute, "aircraft": [], "status": "preflight"}
    try:
        token = common.login()
        identities = discover(token)
        for i in identities:
            print(f"飞机{i.number}: {i.aircraft_sn} / 网关 {i.gateway_sn}", flush=True)
        verify_online_topology(token, identities)
        read_baselines(token, identities)
        osd = read_osd(token, args.ws_url, identities)
        plans = make_plans(identities, osd, args.height)
        if not args.props_removed:
            validate_plan_separation(plans, "起飞点")
        report["preflight_osd"] = osd
        if not args.execute:
            report["status"] = "preflight_passed"
            print("预检通过；未传 --execute，没有下发动作。")
            return 0
        seize_all(token, identities)
        verify_online_topology(token, identities)
        baselines = read_baselines(token, identities)
        # 获取控制权可能耗时，发动作前必须重新读取实时位置和地面状态。
        osd = read_osd(token, args.ws_url, identities)
        plans = make_plans(identities, osd, args.height)
        if not args.props_removed:
            validate_plan_separation(plans, "起飞点")
        report["dispatch_osd"] = osd
        report["status"] = "dispatching"
        barrier = threading.Barrier(2)
        with ThreadPoolExecutor(max_workers=2) as executor:
            futures = []
            for plan in plans:
                record = {"gateway_sn": plan.identity.gateway_sn,
                          "aircraft_sn": plan.identity.aircraft_sn,
                          "target": {"latitude": plan.latitude, "longitude": plan.longitude,
                                     "height": plan.height, "speed": plan.speed},
                          "result": "not_sent"}
                report["aircraft"].append(record)
                futures.append((executor.submit(dispatch, token, plan,
                    baselines[plan.identity.gateway_sn], barrier, args.wait, record), record))
            for future, record in futures:
                try:
                    future.result()
                except Exception as exc:
                    record.update(result="unknown", error=f"本地异常: {type(exc).__name__}")
        starts = [r["request_started_monotonic_ns"] for r in report["aircraft"]
                  if "request_started_monotonic_ns" in r]
        if len(starts) == 2:
            report["client_dispatch_gap_ms"] = round(abs(starts[0] - starts[1]) / 1e6, 3)
            print(f"客户端调用时间差: {report['client_dispatch_gap_ms']} ms（非实际起飞时间差）")
        for record in report["aircraft"]:
            print(f"{record['aircraft_sn']}: {record['result']} / {state_summary(record.get('state'))}")
        try:
            report["post_test_osd"] = read_osd(token, args.ws_url, identities)
        except common.DemoError as exc:
            report["post_test_osd_error"] = str(exc)
        report["status"] = "observed"
        return 0 if all(r["result"] == "device_reported_success" for r in report["aircraft"]) else 2
    except common.DemoError as exc:
        report.update(status="stopped", error=str(exc))
        print(str(exc), flush=True)
        return 1
    except KeyboardInterrupt:
        report["status"] = "interrupted"
        print("本地等待已中断；已受理的动作不因本地退出而停止，不要直接重发。")
        return 130
    finally:
        json.dump(report, report_file, ensure_ascii=False, indent=2)
        report_file.write("\n")
        report_file.close()


if __name__ == "__main__":
    raise SystemExit(main())
