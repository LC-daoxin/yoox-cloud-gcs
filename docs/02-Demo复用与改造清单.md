# YOOX Cloud API Demo 复用与改造清单

## 1. 评估结论

现有 YOOX 上云 API Demo 适合作为 **YOOX 协议适配基线和真机联调样例**，不适合直接作为生产
平台发布。

原因：

- 优点：官方物模型覆盖较完整，已经具备设备、航线、媒体、直播、DRC、机巢调试和 WebSocket。
- 优点：当前工作区已有媒体接入与 WebRTC/WHEP 浏览器播放经验，可直接用于 P0 的
  RTSP -> MediaMTX -> WebRTC/WHEP 链路。
- 限制：仍是 Sample Server 结构，账号、权限、审计、租户隔离、可观测性和 HA 不满足生产要求。
- 限制：Spring Boot 2.7.12、明文密码、示例 JWT Secret、宽泛 MQTT ACL 等必须重构。
- 风险：基础仓库 `LICENSE` 只有一个换行字符，商用前必须取得明确授权。

评估期间未修改 Demo。其工作区已有未提交改动和新增文件，后续迁移应先由负责人建立一个可追溯的
基线提交或补丁集。

## 2. 当前工程能力

### 2.1 可运行组成

| 组件 | 当前实现 |
| --- | --- |
| 后端 | Java 17、Spring Boot 2.7.12、Maven 多模块 |
| 协议 | MQTT 物模型、HTTPS、Spring WebSocket |
| 数据 | MySQL、Redis、MyBatis-Plus |
| 对象存储 | MinIO、阿里云 OSS、AWS S3 |
| MQTT | EMQX BASIC 与 DRC/WSS |
| 媒体 | P0 仅保留 MediaMTX 接收 RTSP、浏览器 WHEP 链路 |
| 前端 | 单文件 `web/index.html` 直播验证页 |
| 交付 | Dockerfile、docker-compose.yml |

### 2.2 领域源码映射

| YOOX 领域 | Demo 入口 | 复用判断 |
| --- | --- | --- |
| YOOX 协议框架 | Demo 的 MQTT 基础框架模块 | 高度复用，先补测试再迁移 |
| 通用模型/枚举 | Demo 的上下文与模型模块 | 选择性复用，统一 YOOX 命名 |
| WebSocket | Demo 的 WebSocket 模块 | 协议处理可复用，重做会话和事件契约 |
| 数据访问 | Demo 的数据库基础模块 | 迁移思路，升级依赖和迁移工具 |
| Redis | Demo 的 Redis 基础模块 | 封装可复用，Key 必须租户化 |
| 对象存储 | Demo 的存储适配模块 | 高度复用，强化最小权限和多租户前缀 |
| SDK 抽象 | Demo 的 Cloud API 抽象模块 | 高度复用，作为设备适配器内核 |
| 设备管理 | Demo 的设备管理业务模块 | 业务逻辑选择性迁移 |
| 指令与 DRC | Demo 的控制业务模块 | 协议逻辑复用，安全会话重构 |
| 航线任务 | Demo 的航线业务模块 | 核心流程复用，补版本、审批、幂等 |
| 地图/围栏 | Demo 的地图业务模块 | 数据与下发逻辑复用，重做 Web 编辑器 |
| 媒体 | Demo 的媒体业务模块 | 回调与 STS 流程复用 |
| 直播 | `LiveStreamServiceImpl` | 已实测链路复用，重做会话、鉴权和调度 |
| 页面 | `web/index.html` | 仅作为联调诊断页，不进入产品前端 |

## 3. 复用策略

### 3.1 直接保留协议语义

以下内容尽量保持与 YOOX 官方 Demo 一致，以降低真机兼容风险：

- MQTT Topic 拼装、消息 `tid/bid`、请求/应答匹配。
- 设备 SDK/固件版本选择与物模型 DTO。
- `Abstract*Service` 和设备事件的路由方式。
- 航线任务、设备拓扑、HMS、日志、直播的 YOOX 方法名。
- STS 凭证、媒体上传回调和设备直传对象存储的流程。

这些类应移动到独立模块 `yoox-device-adapter`，包名可在协议回归通过后逐步调整，避免一次性大规模
重命名导致设备兼容回归。

