# YOOX Cloud GCS API 指南

> 适用版本：1.0.0  
> 更新日期：2026-08-10
> 在线契约：`/swagger-ui/index.html`、`/v3/api-docs`  
> 在线文档门户：`http://<YOOX_PUBLIC_HOST>:<YOOX_API_PORTAL_PORT>`（默认 8081，`api-portal` 静态站）

## 1. 基本约定

HTTP API 统一由 Web 入口提供。以下示例假设：

```bash
BASE_URL=http://127.0.0.1:8080
PILOT_BASE_URL=http://127.0.0.1:9000
```

Web 管理端可使用 `BASE_URL`；遥控器登录和 `/api/v1/ws` 使用 `PILOT_BASE_URL`。

除登录、令牌刷新、健康检查和 OpenAPI 外，请求必须携带：

```http
x-auth-token: <access_token>
Content-Type: application/json
```

普通响应：

```json
{
  "code": 0,
  "message": "success",
  "data": {}
}
```

`code=0` 表示业务成功。HTTP `401` 或业务 `code=401` 表示令牌无效；其他非零值保留设备或
业务错误语义。调用方必须同时检查 HTTP 状态和 `code`。

## 2. 登录

```bash
curl -sS "$BASE_URL/manage/api/v1/login" \
  -H 'content-type: application/json' \
  -d '{"username":"admin","password":"Yoox@123456","flag":1}'
```

`flag=1` 表示 Web 账号，`flag=2` 表示 Pilot 账号。Web 登录成功数据包含 `access_token`、
`workspace_id` 和用户信息。初始化账号只用于首次配置。

刷新：

```bash
curl -sS -X POST "$BASE_URL/manage/api/v1/token/refresh" \
  -H "x-auth-token: $TOKEN"
```

修改当前登录密码：

```bash
curl -sS -X PUT "$BASE_URL/manage/api/v1/users/current/password" \
  -H "x-auth-token: $TOKEN" \
  -H 'content-type: application/json' \
  -d '{"old_password":"<current>","new_password":"<new-strong-password>"}'
```

新密码长度为 12–72 位，并同时包含大写字母、小写字母、数字和特殊字符。修改成功后客户端应
清除旧令牌并重新登录。

## 3. 核心 HTTP API

### 3.1 工作空间、设备与状态

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/manage/api/v1/workspaces/current` | 当前工作空间 |
| GET | `/manage/api/v1/users/current` | 当前用户 |
| GET | `/manage/api/v1/devices/{workspace_id}/devices` | 设备拓扑 |
| GET | `/manage/api/v1/devices/{workspace_id}/devices/{device_sn}` | 设备详情 |
| GET | `/manage/api/v1/devices/{workspace_id}/devices/bound` | 已绑定设备分页 |
| POST | `/manage/api/v1/devices/{device_sn}/binding` | 绑定设备 |
| DELETE | `/manage/api/v1/devices/{device_sn}/unbinding` | 解绑设备 |
| PUT | `/manage/api/v1/devices/{workspace_id}/devices/{device_sn}` | 修改设备 |
| PUT | `/manage/api/v1/devices/{workspace_id}/devices/{device_sn}/property` | 设置单个设备属性 |
| GET | `/map/api/v1/workspaces/{workspace_id}/device-status` | 地图设备状态 |
| GET | `/manage/api/v1/devices/{workspace_id}/devices/hms` | HMS 告警分页 |

设备列表：

```bash
curl -sS "$BASE_URL/manage/api/v1/devices/$WORKSPACE_ID/devices" \
  -H "x-auth-token: $TOKEN"
```

设备拓扑响应中的 `device_sn` 是网关 SN；为避免调用方猜测设备类型，Web 设备列表还返回
明确的 `aircraft_sn` 和 `remote_controller_sn`。遥控器已连接但飞机拓扑尚未上报时，
`aircraft_sn` 为空。修改平台显示名称可调用设备修改接口并提交
`{"nickname":"1 号机"}`。

### 3.2 直播

上云 API 的设备推流能力矩阵：

| `url_type` | 协议 | 当前状态 |
| ---: | --- | --- |
| `1` | RTMP | 后期能力；P0 拒绝请求 |
| `2` | RTSP | P0 唯一启用 |
| `3` | GB28181 | 后期能力；P0 拒绝请求 |

当前 API 会对非 `url_type=2` 返回不支持错误。浏览器从 API 返回的 WHEP URL 建立播放会话；
WHEP 是浏览器播放出口，不是设备推流协议。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/manage/api/v1/live/capacity` | 设备动态上报的相机/码流能力 |
| POST | `/manage/api/v1/live/streams/start` | 下发 RTSP 开始推流并返回播放 URL |
| POST | `/manage/api/v1/live/streams/stop` | 停止视频源 |
| POST | `/manage/api/v1/live/streams/update` | 修改清晰度 |
| POST | `/manage/api/v1/live/streams/switch` | 切换镜头 |

