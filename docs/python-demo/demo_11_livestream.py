"""
demo_11_livestream.py -- 直播全流程

本 Demo 固定使用：
  url_type=2  RTSP，设备推流到服务器配置的 RTSP 地址

video_id 格式：{drone_sn}/{payload_index}/{video_type}-0
  从 MQTT OSD 的 live_status 中获取

video_quality（仅以下值有效）：
  2=标清  3=高清

video_type（镜头切换，仅 zoom / ir 有效）：
  zoom / ir

RTSP 拉流地址格式（MediaMTX 动态路径）：
  rtsp://{server_ip}:{port}/{drone_sn}-{payload_index}

运行：
    python3 demo_11_livestream.py
"""
import json
import re
import shutil
import subprocess
import time
import paho.mqtt.client as mqtt
from config import (
    MQTT_HOST,
    MQTT_PASSWORD,
    MQTT_PORT,
    MQTT_USERNAME,
    RTSP_HOST,
    RTSP_PORT,
)
from demo_common import DemoApiError, DemoError, api_call, login, print_error_and_hint


# ── 直播配置 ──────────────────────────────────────────────
URL_TYPE = 2          # 本 Demo 与当前 P0 运行时固定使用 RTSP
VIDEO_QUALITY = 2     # 默认标清更流畅；可在菜单中切换到 3=高清
# RTSP 服务配置来自 .env，对应 application.yml 中 livestream.url.rtsp。


def get_video_ids_from_mqtt(timeout_sec=8) -> list:
    """通过 MQTT 订阅遥控器 OSD，从 live_status 中提取 video_id 列表。

    注意：这里只是枚举设备上报过的镜头通道（video_id），
    不代表 status==1（正在推流）；是否真的有流请用 probe_rtsp 判断。"""
    video_ids = []
    seen = set()
    done = [False]

    def on_connect(client, userdata, flags, reason_code, properties):
        if reason_code == 0:
            client.subscribe("thing/product/+/osd")
        else:
            done[0] = True

    def on_message(client, userdata, msg):
        try:
            payload = json.loads(msg.payload)
            data = payload.get("data", {})
            # 遥控器 OSD 包含 live_status
            live_status = data.get("live_status")
            if live_status:
                for ls in live_status:
                    vid = ls.get("video_id")
                    vtype = ls.get("video_type", "normal")
                    status = ls.get("status")
                    if vid and vid not in seen:
                        seen.add(vid)
                        video_ids.append(vid)
                        state = "设备自报推流中" if status == 1 else "未推流"
                        print(f"  发现镜头通道: {vid}  type={vtype}  status={status}({state})")
                if video_ids:
                    done[0] = True
                    client.disconnect()
        except Exception:
            pass

    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2,
                         client_id=f"demo_11_{int(time.time())}")
    if MQTT_USERNAME:
        client.username_pw_set(MQTT_USERNAME, MQTT_PASSWORD)
    client.on_connect = on_connect
    client.on_message = on_message

    try:
        client.connect(MQTT_HOST, MQTT_PORT, keepalive=10)
        client.loop_start()
        start = time.time()
        while not done[0] and time.time() - start < timeout_sec:
            time.sleep(0.2)
        client.loop_stop()
        client.disconnect()
    except Exception as e:
        print(f"[!] MQTT 连接失败: {e}")

    return video_ids


def build_rtsp_playback_url(video_id: str) -> str:
    """不带账号密码的拉流地址（展示/播放提示用，避免在终端泄露凭据）"""
    parts = video_id.split("/")
    drone_sn = parts[0] if len(parts) > 0 else "unknown"
    payload_index = parts[1] if len(parts) > 1 else "0-0-0"
    return f"rtsp://{RTSP_HOST}:{RTSP_PORT}/{drone_sn}-{payload_index}"


def parse_video_type(video_id: str) -> str:
    """从 video_id（如 drone_sn/payload_index/zoom-0）中解析出当前镜头类型。"""
    parts = video_id.split("/")
    if len(parts) < 3:
        return "normal"
    return parts[2].split("-")[0] or "normal"


def resolve_alternate_lens(desired_lens: str) -> str:
    """与前端 CockpitView.vue 的双传感器兜底策略一致：zoom<->ir 互为唤醒镜头。"""
    if desired_lens == "zoom":
        return "ir"
    if desired_lens == "ir":
        return "zoom"
    return "ir"