### 3.2 选择性迁移的业务逻辑

- 设备在线/离线与拓扑的处理规则。
- OSD 最新态 Redis 缓存。
- `live_capacity` 的相机、视频流和镜头解析。
- 直播开始/停止/清晰度/镜头切换。
- DRC 进入/退出、飞行权和负载权获取。
- 航线准备、执行、暂停、恢复、取消与进度处理。
- 机巢远程调试命令和属性设置。

迁移时必须增加组织/项目校验、资源权限、审计、幂等、指标和统一异常映射。

### 3.3 不直接复用

- `manage_user` 的明文密码和当前登录实现。
- 只按一个 `workspace_id` 工作的授权模型。
- `application.yml` 中的示例 Secret、账号、IP 和公网候选地址。
- 当前宽泛的 MQTT ACL 初始化方式。
- 单 HTML 页面和浏览器内存状态管理。
- 将 Stream Name 直接暴露为可猜测播放路径的做法。
- 手工导 SQL 的数据库发布方式。
- 使用 `latest` 容器标签和缺少版本锁定的部署清单。
- P0 不启用 RTMP、GB28181 接入容器、监听端口和前端选项。

## 4. 目标仓库结构

```text
YOOX_Cloud_GCS/
├── apps/
│   ├── web-console/               # Vue 管理台、地图、直播、座舱
│   └── server/                    # Spring Boot 可运行应用
├── modules/
│   ├── yoox-domain/               # 稳定领域模型
│   ├── yoox-application/          # 用例、权限、安全状态机
│   ├── yoox-device-adapter/       # YOOX MQTT/HTTP/物模型适配
│   ├── yoox-device/               # 设备与态势
│   ├── yoox-cockpit/              # 控制会话、租约、指令
│   ├── yoox-livestream/           # 直播会话与媒体节点
│   ├── yoox-wayline/              # 航线、任务和 WPML
│   ├── yoox-media/                # 媒体、飞行记录
│   └── yoox-iam/                  # 组织、项目、RBAC、审计
├── deploy/
│   ├── compose/
│   │   ├── dev/
│   │   ├── test/
│   │   ├── public/
│   │   └── private/
│   ├── images/
│   └── offline/
├── ops/
│   ├── prometheus/
│   ├── grafana/
│   ├── loki/
│   └── alertmanager/
├── docs/
│   ├── 配置指南.md
│   └── API指南.md
├── openapi/
├── asyncapi/
├── tests/
│   ├── protocol-fixtures/         # MQTT 请求/应答样本
│   ├── integration/
│   └── hardware/
└── pom.xml
```

如团队不希望 Maven 同时管理前端，可将 `apps/web-console` 使用 pnpm workspace 独立构建，但仍由一个
CI 流水线产生可追溯版本。

## 5. 推荐迁移顺序

### Step 0：冻结可追溯基线

1. 由 Demo 维护者确认当前未提交改动的归属。
2. 保存真机实测的 MQTT、REST、WHEP 样本和固件信息。
3. 固定 Docker 镜像、Maven 依赖和 MediaMTX 版本。
4. 确认 YOOX 示例源码和文档的商用许可。

### Step 1：建立协议回归保护

- 为 Topic 路由、序列化、`tid/bid` 应答、直播 URL、航线状态编写契约测试。
- 将真实设备数据脱敏后放入 `tests/protocol-fixtures`。
- 建立 MQTT 仿真器，能重放上线、OSD、直播能力、任务进度和异常。
- 保留 Demo 直播页作为仅开发环境可用的诊断工具。

### Step 2：抽取 YOOX 适配层

- 迁入 MQTT 基础框架和 Cloud API 抽象层的必要代码。
- 提供稳定端口：`DeviceIngressPort`、`CommandPort`、`LivestreamPort`、`WaylinePort`。
- 将设备物模型 DTO 映射为 YOOX 领域 DTO，禁止控制器直接返回底层协议类。
- 用兼容性测试保证抽取前后 MQTT 消息字节级语义不变。

### Step 3：重建生产底座

