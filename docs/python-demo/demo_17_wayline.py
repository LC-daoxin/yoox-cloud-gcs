"""航线任务全流程：上传 KMZ、下发执行、进度上报、暂停、继续与取消。

上传：菜单项 6 把本地 ``.kmz`` 以 multipart 上传到 ``waylines/file/upload``，
服务端会校验 KMZ 结构（wpmz 模板、无人机/负载型号枚举、UTF-8 编码）。

任务类型：本 Demo 下发的是 **立即任务**（``task_type=0``，prepare 成功后
立即 execute）。服务端还支持定时（``1``）与条件（``2``）任务，但需额外
的执行时间/就绪条件参数，不在本 Demo 演示范围内。

REST 接口由当前 GCS 服务端负责转换为 ``flighttask_prepare/execute/pause/
recovery/undo``。RC 网关的 ``device_list`` 也由服务端统一补齐，Demo 不直接
拼 MQTT 控制报文。任何动作请求超时都只刷新状态，不会自动重发。
"""
from __future__ import annotations

import json
import os
import queue
import threading
import time
from pathlib import Path
from typing import Any, Callable

import paho.mqtt.client as mqtt

from config import (
    DOCK_SN,
    DRONE_SN,
    MQTT_HOST,
    MQTT_PASSWORD,
    MQTT_PORT,
    MQTT_USERNAME,
    WORKSPACE_ID,
)
from demo_common import (
    DemoApiError,
    DemoError,
    api_call,
    choose,
    login,
    print_error_and_hint,
    require_config,
)

# 子飞机 SN：优先用 DRONE_SN，未配置时从网关详情接口的 child_device_sn 解析。
GATEWAY_CHILD_SN = DRONE_SN if DRONE_SN not in ("", "YOUR_DRONE_SN") else ""

MODE_LABELS = {
    0: "停桨待机", 1: "起飞准备", 2: "起飞完成", 3: "手动悬停", 4: "自动起飞",
    5: "航线执行", 6: "全景拍摄", 7: "智能跟踪", 8: "ADS-B 避让", 9: "自动返航",
    10: "自动降落", 11: "强制降落", 12: "三桨降落", 13: "升级中", 14: "失联",
    15: "APAS", 16: "虚拟摇杆", 17: "指令飞行", 18: "RTK 固定", 19: "机巢评估",
    20: "兴趣环绕", 37: "指点飞行", 39: "KML 航线",
}


JOB_STATUS = {
    1: "待执行",
    2: "执行中",
    3: "成功",
    4: "已取消",
    5: "失败",
    6: "已暂停",
}
PAUSABLE = {2}
RESUMABLE = {6}
CANCELABLE = {1, 2, 4, 6}
FINAL_JOB_STATUS = {3, 4, 5}

FLIGHTTASK_STATUS = {
    "sent": "已下发",
    "in_progress": "执行中",
    "paused": "已暂停",
    "ok": "执行成功",
    "failed": "失败",
    "canceled": "已取消",
    "partially_done": "部分完成",
    "rejected": "已拒绝",
    "timeout": "超时",
    "pending": "准备执行",
}


def _items(data: Any) -> list[dict[str, Any]]:
    if isinstance(data, list):
        return [item for item in data if isinstance(item, dict)]
    if isinstance(data, dict):
        for key in ("list", "records", "items"):
            value = data.get(key)
            if isinstance(value, list):
                return [item for item in value if isinstance(item, dict)]
    return []


def list_waylines(token: str) -> list[dict[str, Any]]:
    result = api_call(
        token,
        "GET",
        f"/wayline/api/v1/workspaces/{WORKSPACE_ID}/waylines",
        action="查询航线库",
        params={"page": 1, "page_size": 50, "order_by": "update_time desc"},
        timeout=15,
    )
    return _items(result.get("data"))


def list_jobs(token: str) -> list[dict[str, Any]]:
    result = api_call(
        token,
        "GET",
        f"/wayline/api/v1/workspaces/{WORKSPACE_ID}/jobs",
        action="查询航线任务",
        params={"page": 1, "page_size": 50},
        timeout=15,
    )
    return _items(result.get("data"))


def _job_id(job: dict[str, Any]) -> str:
    return str(job.get("job_id") or "")


def _job_status(job: dict[str, Any]) -> int:
    try:
        return int(job.get("status"))
    except (TypeError, ValueError):
        return -1


def _job_line(job: dict[str, Any]) -> str:
    status = _job_status(job)
    return (
        f"{job.get('job_name') or '(未命名)'} | {JOB_STATUS.get(status, status)} "
        f"| {job.get('progress', 0) or 0}% | id={_job_id(job)}"
    )


