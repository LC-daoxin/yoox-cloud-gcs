# YOOX Cloud GCS 新 Nano 快速部署（Mac 构建镜像）

本文适用于在全新的 Jetson Nano 上部署 YOOX Cloud GCS，且构建工作在 Mac（Apple Silicon）上完成。
由于 Mac M 系列与 Jetson Nano 同为 `arm64` 架构，Mac 构建的镜像可以直接在 Nano 上运行，
无需在 Nano 上执行耗时的 Maven 和 npm 编译。

仓库地址（公开仓库，无需配置 SSH 密钥）：

```text
https://github.com/LC-daoxin/yoox-cloud-gcs.git
```

示例设备与目录：

```text
开发机（Mac）：    lince@<Mac 主机名>
Nano 设备：        jetson@172.20.10.4
Nano 部署目录：    /home/jetson/1_projects/yoox-cloud-gcs
```

## 1. 方案概述

| 步骤 | 执行位置 | 说明 |
| --- | --- | --- |
| 构建镜像 | Mac | 编译后端 JAR、前端静态文件，打包为 Docker 镜像 |
| 导出镜像 | Mac | `docker save` 打包 4 个自定义镜像 |
| 传输镜像 | Mac → Nano | `scp` 或 U 盘拷贝 |
| 克隆仓库 | Nano | 公开仓库，直接 HTTPS 克隆，获取 `compose.yml`、`deploy/`、`sql/` 等配置文件 |
| 加载镜像 | Nano | `docker load` 导入镜像，不触发构建 |
| 启动服务 | Nano | `docker compose up -d`，复用已加载镜像 |

第三方镜像（mysql、redis、emqx、minio 等）由 Nano 直接从 Docker Hub 拉取，均为多架构镜像，
无需手动处理。

## 2. 前提条件

### 2.1 Mac 侧

- Apple Silicon（M1/M2/M3/M4），架构为 `arm64`。
- 已安装 Docker Desktop 或 Colima，并已启动。
- 已克隆仓库。

确认 Mac 架构：

```bash
uname -m
# 预期输出：arm64
```

确认 Docker 架构：

```bash
docker version --format '{{.Server.Os}}/{{.Server.Arch}}'
# 预期输出：linux/arm64
```

### 2.2 Nano 侧

- Jetson Nano 已刷 **64 位** JetPack 系统。
- 已安装 Docker Engine 24+ 和 Compose v2 插件。

确认 Nano 架构：

```bash
ssh jetson@172.20.10.4 'uname -m'
# 预期输出：aarch64
```

> 如果输出 `armv7l`，说明 Nano 运行的是 32 位系统，Mac 构建的 arm64 镜像无法运行。
> 需要重新刷 64 位 JetPack 系统后才能继续。

确认 Docker：

```bash
ssh jetson@172.20.10.4 'docker version && docker compose version'
```

## 3. RTSP 传输协议说明

RTSP 传输协议由 `.env` 中的 `YOOX_RTSP_TRANSPORTS` 控制，不同环境的设置不同：

| 环境 | `YOOX_RTSP_TRANSPORTS` | 说明 |
| --- | --- | --- |
| Jetson Nano（Linux） | `udp,tcp`（默认，无需设置） | 优先低延迟 UDP；UDP 不通时自动回退 TCP |
| Mac/Docker Desktop | **必须设置为 `tcp`** | Docker Desktop UDP 端口转发不可靠，强制 TCP 交织传输 |

- **Nano 侧**（`.env.nano1`）：**不需要**设置 `YOOX_RTSP_TRANSPORTS`，保持默认 `udp,tcp` 即可。
- **Mac 本地开发**（`.env`）：必须添加 `YOOX_RTSP_TRANSPORTS=tcp`，否则设备 UDP 推流包经 Docker 端口转发后会丢失。

## 4. Mac 上准备配置和构建镜像

### 4.1 拉取最新代码

