# Python Demo 使用指南

本目录用独立 Python 脚本演示 YOOX Cloud GCS 的设备查询、遥测、负载、直播、DRC、点飞和航线任务接口。飞行类 Demo 以当前项目的真实 REST 封装为准：服务端负责 MQTT 指令下发、RC 子设备寻址和设备回复，脚本负责安全确认、状态恢复与错误说明。

## 1. 准备环境

推荐使用一键脚本。它会创建隔离的 `.venv`、安装声明的依赖并载入本机 `.env`：

```bash
cd docs/python-demo
test -f .env || cp .env.example .env
chmod +x run.sh
./run.sh demo_01_login.py
```

命令只会在 `.env` 不存在时从模板创建，不会覆盖已有配置。`docs/python-demo/.env` 含账号、
设备 SN、工作空间 ID 和飞行坐标，只用于本地联调并被 Git 忽略；仓库只提交
`.env.example`。Windows C++ 对应实现位于 [`../cpp-demo`](../cpp-demo/README.md)，可通过
`--env-file ../python-demo/.env` 复用同一套 `YOOX_*` 配置语义。

也可以手动运行：

```bash
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements.txt
set -a; source .env; set +a
python demo_02_devices.py
```

不要向 Homebrew/系统 Python 全局安装依赖；macOS 可能报 `externally-managed-environment`。

## 2. 配置 `.env`

至少填写 Web 登录和工作空间：

```dotenv
YOOX_SERVER_IP=127.0.0.1
YOOX_SERVER_PORT=8080
YOOX_PILOT_PORT=9000
YOOX_PILOT_BASE_URL=http://127.0.0.1:9000
YOOX_WS_URL=ws://127.0.0.1:9000/api/v1/ws
YOOX_WEB_USERNAME=admin
YOOX_WEB_PASSWORD=change_me
YOOX_WORKSPACE_ID=YOUR_WORKSPACE_ID
```

先运行 `demo_02_devices.py`，再把服务端返回的值写入 `.env`：

```dotenv
YOOX_DOCK_SN=YOUR_DOCK_SN
YOOX_DRONE_SN=YOUR_DRONE_SN
YOOX_PAYLOAD_INDEX=YOUR_PAYLOAD_INDEX
```

点飞和 Look At 不再内置真实或示例坐标，必须显式配置或通过命令行传入：

```dotenv
YOOX_TARGET_LATITUDE=
YOOX_TARGET_LONGITUDE=
YOOX_TARGET_HEIGHT=
YOOX_TARGET_MAX_SPEED=5
```

一键起飞使用独立经纬度，避免与 FlyTo/Look At 的目标位置互相覆盖：

```dotenv
YOOX_TAKEOFF_TARGET_LATITUDE=
YOOX_TAKEOFF_TARGET_LONGITUDE=
```

默认 Web/REST 入口为 `http://127.0.0.1:8080`，Pilot 登录与 WebSocket 网关为 `http://127.0.0.1:9000`。反向代理/TLS 场景可设置完整的 `YOOX_BASE_URL`、`YOOX_PILOT_BASE_URL` 和 `YOOX_WS_URL`。MQTT、RTSP、超时等完整配置见 [.env.example](./.env.example)。`config.py` 只读取环境变量，不应写入部署信息或凭证。

设备在线状态来自 `/manage/api/v1/devices/{workspace}/devices` 的 `status`，Demo 不要求能访问部署机的 Docker 或 Redis。

## 3. Demo 索引