开始直播：

```bash
curl -sS "$BASE_URL/manage/api/v1/live/streams/start" \
  -H "x-auth-token: $TOKEN" \
  -H 'content-type: application/json' \
  -d '{
    "video_id":"<device/camera/video id>",
    "url_type":2,
    "video_quality":2
  }'
```

可选字段 `url` 可覆盖平台生成的 RTSP 推流地址，仅供受控联调使用。`video_quality` 取值以设备
能力上报和枚举为准；Web 驾驶舱默认使用 `2`（标清），用户可切换到 `3`（高清）。

成功响应的 `data.reused=true` 表示返回了 MediaMTX 中已经就绪的 publisher；
`data.started_by_request` 进一步区分来源：`false` 表示请求到达前就已存在，当前页面不得认领；
`true` 表示本次请求已经下发设备启动指令（包括设备回复丢失后通过媒体状态恢复为成功），调用方
应把它作为本次页面拥有的 publisher，在真正离开座舱时负责清理。

多设备驾驶舱切换只关闭浏览器当前的 WHEP 播放会话，不向原设备发送 `streams/stop`。设备到
MediaMTX 的 publisher 按 `drone_sn/payload_index` 保留；切回设备时先探测并复用已有 publisher，
避免重复下发停止/启动指令和再次等待首帧。A→B→A 的设备切换不得调用停止接口；用户明确停止、
设备离线或播放恢复确认 publisher 已失效时可以停止。真正离开座舱或退出登录时，还应停止当前
页面通过 `started_by_request=true` 启动并持久追踪的 publisher；只复用且不属于本页面的 publisher
不得随页面退出停止。调用方不得把 WHEP reader 断开等同于设备停止推流。

停止：

```bash
curl -sS "$BASE_URL/manage/api/v1/live/streams/stop" \
  -H "x-auth-token: $TOKEN" \
  -H 'content-type: application/json' \
  -d '{"video_id":"<video id>"}'
```

### 3.3 DRC 虚拟座舱

DRC 是有状态安全流程：