def probe_rtsp(rtsp_url: str, timeout_sec=12) -> bool | None:
    """用 ffprobe 确认 MediaMTX 路径。True=有流，False=已探测但无流，None=无法探测。"""
    ffprobe = shutil.which("ffprobe")
    if not ffprobe:
        print("    [!] 未安装 ffprobe，无法判定媒体流是否存在")
        return None
    try:
        result = subprocess.run(
            [ffprobe, "-v", "error", "-rtsp_transport", "tcp",
             "-show_entries", "stream=codec_name,width,height",
             "-of", "default=noprint_wrappers=1", rtsp_url],
            capture_output=True, text=True, timeout=timeout_sec)
        if result.returncode == 0 and result.stdout.strip():
            print("    [✓] MediaMTX 已收到视频流")
            for line in result.stdout.strip().splitlines():
                print(f"        {line}")
            return True
        detail = result.stderr.strip() or "未发现视频轨道"
        # ffprobe 常会在 stderr 中回显输入 URL；无论调用方是否
        # 误传了带凭证的 URL，都不允许密码进入终端日志。
        detail = re.sub(r"(?i)rtsp://[^\s]+@", "rtsp://***@", detail)
        print(f"    [✗] RTSP 路径暂不可播放: {detail}")
    except subprocess.TimeoutExpired:
        print(f"    [✗] {timeout_sec} 秒内未收到 RTSP 视频数据")
    return False


def live_start(token, video_id: str, url_type: int = 2, video_quality: int = VIDEO_QUALITY,
               video_type: str | None = None):
    """开始直播

    video_type 默认从 video_id 中解析当前镜头并显式下发，避免设备沿用上一次
    会话残留的镜头（例如恢复流程临时切到红外后未正确切回，导致新会话仍是红外）。
    """
    if video_type is None:
        video_type = parse_video_type(video_id)
    body = {
        "url_type": url_type,
        "video_id": video_id,
        "video_quality": video_quality,
    }
    if video_type in ("zoom", "ir"):
        body["video_type"] = video_type
    # 默认不传自定义 url：由服务端使用已配置的发布凭证，并允许
    # LiveStreamServiceImpl 先检查 MediaMTX publisher 后复用，避免重启已有直播。
    # 服务端等待设备 MQTT 应答最坏情况可达 3次×20秒=60秒（见 AbstractLivestreamService.DEFAULT_TIMEOUT ＋
    # MqttGatewayPublish.DEFAULT_RETRY_COUNT），这里要盖过那个时长，否则会先于服务端报出 ReadTimeout
    result = api_call(
        token,
        "POST",
        "/manage/api/v1/live/streams/start",
        action="开始直播",
        json_body=body,
        timeout=65,
    )
    print(f"[✓] 直播开始指令已受理  video_id={video_id}")
    if url_type == 2:
        live_data = result.get("data") or {}
        response_url = live_data.get("url") if isinstance(live_data, dict) else None
        playback_url = build_rtsp_playback_url(video_id)
        print(f"    RTSP 路径（凭证已隐藏）: {playback_url}")
        if isinstance(response_url, str) and response_url and not response_url.startswith("rtsp://"):
            print(f"    服务端播放端点: {response_url}")
        print("    播放地址不包含设备发布凭据；不要把服务端发布密码写入日志或命令历史。")
        print("    正在等待首帧并检查实际媒体流...")
        # /streams/start 在当前项目返回 WHEP 播放 URL，ffprobe 只能
        # 探测对应的 MediaMTX RTSP 路径，不能把 data.url 当 RTSP。
        probe_rtsp(playback_url)
    return result


def ensure_rtsp_live(token, video_id: str) -> str | None:
    """复用现有发布流；没有流时清理设备旧状态并重新启动。"""
    rtsp_url = build_rtsp_playback_url(video_id)
    print("[*] 检查 MediaMTX 中是否已有可复用的视频流...")
    probe = probe_rtsp(rtsp_url, timeout_sec=4)
    if probe is True:
        print("[✓] 已有直播流，直接复用")
        return rtsp_url

    if probe is False:
        print("[*] 当前路径未检测到媒体；先调用服务端开始/复用接口，不提前停止设备推流")
    else:
        # 缺少 ffprobe 只代表“未知”，不是“无流”。不先 stop，避免
        # 把多设备切换后仍在使用的 publisher 误关闭。
        print("[*] 媒体状态无法探测：不停止现有 publisher，直接发送开始/复用请求")
    try:
        live_start(token, video_id, URL_TYPE, VIDEO_QUALITY)
    except DemoApiError as exc:
        try:
            api_code = int(exc.api_code)
        except (TypeError, ValueError):
            api_code = 0
        stale_device_state = (
            not exc.ambiguous
            and probe is False
            and api_code % 100000 == 13003
        )
        if not stale_device_state:
            raise
        print("[!] 设备报告直播已开始，但 MediaMTX 中没有媒体；可能是设备遗留状态。")
        if input("    输入 YES 确认停止该 video_id 后重新开始: ").strip() != "YES":
            raise DemoError("已取消清理；未自动重发直播指令") from exc
        live_stop(token, video_id)
        time.sleep(1)
        live_start(token, video_id, URL_TYPE, VIDEO_QUALITY)

    # live_start 已做一次首帧探测；这里再次确认发布者仍在线，避免把
    # 设备的“指令成功”误报成可播放。
    final_probe = probe_rtsp(rtsp_url, timeout_sec=6)
    return rtsp_url if final_probe is not False else None