```bash
cd ~/git/yooxplore/Autel/YOOX_Cloud_GCS
git pull --ff-only
```

确认工作树干净：

```bash
git status --short
```

### 4.2 为 Nano 准备 .env

从模板复制一份 Nano 专用配置：

```bash
cp .env.example .env.nano1
```

编辑 `.env.nano1`，至少修改以下字段：

```dotenv
COMPOSE_PROJECT_NAME=yoox-cloud-gcs

# Nano 的局域网 IP
YOOX_PUBLIC_HOST=172.20.10.4

# 用提交短号作为镜像版本，方便回滚
YOOX_VERSION=nano1-$(git rev-parse --short HEAD)
```

> `YOOX_VERSION` 不在 `.env.example` 中，需要手动添加。Compose 默认使用 `local`，
> 设置独立版本号可以避免与 Mac 本地镜像混淆，也方便回滚。

Nano 是 Linux 主机，**不需要**在 `.env.nano1` 中设置 `YOOX_RTSP_TRANSPORTS`，保持默认
`udp,tcp`（优先 UDP，UDP 不通时自动回退 TCP）即可。

同时替换所有 `change_me` 和 `replace_with` 值，包括但不限于：

- `YOOX_DB_PASSWORD`、`YOOX_DB_ROOT_PASSWORD`
- `YOOX_REDIS_PASSWORD`
- `YOOX_JWT_SECRET`（至少 32 个字符）
- `YOOX_MQTT_PASSWORD`
- `YOOX_EMQX_DASHBOARD_PASSWORD`
- `YOOX_MINIO_SECRET_KEY`
- `YOOX_RTSP_PASSWORD`
- `YOOX_CLOUD_APP_ID`、`YOOX_CLOUD_APP_KEY`、`YOOX_CLOUD_APP_LICENSE`
- `YOOX_GRAFANA_ADMIN_PASSWORD`

以下固定值**不要修改**，否则 preflight 会报错：

```dotenv
YOOX_MQTT_USERNAME=yoox-cloud
YOOX_DEVICE_MQTT_USERNAME=pilot
YOOX_DEVICE_MQTT_PASSWORD=pilot123
```

确认配置通过预检：

```bash
./scripts/preflight.sh .env.nano1
```

### 4.3 构建 4 个自定义镜像

```bash
cd ~/git/yooxplore/Autel/YOOX_Cloud_GCS

# 读取版本号
export YOOX_VERSION=$(sed -n 's/^YOOX_VERSION=//p' .env.nano1)

COMPOSE_BAKE=false docker compose --env-file .env.nano1 build api web api-portal mediamtx
```

> 如果 `docker compose build` 卡在 `[+] build 0/2` 且进度长时间不动，这是 Compose bake
> 构建器的已知问题。使用 `COMPOSE_BAKE=false` 禁用 bake 即可正常完成。

确认镜像已构建：

```bash
docker image ls --format '{{.Repository}}:{{.Tag}}' | grep "$YOOX_VERSION"
```

预期输出包含：

```text
yoox/cloud-gcs-api:nano1-<提交号>
yoox/cloud-gcs-web:nano1-<提交号>
yoox/cloud-gcs-api-portal:nano1-<提交号>
```

以及固定标签的 mediamtx：

```bash
docker image ls --format '{{.Repository}}:{{.Tag}}' | grep mediamtx
# 预期输出：yoox/mediamtx:1.19.3
```

## 5. 导出并传输镜像

### 5.1 导出镜像

```bash
docker save \
  yoox/cloud-gcs-api:$YOOX_VERSION \
  yoox/cloud-gcs-web:$YOOX_VERSION \
  yoox/cloud-gcs-api-portal:$YOOX_VERSION \
  yoox/mediamtx:1.19.3 \
  -o /tmp/yoox-nano1-images.tar
```

压缩以减少传输量：

```bash
gzip /tmp/yoox-nano1-images.tar
ls -lh /tmp/yoox-nano1-images.tar.gz
```

