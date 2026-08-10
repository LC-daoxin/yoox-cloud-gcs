"""
demo_03_websocket_osd.py -- 实时接收飞机遥测数据（OSD）

通过 WebSocket 订阅服务端推送，实时打印：
  - 飞机位置（经纬度、高度）
  - 电量、速度、姿态
  - 遥控器状态
  - 设备上下线、任务进度、目标识别、DRC 事件

依赖：pip3 install websocket-client

运行：
    python3 demo_03_websocket_osd.py
"""
import json
import websocket
import threading
import time
from config import WS_URL
from demo_common import DemoError, login, print_error_and_hint


last_msg_time = [0]
msg_count = [0]

# joystick_invalid_notify 的 reason 取值
JOYSTICK_INVALID_REASON = {
    0: "遥控器失联",
    1: "低电量返航",
    2: "低电量降落",
    3: "靠近限飞区",
    4: "遥控器夺权（如 B 控触发返航）",
}


def _fmt(val, unit=""):
    """格式化数值，N/A 时返回占位"""
    if val is None or val == "":
        return "N/A"
    if isinstance(val, float):
        return f"{val:.1f}{unit}"
    return f"{val}{unit}"


def on_message(ws, message):
    last_msg_time[0] = time.time()
    msg_count[0] += 1

    try:
        msg = json.loads(message)
        biz_code = msg.get("biz_code", "")
        data = msg.get("data", {})

        if biz_code == "device_osd":
            # 无人机 OSD -- 打印完整数据
            print(f"[无人机 #{msg_count[0]}]")
            print(json.dumps(data, indent=2, ensure_ascii=False))

        elif biz_code == "gateway_osd":
            # 遥控器 OSD -- 打印完整数据
            print(f"[遥控器 #{msg_count[0]}]")
            print(json.dumps(data, indent=2, ensure_ascii=False))

        elif biz_code == "dock_osd":
            # 遥控器 OSD -- 打印完整数据
            print(f"[遥控器 #{msg_count[0]}]")
            print(json.dumps(data, indent=2, ensure_ascii=False))

        elif biz_code == "device_online":
            print(f"[事件] 设备上线: {json.dumps(data, ensure_ascii=False)}")

        elif biz_code == "device_offline":
            print(f"[事件] 设备离线: {json.dumps(data, ensure_ascii=False)}")

        elif biz_code == "fly_to_point_progress":
            # 服务端已透传完整字段（需重新构建镜像生效），原始 MQTT 报文见 demo_08
            print(f"[进度] fly_to_point_progress: status={data.get('status')} "
                  f"result={data.get('result')} 航点={data.get('way_point_index')} "
                  f"剩余={data.get('remaining_distance')}m/{data.get('remaining_time')}s")

        elif biz_code in ("takeoff_to_point_progress", "flighttask_progress"):
            print(f"[进度] {biz_code}: result={data.get('result')} {data.get('message', '')}")

        elif biz_code in {"target_detect_result_report", "target_detect_result"}:
            print(f"[目标识别] {json.dumps(data, ensure_ascii=False)}")

        elif biz_code == "drc_status_notify":
            print(f"[DRC状态] {json.dumps(data, ensure_ascii=False)}")

        elif biz_code == "joystick_invalid_notify":
            reason = data.get("reason")
            desc = JOYSTICK_INVALID_REASON.get(reason, "未知原因")
            print(f"[!!] Joystick 已失效: reason={reason} {desc}")
            print("     drone_control 不再生效，手动操控不可用")

        else:
            print(f"[消息#{msg_count[0]}] biz_code={biz_code}: {json.dumps(data, ensure_ascii=False)}")

    except Exception:
        print(f"[原始] {message}")


def on_error(ws, error):
    print(f"[✗] WebSocket 错误: {error}")


def on_close(ws, close_status_code, close_msg):
    print(f"[*] WebSocket 已关闭: {close_status_code} {close_msg}")


def on_open(ws):
    print("[✓] WebSocket 已连接，等待推送数据...\n")

    def _watchdog():
        time.sleep(12)
        if msg_count[0] == 0:
            print("\n[!] 12 秒内未收到任何推送，可能原因：")
            print("    - 设备未完成上线握手（未发送 update_topo）")
            print("    - 设备已离线，或当前工作空间没有在线设备")
            print("    验证: 运行 demo_02_devices.py 查看服务端设备状态\n")
    threading.Thread(target=_watchdog, daemon=True).start()


if __name__ == "__main__":
    try:
        token = login()
        separator = "&" if "?" in WS_URL else "?"
        ws_url = f"{WS_URL}{separator}x-auth-token={token}"
        print(f"[*] 连接 WebSocket: {WS_URL}（token 不回显）")

        ws = websocket.WebSocketApp(
            ws_url,
            on_open=on_open,
            on_message=on_message,
            on_error=on_error,
            on_close=on_close
        )
        wst = threading.Thread(target=ws.run_forever, daemon=True)
        wst.start()
        try:
            while True:
                time.sleep(1)
        except KeyboardInterrupt:
            print("\n[*] 用户中断，关闭连接")
            ws.close()
    except DemoError as exc:
        print_error_and_hint(exc)
        raise SystemExit(1)
