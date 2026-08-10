"""
demo_06_gimbal_pitch.py -- 云台控制（camera_aim 指点 + camera_screen_drag 速度 + gimbal_reset 复位）

三类接口：
  1. camera_aim         -- 屏幕坐标指点（单次指向目标点）
  2. camera_screen_drag -- 持续速度控制（pitch/yaw 角速度，度/秒）
  3. gimbal_reset       -- 云台复位（4 种模式）

运行：
    python3 demo_06_gimbal_pitch.py
"""
from config import DOCK_SN, PAYLOAD_INDEX
from demo_common import (
    DemoError,
    login,
    print_error_and_hint,
    require_config,
    seize_payload_authority,
    send_payload_command,
)

# gimbal_reset 模式名映射（对应 API 文档）
RESET_MODES = {0: "回中", 1: "向下", 2: "偏航回中", 3: "向下45度"}

# camera_screen_drag 默认速度（度/秒）
DEFAULT_DRAG_SPEED = 0.1


def _payload_command(token, cmd: str, data: dict):
    try:
        return send_payload_command(token, cmd, data, timeout=10)
    except DemoError as exc:
        print_error_and_hint(exc)
        return None


def camera_aim(token, x: float, y: float, camera_type: str = "zoom", locked: bool = False):
    """屏幕坐标指点控制：x/y 为 0.0~1.0 归一化坐标"""
    return _payload_command(token, "camera_aim", {
        "payload_index": PAYLOAD_INDEX,
        "x": x,
        "y": y,
        "locked": locked,
        "camera_type": camera_type,
    })


def camera_screen_drag(token, pitch_speed: float, yaw_speed: float, locked: bool = False):
    """画面拖动速度控制：pitch_speed/yaw_speed 单位为度/秒"""
    return _payload_command(token, "camera_screen_drag", {
        "payload_index": PAYLOAD_INDEX,
        "locked": locked,
        "pitch_speed": pitch_speed,
        "yaw_speed": yaw_speed,
    })


def gimbal_reset(token, mode: int = 0):
    """云台复位：0=回中 1=向下 2=偏航回中 3=向下45度"""
    return _payload_command(token, "gimbal_reset", {
        "payload_index": PAYLOAD_INDEX,
        "reset_mode": mode,
    })


def _ok(result):
    return bool(result and result.get("code") == 0)


def _print_result(label, result):
    if result is None:
        return
    mark = "✓" if _ok(result) else "✗"
    msg = result.get("message", "")
    print(f"  [{mark}] {label}: {msg}")


if __name__ == "__main__":
    try:
        require_config(YOOX_DOCK_SN=DOCK_SN, YOOX_PAYLOAD_INDEX=PAYLOAD_INDEX)
        print(f"[*] 目标设备: {DOCK_SN}")
        print(f"[*] 负载索引: {PAYLOAD_INDEX}\n")

        token = login()
        seize_payload_authority(token, PAYLOAD_INDEX)
    except DemoError as exc:
        print_error_and_hint(exc)
        raise SystemExit(1)

    print(f"""═══ 云台控制指令 ═══

── camera_aim（屏幕坐标指点） ──
  up        指向上方  (x=0.5, y=0.0)
  down      指向下方  (x=0.5, y=1.0)
  left      指向左方  (x=0.0, y=0.5)
  right     指向右方  (x=1.0, y=0.5)
  center    画面中心  (x=0.5, y=0.5)

── camera_screen_drag（速度控制，度/秒） ──
  8 / w     仰角向上  (pitch_speed=-{DEFAULT_DRAG_SPEED})
  2 / s     俯角向下  (pitch_speed=+{DEFAULT_DRAG_SPEED})
  4 / a     偏航向左  (yaw_speed=-{DEFAULT_DRAG_SPEED})
  6 / d     偏航向右  (yaw_speed=+{DEFAULT_DRAG_SPEED})
  5 / stop  停止拖动  (速度归零)
  locked=true/false  切换锁定模式（默认 false 仅云台转）
  speed=0.2  设置拖动速度（当前 {DEFAULT_DRAG_SPEED}）

── gimbal_reset（云台复位） ──
  r0 / horizon   回中       (mode=0)
  r1 / down90    向下       (mode=1)
  r2             偏航回中   (mode=2)
  r3             向下45度   (mode=3)

  q  退出
""")

    drag_locked = False
    drag_speed = DEFAULT_DRAG_SPEED

    while True:
        cmd = input("输入指令: ").strip().lower()

        if not cmd:
            continue

        # ── 退出 ──
        if cmd == "q":
            break

        # ── camera_aim 屏幕坐标指点 ──
        elif cmd == "up":
            _print_result("指向上方", camera_aim(token, x=0.5, y=0.0))
        elif cmd == "down":
            _print_result("指向下方", camera_aim(token, x=0.5, y=1.0))
        elif cmd == "left":
            _print_result("指向左方", camera_aim(token, x=0.0, y=0.5))
        elif cmd == "right":
            _print_result("指向右方", camera_aim(token, x=1.0, y=0.5))
        elif cmd == "center":
            _print_result("指向画面中心", camera_aim(token, x=0.5, y=0.5))

        # ── camera_screen_drag 速度控制 ──
        elif cmd in ("8", "w"):
            _print_result("仰角向上", camera_screen_drag(token, pitch_speed=-drag_speed, yaw_speed=0, locked=drag_locked))
        elif cmd in ("2", "s"):
            _print_result("俯角向下", camera_screen_drag(token, pitch_speed=drag_speed, yaw_speed=0, locked=drag_locked))
        elif cmd in ("4", "a"):
            _print_result("偏航向左", camera_screen_drag(token, pitch_speed=0, yaw_speed=-drag_speed, locked=drag_locked))
        elif cmd in ("6", "d"):
            _print_result("偏航向右", camera_screen_drag(token, pitch_speed=0, yaw_speed=drag_speed, locked=drag_locked))
        elif cmd in ("5", "stop"):
            _print_result("停止拖动", camera_screen_drag(token, pitch_speed=0, yaw_speed=0, locked=drag_locked))
        elif cmd.startswith("locked="):
            drag_locked = cmd.split("=", 1)[1].strip().lower() in ("true", "1", "yes")
            print(f"  [•] 锁定模式: {drag_locked} ({'机头锁定，云台+机身一起转' if drag_locked else '仅云台转，机身不转'})")
        elif cmd.startswith("speed="):
            try:
                drag_speed = float(cmd.split("=", 1)[1])
                print(f"  [•] 拖动速度已设为 {drag_speed} 度/秒")
            except ValueError:
                print("  格式错误，示例: speed=0.2")

        # ── gimbal_reset 云台复位 ──
        elif cmd in ("r0", "horizon"):
            _print_result(f"云台复位({RESET_MODES[0]})", gimbal_reset(token, mode=0))
        elif cmd in ("r1", "down90"):
            _print_result(f"云台复位({RESET_MODES[1]})", gimbal_reset(token, mode=1))
        elif cmd == "r2":
            _print_result(f"云台复位({RESET_MODES[2]})", gimbal_reset(token, mode=2))
        elif cmd == "r3":
            _print_result(f"云台复位({RESET_MODES[3]})", gimbal_reset(token, mode=3))

        else:
            print("  未知指令，输入 q 退出")