def _find_job(token: str, job_id: str) -> dict[str, Any] | None:
    return next((job for job in list_jobs(token) if _job_id(job) == job_id), None)


def _wait_job(
    token: str,
    predicate: Callable[[dict[str, Any]], bool],
    *,
    timeout: float = 12,
) -> dict[str, Any] | None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        for job in list_jobs(token):
            if predicate(job):
                return job
        time.sleep(1)
    return None


def upload_wayline(token: str) -> bool:
    """上传本地 KMZ 到航线库（multipart）。后端校验 KMZ 结构与设备型号。"""
    default = os.getenv("YOOX_WAYLINE_KMZ", "").strip()
    prompt = f"输入本地 KMZ 路径{f'（回车用 {default}）' if default else ''}> "
    raw = input(prompt).strip() or default
    if not raw:
        print("[!] 未提供 KMZ 路径")
        return False
    path = Path(raw).expanduser()
    if not path.is_file():
        print(f"[✗] 文件不存在: {path}")
        return False
    if path.suffix.lower() != ".kmz":
        print("[✗] 仅支持 .kmz（KMZ 内需含 wpmz 模板与匹配的无人机/负载型号）")
        return False
    print(f"[*] 上传航线文件: {path.name}（{path.stat().st_size} 字节）")
    try:
        with path.open("rb") as handle:
            api_call(
                token,
                "POST",
                f"/wayline/api/v1/workspaces/{WORKSPACE_ID}/waylines/file/upload",
                action="上传 KMZ 航线文件",
                files={"file": (path.name, handle, "application/vnd.google-earth.kmz")},
                timeout=60,
            )
    except DemoApiError as exc:
        print_error_and_hint(exc)
        # 后端会校验 KMZ 结构（型号枚举、UTF-8、wpmz 模板）；格式不符返回 4xx。
        return False
    print("[✓] 上传成功；已加入航线库，可用菜单项 1 下发执行")
    return True


def create_job(token: str, wayline: dict[str, Any], watcher: "ProgressWatcher | None" = None) -> bool:
    file_id = str(wayline.get("id") or "")
    if not file_id:
        print("[✗] 航线记录缺少 id，不能下发")
        return False
    # 固件限制（官方文档）：航线任务指令需要在无人机关机或停桨时才能执行。
    # mode_code 已知且非 0 时直接拦截，避免设备侧 104 拒绝后留下失败任务。
    if watcher is not None and watcher.aircraft_mode_code is not None:
        mode_code = watcher.aircraft_mode_code
        if mode_code != 0:
            print(
                f"[✗] 飞机当前状态为“{MODE_LABELS.get(mode_code, mode_code)}”(mode_code={mode_code})，"
                "航线任务需飞机停桨在地面才能执行；请先降落或返航后再下发"
            )
            return False
    template_types = wayline.get("template_types") or [0]
    task_name = f"{wayline.get('name') or 'wayline'}-demo-{int(time.time() * 1000)}"
    body = {
        "name": task_name,
        "file_id": file_id,
        "dock_sn": DOCK_SN,
        "wayline_type": template_types[0],
        # 立即任务：服务端成功 prepare 后立即 execute。
        "task_type": 0,
        "rth_altitude": 100,
        "out_of_control_action": 0,
        "min_battery_capacity": 50,
        "min_storage_capacity": 0,
        "wayline_precision_type": 0,
        "barrier_switch_state": 1,
        "takeoff_altitude": 100,
        "first_waypoint_speed": 10,
        "return_speed": 10,
        "media_upload_method": 0,
        "alternate_land_point": {"is_configured": 0},
    }
    print(f"[*] 下发并执行航线: {task_name}")
    if input(
        f"[!!] 确认立即执行真实航线？dock={DOCK_SN} "
        f"rth={body['rth_altitude']}m takeoff={body['takeoff_altitude']}m；输入 YES: "
    ).strip() != "YES":
        print("[*] 已取消航线下发")
        return False
    ambiguous = False
    try:
        api_call(
            token,
            "POST",
            f"/wayline/api/v1/workspaces/{WORKSPACE_ID}/flight-tasks",
            action="下发并执行航线任务",
            json_body=body,
            # prepare + execute 各自等待设备回复，给同步链路更充足时间。
            timeout=45,
        )
    except DemoApiError as exc:
        print_error_and_hint(exc)
        if not exc.ambiguous:
            return False
        ambiguous = True

    # 创建接口不返回 job_id，以唯一任务名从列表恢复；超时时同样禁止重发。
    job = _wait_job(token, lambda item: item.get("job_name") == task_name, timeout=15)
    if not job:
        print("[!!] 未能在任务列表中恢复本次任务；不要重复创建，稍后按任务名查询。")
        return False
    print(f"[{'恢复' if ambiguous else '✓'}] {_job_line(job)}")
    return True