def live_stop(token, video_id: str):
    """停止直播"""
    body = {"video_id": video_id}
    result = api_call(
        token, "POST", "/manage/api/v1/live/streams/stop",
        action="停止直播", json_body=body, timeout=65,
    )
    print("[✓] 停止直播指令已受理")
    return result


def live_set_quality(token, video_id: str, video_quality: int):
    """切换清晰度  仅 2=标清  3=高清 有效"""
    body = {"video_id": video_id, "video_quality": video_quality}
    result = api_call(
        token, "POST", "/manage/api/v1/live/streams/update",
        action="切换直播清晰度", json_body=body, timeout=65,
    )
    quality_map = {2: "标清", 3: "高清"}
    print(f"[✓] 切换清晰度 -> {quality_map.get(video_quality, video_quality)}")
    return result


def live_switch_lens(token, video_id: str, video_type: str):
    """切换镜头  仅 zoom / ir 有效"""
    body = {"video_id": video_id, "video_type": video_type}
    result = api_call(
        token, "POST", "/manage/api/v1/live/streams/switch",
        action="切换直播镜头", json_body=body, timeout=65,
    )
    print(f"[✓] 切换镜头 -> {video_type}")
    return result


def live_start_recover_by_lens_switch(token, video_id: str) -> str | None:
    """开始直播后经常出现设备指令应答成功、却始终不推流的情况——EVO Max 固件缺陷：
    RTSP 轨道已宣告但编码器未真正开始产生媒体包，直到镜头被“碰一下”才会吐首帧。

    与前端 CockpitView.vue 的 recoverVideoEncoder 保持一致的判定/延迟节奏：
    等待 4 秒判断首帧是否到达；未到达则临时切到备用镜头触发编码器重建，
    等 1.8 秒后切回目标镜头，再等 1.2 秒让编码器吐出目标镜头的首帧；
    若切回目标镜头失败，会补发一次纠正指令，避免设备停留在临时镜头上。"""
    rtsp_url = build_rtsp_playback_url(video_id)
    desired_lens = parse_video_type(video_id)
    if desired_lens not in {"normal", "wide", "zoom", "ir"}:
        print(
            f"[✗] 镜头 {desired_lens} 不支持安全的切换恢复；"
            "不会先切到备用镜头，请改用普通开始并检查该负载能力"
        )
        return None
    alternate_lens = resolve_alternate_lens(desired_lens)

    live_start(token, video_id, URL_TYPE, VIDEO_QUALITY, video_type=desired_lens)

    print("[*] 等待 4 秒检查是否已产生首帧...")
    time.sleep(4)
    probe = probe_rtsp(rtsp_url, timeout_sec=3)
    if probe is True:
        print("[✓] 已检测到推流")
        return rtsp_url
    if probe is None:
        print("[!] 无法验证首帧，为避免不必要的镜头切换，不执行编码器恢复")
        return rtsp_url

    print(f"[*] 未检测到推流，临时切到 {alternate_lens} 触发编码器重建...")
    alternate_may_have_applied = False
    desired_restored = False
    try:
        try:
            live_switch_lens(token, video_id, alternate_lens)
            alternate_may_have_applied = True
        except DemoApiError as exc:
            alternate_may_have_applied = exc.ambiguous
            if not exc.ambiguous:
                raise
            print("[!] 临时镜头切换结果未知；将继续下发目标镜头作为安全纠正，不重复临时切换。")
        time.sleep(1.8)
        print(f"[*] 切回目标镜头 {desired_lens}...")
        try:
            live_switch_lens(token, video_id, desired_lens)
            desired_restored = True
        except DemoApiError as first_error:
            if not alternate_may_have_applied:
                raise
            # 设备可能已经移动到临时镜头。即使第一次切回结果未知，也只补发
            # 目标镜头这一安全纠正，不再次发送临时镜头指令。
            print(f"[!] 切回 {desired_lens} 未确认（{first_error}），补发一次目标镜头纠正指令...")
            try:
                live_switch_lens(token, video_id, desired_lens)
                desired_restored = True
            except DemoApiError as retry_error:
                raise DemoError(
                    f"目标镜头 {desired_lens} 连续两次未确认；请立即从画面/设备状态核验当前镜头"
                ) from retry_error
    finally:
        if alternate_may_have_applied and not desired_restored:
            # KeyboardInterrupt、sleep 异常或意外运行时错误都不能绕过镜头恢复。
            # 保留原异常，只把本次纠正失败记录到终端。
            print(f"[!] 恢复流程异常中断，最后尝试切回目标镜头 {desired_lens}...")
            try:
                live_switch_lens(token, video_id, desired_lens)
            except Exception as correction_error:
                print(f"[!!] 最终镜头纠正未确认: {correction_error}")
    time.sleep(1.2)

    final_probe = probe_rtsp(rtsp_url, timeout_sec=6)
    if final_probe is True:
        print("[✓] 切换镜头后已恢复推流")
        return rtsp_url
    if final_probe is None:
        print("[!] 无法验证恢复结果，请从 WHEP/RTSP 播放端确认")
        return rtsp_url

    print("[✗] 切换镜头后仍未恢复推流")
    return None


