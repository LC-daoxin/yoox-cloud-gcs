"""
demo_04_mqtt_osd.py -- 通过 MQTT 直接订阅设备 OSD 原始数据

比 demo_03 (WebSocket) 获取的数据更完整：
  - 遥控器 OSD: 图传质量、4G状态、直播能力、设备列表、无人机摘要
  - 无人机 OSD: 位置、速度、姿态、NED坐标、电池、GPS/RTK、相机参数(焦距/视场角/
                拍照状态/录像状态/剩余数量)、云台角度、避障、限高限远、
                遥控器丢失动作、Remote ID、红外调色板、NTRIP状态、测距等

依赖：pip install paho-mqtt

运行：
    python3 demo_04_mqtt_osd.py
"""
import sys
import json
import time
import paho.mqtt.client as mqtt
from config import MQTT_HOST, MQTT_PORT, MQTT_USERNAME, MQTT_PASSWORD


def on_connect(client, userdata, flags, reason_code, properties):
    if reason_code == 0:
        print("[✓] MQTT 已连接")
        client.subscribe("thing/product/+/osd")
        print("[✓] 已订阅 thing/product/+/osd\n")
    else:
        print(f"[✗] MQTT 连接失败: {reason_code}")


def on_message(client, userdata, msg):
    try:
        payload = json.loads(msg.payload)
        topic = msg.topic
        sn = topic.split("/")[2]
        data = payload.get("data", {})
        gateway = payload.get("gateway", "")

        if "drone_list" in data or "device_list" in data:
            _print_gateway_osd(sn, gateway, data)
        else:
            _print_drone_osd(sn, gateway, data)

    except Exception as e:
        print(f"[!] 解析异常: {e}")


def _print_gateway_osd(sn, gateway, data):
    """打印遥控器 OSD"""
    print(f"{'='*72}")
    print(f"[遥控器 OSD] SN={sn}")
    print(f"  电量: {data.get('capacity_percent','N/A')}%")
    print(f"  位置: lat={data.get('latitude')} lon={data.get('longitude')} h={data.get('height')}")
    print(f"  固件: {data.get('firmware_version','N/A')}")

    wl = data.get("wireless_link") or {}
    print(f"  图传: SDR={'开' if wl.get('sdr_link_state') else '关'} Q{wl.get('sdr_quality','N/A')}"
          f"  4G={'开' if wl.get('4g_link_state') else '关'} Q{wl.get('4g_uav_quality','N/A')}"
          f"  SIM={'有' if wl.get('4g_sim_card_detected') else '无'}")

    for ls in data.get("live_status") or []:
        print(f"  直播: {ls.get('video_id')} type={ls.get('video_type')} "
              f"status={ls.get('status')} quality={ls.get('video_quality')} err={ls.get('error_status')}")

    for dev in data.get("device_list") or []:
        for cam in dev.get("camera_list") or []:
            vids = [v.get("video_type") for v in cam.get("video_list") or []]
            print(f"  相机: index={cam.get('camera_index')} 镜头={vids} "
                  f"可用={cam.get('available_video_number')} 最大共存={cam.get('coexist_video_number_max')}")

    for d in data.get("drone_list") or []:
        bat = (d.get("battery") or {}).get("capacity_percent", "N/A")
        pos = d.get("position_state") or {}
        print(f"  无人机摘要: SN={d.get('sn')} 电量={bat}% "
              f"h={d.get('height')} spd={d.get('horizontal_speed')} "
              f"GPS={pos.get('gps_number',0)} mode={d.get('mode_code')}")
        print(f"              名称={d.get('drone_name')} ID={d.get('drone_id')} "
              f"DSP={d.get('dsp_quality')} 存储={d.get('storage',{}).get('used','?')}/{d.get('storage',{}).get('total','?')}")


