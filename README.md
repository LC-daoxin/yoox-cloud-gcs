# YOOX Cloud GCS

YOOX Cloud GCS 是一套可容器化部署的网页云端地面站。当前版本以现有上云 API Demo
的设备协议实现为基础，提供设备管理、RTSP 实时直播、专业多窗虚拟座舱、DRC 远程控制、
KMZ 航线管理、任务状态、媒体归档和运行健康检查。

平台界面和文案只使用 YOOX 品牌。虚拟座舱采用左侧飞行会话、中部态势/机巢辅助画面、
右侧主视频与遥测 HUD 的布局，并加入控制权租约、安全确认、键盘/双摇杆和紧急操作联锁。

## 立即启动

前置条件：Docker Engine 24+、Docker Compose v2，建议至少 4 核 CPU、8 GB 内存和
30 GB 可用磁盘。

```bash
cp .env.example .env
# 编辑 .env：更换所有 change_me，并把 YOOX_PUBLIC_HOST 改为设备可访问的地址
./scripts/preflight.sh
docker compose --env-file .env config --quiet
docker compose --env-file .env up -d --build --wait --wait-timeout 300
docker compose --env-file .env ps
./scripts/smoke-test.sh
```

打开 `http://<YOOX_PUBLIC_HOST>:<YOOX_HTTP_PORT>`。初始化 Web 账号为
`admin / Yoox@123456`，仅用于首次登录；投产前必须修改初始化账号和所有示例密钥。

遥控器「设置 → 云服务」使用独立的 Pilot 网关端口：

- 登录地址：`http://<YOOX_PUBLIC_HOST>:<YOOX_PILOT_PORT>`（默认 9000）
- WebSocket：`ws://<YOOX_PUBLIC_HOST>:<YOOX_PILOT_PORT>/api/v1/ws`

APP 登录并上报设备拓扑后，平台会自动注册、绑定并关联遥控器与飞机。设备页分别显示
`aircraft_sn` 和 `remote_controller_sn`，平台名称可在设备详情中修改。

可选监控栈：

```bash
docker compose --env-file .env --profile monitoring up -d
```

若跨 NAT 的 WebRTC 播放需要 TURN：

```bash
docker compose --env-file .env --profile turn up -d
```

## 首期媒体边界

- 设备侧上云 API 的协议能力矩阵为 RTMP、RTSP、GB28181。
- 当前运行版本仅开放 RTSP 入流，前端没有其他入流协议选项。
- 浏览器无法直接播放 RTSP，由 MediaMTX 转换为 WebRTC，并通过 WHEP 会话播放。
- 后期启用 RTMP 或 GB28181 时，应新增对应 profile、鉴权、端口和容量测试。

## 容器

核心 profile 包含 Web、API、在线 API 文档门户、MySQL、Redis、MinIO、EMQX 和 MediaMTX；所有业务功能均在
容器中运行。`monitoring` profile 增加 Prometheus、Grafana、Loki、Promtail、cAdvisor 和
Node Exporter。后期 YOOX Ops Console 可在不改变业务容器接口的前提下统一管理容器、探针、
指标、日志和告警。

## 本地构建验证

```bash
# 后端必须使用 JDK 17
JAVA_HOME=/path/to/jdk17 mvn -B -DskipTests clean package -pl cloud-service -am

# 前端
cd web-console
npm ci
npm run build

# Compose
docker compose --env-file .env.example config --quiet
```

## Python 联调 Demo

`docs/python-demo` 提供独立的 Python 3 示例，覆盖登录、设备发现、OSD、直播、
一键起飞、指点飞行及停止、返航及取消返航、DRC、航线任务执行/暂停/恢复/取消和进度监听。
Demo 默认只使用安全占位配置；先复制示例环境文件并填写测试环境地址、工作空间和设备 SN：

```bash
cd docs/python-demo
cp .env.example .env
# 编辑 .env 后运行；首次执行会在本目录创建隔离虚拟环境
./run.sh demo_01_login.py
```

飞行类脚本均要求人工确认。真机联调前请先运行设备查询和 OSD 监听，确认目标 SN、飞机状态、
定位、电量、控制权和现场接管条件；HTTP 超时不等于指令未执行，必须先查询任务状态，禁止盲目重发。
完整说明见 [Python Demo 使用指南](docs/python-demo/README.md)。

## 文档

- [macOS 本地构建与部署](docs/10-macOS本地构建与部署.md)
- [Linux 本地构建与部署](docs/11-Linux本地构建与部署.md)
- [配置指南](docs/06-配置指南.md)
- [API 指南](docs/07-API指南.md)
- [部署与运维指南](docs/08-部署运维指南.md)
- [遥控器云服务频繁断连排查](docs/09-遥控器云服务频繁断连排查.md)
- [Python Demo 使用指南](docs/python-demo/README.md)
- [项目总体方案](docs/01-项目总体方案.md)
- [Demo 复用与改造清单](docs/02-Demo复用与改造清单.md)
- [实施计划与验收标准](docs/03-实施计划与验收标准.md)
- [部署与容量规划](docs/04-部署与容量规划.md)

运行后还可访问：

- 在线 API 文档门户：`http://<YOOX_PUBLIC_HOST>:<YOOX_API_PORTAL_PORT>`（默认 8081，`api-portal` 静态站）
- Swagger UI：`http://<host>:<http-port>/swagger-ui/index.html`
- OpenAPI JSON：`http://<host>:<http-port>/v3/api-docs`
- 健康探针：`http://<host>:<http-port>/actuator/health`
- Prometheus 指标：`http://<host>:<http-port>/actuator/prometheus`

## 真机上线前

远程飞行必须在授权空域、具备现场安全保障且网络条件满足的情况下开展。应先完成机型、负载、
固件、上云账号、设备能力、RTSP 首帧、DRC 排他控制、失联返航、紧急制动和弱网恢复测试。
本仓库完成了软件构建验证；真机协议与飞行安全验收必须使用实际设备完成。