def change_job(token: str, job: dict[str, Any], target_status: int) -> bool:
    job_id = _job_id(job)
    action = "暂停" if target_status == 0 else "继续"
    allowed = PAUSABLE if target_status == 0 else RESUMABLE

    # 每次动作前重新读取，避免使用菜单打开后的陈旧状态。
    current = _find_job(token, job_id)
    if not current:
        print("[✗] 任务已不存在或不属于当前工作空间")
        return False
    status = _job_status(current)
    if status not in allowed:
        print(
            f"[✗] 当前状态为 {JOB_STATUS.get(status, status)}，"
            f"不能{action}；请刷新后选择正确任务"
        )
        return False

    ambiguous = False
    try:
        api_call(
            token,
            "PUT",
            f"/wayline/api/v1/workspaces/{WORKSPACE_ID}/jobs/{job_id}",
            action=f"{action}航线任务",
            json_body={"status": target_status},
            timeout=25,
        )
    except DemoApiError as exc:
        print_error_and_hint(exc)
        if not exc.ambiguous:
            return False
        ambiguous = True

    expected = 6 if target_status == 0 else 2
    updated = _wait_job(
        token,
        lambda item: _job_id(item) == job_id and _job_status(item) == expected,
        timeout=10,
    )
    if updated:
        print(f"[{'恢复' if ambiguous else '✓'}] {action}已确认: {_job_line(updated)}")
        return True
    print(f"[!!] {action}结果尚未确认；不要自动重发，先观察 progress/OSD 后刷新列表。")
    return False


def cancel_job(token: str, job: dict[str, Any]) -> bool:
    job_id = _job_id(job)
    current = _find_job(token, job_id)
    if not current:
        print("[✗] 任务已不存在或不属于当前工作空间")
        return False
    status = _job_status(current)
    already_canceled = status == 4
    if status not in CANCELABLE:
        print(f"[✗] {JOB_STATUS.get(status, status)}任务不可取消")
        return False
    prompt_action = "对已取消任务执行幂等收敛" if status == 4 else "取消"
    if input(f"[!] 确认{prompt_action}“{current.get('job_name')}”？输入 YES: ").strip() != "YES":
        print("[*] 已取消操作")
        return False

    ambiguous = False
    try:
        api_call(
            token,
            "DELETE",
            f"/wayline/api/v1/workspaces/{WORKSPACE_ID}/jobs",
            action="取消航线任务",
            params={"job_id": job_id},
            timeout=30,
        )
    except DemoApiError as exc:
        print_error_and_hint(exc)
        if not exc.ambiguous:
            # 运行中取消的后端流程是 pause 后 undo。undo 明确失败时
            # 任务可能已停在 PAUSED，所以失败后也必须刷新，不能继续用旧状态。
            refreshed = _find_job(token, job_id)
            if refreshed:
                print(f"[*] 取消失败后已刷新: {_job_line(refreshed)}")
                if _job_status(refreshed) == 6:
                    print("[!] 暂停已生效但撤销未完成；可在确认设备状态后重试取消或选择继续")
            return False
        ambiguous = True

    if already_canceled:
        if ambiguous:
            print("[!!] 任务原本已是取消状态，状态 4 无法证明本次缓存收敛已执行；结果未知。")
            print("     不要盲目重发；请由服务端日志/Redis 只读检查确认残留状态。")
            return False
        print(f"[✓] 已取消任务的幂等本地收敛调用成功: {_job_line(current)}")
        return True

    # 运行中取消后端为 pause→undo 链路，undo 要等设备回复，收敛可能较慢。
    updated = _wait_job(
        token,
        lambda item: _job_id(item) == job_id and _job_status(item) == 4,
        timeout=25,
    )
    if updated:
        print(f"[{'恢复' if ambiguous else '✓'}] 取消已确认: {_job_line(updated)}")
        return True
    refreshed = _find_job(token, job_id)
    if refreshed:
        print(f"[*] 当前任务状态: {_job_line(refreshed)}")
    print("[!!] 取消结果尚未确认；不要自动重发，先观察 progress/OSD 并刷新任务列表。")
    return False