- 迁移至 Java 21 和受支持的 Spring Boot 3.x。
- 使用 Spring Security 重建认证授权，密码哈希化。
- 使用 Flyway 管理数据库，新增多租户字段和唯一约束。
- 引入结构化日志、OpenTelemetry、Prometheus、健康检查和审计 Outbox。
- 敏感配置从环境/Secret 注入，移除所有示例凭证。
- 为每个业务和基础服务提供 Dockerfile、健康检查、资源限制和 Compose 服务定义。

### Step 4：建设领域模块与 Web

- 先交付 IAM、设备态势和直播，再交付虚拟座舱和航线。
- 每个危险功能先用模拟器验证，再进入真机分级测试。
- 以能力 API 驱动页面，不在前端维护机型判断表。
- 同步维护 `.env.example`、`docs/配置指南.md`、`docs/API指南.md`、OpenAPI 和 AsyncAPI。

### Step 5：Compose 生产部署和灰度

- 开发、测试、公有云和私有化全部使用 Docker Compose，按环境组合基础、媒体、监控和离线 profiles。
- 多节点环境在每个节点运行受版本控制的 Compose 项目，并通过反向代理和节点清单统一路由。
- 在指定机型/固件白名单上启用远控。
- 先单项目、少设备灰度，监控错误码、视频首帧和 DRC 延迟。
- 后期接入 YOOX Ops Console，统一展示容器状态、健康度、日志、指标、告警和版本。

## 6. 关键代码级改造项

| 优先级 | 当前问题 | 目标改造 |
| --- | --- | --- |
| P0 | `UserServiceImpl` 明文比较密码 | Argon2id/bcrypt、锁定、MFA 预留 |
| P0 | JWT Secret 为示例值 | KMS/Secret 注入、Key ID、轮换和撤销 |
| P0 | DRC 初始 ACL 过宽 | 默认拒绝，按会话精确授予 Topic |
| P0 | 缺少严格租户边界 | 查询、缓存 Key、Topic 映射均携带租户/项目 |
| P0 | 危险操作无完整审计 | Before/after、操作者、会话、结果、Trace |
| P0 | 媒体路径可预测 | 随机会话路径、短时令牌、媒体节点鉴权 |
| P0 | 配置含固定公网 IP | 环境化、服务发现、ICE/TURN 动态配置 |
| P0 | SQL 手工初始化 | Flyway 基线与可回滚升级说明 |
| P1 | `javax` 与老依赖 | Boot 3.x / Jakarta 迁移和依赖漏洞治理 |
| P1 | DTO 直接穿透领域 | Anti-corruption layer 和版本化 Web API |
| P1 | OSD 仅存 Redis | 分段持久化飞行轨迹和保留策略 |
| P1 | 单实例会话假设 | Redis 租约、事件总线、WebSocket 粘性/共享 |
| P1 | 容器镜像 `latest` | Digest/版本锁定、SBOM、签名和离线镜像包 |

## 7. 数据迁移

建议把原有表作为 `legacy_*` 只读迁移源，不直接扩展成最终表：

| 原表 | 新领域 |
| --- | --- |
| `manage_workspace` | `organization` + `project` |
| `manage_user` | `user` + `identity` + `role_binding` |
| `manage_device` | `device` + `device_relation` |
| `manage_device_payload` | `payload` |
| `manage_device_hms` | `alert` + `device_event` |
| `wayline_file` | `wayline` + `wayline_version` |
| `wayline_job` | `mission_plan` + `mission_run` |
| `media_file` | `media_asset` |
| 地图三张表 | `map_layer` + GeoJSON `map_feature` |

迁移脚本必须可重复执行，并生成记录数、空值、孤儿关系和对象存储文件存在性报告。

## 8. 退出标准

完成 Demo 迁移需要同时满足：

- 协议样本回归测试通过率 100%。
- EVO Max 4T + 已确认遥控器/机巢完成核心端到端真机测试。
- 代码和镜像中无默认密码、固定公网 IP、长期 OSS 密钥。
- 所有 Web API 均通过组织/项目隔离测试。
- 直播、远控、航线核心动作可审计并可从 Trace ID 追溯。
- Demo 原页面不暴露在生产路由。
- 依赖、镜像和许可证清单完成评审。
- 所有业务和基础服务都可通过 Docker Compose 启动、停止、升级和恢复。
- 配置指南与实际环境变量一致，API 指南与 OpenAPI/AsyncAPI 契约一致。