| 文件 | 功能 | 必需配置 |
|---|---|---|
| `demo_01_login.py` | 验证 Web 登录；配置 Pilot 密码时也验证 Pilot 登录 | Web 账号；Pilot 可选 |
| `demo_02_devices.py` | 查询网关、飞机、在线状态和 `payload_index` | workspace |
| `demo_03_websocket_osd.py` | WebSocket 遥测、上下线、点飞/航线/DRC 事件 | Web 账号 |
| `demo_04_mqtt_osd.py` | MQTT 原始 OSD | MQTT |
| `demo_05_gimbal_zoom.py` | 设置变焦倍率 | dock、payload |
| `demo_06_gimbal_pitch.py` | `camera_aim`、拖动和云台复位 | dock、payload |
| `demo_07_camera.py` | 拍照、开始/停止录像 | dock、payload |
| `demo_08_fly_to_point.py` | 指点飞行、状态查询、安全停止 | dock、目标坐标 |
| `demo_09_dock_control.py` | 机巢调试、一键返航、取消返航 | dock |
| `demo_10_takeoff_to_point.py` | 一键起飞、任务身份恢复、终态等待 | dock、目标坐标 |
| `demo_11_livestream.py` | 开始/停止直播、标清/高清、切镜头、无首帧恢复 | MQTT、RTSP |
| `demo_12_drc.py` | DRC 专用 MQTT、心跳、摇杆和应急指令 | dock、workspace |
| `demo_13_payload_advanced.py` | YOOX 扩展负载指令 | dock、payload |
| `demo_14_target_detection.py` | 开启/关闭目标识别 | dock |
| `demo_15_emergency.py` | 返航/取消返航及 DRC 应急处置 | dock；DRC 另需 workspace |
| `demo_16_look_at.py` | GPS Look At | dock、payload、目标坐标 |
| `demo_17_wayline.py` | KMZ 上传、航线下发执行、进度、暂停、继续、取消 | dock、workspace、KMZ |
| `demo_18_continuous_landing.py` | DRC 持续下降至待机，带 ACK/OSD/归零闭环 | dock、drone、workspace |
| `demo_19_return_home.py` | 一键返航/取消返航，OSD 旁路监视 mode_code 确认 | dock；监视另需 MQTT |
| `demo_20_kmz_generate.py` | 离线生成与参考文件结构相同的 wpmz 航线 KMZ，输出到 `kmz/` | 无（纯本地） |
| `demo_21_kmz_interactive.py` | 交互式航线编辑：设航线信息→逐点设航点（可选云台动作）→生成 KMZ | 无（纯本地） |
| `demo_22_two_drone_control.py` | 双机并发一键起飞、分别 FlyTo、独立状态恢复 | 两套网关/飞机 SN、两套起飞点和 FlyTo 目标 |
| `demo_23_two_drone_takeoff_test.py` | 自动发现当前两机、实时 OSD 预检、并发起飞测试与 JSON 报告 | 服务地址、登录账号、workspace；执行需显式 `--execute` |

## 4. 飞行控制流程

### 4.1 指点飞行与停止

前提是飞机在线、已在空中且为 `MANUAL` 模式。`max_speed` 在服务端模型中是整数，必须为 1–15 m/s。

```bash
# 目标经纬度、高度和速度从本机 .env 读取
./run.sh demo_08_fly_to_point.py go

./run.sh demo_08_fly_to_point.py status
./run.sh demo_08_fly_to_point.py stop
./run.sh demo_08_fly_to_point.py auth
```

`go` 的真实顺序：

1. 查询 `GET /control/api/v1/devices/{sn}/jobs/point-flight/status`，发现活动或待确认任务时拒绝重复下发。
2. 显式调用 `POST .../{sn}/authority/flight` 抢占飞行控制权。
3. 只调用一次 `POST .../{sn}/jobs/fly-to-point`。
4. 从状态接口恢复服务端生成的 `fly_to_id`，只接受同一 ID 的进度。
5. 旁路展示 MQTT `fly_to_point_progress`，最终结果以匹配任务 ID 的 HTTP 状态为准。

`stop` 只允许停止活动的 `kind=flyto` 任务。它会再次确认控制权，再调用 `DELETE .../{sn}/jobs/fly-to-point`。停止请求超时后先查询状态；只有确认仍在 FlyTo 时，才由操作者决定是否再次运行 `stop`。

常见点飞状态：

| 状态 | 含义 |
|---|---|
| `command_pending` / `command_accepted` | 正在发送 / 设备已受理 |
| `command_unknown` | 设备回复丢失，结果待确认；禁止重复 `go` |
| `wayline_progress` | 飞行中 |
| `wayline_ok` / `wayline_failed` | 到达 / 失败 |
| `cancel_requested` / `cancel_unknown` | 正在取消 / 取消结果待确认 |
| `cancel_confirmed` / `wayline_cancel` | 停止已确认 / 设备上报取消 |

按 `Ctrl+C` 只会停止本地监听，不会停止飞机；需要停止 FlyTo 时另行运行 `stop`。

### 4.2 一键起飞

前提是飞机在地面、在线且为 `IDLE`。`demo_10` 从
`YOOX_TAKEOFF_TARGET_LATITUDE/LONGITUDE` 读取专用坐标，通常应设为飞机当前有效
GPS 坐标，只改变相对高度：

```bash
# 目标经纬度、高度和速度从本机 .env 读取
./run.sh demo_10_takeoff_to_point.py go
./run.sh demo_10_takeoff_to_point.py status
```

