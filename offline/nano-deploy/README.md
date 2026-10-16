# YOOX Cloud GCS Nano 离线部署包

由 `scripts/build-nano-offline-bundle.sh` 生成的单一 `.tar.gz` 文件包含
**Jetson Nano 完全离线部署所需的全部内容**，无需目标 Nano 联网或克隆仓库。
镜像范围包括核心业务、MySQL、Redis、MinIO、EMQX、MediaMTX、TURN，以及完整监控栈。

## 目录结构

```text
yoox-cloud-gcs-nano-<version>/
├── install.sh                              # Nano 一键安装脚本
├── DEPLOYMENT.md                           # 完整部署、运维与排障指南
├── WINDOWS-DEPLOYMENT.md                   # Windows x64 Docker Desktop 部署指南
├── .env.template                           # 部署环境配置
├── VERSION                                 # 业务镜像版本
├── IMAGE-MANIFEST.txt                      # 全部镜像清单
├── images/
│   └── yoox-cloud-gcs-<version>.tar.gz     # 全部 arm64 镜像（单一镜像归档）
└── deploy/                                 # 部署包内容（install.sh 复制到 ~/yoox-cloud-gcs/）
    ├── compose.yml
    ├── compose.nano.yml
    ├── compose.windows-x64.yml
    ├── scripts/
    │   ├── preflight.sh
    │   └── smoke-test.sh
    ├── deploy/
    │   ├── emqx/
    │   │   ├── acl.conf
    │   │   └── base.hocon
    │   ├── mediamtx/
    │   ├── grafana/
    │   ├── loki/
    │   ├── prometheus/
    │   └── promtail/
    └── sql/
        └── cloud_api.sql
```

## 使用方法（Nano 完全无网环境）

1. 把生成的单一 `.tar.gz` 包复制到 U 盘或 Nano
2. 在 Nano 上解压
3. 进入解压目录执行一键安装，参数是本机局域网 IP：

   ```bash
   tar xzf yoox-cloud-gcs-nano-<version>.tar.gz
   cd yoox-cloud-gcs-nano-<version>
   bash install.sh 172.20.10.4     # 替换为本机实际 IP
   ```

脚本自动完成：加载镜像 → 生成 `.env` → 校验配置 → 启动服务 → 烟雾测试。

### 更新模式

代码更新后，在 Mac 重新打包镜像并传到 Nano，然后以 `--update` 参数运行：

```bash
bash install.sh 172.20.10.4 --update
```

更新模式只替换配置文件和镜像，不覆盖 `.env`，并自动将 `YOOX_VERSION` 更新为新版本。

包内也包含可选服务镜像。考虑 Nano 资源限制，默认只启动核心服务；按需启用：

```bash
bash install.sh 172.20.10.4 --with-turn
bash install.sh 172.20.10.4 --with-monitoring
bash install.sh 172.20.10.4 --with-turn --with-monitoring
```

## 首次安装的 `.env` 说明

### 有 `.env.template`（推荐）

如果打包人已准备好 `.env.template`（含预设密码），install.sh 会直接使用，
并自动填入 `YOOX_PUBLIC_HOST` 和 `YOOX_VERSION`。

### 仅有 `.env.example`

如果部署包根目录没有 `.env.template`，install.sh 会使用同目录的 `.env.example` 并暂停，
提示需要手动编辑以下变量：

```dotenv
YOOX_DB_PASSWORD          # MySQL 密码
YOOX_DB_ROOT_PASSWORD     # MySQL root 密码
YOOX_REDIS_PASSWORD       # Redis 密码
YOOX_JWT_SECRET           # JWT 密钥（至少 32 位）
YOOX_MQTT_PASSWORD        # 云服务 MQTT 密码
YOOX_EMQX_DASHBOARD_PASSWORD
YOOX_MINIO_SECRET_KEY
YOOX_RTSP_PASSWORD
YOOX_CLOUD_APP_ID         # 设备云 AppID
YOOX_CLOUD_APP_KEY
YOOX_CLOUD_APP_LICENSE
```

以下固定值**不要修改**：

```dotenv
YOOX_MQTT_USERNAME=yoox-cloud
YOOX_DEVICE_MQTT_USERNAME=pilot
YOOX_DEVICE_MQTT_PASSWORD=pilot123
```

编辑后重新运行 `bash install.sh <IP>` 即可继续。

## 部署后登录信息

| 功能 | 地址 |
| --- | --- |
| Web 控制台 | `http://<Nano IP>:8080` |
| Pilot 网关 | `http://<Nano IP>:9000` |
| API 文档 | `http://<Nano IP>:8081` |

初始 Web 账号：`admin / Yoox@123456`（首次登录后请立即修改密码）

## 如何在 Mac 上生成单文件离线包

目标机器为同架构的 64 位 Jetson Nano/Orin。Docker Desktop 必须使用
`linux/arm64` 服务端。

```bash
cd ~/git/yooxplore/Autel/YOOX_Cloud_GCS
./scripts/build-nano-offline-bundle.sh \
  --version nano-$(date +%Y%m%d)-$(git rev-parse --short HEAD) \
  --env-template .env.nano1
```

输出位于 `offline/dist/`：

```text
yoox-cloud-gcs-nano-<version>.tar.gz
```

脚本会自动同步配置、构建 4 个业务镜像、拉取全部第三方 arm64 镜像、检查架构、
导出镜像并封装为一个文件。也可运行：

```bash
make nano-offline-bundle ENV_TEMPLATE=.env.nano1
```

不想把真实密钥放进包时，省略 `--env-template .env.nano1`；默认使用安全占位模板，
首次安装会提示在 Nano 上填写。包含真实密钥的包应只通过可信介质传输并妥善保管。

## 注意事项

- 镜像为 `linux/arm64`，默认在 aarch64 Jetson Nano 上运行；Windows x64 Docker Desktop 可按
  `WINDOWS-DEPLOYMENT.md` 使用 QEMU 模拟运行。
- `offline/dist/` 和所有 `.tar.gz` 文件均不应提交 Git。
- 使用 `.env.nano1` 打包时，归档包含明文密钥；不要上传公共网盘或发送给无关人员。
- Nano 默认使用 `udp,tcp` RTSP 传输（优先低延迟 UDP，UDP 不通时回退 TCP）。
  Mac 本地开发需在 `.env` 中设置 `YOOX_RTSP_TRANSPORTS=tcp`。
- **不要使用** `docker compose down -v`，会删除 MySQL/Redis/MinIO/EMQX 数据卷。

## deploy/ 目录与项目根目录的同步

`offline/nano-deploy/deploy/` 是项目关键文件的快照，每次以下文件发生变更后
需运行 `bash scripts/sync-offline.sh` 同步：

- `compose.yml`
- `deploy/compose.windows-x64.yml`
- `scripts/preflight.sh`
- `scripts/smoke-test.sh`
- `deploy/emqx/acl.conf`
- `deploy/emqx/base.hocon`
- `deploy/mediamtx/mediamtx.yml`
- `deploy/grafana/`
- `deploy/loki/`
- `deploy/prometheus/`
- `deploy/promtail/`
- `sql/cloud_api.sql`