def _print_drone_osd(sn, gateway, data):
    """打印无人机 OSD -- 完整输出所有字段"""
    print(f"{'='*72}")
    print(f"[无人机 OSD] SN={sn}  gateway={gateway}")

    # 位置和运动
    print("  ── 位置 ──")
    print(f"    lat={data.get('latitude')} lon={data.get('longitude')} "
          f"h={data.get('height')} elev={data.get('elevation')}")
    print(f"    NED: alt={data.get('ned_altitude')} lat={data.get('ned_latitude')} lon={data.get('ned_longitude')}")
    print(f"    NED速度: x={data.get('vel_ned_x')} y={data.get('vel_ned_y')} z={data.get('vel_ned_z')}")
    print(f"    速度: H={data.get('horizontal_speed')} V={data.get('vertical_speed')}")

    # 姿态
    print("  ── 姿态 ──")
    print(f"    head={data.get('attitude_head')} pitch={data.get('attitude_pitch')} "
          f"roll={data.get('attitude_roll')} 齿轮={data.get('gear_level')}")

    # 电池
    bat = data.get("battery") or {}
    print("  ── 电池 ──")
    print(f"    电量={bat.get('capacity_percent','N/A')}% 电压={bat.get('voltage','N/A')}mV")
    print(f"    剩余飞行={bat.get('remain_flight_time','N/A')}s "
          f"降落电量={bat.get('landing_power','N/A')} 返航电量={bat.get('return_home_power','N/A')}")

    # GPS/RTK
    pos = data.get("position_state") or {}
    print("  ── 定位 ──")
    print(f"    GPS卫星={pos.get('gps_number',0)} RTK卫星={pos.get('rtk_number',0)} "
          f"定位={pos.get('is_fixed',0)} 质量={pos.get('quality',0)}")
    print(f"    RTK: lat={pos.get('rtk_lat')} lon={pos.get('rtk_lon')} hgt={pos.get('rtk_hgt')} "
          f"inpos={pos.get('rtk_inpos')} used={pos.get('rtk_used')}")
    print(f"    NTRIP={data.get('ntrip_status','N/A')} pos_type={data.get('pos_type','N/A')}")

    # 模式和状态
    print("  ── 状态 ──")
    print(f"    mode={data.get('mode_code')} 固件={data.get('firmware_version')} "
          f"国家={data.get('country')} 兼容={data.get('compatible_status')}")
    print(f"    夜灯={data.get('night_lights_state')} 警报={data.get('alarm_status')} "
          f"AI={data.get('ai_enable_func')} 红外调色板={data.get('infrared_palette_mode')}")
    print(f"    云台跟随={data.get('gimbal_angle_follow_toggle')} "
          f"RemoteID={data.get('rid_state')} remote_id_status={data.get('remote_id_status')}")
    print(f"    遥控丢失动作={data.get('rc_lost_action')} 总飞行={data.get('total_flight_time','N/A')}s "
          f"总武装={data.get('total_armed_time','N/A')}s")

    # 避障
    obs = data.get("obstacle_avoidance") or {}
    print("  ── 避障 ──")
    print(f"    上={obs.get('upside')} 下={obs.get('downside')} 水平={obs.get('horizon')}")

    # 限高限远
    dls = data.get("distance_limit_status") or {}
    print("  ── 限制 ──")
    print(f"    限远={dls.get('distance_limit','N/A')}m state={dls.get('state')} "
          f"接近限远={'是' if dls.get('is_near_distance_limit') else '否'}")
    print(f"    限高={data.get('height_limit','N/A')}m "
          f"接近限高={'是' if data.get('is_near_height_limit') else '否'} "
          f"接近限区={'是' if data.get('is_near_area_limit') else '否'}")
    print(f"    返航距离={data.get('home_distance','N/A')}m")

    # 存储
    storage = data.get("storage") or {}
    print("  ── 存储 ──")
    print(f"    used={storage.get('used','N/A')} total={storage.get('total','N/A')} "
          f"type={storage.get('storage_type','N/A')}")

    # 相机详情
    for cam in data.get("cameras") or []:
        print(f"  ── 相机 {cam.get('payload_index')} ──")
        print(f"    变焦: factor={cam.get('zoom_factor')} focal={cam.get('zoom_focal_length')}mm "
              f"FOV={cam.get('zoom_fov_h')}x{cam.get('zoom_fov_v')}")
        print(f"    红外: factor={cam.get('ir_zoom_factor')} focal={cam.get('ir_focal_length')}mm "
              f"FOV={cam.get('ir_fov_h')}x{cam.get('ir_fov_v')}")
        print(f"    状态: mode={cam.get('camera_mode')} photo={cam.get('photo_state')} "
              f"recording={cam.get('recording_state')} record_time={cam.get('record_time')}s")
        print(f"    剩余: 照片={cam.get('remain_photo_num')}张 录像={cam.get('remain_record_duration')}s")
        print(f"    存储: 照片={cam.get('photo_storage_settings')} 录像={cam.get('video_storage_settings')}")
        print(f"    分屏={cam.get('screen_split_enable')} 红外测光={cam.get('ir_metering_mode')}")

    # 云台角度和测距（以 payload_index 为 key 的字段）
    for key, val in data.items():
        if "-" in key and isinstance(val, dict) and "gimbal_pitch" in val:
            print(f"  ── 云台 {val.get('payload_index')} ──")
            print(f"    pitch={val.get('gimbal_pitch')} roll={val.get('gimbal_roll')} "
                  f"yaw={val.get('gimbal_yaw')} zoom={val.get('zoom_factor')}")
            # 测距信息
            err = val.get('measure_target_error_state', None)
            dist = val.get('measure_target_distance', None)
            if dist is not None:
                tlat = val.get('measure_target_latitude', 'N/A')
                tlon = val.get('measure_target_longitude', 'N/A')
                talt = val.get('measure_target_altitude', 'N/A')
                print(f"    测距: 距离={dist}m 目标lat={tlat} lon={tlon} 相对高度={talt}m 错误状态={err}")
            else:
                print(f"    测距: 无数据 (错误状态={err})")

    # 激活时间
    print("  ── 其他 ──")
    print(f"    激活时间={data.get('activation_time','N/A')} "
          f"GPS时间={data.get('gps_time','N/A')}")


if __name__ == "__main__":
    print(f"[*] MQTT 地址: {MQTT_HOST}:{MQTT_PORT}")
    print(f"[*] 账号: {MQTT_USERNAME or '(匿名)'}")
    print()

    client_id = f"demo_04_{int(time.time())}"
    client = mqtt.Client(
        callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
        client_id=client_id,
    )
    if MQTT_USERNAME:
        client.username_pw_set(MQTT_USERNAME, MQTT_PASSWORD)
    client.on_connect = on_connect
    client.on_message = on_message

    try:
        client.connect(MQTT_HOST, MQTT_PORT, keepalive=30)
    except Exception as e:
        print(f"[✗] MQTT 连接失败: {e}")
        sys.exit(1)

    client.loop_forever()
