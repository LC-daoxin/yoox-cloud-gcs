#!/usr/bin/env python3
"""MQTT services 指令报文 A/B 测试工具（直连 EMQX，绕开云端业务层）。

用途
----
当某条 services 指令报 211001（No message reply received）时，本工具直接向
EMQX 发布候选报文并监听 services_reply，用「哪些变体能收到回复」判定固件
真正接受的报文格式，避免反复改代码/重建镜像。

用法
----
    # 使用 .env 或内置默认值；--gateway/--drone 覆盖 SN
    # python-demo 虚拟环境已自带 paho-mqtt：
    docs/python-demo/.venv/bin/python scripts/mqtt-services-abtest.py --method camera_look_at

    # 自定义变体（JSON 形式 data，多组用分号分隔）：
    ... scripts/mqtt-services-abtest.py --method return_home \
        --variant '{"data": {"key": "value1"}}' \
        --variant '{"data": {"key": "value2"}, "no_device_list": true}'

约定（源自 2026-08-11/12 实测，详见 docs/15-MQTT直连AB测试指南.md）
--------------------------------------------------------------------
- EVO RC 对 **data:null 的报文一律静默丢弃**（如 return_home 必须 data:{}）；
- 同 method 全变体零回复、而对照组指令秒回 → 固件未实现该 method（如
  camera_look_at，1.9.1.203 实测 9 种变体全部丢弃）。

注意
----
本工具会**真实触发指令**（返回 result=0 即设备已执行）。测试前确认飞机处于
安全状态（悬停/地面），return_home/fly_to_point 等飞控类指令会实际改变飞行。
"""
import argparse
import json
import time
import uuid

import paho.mqtt.client as mqtt

DEFAULTS = {
    "host": "127.0.0.1",
    "port": 1883,
    "username": "yoox-cloud",
    "password": "yoox_mqtt_local_dev",
    "gateway_sn": "TH7926043417",
    "drone_sn": "1748FEV3HMP925511143",
}

# 常用负载索引（EVO Max 4T 可见光）。
PAYLOAD_INDEX = "10806-0-0"

# 已知可秒回的对照指令：用于排除链路/权限因素，任何 A/B 测试都应带上它。
CONTROL = {
    "label": "对照: camera_screen_drag（已知成功格式）",
    "method": "camera_screen_drag",
    "data": {"payload_index": PAYLOAD_INDEX, "locked": False,
             "pitch_speed": 0.0, "yaw_speed": 0.0},
    "device_list": True,
}


def parse_args():
    parser = argparse.ArgumentParser(description="MQTT services 指令 A/B 测试")
    parser.add_argument("--method", required=True, help="method 名，如 camera_look_at / return_home")
    parser.add_argument("--variant", action="append", default=[],
                        help="变体 JSON：{\"data\": {...}, \"no_device_list\": true, "
                             "\"drone_topic\": true}。可多次传入；不传则发送纯文档格式(空 data)")
    parser.add_argument("--gateway", default=DEFAULTS["gateway_sn"])
    parser.add_argument("--drone", default=DEFAULTS["drone_sn"])
    parser.add_argument("--host", default=DEFAULTS["host"])
    parser.add_argument("--port", type=int, default=DEFAULTS["port"])
    parser.add_argument("--username", default=DEFAULTS["username"])
    parser.add_argument("--password", default=DEFAULTS["password"])
    parser.add_argument("--wait", type=float, default=4.0, help="每个变体等待回复秒数")
    parser.add_argument("--no-control", action="store_true", help="跳过对照指令")
    return parser.parse_args()


def main():
    args = parse_args()
    gateway_sn, drone_sn = args.gateway, args.drone
    gateway_topic = f"thing/product/{gateway_sn}/services"
    drone_topic = f"thing/product/{drone_sn}/services"
    gateway_reply = f"thing/product/{gateway_sn}/services_reply"
    drone_reply = f"thing/product/{drone_sn}/services_reply"

    replies = []

    def on_message(_client, _userdata, msg):
        body = json.loads(msg.payload)
        replies.append(body)
        print(f"  << [{msg.topic}] method={body.get('method')} "
              f"tid={body.get('tid')} data={body.get('data')}")

    client = mqtt.Client(client_id=f"abtest-{uuid.uuid4().hex[:8]}")
    client.username_pw_set(args.username, args.password)
    client.on_message = on_message
    client.connect(args.host, args.port, 30)
    # 同时监听网关与子飞机的 services_reply：部分固件回复主题与下发主题不一致。
    client.subscribe(gateway_reply, qos=0)
    client.subscribe(drone_reply, qos=0)
    client.loop_start()
    time.sleep(1)

    # 变体矩阵：未指定时退化为「空 data」单变体。
    cases = []
    if args.variant:
        for index, raw in enumerate(args.variant, 1):
            spec = json.loads(raw)
            cases.append({
                "label": f"变体{index}: {raw if len(raw) < 60 else raw[:57] + '...'}",
                "method": args.method,
                "data": spec.get("data"),
                "device_list": not spec.get("no_device_list"),
                "topic": drone_topic if spec.get("drone_topic") else gateway_topic,
            })
    else:
        for with_dl in (True, False):
            cases.append({
                "label": f"{args.method} data={{}} "
                         f"{'+' if with_dl else '无 '}device_list",
                "method": args.method,
                "data": {},
                "device_list": with_dl,
                "topic": gateway_topic,
            })

    if not args.no_control:
        cases.append({**CONTROL, "topic": gateway_topic})

    for case in cases:
        tid = str(uuid.uuid4())
        payload = {
            "tid": tid,
            "bid": tid,
            "timestamp": int(time.time() * 1000),
            "method": case["method"],
            "data": case["data"],
        }
        if case["device_list"]:
            payload["device_list"] = [{"sn": drone_sn}]
        print(f"\n>> {case['label']}\n   topic={case['topic']}\n"
              f"   {json.dumps(payload, ensure_ascii=False)}")
        before = len(replies)
        client.publish(case["topic"], json.dumps(payload), qos=0)
        time.sleep(args.wait)
        matched = [r for r in replies[before:] if r.get("tid") == tid]
        if matched:
            print(f"   ✅ 收到回复 result={matched[0].get('data')}")
        else:
            print("   ❌ 无回复")

    client.loop_stop()
    client.disconnect()
    print("\n测试结束")


if __name__ == "__main__":
    main()
