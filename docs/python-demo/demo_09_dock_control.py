"""
demo_09_dock_control.py -- 设备远程控制（返航、重启等）

接口：POST /control/api/v1/devices/{sn}/jobs/{method}（无请求体）

其中 5/6 为一键返航与取消返航，无论是否有机巢都可用；
开舱盖/充电/推杆/补光灯等仅在机巢场景下有效。
DRC 急停悬停示例见 demo_15_emergency.py。

运行：
    python3 demo_09_dock_control.py

根据菜单选择操作。
"""
from config import DOCK_SN
from demo_common import (
    DemoApiError,
    DemoError,
    api_call,
    login,
    print_error_and_hint,
    require_config,
    seize_flight_authority,
)

COMMANDS = {
    "1":  ("cover_open",           "开舱盖"),
    "2":  ("cover_close",          "关舱盖"),
    "3":  ("drone_open",           "开无人机电源"),
    "4":  ("drone_close",          "关无人机电源"),
    "5":  ("return_home",          "一键返航（飞行器飞回返航点）"),
    "6":  ("return_home_cancel",   "取消返航（原地悬停）"),
    "7":  ("device_reboot",        "重启设备"),
    "8":  ("charge_open",          "开始充电"),
    "9":  ("charge_close",         "停止充电"),
    "10": ("putter_open",          "推杆伸出"),
    "11": ("putter_close",         "推杆收回"),
    "12": ("supplement_light_open",  "开补光灯"),
    "13": ("supplement_light_close", "关补光灯"),
    "14": ("debug_mode_open",      "进入调试模式"),
    "15": ("debug_mode_close",     "退出调试模式"),
}

def send_dock_command(token, cmd_id: str):
    if cmd_id not in COMMANDS:
        print(f"[!] 无效指令编号: {cmd_id}")
        return

    method, desc = COMMANDS[cmd_id]

    # 与当前 Web 控制台一致，返航及取消返航都需要二次确认并显式抢权。
    if method in {"return_home", "return_home_cancel"}:
        prompt = "确认一键返航？" if method == "return_home" else "确认取消返航并原地悬停？"
        if input(f"  [!] {prompt} 输入 YES 确认: ").strip() != "YES":
            print("  已取消")
            return
        try:
            seize_flight_authority(token)
        except DemoApiError as exc:
            print_error_and_hint(exc)
            return

    print(f"[*] 发送指令: {desc} ({method})")
    try:
        api_call(
            token,
            "POST",
            f"/control/api/v1/devices/{DOCK_SN}/jobs/{method}",
            action=desc,
        )
        if method == "return_home":
            print("[✓] 返航指令调用成功；这不代表已经返航或落地，请继续观察 OSD")
        elif method == "return_home_cancel":
            print("[✓] 取消返航调用成功；飞行器应悬停，请通过 OSD/现场确认")
        else:
            print(f"[✓] {desc} 调用成功")
    except DemoApiError as exc:
        print_error_and_hint(exc)
        if exc.ambiguous and method in {"return_home", "return_home_cancel"}:
            print("    先确认 OSD mode_code/现场状态；不要因客户端超时立即重复发送。")

if __name__ == "__main__":
    try:
        require_config(YOOX_DOCK_SN=DOCK_SN)
        print(f"[*] 目标设备: {DOCK_SN}\n")
        token = login()

        print("设备控制菜单：")
        for k, (method, desc) in COMMANDS.items():
            print(f"  {k:>2}. {desc}")
        print("   q. 退出\n")

        while True:
            cmd = input("选择操作编号: ").strip()
            if cmd == "q":
                break
            send_dock_command(token, cmd)
            print()
    except DemoError as exc:
        print_error_and_hint(exc)
        raise SystemExit(1)