def main() -> int:
    token = login()
    # 1. 从 MQTT OSD 获取 video_id（直播能力接口 camerasList 为空时走此路径）
    print("[*] 从 MQTT OSD 获取视频流...")
    video_ids = get_video_ids_from_mqtt()

    if not video_ids:
        # 回退到直播能力接口
        print("[*] MQTT 未获取到，尝试直播能力接口...")
        result = api_call(
            token, "GET", "/manage/api/v1/live/capacity",
            action="查询直播能力", timeout=10,
        )
        seen_video_ids = set(video_ids)
        for dev in result.get("data") or []:
            for cam in dev.get("cameras_list") or dev.get("camerasList") or []:
                payload_index = cam.get("index") or cam.get("payload_index")
                if not payload_index:
                    continue
                videos = cam.get("videos_list") or cam.get("videosList") or []
                for video in videos:
                    video_type = video.get("type") or video.get("video_type")
                    if not video_type:
                        video_index = str(video.get("index") or video.get("video_index") or "")
                        video_type = video_index.split("-")[0]
                    if video_type not in {"normal", "wide", "zoom", "ir", "thermal"}:
                        continue
                    vid = f"{dev.get('sn')}/{payload_index}/{video_type}-0"
                    if vid not in seen_video_ids:
                        seen_video_ids.add(vid)
                        video_ids.append(vid)

    if not video_ids:
        print("[✗] 没有可用的视频流，请确认无人机已上线")
        return 1

    print(f"\n[✓] 可发起直播的镜头通道 ({len(video_ids)} 个，仅表示存在该 video_id，不代表当前正在推流):")
    for i, vid in enumerate(video_ids):
        print(f"  [{i}] {vid}")
        print(f"      RTSP: {build_rtsp_playback_url(vid)}（凭证已隐藏）")

    # 选择视频流
    selected = video_ids[0]
    if len(video_ids) > 1:
        idx = input(f"\n选择视频流编号 [0-{len(video_ids)-1}] (默认0): ").strip()
        if idx.isdigit() and int(idx) < len(video_ids):
            selected = video_ids[int(idx)]

    print(f"\n[*] 使用 video_id: {selected}")
    print(f"[*] RTSP 路径: {build_rtsp_playback_url(selected)}（凭证已隐藏）")

    print("\n操作菜单：")
    print("  1. 开始直播")
    print("  2. 停止直播")
    print("  3. 切换清晰度（2=标清 3=高清）")
    print("  4. 切换镜头（zoom/ir）")
    print("  6. 开始直播，若4秒无首帧则临时切镜头强制恢复（与前端 CockpitView 逻辑一致）")
    print("  q. 退出\n")

    while True:
        cmd = input("输入操作: ").strip()
        try:
            if cmd == "q":
                break
            elif cmd == "1":
                ensure_rtsp_live(token, selected)
            elif cmd == "2":
                live_stop(token, selected)
            elif cmd == "3":
                quality = input("  清晰度(2=标清 3=高清): ").strip()
                if quality not in {"2", "3"}:
                    print("  只支持 2=标清 或 3=高清")
                    continue
                live_set_quality(token, selected, int(quality))
            elif cmd == "4":
                lens = input("  镜头类型(zoom/ir): ").strip()
                if lens not in {"zoom", "ir"}:
                    print("  只支持 zoom 或 ir")
                    continue
                live_switch_lens(token, selected, lens)
            elif cmd == "6":
                live_start_recover_by_lens_switch(token, selected)
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