### 5.2 传输到 Nano

通过局域网 `scp` 传输：

```bash
scp /tmp/yoox-nano1-images.tar.gz jetson@172.20.10.4:/tmp/
scp .env.nano1 jetson@172.20.10.4:/tmp/.env.nano1
```

> 如果局域网带宽有限，也可以将文件拷贝到 U 盘，再在 Nano 上挂载读取。

## 6. Nano 上克隆仓库

仓库为公开仓库，直接 HTTPS 克隆，无需配置 SSH 密钥：

```bash
ssh jetson@172.20.10.4
```

确认目标目录不存在：

```bash
test ! -e /home/jetson/1_projects/yoox-cloud-gcs
```

克隆仓库：

```bash
cd /home/jetson/1_projects

git clone \
  https://github.com/LC-daoxin/yoox-cloud-gcs.git \
  yoox-cloud-gcs
```

检查仓库：

```bash
cd /home/jetson/1_projects/yoox-cloud-gcs
git log -1 --oneline
git status --short
```

## 7. Nano 上加载镜像

```bash
gunzip -c /tmp/yoox-nano1-images.tar.gz | docker load
```

确认镜像已加载：

```bash
docker image ls --format '{{.Repository}}:{{.Tag}}' | grep -E 'yoox|mediamtx'
```

预期输出包含 4 个自定义镜像。第三方镜像（mysql、redis 等）会在 `docker compose up` 时
自动拉取。

## 8. Nano 上配置 .env

将 Mac 传过来的 `.env` 放到部署目录：

```bash
cp -p /tmp/.env.nano1 /home/jetson/1_projects/yoox-cloud-gcs/.env
chmod 600 /home/jetson/1_projects/yoox-cloud-gcs/.env
```

确认 `.env` 不会被 Git 提交：

```bash
cd /home/jetson/1_projects/yoox-cloud-gcs
git check-ignore .env
```

预期输出为 `.env`。如果没有输出，必须先修复 `.gitignore`，不能继续部署。

确认关键配置：

```bash
grep -E '^(COMPOSE_PROJECT_NAME|YOOX_PUBLIC_HOST|YOOX_VERSION)=' .env
```

预期输出：

```text
COMPOSE_PROJECT_NAME=yoox-cloud-gcs
YOOX_PUBLIC_HOST=172.20.10.4
YOOX_VERSION=nano1-<提交号>
```

> `YOOX_VERSION` 必须与 Mac 构建时完全一致，否则 Compose 找不到镜像会尝试 build。

运行预检：

```bash
make preflight
docker compose --env-file .env config -q
```

## 9. 启动服务

```bash
cd /home/jetson/1_projects/yoox-cloud-gcs

docker compose --env-file .env up \
  -d \
  --remove-orphans \
  --wait \
  --wait-timeout 300
```

> **不要使用 `make up`**，因为 `make up` 带 `--build` 参数，会尝试在 Nano 上重新构建镜像。
> 此处镜像已从 Mac 加载，只需 `up -d` 即可。

首次启动时 Docker 会自动拉取 mysql、redis、emqx、minio 等第三方镜像，需要联网且耗时较长。

查看状态和日志：

```bash
docker compose --env-file .env ps
docker compose --env-file .env logs --tail=200 api web emqx mediamtx
```

## 10. 查看运行状态

### 10.1 容器状态与日志

```bash
cd /home/jetson/1_projects/yoox-cloud-gcs

# 查看所有容器健康状态
docker compose --env-file .env ps

# 查看关键服务日志（最近 200 行）
docker compose --env-file .env logs --tail=200 api web emqx mediamtx

# 持续跟踪日志（Ctrl+C 退出）
docker compose --env-file .env logs -f api
```

### 10.2 系统资源监控