请求体与当前 Web 控制台一致，只包含 `target_longitude`、`target_latitude`、`target_height` 和 `max_speed`。服务端统一补充安全起飞高度、返航高度和失联返航动作，并在 RC 网关场景加入 `device_list`。

脚本在 HTTP 成功或超时后都通过 point-flight 状态恢复 `flight_id`。如果暂时不能恢复，脚本会退出并要求观察 OSD，绝不会自动重复起飞。

### 4.2.1 双机起飞与分别指点飞行

`demo_22_two_drone_control.py` 一次 Web 登录后控制两套明确配置的“网关 + 子飞机”。
先在 `.env` 填写 `YOOX_DUAL_*` 配置；网关 SN 必须是遥控器或机巢 SN，飞机 SN
仅用于运行前核对拓扑。脚本要求两起飞点、两 FlyTo 目标的水平间隔都至少为 10 m。

```bash
# 只核对双机在线拓扑和当前点飞状态，不发送动作
./run.sh demo_22_two_drone_control.py status

# 并发抢权并分别下发一次一键起飞
./run.sh demo_22_two_drone_control.py takeoff

# 两机已在空中 MANUAL 后，分别飞往各自目标
./run.sh demo_22_two_drone_control.py flyto

# 起飞全部明确成功后，再次人工确认并进入 FlyTo 阶段
./run.sh demo_22_two_drone_control.py all
```

起飞阶段必须输入 `TAKEOFF TWO`，FlyTo 阶段必须输入 `FLYTO TWO`。任意一台出现
超时、未知结果或失败时，`all` 都不会继续下一阶段。两台设备不是事务：一台已受理后，
另一台仍可能失败，因此必须为每台飞机分别保留现场飞手、返航/悬停处置和安全隔离空域。

### 4.2.2 当前在线双机起飞接口测试

`demo_23_two_drone_takeoff_test.py` 从指定工作空间发现**恰好两套在线网关与飞机**，
通过 WebSocket 获取各机实时 GPS 和地面待机状态，以各自当前位置为目标，默认
目标相对高度 2 m、速度 1 m/s。先检查 `.env` 中的账号和工作空间是否对应目标服务。

```bash
# 只预检，不获取控制权、不发送飞行动作
./run.sh demo_23_two_drone_takeoff_test.py \
  --base-url http://192.168.5.3:8080 --ws-url ws://192.168.5.3:9000/api/v1/ws

# 已确认两台均拆桨的台架测试：每机并发调用一次起飞 API
./run.sh demo_23_two_drone_takeoff_test.py \
  --base-url http://192.168.5.3:8080 --ws-url ws://192.168.5.3:9000/api/v1/ws \
  --execute --props-removed --wait 30 --report /tmp/dual-takeoff-test.json

# 离线回归检查，不连接设备
.venv/bin/python3 -m unittest discover -s tests -v
```

报告文件必须不存在，省略 `--report` 时自动新建临时 JSON 文件。报告包含设备 SN、
遥测、目标位置、各机调用时间、API 响应、任务状态与客户端调用时间差，不记录登录 Token。
请求超时或结果未知时客户端不重发；服务端 MQTT 层仍有自己的重试策略。
退出码 0 表示预检通过或两机均上报明确成功，1 表示预检/连接失败，2 表示有失败或未确认结果。

`--props-removed` 是操作者对两台均拆桨的明确声明，仅允许台架测试省略原有的
10 m 起飞点间隔检查；在线、地面待机、新鲜 OSD、有效坐标和设备原生检查仍然保留。
**无桨测试只能验证指令链路，不能证明实际起飞成功或精确同步。** `task_finish`
可能只是设备一直待机后的任务清理，报告将标为 `ended_unverified`。

### 4.3 一键返航与取消返航

专用脚本 `demo_19_return_home.py`，也可在 `demo_09_dock_control.py` 或
`demo_15_emergency.py` 使用：

```bash
./run.sh demo_19_return_home.py rth     # 一键返航
./run.sh demo_19_return_home.py cancel  # 取消返航（原地悬停）
./run.sh demo_19_return_home.py watch   # 只监视当前 mode_code，不下发指令
```

脚本先 `flight_authority_grab`，再单次调用
`POST .../{sn}/jobs/return_home`（或 `return_home_cancel`），随后通过 MQTT
旁路监视飞机 OSD 的 `mode_code`：返航期望进入 `9`（RETURN_AUTO）并最终回到
`0`；取消返航期望离开 `9` 转为悬停。

