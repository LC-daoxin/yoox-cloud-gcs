# YOOX Windows x64 C++ Demo

本目录是 [`../python-demo`](../python-demo/README.md) 的 C++17 对应实现。它复用相同的
REST、MQTT、WebSocket、DRC、任务恢复和安全语义，所有动作默认 dry-run。

这是一套平台 API 示例，不是无人机固件，也不会自动完成客户 RZXDM 协议接入。客户应用应把
自己的 `ActionDispatcher` 映射到 `YooxClient`，并在真机前完成现场飞行安全验收。

## 依赖选择

- libcurl：成熟的 Windows HTTP/TLS/multipart 实现，无动作请求自动重试。
- nlohmann/json：与服务端 JSON 字段直接映射。
- Eclipse Paho MQTT C++：支持 TLS、自动重连、QoS 和异步回调；重连后重新订阅。
- IXWebSocket：支持 TLS、Ping、自动重连及 Windows/MSVC。
- libzip：生成和校验标准 ZIP/KMZ，避免自行拼接 ZIP 二进制。

依赖由 vcpkg 清单和固定 baseline 管理，CMake 只使用 `find_package`，不会通过 FetchContent
静默联网下载。MSVC 与 MinGW 必须使用各自 triplet 构建，不能混用二进制包。

## Windows 构建

安装 Visual Studio 2022（Desktop development with C++）、CMake 3.24+、Git 和 vcpkg：

```powershell
git clone https://github.com/microsoft/vcpkg.git C:\dev\vcpkg
Set-Location C:\dev\vcpkg
git checkout 6ade29bbd5a4c7e99439ec52adbc63c073953a57
.\bootstrap-vcpkg.bat
$env:VCPKG_ROOT = 'C:\dev\vcpkg'

Set-Location <repo>\docs\cpp-demo
.\build.ps1
```

`build.ps1` 默认使用 `windows-msvc-release`，执行配置、Release 构建和 CTest。MinGW-w64 可用：

```powershell
.\build.ps1 -Preset windows-mingw-release
```

不要把本机 vcpkg 绝对路径写进仓库；Preset 从 `VCPKG_ROOT` 读取 toolchain。

## 配置与运行

配置优先级为：命令行映射/`--set` > 系统环境变量 > `--env-file` > 默认 `.env` > 内置安全默认值。

```powershell
# 可创建本地文件（已忽略），或直接复用 Python 配置
Copy-Item .env.example .env
.\run.ps1 -Demo demo_01_login -EnvFile ..\python-demo\.env
.\run.ps1 -Demo demo_02_devices
.\run.ps1 -Demo demo_03_websocket_osd -DemoArgs '--seconds','30'

# 动作 Demo 默认只打印计划，不发送写请求
.\run.ps1 -Demo demo_08_fly_to_point -DemoArgs 'go'

# 真机仅在现场验收后使用；还需对应确认词
.\run.ps1 -Demo demo_08_fly_to_point -Execute `
  -DemoArgs 'go','--confirm','FLY TO POINT'
