"""应急处置：返航/取消返航，以及已建立 DRC 会话中的应急指令。

HTTP 返航流程与当前 Web 控制台一致：显式抢占飞行控制权，再调用
``return_home`` 或 ``return_home_cancel``。客户端超时不代表设备没执行，脚本
不会自动重发。DRC 部分复用 demo_12 的专用 Broker 凭证、ACL、首帧心跳和
当前会话双帧零杆探针逻辑。
"""
from __future__ import annotations

from config import DOCK_SN, WORKSPACE_ID
from demo_12_drc import (
    DrcSession,
    drc_connect,
    drc_enter,
    drc_exit,
    start_side_watcher,
)
from demo_common import (
    DemoApiError,
    DemoError,
    api_call,
    login,
    print_error_and_hint,
    require_config,
    seize_flight_authority,
)


def confirm(text: str) -> bool:
    return input(f"[!] {text} 输入 YES 确认: ").strip() == "YES"


def return_action(token: str, method: str) -> bool:
    if method not in {"return_home", "return_home_cancel"}:
        return False
    label = "一键返航" if method == "return_home" else "取消返航"
    prompt = "确认一键返航？" if method == "return_home" else "确认取消返航并原地悬停？"
    if not confirm(prompt):
        print("[*] 已取消操作")
        return False
    try:
        seize_flight_authority(token)
        api_call(
            token,
            "POST",
            f"/control/api/v1/devices/{DOCK_SN}/jobs/{method}",
            action=label,
            timeout=25,
        )
        if method == "return_home":
            print("[✓] 返航指令调用成功；不代表已返航/落地，请持续观察 OSD 与现场")
        else:
            print("[✓] 取消返航调用成功；飞行器应悬停，请持续观察 OSD 与现场")
        return True
    except DemoApiError as exc:
        print_error_and_hint(exc)
        if exc.ambiguous:
            print("    先核对飞机模式和现场状态；不要因客户端超时立即重复发送。")
        return False


class EmergencyDrc:
    def __init__(self, token: str) -> None:
        self.token = token
        self.client_id = ""
        self.session: DrcSession | None = None
        self.watcher = None
        self.enter_attempted = False

    def enter(self) -> bool:
        if self.session and self.session.client and self.session.client.is_connected():
            print("[*] DRC 已连接")
            return True
        require_config(YOOX_WORKSPACE_ID=WORKSPACE_ID)
        broker = drc_connect(self.token)
        self.client_id = str(broker.get("client_id") or "")
        self.enter_attempted = True
        try:
            acl = drc_enter(self.token, self.client_id)
            session = DrcSession(broker, acl)
            if not session.connect():
                self.exit()
                return False
            # CONNACK + SUBACK 后 DrcSession 已立即发送心跳和双帧零杆探针。
            session.start_heartbeat_loop()
            self.session = session
            self.watcher = start_side_watcher()
            if session.controls_ready.is_set():
                print("[✓] DRC 控制链路已通过当前会话双帧探针")
            else:
                print("[!] DRC MQTT 已连接，控制探针仍在重试；非零摇杆保持锁定")
            return True
        except DemoError:
            # enter 响应丢失时设备仍可能进入 DRC；尽力用同一 client_id 退出。
            self.exit()
            raise

    def exit(self) -> None:
        try:
            if self.session:
                self.session.disconnect()
        except Exception as exc:
            print(f"[!] 本地 DRC MQTT 清理异常，将继续请求服务端退出: {exc}")
        finally:
            self.session = None
        if self.watcher:
            try:
                self.watcher.loop_stop()
            except Exception as exc:
                print(f"[!] 旁路 MQTT loop_stop 失败: {exc}")
            try:
                self.watcher.disconnect()
            except Exception as exc:
                print(f"[!] 旁路 MQTT disconnect 失败: {exc}")
            self.watcher = None
        try:
            if self.enter_attempted and self.client_id:
                try:
                    drc_exit(self.token, self.client_id)
                except DemoError as exc:
                    print_error_and_hint(exc)
        finally:
            self.enter_attempted = False
            self.client_id = ""

    def require_session(self) -> DrcSession | None:
        if not self.session or not self.session.client or not self.session.client.is_connected():
            print("[✗] DRC 未就绪，请先选择 e；不会把应急指令发往普通 MQTT Broker")
            return None
        return self.session


def main() -> int:
    require_config(YOOX_DOCK_SN=DOCK_SN)
    token = login()
    drc = EmergencyDrc(token)
    print(f"[*] 设备 SN: {DOCK_SN}\n")
    print("""应急处置菜单：
  1. 一键返航       HTTP return_home
  2. 取消返航       HTTP return_home_cancel（悬停）
  3. 急停悬停       DRC drone_emergency_stop
  e. 建立 DRC 专用链路并启动心跳
  x. 退出 DRC
  q. 退出脚本
""")
    try:
        while True:
            command = input("选择操作: ").strip().lower()
            try:
                if command == "q":
                    break
                if command == "1":
                    return_action(token, "return_home")
                elif command == "2":
                    return_action(token, "return_home_cancel")
                elif command == "e":
                    drc.enter()
                elif command == "x":
                    drc.exit()
                elif command == "3":
                    session = drc.require_session()
                    if session and confirm("确认急停并原地悬停？"):
                        session.emergency_stop()
                else:
                    print("[!] 未知操作")
            except DemoError as exc:
                print_error_and_hint(exc)
    finally:
        drc.exit()
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except DemoError as exc:
        print_error_and_hint(exc)
        raise SystemExit(1)