MQTT 报文要点（2026-08-12 A/B 实测，见 `docs/15-MQTT直连AB测试指南.md`）：
EVO RC 固件要求 `return_home`/`return_home_cancel` 发送 `data:{}`（空对象）
并携带 `device_list:[{sn:无人机SN}]`，`data:null` 的任何写法都会被静默丢弃
并报 211001。服务端 `returnHomeRc/returnHomeCancelRc` 已按实测格式实现，
Demo 只走 REST，无需自行拼装机载指令。

HTTP `code=0` 只代表设备接受调用，不代表已返航、悬停或落地，必须继续观察
`mode_code` 和现场。取消返航会使飞机悬停，脚本要求 `YES` 二次确认。若请求
超时，先确认 `mode_code` 和现场状态，不要立即重复发送。

涉及的 REST 端点：

```text
POST /control/api/v1/devices/{sn}/authority/flight
POST /control/api/v1/devices/{sn}/jobs/return_home
POST /control/api/v1/devices/{sn}/jobs/return_home_cancel
```

服务端也会在返航/取消返航内部再次确认控制权（与 Web 控制台一致的冗余保护）。

### 4.4 航线上传、下发、暂停、继续和取消

直接运行，菜单项 6 可把本地 KMZ 上传到航线库（无需先去 Web 控制台）：

```bash
./run.sh demo_17_wayline.py
```

上传时可直接输入本地 `.kmz` 路径，或在 `.env` 预设 `YOOX_WAYLINE_KMZ` 后回车使用。
服务端会校验 KMZ 结构（`wpmz` 模板、UTF-8 编码）与内置的无人机/负载型号枚举，
型号与当前设备不匹配的 KMZ 无法下发。

| 菜单动作 | REST | 设备方法 |
|---|---|---|
| 上传 KMZ 航线文件 | `POST .../waylines/file/upload`（multipart） | —（入库，不下发设备） |
| 下发并立即执行 | `POST .../flight-tasks` | `flighttask_prepare` + `flighttask_execute` |
| 暂停执行中任务 | `PUT .../jobs/{job_id}`，`status=0` | `flighttask_pause` |
| 继续已暂停任务 | `PUT .../jobs/{job_id}`，`status=1` | `flighttask_recovery` |
| 取消任务 | `DELETE .../jobs?job_id=...` | `flighttask_undo` |

任务类型：Demo 下发的是 **立即任务**（`task_type=0`）。服务端另支持定时（`1`）与
条件（`2`）任务，需额外的执行时间/电量与存储就绪条件参数，本 Demo 不演示。

状态约束：执行中 `2` 可暂停；已暂停 `6` 可继续；待执行 `1`、执行中 `2`、已暂停 `6` 均可取消；成功 `3`、失败 `5` 不可取消。已取消 `4` 可重复调用取消接口做幂等本地收敛，不会再次下发 `flighttask_undo`。脚本在每次动作前重新读取任务，避免使用过期列表。

创建接口不返回 `job_id`，脚本使用唯一任务名从列表恢复身份。暂停、继续、取消超时后也只刷新状态，不自动重发。MQTT `flighttask_progress` 会打印 `bid/job_id`、状态、步骤、百分比、航点和 result；REST 列表仍是下一次操作前的依据。

## 5. 直播与 DRC

`demo_11_livestream.py` 默认 `VIDEO_QUALITY=2`（标清），3 为高清。设备发布凭证由服务端配置，Demo 默认不传自定义 `url`，以便后端复用已有 MediaMTX publisher。开始直播返回成功不等于已有媒体帧；脚本使用不含发布凭据的 RTSP 播放路径做 `ffprobe` 探测，错误输出会脱敏。无论探测结果如何，脚本都不会在开始前自动停止现有 publisher；只有设备明确返回“直播已开始”、MediaMTX 又确认无媒体时，才会要求操作者输入 `YES` 后执行一次停止再开始。未安装 `ffprobe` 时状态是“无法探测”而不是“无流”。菜单 6 只对可安全切回的 `normal/wide/zoom/ir` 镜头执行临时切换恢复，`thermal` 等不支持类型会在任何镜头切换前终止。