```

`run.ps1` 默认查找 `docs/cpp-demo/.env`，找不到时回退到 `docs/python-demo/.env`；两者都不存在会
明确失败，不会自动生成虚假配置。日志不会输出密码、Token 或 MQTT 短期凭据。

常用参数：

- `--env-file <path>`：指定配置文件。
- `--set YOOX_NAME=value`：最高优先级覆盖一个配置项。
- `--execute`：允许通过安全门后发送写操作；默认不启用。
- `--confirm <phrase>`：非交互提供严格确认词。
- `--gateway/--drone/--payload/--workspace`：覆盖对应设备配置。
- `--mode-code <n>`：把操作员刚确认的 OSD 模式送入本地安全门；服务端仍会再次校验。

HTTP 超时、HTTP 408/5xx、非 JSON 成功响应及业务 `211001` 都按“结果未知”处理。程序不会自动
重发，并会提示先查询任务、OSD 或设备状态。`code=0` 只显示为“已受理”，不会声称飞行动作完成。

## Python → C++ 功能对照

| Python Demo | C++ 入口 | HTTP API | MQTT / WebSocket | 安全与恢复 | 当前验证 |
| --- | --- | --- | --- | --- | --- |
| 01 login | `demo_01_login` | login、workspace、user | — | Token仅显示短指纹 | Mock覆盖；真实只读待环境 |
| 02 devices | `demo_02_devices` | devices、live/capacity | — | 明确区分 gateway/aircraft/RC | Mock覆盖 |
| 03 websocket_osd | `demo_03_websocket_osd` | login | `/api/v1/ws?x-auth-token=...` | Ping、断线识别、重连 | 源码实现 |
| 04 mqtt_osd | `demo_04_mqtt_osd` | — | `thing/product/+/osd` QoS 0 | CONNACK、SUBACK、重订阅 | 源码实现 |
| 05 gimbal_zoom | `demo_05_gimbal_zoom` | payload authority/commands | OSD旁路可另开 04 | dry-run + `EXECUTE PAYLOAD` | dry-run路径 |
| 06 gimbal_pitch | `demo_06_gimbal_pitch` | payload authority/commands | 同上 | 参数边界、停止向量 | dry-run路径 |
| 07 camera | `demo_07_camera` | payload authority/commands | 同上 | dry-run、逐请求确认 | dry-run路径 |
| 08 fly_to_point | `demo_08_fly_to_point` | authority、fly-to-point、status、DELETE | gateway events可用 04 | 活动任务拒绝、任务ID恢复、未知不重发 | Mock状态接口 |
| 09 dock_control | `demo_09_dock_control` | jobs/{method} | — | 动作确认；返航先抢权 | dry-run路径 |
| 10 takeoff_to_point | `demo_10_takeoff_to_point` | authority、takeoff、status | — | 地面IDLE、任务ID恢复、未知不重发 | Mock状态接口 |
| 11 livestream | `demo_11_livestream` | capacity、start/stop/update/switch | OSD枚举由 04 提供 | dry-run；publisher/首帧需独立确认 | 只读 capacity可运行 |
| 12 drc | `demo_12_drc` | drc/connect、enter、exit | 精确 ACL `drc/down/up` | CONNACK→SUBACK→心跳→双零探针；重连重锁；退出归零 | Core ACK测试 |
| 13 payload_advanced | `demo_13_payload_advanced` | payload authority/commands | OSD旁路 | 命令白名单、dry-run | dry-run路径 |
| 14 target_detection | `demo_14_target_detection` | target-detection | WS结果由 03 提供 | dry-run + 确认 | dry-run路径 |
| 15 emergency | `demo_15_emergency` | authority、return home、DRC | DRC精确 ACL | 各应急动作独立确认、最终归零 | dry-run路径 |
| 16 look_at | `demo_16_look_at` | payload authority/commands | OSD/画面确认 | WGS84/高度检查；RC固件限制见下文 | dry-run路径 |
| 17 wayline | `demo_17_wayline` | waylines、upload、flight-tasks、jobs | `flighttask_progress`可用 04 | KMZ本地校验；超时刷新任务不重发 | KMZ/Mock覆盖 |
| 18 continuous_landing | `demo_18_continuous_landing` | DRC三接口 | DRC 10 Hz | 双零探针、限时、Ctrl+C/异常归零 | 非零控制未真机验证 |
| 19 return_home | `demo_19_return_home` | authority、return_home/cancel | aircraft OSD + gateway events | `watch`只读；动作未知不重发 | watch源码实现 |
| 20 kmz_generate | `demo_20_kmz_generate` | — | — | 临时目录生成、结构校验 | 单元测试覆盖 |
| 21 kmz_interactive | `demo_21_kmz_interactive` | — | — | 逐点校验、临时目录输出 | 本地实现 |
| 22 two_drone_control | `demo_22_two_drone_control` | 双网关 authority/takeoff/flyto/status | — | 两目标≥10m、双机分别跟踪、非事务提示 | dry-run/Mock基础覆盖 |

## DRC 与 KMZ说明

DRC 使用 `/drc/connect` 返回的短期账号与 `/drc/enter` 返回的精确 ACL。每次 MQTT 连接建立都会
增加 generation 并重新锁定；只有当前 generation、匹配 `tid/bid/output.seq`、1.5 秒窗口内的
连续两帧成功零杆 ACK 才解锁非零控制。退出、异常或 Ctrl+C 会尽力发送最终零杆，再调用
`/drc/exit`。自动测试从不发送非零量。

KMZ 使用 libzip，固定生成 `wpmz/template.kml` 和 `wpmz/waylines.wpml`，明确 UTF-8 与 KML/WPML
命名空间。默认写入系统临时目录，不污染仓库；上传前再次做本地结构校验。

## 已知差异与限制

- `ControlServiceImpl` 的 RC返航日志文字仍写“无 device_list”，但实际
  `AbstractWaylineService.returnHomeRc/returnHomeCancelRc` 与回归测试发送的是
  `data={}` + `device_list`。C++ Demo只调用 REST，不自行拼该设备报文，以实际服务端实现为准。
- 当前 RC固件 1.9.1.203/1.9.1.217 的 `camera_look_at` 会静默丢弃；C++保留同等 API入口，但不能
  把 `code=0` 之外的任何结果描述为已完成，详见 `../15-MQTT直连AB测试指南.md`。
- Windows/MSVC、真实 Broker、WebSocket、RTSP首帧和真机动作必须在目标 Windows及受控现场验证；
  macOS核心离线测试不能替代这些验收。

本次实现的逐项验证记录见 [VALIDATION.md](VALIDATION.md)。

## 客户 ActionDispatcher 接入

推荐让业务侧 `ActionDispatcher` 只负责命令白名单、参数归一化和现场策略，把平台调用映射到
`YooxClient`。每条业务命令应携带稳定 command ID；飞行、负载和直播分别使用独立互斥域。HTTP
超时后进入 `UNKNOWN`，由 `TaskTracker` 查询状态收敛，不能由 Dispatcher 自动重放。DRC应作为
独立会话状态机接入，不能退化成普通 HTTP循环。

