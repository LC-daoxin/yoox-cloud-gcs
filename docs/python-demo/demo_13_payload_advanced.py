"""
demo_13_payload_advanced.py —— 高级负载控制

包含以下指令（部分指令需要 YOOX Cloud GCS 版本，已标注）：
  camera_screen_drag    ← 画面拖动控制（云台连续转速）[YOOX扩展]
  camera_focal_length_drag ← 连续变焦                [YOOX扩展]
  camera_look_at        ← Look At（GPS 坐标指向）     [YOOX扩展]
  photo_storage_set     ← 照片存储镜头设置             [YOOX扩展]
  video_storage_set     ← 视频存储镜头设置             [YOOX扩展]

基础负载指令：
  camera_mode_switch / camera_photo_take / camera_focal_length_set 见 demo_05/07

运行：
    python3 demo_13_payload_advanced.py

注意：本 demo 针对 YOOX Cloud GCS 项目。
      标注 [YOOX] 的指令属于 YOOX Cloud GCS 扩展能力。
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


def send_payload_cmd(token, cmd: str, extra: dict = None) -> dict:
    data = {"payload_index": PAYLOAD_INDEX}
    if extra:
        data.update(extra)
    result = send_payload_command(token, cmd, data)
    print(f"[✓] {cmd} 调用成功；实际负载状态以 OSD 为准")
    return result


def camera_screen_drag(token, pitch_speed: float, yaw_speed: float,
                       locked: bool = False):
    """
    [YOOX扩展] 画面拖动控制（云台连续转速）
    pitch_speed: 俯仰速度 -1.0~1.0（正=向下，负=向上）
    yaw_speed:   偏航速度 -1.0~1.0（正=向右，负=向左）
    locked: True=锁定目标跟踪
    """
    return send_payload_cmd(token, "camera_screen_drag", {
        "pitch_speed": pitch_speed,
        "yaw_speed": yaw_speed,
        "locked": locked
    })


def camera_focal_length_drag(token, zoom_type: int, camera_type: str = "zoom"):
    """
    [YOOX扩展] 连续变焦
    zoom_type: 0=停止变焦  1=放大（拉近）  2=缩小（拉远）
    camera_type: zoom / wide / ir
    """
    return send_payload_cmd(token, "camera_focal_length_drag", {
        "zoom_type": zoom_type,
        "camera_type": camera_type
    })


def camera_look_at(token, latitude: float, longitude: float, height: float):
    """
    [YOOX扩展] Look At —— 云台指向 GPS 坐标对应的方向
    latitude:  -90 ~ 90
    longitude: -180 ~ 180
    height:    2 ~ 10000 (米)
    """
    return send_payload_cmd(token, "camera_look_at", {
        "locked": True,
        "latitude": latitude,
        "longitude": longitude,
        "height": height
    })


def photo_storage_set(token, lenses: list):
    """
    [YOOX扩展] 照片存储镜头设置
    lenses: 列表，可选值 current / zoom / wide / ir / NightVision
    示例: ["zoom", "wide"]
    """
    return send_payload_cmd(token, "photo_storage_set", {
        "photo_storage_settings": lenses
    })


def video_storage_set(token, lenses: list):
    """
    [YOOX扩展] 视频存储镜头设置
    lenses: 列表，可选值同上
    """
    return send_payload_cmd(token, "video_storage_set", {
        "video_storage_settings": lenses
    })


def main() -> int:
    require_config(YOOX_DOCK_SN=DOCK_SN, YOOX_PAYLOAD_INDEX=PAYLOAD_INDEX)
    print(f"[*] 目标设备: {DOCK_SN}")
    print(f"[*] 负载索引: {PAYLOAD_INDEX}")
    print("[!] 注意：标注[YOOX]的是 YOOX Cloud GCS 扩展负载指令\n")

    token = login()
    seize_payload_authority(token, PAYLOAD_INDEX)

    print("""高级负载控制菜单：
  1. [YOOX] 画面拖动 - 云台向上（pitch=-0.5）
  2. [YOOX] 画面拖动 - 云台向下（pitch=+0.5）
  3. [YOOX] 画面拖动 - 云台向左（yaw=-0.5）
  4. [YOOX] 画面拖动 - 云台向右（yaw=+0.5）
  5. [YOOX] 画面拖动 - 停止（pitch=0, yaw=0）
  6. [YOOX] 连续变焦 - 放大
  7. [YOOX] 连续变焦 - 缩小
  8. [YOOX] 连续变焦 - 停止
  9. [YOOX] Look At   - 输入GPS坐标
  A. [YOOX] 照片存储设置 - zoom+wide
  B. [YOOX] 视频存储设置 - zoom+wide
  q. 退出\n""")

    while True:
        cmd = input("选择操作: ").strip().upper()
        try:
            if cmd == "Q":
                break
            elif cmd == "1":
                camera_screen_drag(token, pitch_speed=-0.5, yaw_speed=0)
            elif cmd == "2":
                camera_screen_drag(token, pitch_speed=0.5, yaw_speed=0)
            elif cmd == "3":
                camera_screen_drag(token, pitch_speed=0, yaw_speed=-0.5)
            elif cmd == "4":
                camera_screen_drag(token, pitch_speed=0, yaw_speed=0.5)
            elif cmd == "5":
                camera_screen_drag(token, pitch_speed=0, yaw_speed=0)
            elif cmd == "6":
                camera_focal_length_drag(token, zoom_type=1)
            elif cmd == "7":
                camera_focal_length_drag(token, zoom_type=2)
            elif cmd == "8":
                camera_focal_length_drag(token, zoom_type=0)
            elif cmd == "9":
                lat = float(input("  纬度 (-90~90): "))
                lon = float(input("  经度 (-180~180): "))
                height = float(input("  高度 (2~10000 米): "))
                if not (-90 <= lat <= 90 and -180 <= lon <= 180 and 2 <= height <= 10000):
                    print("  坐标/高度超出允许范围")
                    continue
                camera_look_at(token, lat, lon, height)
            elif cmd == "A":
                photo_storage_set(token, ["zoom", "wide"])
            elif cmd == "B":
                video_storage_set(token, ["zoom", "wide"])
            else:
                print("  未知操作")
        except ValueError:
            print("  坐标必须是数字")
        except DemoError as exc:
            print_error_and_hint(exc)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except DemoError as exc:
        print_error_and_hint(exc)
        raise SystemExit(1)