DRC 必须使用 `/drc/connect` 返回的专用 Broker 凭证和 `/drc/enter` 返回的 pub/sub ACL，不能把 DRC 指令直接发到普通 MQTT 连接。`demo_12` 和 `demo_15` 会等待 MQTT `CONNACK` 与订阅 `SUBACK`，随后立即发送心跳和连续两帧全零 `drone_control` 探针（`seq=0→1`）。只有当前 DRC 会话、当前 `drc/up` Topic、短时窗内的两帧回包都带显式 `result=0` 且 `output.seq` 依次匹配时才解锁非零摇杆；若回包携带 `tid/bid`，还必须匹配对应请求 ID。重连会重新锁定并重新握手，迟到、乱序、缺少 `seq` 或结果不明的 ACK 均不会解锁。这同时规避了部分 RC 固件不回显 `heart_beat` 导致“第一次进入 DRC 无心跳/无法控制”的问题。
脚本会订阅 ACL 中全部 `sub` Topic，并选择与控制 `drc/down` 对应的 `drc/up`。

`demo_18_continuous_landing.py` 是独立的持续降落示例，以 10 Hz 连续发送 `drone_control` 的负向 `h`。它要求配置 `YOOX_DRONE_SN`，通过平台 WebSocket 监控目标飞机 OSD：当前下降帧必须收到严格关联 `tid/bid` 的成功 ACK（固件正确回显时也校验 `output.seq`），3 秒内必须观测到负垂速或高度下降，并在 `mode_code=0`（待机/已落地）时自动归零退出。ACK/OSD 超时、MQTT 重连、Joystick 失效或控制权转移也会归零停止。不要同时运行驾驶舱 DRC 或其他 DRC Demo。

```bash
./run.sh demo_18_continuous_landing.py
# 可降低下降速度，并设置最长运行时间：
./run.sh demo_18_continuous_landing.py --speed 1.0 --max-seconds 180
```

`seq=0→1` 只是首轮全零向量的序号。若 ACK 丢失或设备拒绝，设备可能已消费旧帧，因此重试必须让同一零向量的 `seq` 继续递增，不能回放 `0→1`；只有 `x/y/h/w` 实际变化时才从 0 重新计数。

退出或异常时脚本先按当前向量序号规则发布最终零杆量并有界等待交给 MQTT 传输层，再断开 MQTT，最后使用同一 `client_id` 调用 `/drc/exit`。如果 enter/exit 超时，结果仍可能已经生效，应检查服务端会话与飞机状态，不要快速创建多个 DRC 会话。

## 6. 错误与恢复

所有 HTTP Demo 使用 `demo_common.py` 的统一调用层，不会对动作请求配置自动重试。

| 错误/现象 | 含义与处理 |
|---|---|
| `Connection refused` | 服务地址错误或服务未启动；检查 `.env` 中 URL/IP/端口 |
| HTTP 401/403 | 账号、密码、flag、token 或工作空间权限不匹配 |
| HTTP 408 / 5xx | 超时或服务端错误不能证明后端没执行；变更动作结果按“未知”处理 |
| `210001` / not registered | SN 不属于当前工作空间；运行 demo_02 重新获取 |
| `211001` / No message reply | 设备没有回复；动作可能已到达设备，先查状态/OSD，禁止盲目重发 |
| gateway/aircraft offline | 网关或子设备离线；以 demo_02 的服务端 `status` 和 MQTT 心跳为准 |
| current state does not support | 起飞需地面 IDLE，FlyTo 需空中 MANUAL，航线操作需匹配状态 |
| point-flight already active | 先运行 demo_08 `status`；活动 FlyTo 用 `stop`，不要重复起飞 |
| 请求超时 | 只是客户端停止等待，不代表设备没执行；先做身份/状态恢复 |
| MQTT 无事件 | HTTP 状态仍可用；检查 Broker 地址、ACL、账号和设备上报 |

统一安全原则：

1. `POST/PUT/DELETE` 超时、断线、非 JSON 成功响应、HTTP 408/全部 5xx 和 211001 都视为可能已执行。
2. 一键起飞和 FlyTo 用 point-flight 状态恢复；航线动作刷新任务列表；返航/取消返航查看 OSD 与现场。
3. 只有收到明确业务失败，或核实设备未执行后，才由操作者决定是否重新发送。
4. `code=0` 是“调用成功”，不是“飞行完成”。

## 7. 验证

离线语法检查不会访问设备：

```bash
python -m compileall -q docs/python-demo
```

运行验证建议按风险递增：

1. `demo_01` 登录、`demo_02` 设备列表。
2. `demo_03`/`demo_04` 只读遥测。
3. 负载与直播。
4. 在飞手、净空、应急预案和有效 GPS 条件下验证起飞、FlyTo、航线与 DRC。

所有真实飞行动作都应保留现场飞手和可用的返航/降落处置手段。