class ProgressWatcher:
    """旁路订阅设备 ``flighttask_progress`` 与飞机 OSD，REST 查询仍是操作前的依据。"""

    def __init__(self) -> None:
        self.topic = f"thing/product/{DOCK_SN}/events"
        # 固件限制：航线任务需无人机关机或停桨（mode_code=0）才能执行，
        # 下发前用子飞机 OSD 的 mode_code 本地判断。
        self.osd_topic = f"thing/product/{GATEWAY_CHILD_SN}/osd" if GATEWAY_CHILD_SN else ""
        self.aircraft_mode_code: int | None = None
        self.connected = threading.Event()
        self.subscribed = threading.Event()
        self._stopping = False
        # MQTT 回调线程只入队，不直接打印；由菜单主线程在提示前统一刷新，
        # 避免 progress 洪峰把 input() 提示行冲掉、导致无法输入取消命令。
        self._progress_queue: queue.Queue[str] = queue.Queue()
        self._last_line = ""
        self._repeat_count = 0
        self._latest_line: str | None = None
        self.client = mqtt.Client(
            callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
            client_id=f"demo17_wayline_{int(time.time())}",
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
            print(f"[!] MQTT 进度监听启动失败: {exc}；REST 操作仍可使用")
            return False
        if not self.connected.wait(5) or not self.subscribed.wait(5):
            print("[!] MQTT 连接/订阅未确认；REST 操作仍可使用")
            return False
        return True

    def _on_connect(self, client, userdata, flags, reason_code, properties) -> None:
        if reason_code == 0:
            self.connected.set()
            client.subscribe(self.topic, qos=0)
            if GATEWAY_CHILD_SN:
                client.subscribe(self.osd_topic, qos=0)
        else:
            print(f"[!] MQTT 连接被拒绝: {reason_code}")

    def _on_subscribe(self, client, userdata, mid, reason_code_list, properties) -> None:
        self.subscribed.set()
        print(f"[✓] 已订阅航线进度 {self.topic}")

    def _on_disconnect(self, client, userdata, disconnect_flags, reason_code, properties) -> None:
        if not self._stopping and reason_code != 0:
            self._progress_queue.put(f"[!] MQTT 进度监听断开: {reason_code}")

    def _on_message(self, client, userdata, message) -> None:
        try:
            payload = json.loads(message.payload.decode("utf-8"))
            method = payload.get("method")
            if message.topic == self.osd_topic:
                mode_code = (payload.get("data") or {}).get("mode_code")
                if isinstance(mode_code, int):
                    self.aircraft_mode_code = mode_code
                return
            if method != "flighttask_progress":
                return
            data = payload.get("data") or {}
            output = data.get("output") or {}
            progress = output.get("progress") or {}
            ext = output.get("ext") or {}
            status = str(output.get("status") or "")
            job_id = str(payload.get("bid") or data.get("bid") or ext.get("flight_id") or "")
            result = data.get("result")
            if isinstance(result, dict):
                result = result.get("code")
            line = (
                f"[任务上报] id={job_id or '?'} "
                f"status={FLIGHTTASK_STATUS.get(status, status or '?')} "
                f"step={progress.get('current_step')} percent={progress.get('percent')} "
                f"waypoint={ext.get('current_waypoint_index')} result={result}"
            )
            self._progress_queue.put(line)
        except Exception as exc:
            self._progress_queue.put(f"[!] 航线进度消息解析失败: {exc}")

    def flush(self) -> None:
        """主线程安全刷新：打印缓冲的进度消息，连续相同的上报只保留一条。

        回调线程不再直接 print，因此 ``input()`` 等待期间提示行不会被打断，
        取消/继续等需要交互确认的操作可正常输入。
        """
        drained: list[str] = []
        while True:
            try:
                drained.append(self._progress_queue.get_nowait())
            except queue.Empty:
                break
        for line in drained:
            if self._repeat_count > 1 and line != self._last_line:
                print(f"    (上一条进度连续重复 {self._repeat_count} 次)")
                self._repeat_count = 1
            if line == self._last_line and self._repeat_count >= 1:
                self._repeat_count += 1
                continue
            print(line)
            self._last_line = line
            self._repeat_count = 1
        if drained:
            self._latest_line = drained[-1]
        elif self._repeat_count > 1 and self._last_line:
            # 队列已空但还有未汇总的重复：提示一次并重置，避免每次刷新都打印。
            print(f"    (上一条进度连续重复 {self._repeat_count} 次)")
            self._repeat_count = 1

    def latest_line(self) -> str | None:
        """最近一条进度上报，用于菜单提示前展示当前任务状态。"""
        return self._latest_line

    def stop(self) -> None:
        self._stopping = True
        try:
            self.client.loop_stop()
            self.client.disconnect()
        except Exception:
            pass


def _select_job(token: str, statuses: set[int], verb: str) -> dict[str, Any] | None:
    jobs = [job for job in list_jobs(token) if _job_status(job) in statuses]
    if not jobs:
        labels = "/".join(JOB_STATUS[item] for item in sorted(statuses))
        print(f"[!] 没有可{verb}的任务（要求状态：{labels}）")
        return None
    if len(jobs) == 1:
        # 唯一候选直接选中，省去交互步骤：进度洪峰时快速取消更可靠。
        print(f"[*] 唯一可{verb}任务，已自动选中: {_job_line(jobs[0])}")
        return jobs[0]
    return choose(jobs, "选择任务序号> ", _job_line)


def menu(token: str, watcher: ProgressWatcher) -> None:
    while True:
        # 提示前统一刷新进度上报：此时不在 input() 等待中，不会打断输入。
        watcher.flush()
        latest = watcher.latest_line()
        if latest:
            print(f"[当前进度] {latest}")
        if watcher.aircraft_mode_code is None:
            print("[飞机状态] 尚未收到 OSD（订阅子飞机 osd 主题约 2s 内可就绪）")
        else:
            mode_code = watcher.aircraft_mode_code
            label = MODE_LABELS.get(mode_code, mode_code)
            hint = "可执行航线任务" if mode_code == 0 else "需先降落/返航（航线任务仅限停桨状态）"
            print(f"[飞机状态] {label}(mode_code={mode_code}) -> {hint}")
        print("\n航线任务菜单：")
        print("  1. 下发并立即执行（flighttask_prepare + execute）")
        print("  2. 暂停执行中任务（flighttask_pause）")
        print("  3. 继续已暂停任务（flighttask_recovery）")
        print("  4. 取消待执行/执行中/已暂停任务；已取消任务可做幂等收敛（单任务自动选中）")
        print("  5. 刷新任务列表（重新调用 REST 查询最新任务状态，暂停/取消等操作前先刷新）")
        print("  6. 上传 KMZ 航线文件到航线库")
        print("  0. 退出")
        choice_value = input("选择> ").strip()
        try:
            if choice_value == "0":
                return
            if choice_value == "1":
                waylines = list_waylines(token)
                if not waylines:
                    print("[!] 航线库为空，请先用菜单项 6 上传 KMZ，或在 Web 控制台上传")
                    continue
                selected = choose(
                    waylines,
                    "选择航线序号> ",
                    lambda item: f"{item.get('name')} ({item.get('drone_model_key', '—')})",
                )
                if selected:
                    create_job(token, selected, watcher)
            elif choice_value == "2":
                selected = _select_job(token, PAUSABLE, "暂停")
                if selected:
                    change_job(token, selected, 0)
            elif choice_value == "3":
                selected = _select_job(token, RESUMABLE, "继续")
                if selected:
                    change_job(token, selected, 1)
            elif choice_value == "4":
                selected = _select_job(token, CANCELABLE, "取消")
                if selected:
                    cancel_job(token, selected)
            elif choice_value == "5":
                jobs = list_jobs(token)
                if not jobs:
                    print("  (无任务)")
                for job in jobs:
                    print(f"  - {_job_line(job)}")
            elif choice_value == "6":
                upload_wayline(token)
            else:
                print("[!] 无效选择")
        except DemoError as exc:
            print_error_and_hint(exc)


def main() -> int:
    require_config(YOOX_DOCK_SN=DOCK_SN, YOOX_WORKSPACE_ID=WORKSPACE_ID)
    global GATEWAY_CHILD_SN
    if not GATEWAY_CHILD_SN:
        # 未配置 DRONE_SN 时从网关详情接口解析 child_device_sn（OSD 订阅需要）。
        try:
            token = login()
            detail = api_call(
                token,
                "GET",
                f"/manage/api/v1/devices/{WORKSPACE_ID}/devices/{DOCK_SN}",
                action="查询网关详情",
                timeout=15,
            )
            GATEWAY_CHILD_SN = str((detail.get("data") or {}).get("child_device_sn") or "")
        except Exception as exc:
            print(f"[!] 无法解析子飞机 SN，停桨前置判断将不可用: {exc}")
            token = None
    else:
        token = login()
    print(f"[*] 目标网关: {DOCK_SN}  工作空间: {WORKSPACE_ID}")
    if token is None:
        token = login()
    watcher = ProgressWatcher()
    watcher.start()
    try:
        menu(token, watcher)
    finally:
        watcher.stop()
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except DemoError as exc:
        print_error_and_hint(exc)
        raise SystemExit(1)
