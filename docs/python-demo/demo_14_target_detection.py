"""
demo_14_target_detection.py -- 目标识别（AI 检测）

[YOOX Cloud GCS 专用接口]
该接口属于 YOOX Cloud GCS 的目标识别能力。

接口：
  POST /control/api/v1/devices/{sn}/target-detection    开启
  DELETE /control/api/v1/devices/{sn}/target-detection  关闭

参数说明：
  ai_lens_type:    0=可见光  1=红外
  scene_type:      0=通用（目前仅支持0）
  target_type_list: 目标类型列表
    0=person（人）
    1=car（车）
    2=boat（船）

识别结果：
  通过 WebSocket bizCode=target_detect_result_report 推送
  可用 demo_03_websocket_osd.py 接收（已自动显示未知 bizCode 的原始数据）

运行：
    python3 demo_14_target_detection.py
"""
from config import DOCK_SN
from demo_common import DemoError, api_call, login, print_error_and_hint, require_config

# ── 目标识别配置 ──────────────────────────────────────────
AI_LENS_TYPE = 0         # 0=可见光  1=红外
SCENE_TYPE = 0           # 目前只支持 0
TARGET_TYPES = [0, 1]    # 0=人  1=车  2=船


def open_target_detection(token):
    """开启目标识别"""
    body = {
        "ai_lens_type": AI_LENS_TYPE,
        "scene_type": SCENE_TYPE,
        "target_type_list": TARGET_TYPES
    }
    result = api_call(
        token, "POST",
        f"/control/api/v1/devices/{DOCK_SN}/target-detection",
        action="开启目标识别", json_body=body, timeout=20,
    )
    print(f"[✓] 目标识别已开启  lens={AI_LENS_TYPE} targets={TARGET_TYPES}")
    print("    识别结果通过 WebSocket biz_code=target_detect_result_report 推送")
    return result


def close_target_detection(token):
    """关闭目标识别"""
    result = api_call(
        token, "DELETE",
        f"/control/api/v1/devices/{DOCK_SN}/target-detection",
        action="关闭目标识别", timeout=20,
    )
    print("[✓] 目标识别已关闭")
    return result


def main() -> int:
    require_config(YOOX_DOCK_SN=DOCK_SN)
    print(f"[*] 目标设备: {DOCK_SN}")
    print("[!] 本 demo 调用 YOOX Cloud GCS 目标识别接口\n")

    token = login()

    print("操作菜单：")
    print("  1. 开启目标识别")
    print("  2. 关闭目标识别")
    print("  q. 退出\n")

    while True:
        cmd = input("选择: ").strip()
        try:
            if cmd == "q":
                break
            elif cmd == "1":
                open_target_detection(token)
            elif cmd == "2":
                close_target_detection(token)
            else:
                print("  未知操作")
        except DemoError as exc:
            print_error_and_hint(exc)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except DemoError as exc:
        print_error_and_hint(exc)
        raise SystemExit(1)