```text
connect -> enter -> MQTT 心跳/控制 -> exit
```

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/control/api/v1/workspaces/{workspace_id}/drc/connect` | 获取 MQTT 短时连接配置 |
| POST | `/control/api/v1/workspaces/{workspace_id}/drc/enter` | 获取控制权并生成精确 Topic ACL |
| POST | `/control/api/v1/workspaces/{workspace_id}/drc/exit` | 释放控制权并清理 ACL |

连接：

```json
{
  "client_id": "",
  "expire_sec": 3600
}
```

进入与退出：

```json
{
  "client_id": "<connect 返回值>",
  "dock_sn": "<dock sn>",
  "expire_sec": 3600,
  "device_info": {
    "osd_frequency": 10,
    "hsi_frequency": 1
  }
}
```

`expire_sec` 范围是 1800–86400 秒。进入前服务会检查设备在线、飞行状态、任务状态和控制权。
连接信息中的 `password` 是短时 JWT；客户端不得持久化。控制端应每秒发送心跳，并以 10 Hz
发布 `drone_control`。窗口失焦、按键释放、MQTT 断开或退出时必须先发送零杆量并释放控制权。
同一用户刷新座舱时应申请新的浏览器 MQTT client；`enter` 会原子撤销旧 client 的 ACL 并把
活跃租约接管到新 client，不会重复向设备下发 `drc_mode_enter`。不同用户仍不能接管该租约。

部分遥控器固件会消费 `heart_beat` 但不回显心跳。浏览器在当前 MQTT 连接尚未收到链路确认时，
会额外发送连续两帧全零 `drone_control`（`seq=0 → 1`）；两帧明确返回 `result=0` 同样证明上下行
链路可用。零杆量在地面和空中都不包含位移、升降或偏航量，不能替换正常的每秒心跳。只有当前
连接的有效心跳，或同一 MQTT 代际、Topic 和短时窗内连续匹配的两次零杆回包确认后，界面才开放
非零控制量，因而首次进入 DRC 不需要再次点击。首轮零杆探针通常为 `seq=0 → 1`；丢包或被拒绝时保持
锁定并让同一零向量的序号继续递增，不能回放 `0 → 1`，因为设备可能已消费旧帧。只有 `x/y/h/w`
实际变化时才从 `seq=0` 重新计数；上一连接迟到、缺少 `result` 或 `output.seq` 不匹配的 ACK 不得解锁新连接。

DRC MQTT 消息保持设备物模型信封：

```json
{
  "tid": "<uuid>",
  "bid": "<uuid>",
  "timestamp": 1785319200000,
  "method": "drone_control",
  "data": {
    "seq": 0,
    "x": 0,
    "y": 0,
    "h": 0,
    "w": 0,
    "freq": 10,
    "delay_time": 300
  }
}
```

#### DRC 避障信息上报

- Topic：`thing/product/{gateway_sn}/drc/up`
- Direction：`up`
- Method：`hsi_info_push`
- 当前项目 `HsiInfoPush` 协议定义的距离单位为**米（m）**，Web HUD 不做千倍换算；字段值
  `-1` 表示对应传感器未检测到障碍物，`0` 是有效接触距离，不能按“无数据”处理。

| 位置 | 字段 | Web HUD 映射 |
| --- | --- | --- |
| 上 / 下 | `up_distance`、`down_distance` | 画面中上方 / 下方距离标记 |
| 前方 1–4 | `front1_distance` … `front4_distance` | 画面上沿从左到右 4 个横向分段 |
| 后方 1–4 | `rear1_distance` … `rear4_distance` | 画面下沿从左到右 4 个横向分段 |
| 左侧 1–3 | `left1_distance` … `left3_distance` | 画面左沿从上到下 3 个纵向分段 |
| 右侧 1–3 | `right1_distance` … `right3_distance` | 画面右沿从上到下 3 个纵向分段 |
| 避障开关 | `radar_enable` | `true` 为开启，`false` 为关闭 |

```json
{
  "bid": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxx",
  "data": {
    "down_distance": 2,
    "front1_distance": 1,
    "front2_distance": -1,
    "front3_distance": 3,
    "front4_distance": 4,
    "left1_distance": -1,
    "left2_distance": 5,
    "left3_distance": 6,
    "radar_enable": true,
    "rear1_distance": 7,
    "rear2_distance": -1,
    "rear3_distance": 8,
    "rear4_distance": 9,
    "right1_distance": 2,
    "right2_distance": 3,
    "right3_distance": -1,
    "up_distance": 10
  },
  "tid": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxx",
  "timestamp": 1726131014572,
  "method": "hsi_info_push"
}
```

### 3.4 指令控制

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/control/api/v1/devices/{sn}/jobs/{service_identifier}` | 通用服务指令，如返航 |
| POST | `/control/api/v1/devices/{sn}/jobs/fly-to-point` | 飞向目标点 |
| DELETE | `/control/api/v1/devices/{sn}/jobs/fly-to-point` | 取消飞向目标点 |
| POST | `/control/api/v1/devices/{sn}/jobs/takeoff-to-point` | 起飞到点 |
| POST | `/control/api/v1/devices/{sn}/authority/flight` | 获取飞行控制权 |
| POST | `/control/api/v1/devices/{sn}/authority/payload` | 获取负载控制权 |
| POST | `/control/api/v1/devices/{sn}/payload/commands` | 云台、相机和负载命令 |

可用 `service_identifier` 与 payload 命令受机型、固件和设备能力限制；前端应按能力上报显示，
不得把未验证指令作为固定按钮开放。

飞行类 API 的 `{sn}` 是机巢或遥控器**网关 SN**，不是飞机 SN。建议按以下顺序调用：

1. 从设备列表确认网关和子飞机均在线，并从 OSD 核验飞机模式、坐标和高度。
2. `POST /authority/flight` 明确获取飞行控制权；切换设备后不得复用上一设备的本地控制权状态。
3. 下发一次飞行指令，并通过 WebSocket 进度或状态查询确认结果；设备确认前不发送第二个同类指令。
4. HTTP/MQTT 超时表示结果**未知**，不表示设备未执行。先查询状态或发送安全的停止/取消指令，
   不得盲目重发起飞、指点飞行或返航。

一键起飞到当前经纬度上方：

```bash
curl -sS "$BASE_URL/control/api/v1/devices/$GATEWAY_SN/jobs/takeoff-to-point" \
  -H "x-auth-token: $TOKEN" -H 'content-type: application/json' \
  -d '{
    "target_longitude":<TARGET_LONGITUDE>,
    "target_latitude":<TARGET_LATITUDE>,
    "target_height":30.0,
    "max_speed":5
  }'
```