```bash
# 实时显示各容器 CPU / 内存用量（Ctrl+C 退出）
docker stats

# 只看 yoox-cloud-gcs 相关容器
docker stats $(docker ps --filter "name=yoox-cloud-gcs" --format "{{.Names}}" | tr '\n' ' ')

# 查看 Docker 磁盘占用（镜像、容器、卷、构建缓存）
docker system df -v

# 查看 Nano 系统内存
free -h

# 查看 Nano 磁盘空间
df -h /
```

### 10.3 Jetson 专项监控

```bash
# Jetson 内置资源监控（CPU、GPU、内存、温度），Ctrl+C 退出
tegrastats

# 更友好的 jtop（需先安装：sudo pip3 install jetson-stats）
jtop
```

## 11. 部署验证

运行项目烟雾测试：

```bash
cd /home/jetson/1_projects/yoox-cloud-gcs
make smoke
```

检查关键服务：

```bash
curl -fsS http://127.0.0.1:19000/actuator/health
curl -fsS http://127.0.0.1:8080/
curl -fsS http://127.0.0.1:8081/
```

核心入口：

| 功能 | 地址 |
| --- | --- |
| Web 控制台 | `http://172.20.10.4:8080` |
| Pilot 登录和 WebSocket 网关 | `http://172.20.10.4:9000` |
| 在线 API 文档 | `http://172.20.10.4:8081` |
| API 健康检查 | `http://172.20.10.4:8080/actuator/health` |

初始化 Web 账号为 `admin / Yoox@123456`，仅用于首次登录，真机测试前应修改密码。

还应进行以下真机验证：

1. Web 管理端能够登录。
2. Pilot/遥控器能够登录并保持在线。
3. MQTT OSD 持续到达。
4. 无人机和相机拓扑正确。
5. RTSP 推流正常。
6. WebRTC 页面播放正常且延迟可接受。
7. 云台、相机和航线接口按安全条件验证。

## 12. 日常更新流程

代码更新后，在 Mac 上重新构建镜像并传到 Nano。

### 12.1 Mac 侧

```bash
cd ~/git/yooxplore/Autel/YOOX_Cloud_GCS
git pull --ff-only

# 更新版本号
export YOOX_VERSION=nano1-$(git rev-parse --short HEAD)
# 同步更新 .env.nano1 中的 YOOX_VERSION
sed -i '' "s/^YOOX_VERSION=.*/YOOX_VERSION=$YOOX_VERSION/" .env.nano1

# 构建
COMPOSE_BAKE=false docker compose --env-file .env.nano1 build api web api-portal mediamtx

# 导出并传输
docker save \
  yoox/cloud-gcs-api:$YOOX_VERSION \
  yoox/cloud-gcs-web:$YOOX_VERSION \
  yoox/cloud-gcs-api-portal:$YOOX_VERSION \
  yoox/mediamtx:1.19.3 \
  -o /tmp/yoox-nano1-images.tar

gzip -f /tmp/yoox-nano1-images.tar

scp /tmp/yoox-nano1-images.tar.gz jetson@172.20.10.4:/tmp/
scp .env.nano1 jetson@172.20.10.4:/tmp/.env.nano1
```

### 12.2 Nano 侧

```bash
ssh jetson@172.20.10.4
cd /home/jetson/1_projects/yoox-cloud-gcs

# 拉取最新配置文件
git pull --ff-only

# 加载新镜像
gunzip -c /tmp/yoox-nano1-images.tar.gz | docker load

# 更新 .env
cp -p /tmp/.env.nano1 .env
chmod 600 .env

# 验证配置
make preflight
docker compose --env-file .env config -q

# 重启服务
docker compose --env-file .env up \
  -d \
  --remove-orphans \
  --wait \
  --wait-timeout 300

make smoke
```

确认容器使用的是新镜像：

```bash
docker compose --env-file .env ps
docker image ls --format '{{.Repository}}:{{.Tag}}  {{.CreatedAt}}' | grep yoox
```

## 13. 回滚

