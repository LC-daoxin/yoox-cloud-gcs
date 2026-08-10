"""
demo_02_devices.py -- 查询在线设备列表，获取遥控器/无人机 SN 和负载索引

运行：
    python3 demo_02_devices.py

输出结果中的 SN / payload_index 用于填写本机 ``.env``。
"""
from config import WORKSPACE_ID
from demo_common import DemoError, api_call, login, print_error_and_hint, require_config


def get_devices(token):
    domain_map = {0: "无人机", 1: "负载", 2: "遥控器", 3: "机巢"}

    # 设备拓扑
    result = api_call(
        token,
        "GET",
        f"/manage/api/v1/devices/{WORKSPACE_ID}/devices",
        action="查询设备列表",
        timeout=10,
    )
    devices = result.get("data") or []

    print(f"\n[✓] 设备列表 (workspace: {WORKSPACE_ID})")
    print(f"{'SN':<32} {'类型':<8} {'子设备SN':<24} {'在线'}")
    print("-" * 80)

    dock_sn = None
    drone_sn = None
    for d in devices:
        sn = d.get("device_sn", "")
        domain = domain_map.get(d.get("domain"), str(d.get("domain")))
        child = d.get("child_device_sn", "") or ""
        # 服务端 DTO.status 来自设备在线缓存；Demo 不依赖部署机上的 Docker/Redis。
        status = d.get("status")
        online_str = "✓ 在线" if status is True else "○ 离线" if status is False else "? 未知"
        print(f"{sn:<32} {domain:<8} {child:<24} {online_str}")

        # 遥控器/机巢的 child_device_sn 就是无人机 SN
        if child and d.get("domain") in (2, 3):
            drone_sn = child
            # 如果是机巢(domain=3)，dock_sn 就是它自己
            if d.get("domain") == 3:
                dock_sn = sn
            # 如果是遥控器(domain=2)，dock_sn 暂时等于遥控器 SN（后续用直播能力补）
            elif d.get("domain") == 2 and not dock_sn:
                dock_sn = sn

    if not devices:
        print("  (无设备)")
        return

    print("\n[!] 请将以下值填入 .env（不要提交 .env）：")
    if dock_sn:
        print(f"    YOOX_DOCK_SN={dock_sn}")
    if drone_sn:
        print(f"    YOOX_DRONE_SN={drone_sn}")
    else:
        print("    # YOOX_DRONE_SN=（暂无子设备，设备上线后再填写）")

    # 检查是否有设备在线
    any_online = any(d.get("status") is True for d in devices)
    if not any_online:
        print("\n[!] 服务端设备列表中没有在线设备")
        print("    设备需要通过 MQTT 发送 status/update_topo 才能上线")
        print("    请在 Pilot App 中重新连接云服务")

    # 直播能力
    live_result = api_call(
        token,
        "GET",
        "/manage/api/v1/live/capacity",
        action="查询直播能力",
        timeout=10,
    )
    live_data = live_result.get("data") or []

    if live_data:
        print("\n[✓] 在线设备直播能力（可获取 payload_index）")
        for dev in live_data:
            print(f"\n  设备 SN: {dev.get('sn')}")
            # 兼容两种字段格式：cameras_list（当前接口）/ camerasList（旧格式）
            cameras = dev.get("cameras_list") or dev.get("camerasList") or []
            for cam in cameras:
                # 当前接口返回 index；旧版本返回 payload_index
                index = cam.get("index") or cam.get("payload_index")
                name = cam.get("name") or cam.get("camera_type", "")
                # 镜头列表在 videos_list[].type
                videos = cam.get("videos_list") or cam.get("videosList") or []
                lens = [v.get("type") for v in videos if v.get("type")]
                lens_str = f" 镜头: {','.join(lens)}" if lens else ""
                print(f"    摄像头: {index} | {name}{lens_str}")
                print(f"    -> .env: YOOX_PAYLOAD_INDEX={index}")
    else:
        print("\n[!] 直播能力为空（设备未上报 capability）")
        print("    PAYLOAD_INDEX 只能从 OSD 数据获取：")
        print("    1. 运行 demo_03_websocket_osd.py")
        print("    2. 查看推送的 OSD 消息中 payloads[].payload_index 字段")
        print("    3. 格式为 'domain-type-subtype'，如 '1-10052-0'")


if __name__ == "__main__":
    try:
        require_config(YOOX_WORKSPACE_ID=WORKSPACE_ID)
        get_devices(login())
    except DemoError as exc:
        print_error_and_hint(exc)
        raise SystemExit(1)