指点飞行只允许在飞机已起飞且处于可控制模式时执行；`max_speed` 必须是 1–15 m/s 的整数：

```bash
curl -sS "$BASE_URL/control/api/v1/devices/$GATEWAY_SN/jobs/fly-to-point" \
  -H "x-auth-token: $TOKEN" -H 'content-type: application/json' \
  -d '{
    "max_speed":5,
    "points":[{"longitude":<TARGET_LONGITUDE>,"latitude":<TARGET_LATITUDE>,"height":50.0}]
  }'

# 查询平台持久化的起飞/指点飞行状态；超时后先查此接口
curl -sS "$BASE_URL/control/api/v1/devices/$GATEWAY_SN/jobs/point-flight/status" \
  -H "x-auth-token: $TOKEN"

# 结束指点飞行，可在停止结果未知时安全重试
curl -sS -X DELETE "$BASE_URL/control/api/v1/devices/$GATEWAY_SN/jobs/fly-to-point" \
  -H "x-auth-token: $TOKEN"
```

一键返航与取消返航均会在服务端确保平台持有飞行控制权。接口成功仅表示设备已受理，不代表已经
到达返航点或完成悬停；必须继续观察飞机 OSD：

```bash
curl -sS -X POST "$BASE_URL/control/api/v1/devices/$GATEWAY_SN/jobs/return_home" \
  -H "x-auth-token: $TOKEN"
curl -sS -X POST "$BASE_URL/control/api/v1/devices/$GATEWAY_SN/jobs/return_home_cancel" \
  -H "x-auth-token: $TOKEN"
```

> **遥控器（RC）网关与 `device_list` 寻址**
>
> 当网关为遥控器（`DeviceDomainEnum.REMOTER_CONTROL`，如 Autel 遥控器直连上云）时，
> 遥控器把无人机作为子设备管理。所有面向**机身**的 `services` 指令必须在下行报文中携带
> `device_list: [{"sn": <无人机 SN>}]` 显式寻址无人机，否则遥控器会静默丢弃指令、
> 永不回复 `services_reply`，云端表现为 `Error Code: 211001 … No message reply received.`
> （约 9 秒超时，3 次重试 × 3000 ms）。
>
> 服务端已按网关域**自动分流**（`*Rc()` 变体），调用方无需感知，覆盖：
> 一键起飞 `takeoff_to_point`、指点飞行 `fly_to_point` / 结束 `fly_to_point_stop`、
> 一键返航 `return_home` / 取消返航 `return_home_cancel`、飞行控制权 `flight_authority_grab`、
> 负载控制权 `payload_authority_grab`、Look At `camera_look_at` 及全部负载指令、
> 目标识别 `target_detect_open/close`、航线任务
> `flighttask_prepare/execute/undo/pause/recovery`。
>
> 机巢（DOCK）网关不需要 `device_list`，仍走原有下行路径。排查“MQTT 指令无回复(211001)”
> 时，优先确认该指令在 RC 网关下是否已带 `device_list`。

常见控制错误的处理原则：

| 现象 | 含义与处理 |
| --- | --- |
| HTTP `401` / 业务码 `401` | Token 失效；重新登录后再查询状态，不直接重放飞行指令。 |
| `210001` / device not registered | 网关 SN 不属于当前工作空间；重新查询设备列表并更正配置。 |
| `211001` / no message reply | 指令已发出但未收到 `services_reply`，结果可能未知；检查设备在线、RC `device_list` 寻址和 MQTT 日志，再查任务状态。 |
| gateway/aircraft offline | 网关或子飞机离线；两者恢复在线并收到新 OSD 后再操作。 |
| current state does not support | 飞机模式、高度、任务或机巢状态不满足；以最新 OSD 修正前置条件。 |
| point-flight already active | 先查询 `point-flight/status`；确认是指点飞行后执行停止，终态前不得新建起飞/指点任务。 |
| HTTP read timeout / connection reset | 非幂等指令结果未知；保留目标 SN 和任务 ID，先通过状态接口、WebSocket 或 OSD 收敛。 |