如果新版本验证失败，使用旧镜像回滚。

> 建议在 Mac 上归档旧镜像包：
> ```bash
> mv /tmp/yoox-nano1-images.tar.gz ~/backups/yoox-nano1-images-<旧提交号>.tar.gz
> ```

Nano 上回滚到旧版本：

```bash
ssh jetson@172.20.10.4
cd /home/jetson/1_projects/yoox-cloud-gcs

# 如需从 Mac 重新传输旧镜像：
# scp ~/backups/yoox-nano1-images-<旧提交号>.tar.gz jetson@172.20.10.4:/tmp/
gunzip -c /tmp/yoox-nano1-images-<旧提交号>.tar.gz | docker load

# 将 .env 中的 YOOX_VERSION 改回旧版本
nano .env
# 修改 YOOX_VERSION=nano1-<旧提交号>

# 重启
docker compose --env-file .env up \
  -d \
  --remove-orphans \
  --wait \
  --wait-timeout 300

make smoke
```

如果需要回退代码到旧提交：

```bash
git fetch origin
git log --oneline --decorate -10
git checkout <旧提交号>
```

> 代码回退后，`compose.yml` 和 `deploy/` 配置也会回到旧版本。确保旧镜像与旧配置匹配。

## 14. 镜像清理

Nano 磁盘空间有限，定期清理旧镜像：

```bash
# 查看磁盘占用
docker system df

# 删除未被任何容器使用的 dangling 镜像
docker image prune -f

# 查看所有 yoox 镜像
docker image ls --format '{{.Repository}}:{{.Tag}}  {{.ID}}  {{.Size}}' | grep yoox
```

> 不要使用 `docker compose down -v`，该命令会删除 MySQL、Redis、MinIO、EMQX 等命名卷中的
> 业务数据。普通停机使用 `docker compose down` 或 `make down`。

手动删除指定旧版本镜像：

```bash
docker image rm yoox/cloud-gcs-api:nano1-<旧提交号>
docker image rm yoox/cloud-gcs-web:nano1-<旧提交号>
docker image rm yoox/cloud-gcs-api-portal:nano1-<旧提交号>
```

## 15. 常见问题

### 15.1 Nano 上 `docker compose up` 尝试 build

如果 Nano 上报 `building` 且长时间不动，说明 `.env` 中的 `YOOX_VERSION` 与已加载的镜像 tag
不匹配。

```bash
# 检查 .env 中的版本号
grep YOOX_VERSION .env

# 检查已加载的镜像
docker image ls --format '{{.Repository}}:{{.Tag}}' | grep yoox
```

确保两者完全一致。如果 `.env` 中没有 `YOOX_VERSION`，Compose 会使用默认值 `local`，
与 Mac 构建的 `nano1-<提交号>` 不匹配。

### 15.2 `docker load` 报 `no space left on device`

```bash
df -h /
docker system df
```

清理未使用的镜像和构建缓存：

```bash
docker system prune -f
```

> 不要加 `-v` 参数，`docker system prune -v` 行为不同于 `docker compose down -v`，
> 但仍应谨慎。确认要清理的镜像不再使用后再操作。

### 15.3 第三方镜像拉取失败

Nano 需要联网拉取以下镜像：

| 镜像 | 版本 |
| --- | --- |
| mysql | 8.4.6 |
| redis | 7.4.9-alpine |
| minio/minio | RELEASE.2025-04-22T22-12-26Z |
| minio/mc | RELEASE.2025-04-16T18-13-26Z |
| emqx/emqx | 5.8.9 |
| curlimages/curl | 8.15.0 |

如果网络不稳定，可以在 Mac 上提前拉取并一起导出：

