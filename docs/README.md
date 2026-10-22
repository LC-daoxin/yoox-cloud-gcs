# YOOX Cloud GCS 文档索引

> 更新日期：2026-10-19
> 当前应用版本：1.10.0

本目录同时包含当前操作手册、诊断记录和早期规划。执行部署、配置或 API 调用时，应优先使用
“当前操作文档”；规划和历史评审用于理解背景，不能替代运行代码、Compose 或 OpenAPI。

## 当前操作文档

| 文档 | 用途 | 权威来源 |
| --- | --- | --- |
| [配置指南](06-配置指南.md) | 环境变量、端口、多网卡、MQTT、MinIO、媒体 | `.env.example`、Compose、`application.yml` |
| [全国离线地图与高德切换](20-全国离线地图与高德切换.md) | 全国底图、中文字体、离线打包、地图切换和验收 | 地图适配器、地图包清单、安装及 smoke 脚本 |
| [API 指南](07-API指南.md) | 登录、设备、直播、DRC、控制、航线、媒体流程 | Controller、运行时 OpenAPI |
| [部署与运维指南](08-部署运维指南.md) | Nano 运行服务的巡检、配置变更、备份和排障 | Compose、运行时健康检查 |
| [macOS 本地构建与部署](10-macOS本地构建与部署.md) | Docker Desktop 开发和真机局域网接入 | Makefile、Compose |
| [Windows 10/11 Docker Desktop 离线镜像部署](18-Windows10-11本地构建与部署.md) | WSL2、Docker Desktop、ARM64 离线包导入和局域网接入 | 离线包、Compose、Docker/Microsoft 官方文档 |
| [Windows 10/11 客户电脑离线部署实操手册](19-Windows10-11客户电脑离线部署实操手册.md) | 从环境检查到重启恢复、双网卡、RTSP 和防火墙的现场操作 | 实际交付流程、离线包、Compose、运行时健康检查 |
| [Linux 本地构建与部署](11-Linux本地构建与部署.md) | Linux 源码构建和单机部署 | Makefile、Compose |
| [Nano 完全离线快速部署](13-Nano快速部署.md) | Nano 8GB B 版离线安装、更新和回滚 | 离线包构建/安装脚本 |
| [Tailscale 异地访问指南](17-Tailscale异地访问指南.md) | 无公网 IP 的 Nano SSH、RDP、GCS 和子网路由 | Tailscale/GNOME 官方文档、现场网络配置 |
| [部署与容量规划](04-部署与容量规划.md) | 当前容器、端口、卷、资源和容量边界 | Compose、Nano override |

## 诊断与联调文档

| 文档 | 用途 | 状态 |
| --- | --- | --- |
| [遥控器云服务频繁断连排查](09-遥控器云服务频繁断连排查.md) | 优先检查 Nano 联网、NTP 和外网依赖；MQTT 抢占及 APP 重连诊断 | 当前运行手册 |
| [指令链路诊断日志指南](14-指令链路诊断日志指南.md) | 211001、MQTT services 下发/应答对账 | 当前运行手册 |
| [MQTT 直连 A/B 测试指南](15-MQTT直连AB测试指南.md) | 绕过业务层验证固件报文兼容性 | 带日期的实测记录 |
| [a-uav 上云接入分析](16-a-uav上云服务接入分析与实施方案.md) | Android 分支接入差距和实施路径 | 基于指定提交的历史评审 |
| [Python Demo 使用指南](python-demo/README.md) | REST、WebSocket、MQTT、直播和飞控示例 | 当前示例说明 |
| [Windows C++ Demo 使用指南](cpp-demo/README.md) | Python Demo 的 Windows x64/C++17 对应实现 | 默认 dry-run；真机前需现场验收 |

## 背景与规划

| 文档 | 用途 | 注意事项 |
| --- | --- | --- |
| [项目总体方案](01-项目总体方案.md) | 产品、架构和长期目标 | 规划基线；当前技术栈仍为 Java 17/Spring Boot 2.7 |
| [实施计划与验收标准](03-实施计划与验收标准.md) | 原始里程碑和验收框架 | 规划基线，不代表当前排期或完成度 |
| [文档同步与交付规范](05-文档同步交付规范.md) | 文档事实来源、同步矩阵和发布门禁 | 当前维护规范 |

## API 文档优先级

API 信息发生冲突时按以下顺序判断：

1. 当前运行服务的 `/v3/api-docs`；
2. 当前源码的 Controller 和接口注解；
3. [API 指南](07-API指南.md) 的业务流程与示例；
4. API Portal 静态说明；
5. 历史评审或规划文档。

项目不再维护独立 Word API 副本。静态 Word 文件容易与 Controller/OpenAPI 漂移，且无法保证中文
字体、目录和表格在不同环境中稳定渲染。

## 敏感文件说明

`python-demo/.env` 与 `cpp-demo/.env` 都是本地联调凭据文件，必须被 Git 忽略；两个 Demo 可通过
`--env-file` 共用同一份配置。仓库只提交 `.env.example` 安全占位模板。若历史版本曾跟踪真实
配置，应先从 Git 索引与历史中移除并轮换相关凭据。

Nano 离线包如果使用 `.env.nano1` 生成，也会包含明文部署密钥。离线包不得公开分发。

## 已移除的旧文档

- “Demo 复用与改造清单”：迁移阶段已经结束，内容把当前项目描述为单页 Demo，与现状冲突；
- “Jetson Nano 私有 Git 部署与迁移”：已被完全离线包流程取代；
- Word API 副本：由运行时 OpenAPI、Markdown API 指南和 API Portal 取代。

如需追溯这些历史材料，应从 Git 历史读取，不应继续作为当前操作入口。