### 3.5 航线与任务

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/wayline/api/v1/workspaces/{workspace_id}/waylines` | KMZ 航线分页 |
| POST | `/wayline/api/v1/workspaces/{workspace_id}/waylines/file/upload` | 上传 KMZ，字段名 `file` |
| DELETE | `/wayline/api/v1/workspaces/{workspace_id}/waylines/{wayline_id}` | 删除航线 |
| POST | `/wayline/api/v1/workspaces/{workspace_id}/flight-tasks` | 创建飞行任务 |
| GET | `/wayline/api/v1/workspaces/{workspace_id}/jobs` | 任务分页 |
| DELETE | `/wayline/api/v1/workspaces/{workspace_id}/jobs?job_id=<id>` | 取消任务，可重复 `job_id` |
| PUT | `/wayline/api/v1/workspaces/{workspace_id}/jobs/{job_id}` | 暂停/恢复任务 |
| POST | `/wayline/api/v1/workspaces/{workspace_id}/jobs/{job_id}/media-highest` | 媒体优先回传 |

上传：

```bash
curl -sS "$BASE_URL/wayline/api/v1/workspaces/$WORKSPACE_ID/waylines/file/upload" \
  -H "x-auth-token: $TOKEN" \
  -F 'file=@mission.kmz'
```

创建任务核心字段：

```json
{
  "name": "园区巡检-001",
  "file_id": "<wayline id>",
  "dock_sn": "<dock sn>",
  "wayline_type": 0,
  "task_type": 0,
  "rth_altitude": 100,
  "out_of_control_action": 0,
  "min_battery_capacity": 60,
  "min_storage_capacity": 1024
}
```

`rth_altitude` 范围 20–500 米，最低电量范围 15–100。枚举实际值以在线 OpenAPI 和设备能力为准。
定时任务还需 `task_days`、`task_periods`。

任务下发（`flighttask_prepare`）、执行（`flighttask_execute`）、暂停（`flighttask_pause`）、
恢复（`flighttask_recovery`）、取消（`flighttask_undo`）在**遥控器（RC）网关**下同样自动携带
`device_list` 寻址无人机（见 §3.4 说明）；`PUT /jobs/{job_id}` 的 `status=0` 暂停、`status=1` 恢复。
任务进度经 WebSocket `flighttask_progress` 业务码实时推送，前端「航线任务」页据此实时刷新进度与状态。

任务状态值为 `1=待执行`、`2=执行中`、`3=成功`、`4=已取消`、`5=失败`、`6=已暂停`。
暂停只作用于执行中的任务，恢复只作用于暂停任务；重复调用会按当前任务 ID 做幂等判断，不能把同一
网关上另一任务的状态当作目标任务。取消接受待执行、执行中和暂停任务；由于设备不允许直接撤销
执行中的任务，服务端会先确认 `flighttask_pause`，再下发 `flighttask_undo`。只有撤销成功后才写入
状态 `4` 并清理该任务的运行/暂停缓存；撤销失败时保留已暂停状态，便于人工重试或恢复。成功、
失败终态不可取消。状态 `4=已取消` 可重复调用取消接口做幂等本地收敛：服务端只清理可能残留的
运行/暂停缓存，不会再次向设备下发 `flighttask_undo`。

任务创建、暂停、恢复或取消遇到超时时，先调用 `GET /jobs` 按 `job_id` 读取服务端状态，并继续
监听 `flighttask_progress`。设备事件可能重复、乱序或晚于 HTTP 返回；客户端应按 `job_id` 关联，
终态不可被较旧的非终态事件覆盖。示例程序见 [`docs/python-demo/demo_17_wayline.py`](python-demo/demo_17_wayline.py)。

### 3.6 媒体

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/media/api/v1/files/{workspace_id}/files` | 媒体分页 |
| GET | `/media/api/v1/files/{workspace_id}/file/{file_id}/url` | 302 跳转到短时对象 URL |

对象 URL 是短时凭据，不应写入日志、数据库或分享为永久地址。

## 4. WebSocket 与 MQTT

平台 WebSocket 使用 `/api/v1/ws`，用于设备状态、任务进度、告警和媒体事件推送。认证参数与
具体消息业务码由在线 OpenAPI/源码模型给出。设备 MQTT 使用：

```text
sys/product/{gateway_sn}/status
thing/product/{gateway_sn}/requests
thing/product/{gateway_sn}/state
thing/product/{gateway_sn}/events
thing/product/{gateway_sn}/services_reply
```

平台下行服务与属性 Topic 由 SDK 按设备序列号生成。消息必须保留 `tid`、`bid`、`timestamp`、
`method` 和 `data`；请求应答必须关联原始事务 ID。

## 5. 兼容与版本策略

- HTTP 路径版本为 `/api/v1`。
- 设备消息字段以实际固件能力和上云 API 物模型为准。
- 新增可选字段保持向后兼容；删除字段、改变枚举语义或 Topic 属于破坏性变更。
- 每次发布应保存运行实例的 `/v3/api-docs`，并与本指南共同归档。
- RTMP、GB28181 后期启用时必须先补充本指南、配置指南、Compose profile 和安全验收。