```bash
# Mac 上拉取第三方镜像
docker pull --platform linux/arm64 mysql:8.4.6
docker pull --platform linux/arm64 redis:7.4.9-alpine
docker pull --platform linux/arm64 minio/minio:RELEASE.2025-04-22T22-12-26Z
docker pull --platform linux/arm64 minio/mc:RELEASE.2025-04-16T18-13-26Z
docker pull --platform linux/arm64 emqx/emqx:5.8.9
docker pull --platform linux/arm64 curlimages/curl:8.15.0

# 追加到导出包
docker save \
  mysql:8.4.6 \
  redis:7.4.9-alpine \
  minio/minio:RELEASE.2025-04-22T22-12-26Z \
  minio/mc:RELEASE.2025-04-16T18-13-26Z \
  emqx/emqx:5.8.9 \
  curlimages/curl:8.15.0 \
  -o /tmp/yoox-nano1-thirdparty.tar

gzip /tmp/yoox-nano1-thirdparty.tar
scp /tmp/yoox-nano1-thirdparty.tar.gz jetson@172.20.10.4:/tmp/
```

Nano 上加载：

```bash
gunzip -c /tmp/yoox-nano1-thirdparty.tar.gz | docker load
```

### 15.4 `COMPOSE_BAKE=false` 构建仍卡住

```bash
# 检查是否有残留构建进程
ps aux | grep 'docker compose'

# 终止后重试
kill <PID>
COMPOSE_BAKE=false docker compose --env-file .env.nano1 build --progress=plain api
```

### 15.5 Nano 架构不是 aarch64

```bash
ssh jetson@172.20.10.4 'uname -m'
```

- 输出 `aarch64`：64 位系统，可以继续。
- 输出 `armv7l`：32 位系统，Mac 构建的 arm64 镜像不兼容。需要重新刷 64 位 JetPack。

### 15.6 Mac 为 Intel 芯片

如果 Mac 为 Intel（`x86_64`），构建出的镜像是 `linux/amd64`，不能直接在 Nano（`arm64`）上运行。

需要使用 buildx 跨架构构建：

```bash
# 创建 arm64 构建器（仅需一次）
docker buildx create --name arm64builder --use

# 跨架构构建并直接加载到本地镜像列表
COMPOSE_BAKE=false docker buildx build \
  --platform linux/arm64 \
  --load \
  -t yoox/cloud-gcs-api:$YOOX_VERSION \
  -f Dockerfile \
  .

# web、api-portal、mediamtx 同理
```

> 跨架构构建速度较慢，长期使用建议在 ARM64 CI 或专用构建机上生成镜像。

### 15.7 RTSP 设备推流失败（UDP 丢包）

Nano 默认使用 `udp,tcp`（优先 UDP，UDP 不通时自动回退 TCP）。如果在特定网络环境下 UDP
不稳定，可在 `.env` 中强制 TCP：

```dotenv
YOOX_RTSP_TRANSPORTS=tcp
```

然后重启 mediamtx：

```bash
docker compose --env-file .env up -d mediamtx
```

> Mac 本地开发环境因 Docker Desktop 的 UDP 端口转发限制，**必须**使用
> `YOOX_RTSP_TRANSPORTS=tcp`，否则设备推流会失败。

## 16. 完成标准

满足以下条件后，新 Nano 部署才算完成：

- Nano 系统架构为 `aarch64`。
- Mac 构建的 4 个自定义镜像已加载到 Nano。
- `.env` 中 `YOOX_VERSION` 与镜像 tag 一致。
- `.env` 被 Git 忽略且权限为 `600`。
- `make preflight` 和 Compose 配置检查通过。
- `make smoke` 通过。
- 容器状态全部为 `healthy`。
- Pilot、MQTT、RTSP、WebRTC 和关键控制接口完成真机验证。

## 17. 参考资料

- [Linux 本地构建与部署](11-Linux本地构建与部署.md)
- [部署运维指南](08-部署运维指南.md)
- [配置指南](06-配置指南.md)
- [Docker：docker save](https://docs.docker.com/reference/cli/docker/image/save/)
- [Docker：docker load](https://docs.docker.com/reference/cli/docker/image/load/)
