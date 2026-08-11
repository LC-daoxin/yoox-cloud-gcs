# YOOX Cloud GCS Nano 离线部署包

本目录打包了 **Jetson Nano 完全离线部署所需的全部文件**，无需任何网络即可完成部署。

## 目录结构

```text
nano-deploy/
├── install.sh                              # 一键安装脚本（在 Nano 上执行）
├── .env.example                            # 环境变量模板（不含密码）
├── .env.template                           # 预设密码模板（由打包人填写，不提交 Git）
├── images/                                 # Docker 镜像包（*.tar.gz）
│   ├── yoox-cloud-gcs-<version>.tar.gz    # 自定义镜像：api / web / api-portal / mediamtx
│   └── yoox-cloud-gcs-thirdparty-arm64.tar.gz  # 第三方镜像（可选，有网络时 Nano 自动拉取）
└── deploy/                                 # 部署包内容（install.sh 复制到 ~/yoox-cloud-gcs/）
    ├── compose.yml
    ├── scripts/
    │   ├── preflight.sh
    │   └── smoke-test.sh
    ├── deploy/
    │   ├── emqx/
    │   │   ├── acl.conf
    │   │   └── base.hocon
    │   └── mediamtx/
    │       └── mediamtx.yml
    └── sql/
        └── cloud_api.sql
```

## 使用方法（Nano 完全无网环境）

1. 把整个 `nano-deploy/` 目录复制到 U 盘
2. Nano 插入 U 盘并挂载（`lsblk` 查看挂载点）
3. 进入该目录执行一键安装，参数是本机局域网 IP：

   ```bash
   cd /media/jetson/<U盘卷标>/nano-deploy
   bash install.sh 172.20.10.4     # 替换为本机实际 IP
   ```

脚本自动完成：加载镜像 → 生成 `.env` → 校验配置 → 启动服务 → 烟雾测试。

### 更新模式

代码更新后，在 Mac 重新打包镜像并传到 Nano，然后以 `--update` 参数运行：

```bash
bash install.sh 172.20.10.4 --update
```

更新模式只替换配置文件和镜像，不覆盖 `.env`，并自动将 `YOOX_VERSION` 更新为新版本。

## 首次安装的 `.env` 说明

### 有 `.env.template`（推荐）

如果打包人已准备好 `.env.template`（含预设密码），install.sh 会直接使用，
并自动填入 `YOOX_PUBLIC_HOST` 和 `YOOX_VERSION`。

### 仅有 `.env.example`

如果 `images/` 中没有 `.env.template`，install.sh 会使用 `.env.example` 并暂停，
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

## 如何在 Mac 上重新打包此离线包

### 第一步：构建并导出自定义镜像

```bash
cd ~/git/yooxplore/Autel/YOOX_Cloud_GCS
git pull --ff-only

export YOOX_VERSION="nano1-$(git rev-parse --short HEAD)"

# 使用 Nano 专用 .env（不含 YOOX_RTSP_TRANSPORTS=tcp，Nano 使用默认 udp,tcp）
COMPOSE_BAKE=false docker compose --env-file .env.nano1 build api web api-portal mediamtx

# 打包自定义镜像（命名规则：yoox-cloud-gcs-<version>.tar.gz）
docker save \
  yoox/cloud-gcs-api:${YOOX_VERSION} \
  yoox/cloud-gcs-web:${YOOX_VERSION} \
  yoox/cloud-gcs-api-portal:${YOOX_VERSION} \
  yoox/mediamtx:1.19.3 \
  | gzip > offline/nano-deploy/images/yoox-cloud-gcs-${YOOX_VERSION}.tar.gz

# 删除旧版本镜像包（保持 images/ 只有一份）
ls offline/nano-deploy/images/yoox-cloud-gcs-*.tar.gz | grep -v "${YOOX_VERSION}" | xargs rm -f
```

### 第二步：同步部署配置快照

```bash
# 将项目根目录的最新配置同步到离线包
bash scripts/sync-offline.sh
```

### 第三步（可选）：打包第三方镜像

如果 Nano 无法联网拉取第三方镜像，在 Mac 上提前打包：

```bash
docker pull --platform linux/arm64 mysql:8.4.6
docker pull --platform linux/arm64 redis:7.4.9-alpine
docker pull --platform linux/arm64 minio/minio:RELEASE.2025-04-22T22-12-26Z
docker pull --platform linux/arm64 minio/mc:RELEASE.2025-04-16T18-13-26Z
docker pull --platform linux/arm64 emqx/emqx:5.8.9
docker pull --platform linux/arm64 curlimages/curl:8.15.0

docker save \
  mysql:8.4.6 \
  redis:7.4.9-alpine \
  minio/minio:RELEASE.2025-04-22T22-12-26Z \
  minio/mc:RELEASE.2025-04-16T18-13-26Z \
  emqx/emqx:5.8.9 \
  curlimages/curl:8.15.0 \
  | gzip > offline/nano-deploy/images/yoox-cloud-gcs-thirdparty-arm64.tar.gz
```

### 第四步：准备 .env.template（含预设密码）

```bash
# 复制模板并填写所有密码
cp .env.example offline/nano-deploy/.env.template
# 编辑 offline/nano-deploy/.env.template，替换所有 change_me / replace_with 值
```

> `.env.template` 已在 `.gitignore` 中排除，不会提交到 Git。

### 第五步：传输离线包到 Nano

```bash
# 打包整个 nano-deploy 目录并传输（images/ 较大，建议用 U 盘）
tar czf /tmp/nano-deploy.tar.gz -C offline nano-deploy
scp /tmp/nano-deploy.tar.gz jetson@172.20.10.4:/tmp/

# Nano 上解压
ssh jetson@172.20.10.4 'cd /tmp && tar xzf nano-deploy.tar.gz'
ssh jetson@172.20.10.4 'bash /tmp/nano-deploy/install.sh 172.20.10.4'
```

## 注意事项

- 镜像为 `linux/arm64`，只能在 aarch64 Jetson Nano 上运行。
- `images/` 目录已在 `.gitignore` 中排除（`.tar.gz` 文件不提交 Git）。
- `.env.template` 含明文密码，已在 `.gitignore` 中排除，不要提交。
- Nano 默认使用 `udp,tcp` RTSP 传输（优先低延迟 UDP，UDP 不通时回退 TCP）。
  Mac 本地开发需在 `.env` 中设置 `YOOX_RTSP_TRANSPORTS=tcp`。
- **不要使用** `docker compose down -v`，会删除 MySQL/Redis/MinIO/EMQX 数据卷。

## deploy/ 目录与项目根目录的同步

`offline/nano-deploy/deploy/` 是项目关键文件的快照，每次以下文件发生变更后
需运行 `bash scripts/sync-offline.sh` 同步：

- `compose.yml`
- `scripts/preflight.sh`
- `scripts/smoke-test.sh`
- `deploy/emqx/acl.conf`
- `deploy/emqx/base.hocon`
- `deploy/mediamtx/mediamtx.yml`
- `sql/cloud_api.sql`
