# YOOX Cloud GCS Nano 完全离线快速部署

本文说明如何使用项目生成的单文件离线包，在 64 位 Jetson Nano 或 Jetson Orin Nano 上部署
YOOX Cloud GCS。目标设备无需访问 GitHub、Docker Hub、Maven 或 npm，也不需要克隆项目仓库。

当前现场部署目标为 **Nano 8GB B 版**。本文的默认资源配置、安装命令和验收步骤均按该设备
编写；4 GB 设备仅作为兼容性参考，不是当前交付目标。

离线包包含业务镜像、MySQL、Redis、MinIO、EMQX、MediaMTX、TURN、监控组件、Compose
配置、数据库初始化 SQL、环境配置和一键安装脚本。目标 Nano 只需预先安装 Docker Engine 和
Docker Compose v2。

交付文件名格式如下：

```text
yoox-cloud-gcs-nano-<version>.tar.gz
```

完整包包含 17 个 `linux/arm64` 镜像，以及全国普通地图、中文字体和图标，大小以实际归档为准。
本文命令中的 `<version>` 应替换为
实际文件名中的版本。

> 离线包使用 `.env.nano1` 生成时包含明文部署密钥。只能通过可信局域网或可信存储介质传输，
> 不得上传公共网盘、公开制品库或提交到 Git。

## 1. 部署方案

完整流程如下：

| 步骤 | 执行位置 | 是否需要网络 | 说明 |
| --- | --- | --- | --- |
| 构建离线包 | Apple Silicon Mac | 可能需要 | 缺失的第三方镜像需要在构建机拉取 |
| 传输单一归档 | Mac → Nano | 否 | 使用 U 盘、移动硬盘或局域网 `scp` |
| 解压部署包 | Nano | 否 | 解压单一 `.tar.gz` 归档 |
| 一键安装 | Nano | 否 | 自动加载、核对镜像，生成配置并启动服务 |
| 烟雾测试 | Nano | 否 | 安装脚本自动执行健康和接口检查 |

与旧部署方式相比，目标 Nano：

- 不需要克隆项目仓库；
- 不需要执行 Maven 或 npm 构建；
- 不需要运行 `docker pull`；
- 不需要手工加载 MySQL、Redis 等第三方镜像；
- 不需要手工同步 `YOOX_VERSION`。

安装后的运行目录固定为：

```text
$HOME/yoox-cloud-gcs
```

## 2. 离线包内容

解压后的目录结构如下：

```text
yoox-cloud-gcs-nano-<version>/
├── install.sh
├── DEPLOYMENT.md
├── WINDOWS-DEPLOYMENT.md
├── .env.template
├── VERSION
├── IMAGE-MANIFEST.txt
├── README.md
├── images/
│   └── yoox-cloud-gcs-<version>.tar.gz
└── deploy/
    ├── compose.yml
    ├── compose.nano.yml
    ├── compose.windows-x64.yml
    ├── scripts/
    │   ├── preflight.sh
    │   └── smoke-test.sh
    ├── sql/
    │   └── cloud_api.sql
    └── deploy/
        ├── emqx/
        ├── mediamtx/
        ├── grafana/
        ├── loki/
        ├── prometheus/
        └── promtail/
```

### 2.1 包含的 17 个镜像

| 类别 | 镜像 |
| --- | --- |
| 业务后端 | `yoox/cloud-gcs-api:<version>` |
| Web 控制台 | `yoox/cloud-gcs-web:<version>` |
| API Portal | `yoox/cloud-gcs-api-portal:<version>` |
| 流媒体 | `yoox/mediamtx:1.19.3` |
| 数据库 | `mysql:8.4.6` |
| 缓存 | `redis:7.4.9-alpine` |
| 对象存储 | `minio/minio:RELEASE.2025-04-22T22-12-26Z` |
| MinIO 初始化 | `minio/mc:RELEASE.2025-04-16T18-13-26Z` |
| MQTT | `emqx/emqx:5.8.9` |
| EMQX 初始化 | `curlimages/curl:8.15.0` |
| TURN | `coturn/coturn:4.6.3` |
| 指标 | `prom/prometheus:v3.13.0` |
| 可视化 | `grafana/grafana:13.1.0` |
| 日志存储 | `grafana/loki:3.7.2` |
| 日志采集 | `grafana/promtail:2.9.10` |
| 容器指标 | `gcr.io/cadvisor/cadvisor:v0.52.1` |
| 主机指标 | `prom/node-exporter:v1.9.1` |

其中 TURN 和监控镜像也已包含在包中，但考虑 Nano 的资源限制，默认不会启动。

## 3. 前提条件

### 3.1 目标 Nano

目标设备必须满足：

- 64 位 JetPack/Linux，`uname -m` 输出 `aarch64`；
- Docker Engine 已安装且守护进程已启动；
- Docker Compose v2 插件可用；
- 当前用户有权限访问 Docker；
- 含全国地图首次安装建议预留至少 30 GB；按实际归档、解压目录、目标地图和新增镜像层计算空间；
- 当前交付目标为 Nano 8GB B 版；4 GB 型号仅参考第 9.2 节。

检查命令：

```bash
uname -m
docker version
docker compose version
docker info
df -h /
free -h
```

预期架构：

```text
aarch64
```

如果输出 `armv7l`，当前系统是 32 位，不能运行包内的 `linux/arm64` 镜像，必须先安装
64 位系统。

### 3.2 离线边界

全国普通地图、中文字体、图标和样式现随完整离线包交付，首次打开不依赖浏览器缓存。
首页和座舱可切换高德在线地图；高德模式仍需要外网。
构建前先按 [全国离线地图与高德切换](20-全国离线地图与高德切换.md) 准备地图包。

包内包含全部应用和第三方 Docker 镜像，因此安装和启动过程不访问外网。但以下内容不在包内：

- Nano 操作系统和 JetPack；
- Docker Engine；
- Docker Compose v2；
- 设备云侧账号、授权或网络连通性；
- 无人机、遥控器与 Nano 之间的现场网络。

## 4. 传输和解压

### 4.1 传输文件

只需传输单一归档：

```text
yoox-cloud-gcs-nano-<version>.tar.gz
```

可使用 U 盘，也可以通过局域网传输：

```bash
scp \
  offline/dist/yoox-cloud-gcs-nano-<version>.tar.gz \
  jetson@<Nano-IP>:/tmp/
```

### 4.2 解压

```bash
tar xzf yoox-cloud-gcs-nano-<version>.tar.gz
cd yoox-cloud-gcs-nano-<version>
```

## 5. 一键安装

### 5.1 首次安装

安装脚本的第一个参数是 Nano 当前可被遥控器和浏览器访问的局域网 IP：

```bash
bash install.sh 172.20.10.4
```

请替换为目标 Nano 的实际 IP。可先查看本机地址：

```bash
hostname -I
ip addr
```

脚本执行六个阶段：

1. 检查 `aarch64`、Docker、Compose 和镜像包；
2. 使用 `docker load` 导入全部镜像；
3. 对照 `IMAGE-MANIFEST.txt` 核对 17 个镜像；
4. 创建 `$HOME/yoox-cloud-gcs` 并生成 `.env`；
5. 叠加 `compose.nano.yml` 启动核心服务并等待健康检查；
6. 运行烟雾测试并输出访问地址。

安装过程不需要执行 `docker pull`。如果镜像缺失，脚本会报错并停止，不会把联网拉取作为正常
安装步骤。

### 5.2 环境配置

当前交付包使用已经填写完成的 `.env.nano1` 作为 `.env.template`，没有
`change_me` 或 `replace_with` 占位符。首次安装时脚本会自动：

- 把 `YOOX_PUBLIC_HOST` 替换为命令行传入的 Nano IP；
- 从包内 `VERSION` 写入正确的 `YOOX_VERSION`；
- 将最终配置保存为 `$HOME/yoox-cloud-gcs/.env`；
- 将 `.env` 权限设置为 `600`。

首次安装即使中途失败，已经生成的 `.env` 也会保留。重跑安装会自动进入更新模式，
不会把之后修改的 `.env.template` 再次复制过去，也不会用命令行 IP 覆盖已有 `YOOX_PUBLIC_HOST`。
若出现 `change_me / replace_with` 报错，应备份并编辑安装日志显示的实际 `.env` 绝对路径；
root/sudo 安装可能使用 `/root/yoox-cloud-gcs/.env`，不能按当前普通用户的 `~` 猜路径。
具体原因和修复步骤见[部署与运维指南：离线安装占位值校验失败](08-部署运维指南.md#71-离线安装停在-46配置仍含占位值)。

安装后可以检查非敏感配置：

```bash
cd "$HOME/yoox-cloud-gcs"
grep -E '^(COMPOSE_PROJECT_NAME|YOOX_PUBLIC_HOST|YOOX_VERSION)=' .env
stat -c '%a %n' .env
```

不要把 `.env` 内容粘贴到工单、聊天群或公开日志中。

### 5.3 可选服务

默认只启动核心业务服务。按需启用 TURN 或监控：

```bash
# 核心服务 + TURN
bash install.sh 172.20.10.4 --with-turn

# 核心服务 + 监控栈
bash install.sh 172.20.10.4 --with-monitoring

# 核心服务 + TURN + 监控栈
bash install.sh 172.20.10.4 --with-turn --with-monitoring
```

监控栈包含 Prometheus、Grafana、Loki、Promtail、cAdvisor 和 Node Exporter。4 GB Nano 不建议
启用完整监控栈；8 GB Nano 同时运行 YOLOv5 时也应先观察实际内存占用。

## 6. 服务和端口

### 6.1 核心入口

| 功能 | 地址或端口 | 说明 |
| --- | --- | --- |
| Web 控制台 | `http://<Nano-IP>:8080` | 浏览器管理端 |
| Pilot 网关 | `http://<Nano-IP>:9000` | Pilot 登录、HTTP 和 WebSocket |
| API Portal | `http://<Nano-IP>:8081` | 离线 API 文档 |
| API 诊断 | `127.0.0.1:19000` | 仅本机健康检查 |
| MQTT | `<Nano-IP>:1883` | 设备和云服务消息 |
| MinIO S3 | `http://<Nano-IP>:9100` | 航线和媒体文件 |
| RTSP | `<Nano-IP>:8554` | 视频推流/拉流控制及 TCP 交织传输 |
| WebRTC ICE | `<Nano-IP>:8000/udp,tcp` | 浏览器低延迟播放 |
| RTP | `<Nano-IP>:8188/udp` | RTSP UDP 媒体 |
| RTCP | `<Nano-IP>:8189/udp` | RTSP UDP 控制 |

初始 Web 账号：

```text
admin / Yoox@123456
```

首次登录后应立即修改密码。

### 6.2 视频流使用 TCP 还是 UDP

Nano 包的 `.env.template` 明确设置为：

```dotenv
YOOX_RTSP_TRANSPORTS=udp,tcp
```

MediaMTX 实际配置也是：

```yaml
rtspTransports: [udp, tcp]
```

因此：

- RTSP 同时允许 UDP 和 TCP；
- UDP 媒体使用 `8188/udp` 和 `8189/udp`；
- TCP 交织传输使用 RTSP 端口 `8554/tcp`；
- WebRTC ICE 同时开放 `8000/udp` 和 `8000/tcp`。

若现场网络丢弃 UDP，可在安装目录的 `.env` 中强制 RTSP 使用 TCP：

```bash
cd "$HOME/yoox-cloud-gcs"
nano .env
# 新增或修改为：YOOX_RTSP_TRANSPORTS=tcp
docker compose -f compose.yml -f compose.nano.yml --env-file .env up -d mediamtx
```

如果 `.env` 已经存在该变量，应编辑原值，不要追加重复项。

## 7. 部署验证

安装脚本会自动运行烟雾测试。安装完成后仍建议手工确认：

```bash
cd "$HOME/yoox-cloud-gcs"

docker compose -f compose.yml -f compose.nano.yml --env-file .env ps
bash scripts/smoke-test.sh .env

curl -fsS http://127.0.0.1:19000/actuator/health
curl -fsS http://127.0.0.1:8080/
curl -fsS http://127.0.0.1:8081/
```

查看关键服务日志：

```bash
docker compose -f compose.yml -f compose.nano.yml --env-file .env \
  logs --tail=200 api web mysql redis emqx mediamtx
```

真机验收至少包括：

1. Web 管理端能够登录；
2. Pilot/遥控器能够登录并保持在线；
3. MQTT OSD 持续到达；
4. 无人机和相机拓扑正确；
5. RTSP 推流正常；
6. WebRTC 页面能够播放且延迟可接受；
7. 云台、相机、航线和飞控接口按安全条件验证。

## 8. 日常运维

### 8.1 状态和日志

```bash
cd "$HOME/yoox-cloud-gcs"

docker compose -f compose.yml -f compose.nano.yml --env-file .env ps
docker compose -f compose.yml -f compose.nano.yml --env-file .env logs --tail=200
docker compose -f compose.yml -f compose.nano.yml --env-file .env logs -f api
```

### 8.2 资源和磁盘

```bash
docker stats
docker system df
free -h
df -h /
tegrastats
```

### 8.3 停止和重新启动

停止并删除容器和项目网络，但保留命名数据卷：

```bash
cd "$HOME/yoox-cloud-gcs"
docker compose -f compose.yml -f compose.nano.yml --env-file .env down
```

重新启动：

```bash
docker compose -f compose.yml -f compose.nano.yml --env-file .env up \
  -d --remove-orphans --wait --wait-timeout 300
```

> 禁止执行 `docker compose down -v`。`-v` 会删除 MySQL、Redis、MinIO、EMQX 和监控数据卷，
> 可能造成不可恢复的数据丢失。

## 9. Nano 资源限制

安装脚本默认叠加 `compose.nano.yml`，为核心服务设置资源上限，以便和 YOLOv5 等任务共存。

### 9.1 Nano 8GB B 版（当前目标）

| 服务 | 内存上限 | CPU 上限 | 主要优化 |
| --- | ---: | ---: | --- |
| MySQL | 480 MB | 0.4 核 | 192 MB buffer pool、64 连接 |
| Redis | 96 MB | 0.2 核 | 64 MB LRU |
| MinIO | 256 MB | 0.3 核 | 对象存储限额 |
| EMQX | 512 MB | 0.4 核 | 限制连接数 |
| MediaMTX | 128 MB | 0.3 核 | 视频转发 |
| API | 900 MB | 1.0 核 | `-Xmx700m`、SerialGC |
| Web + API Portal | 112 MB | 0.2 核 | nginx 静态服务 |

初始化容器只短暂运行。核心常驻服务上限合计约 2.5 GB 内存和 2.8 核 CPU。
该配置为当前 8GB B 版的默认配置，无需在首次部署时手工修改 `compose.nano.yml`。

### 9.2 4 GB Nano（非当前交付目标）

4 GB 设备同时运行完整 GCS 和视觉推理较紧张，建议：

- 不启用 `--with-monitoring`；
- 使用 YOLOv5n/YOLOv5s 或量化模型；
- 将 API 上限降至约 `700M`，JVM `-Xmx550m`；
- 将 MySQL 上限降至约 `350M`，buffer pool 调为 `128M`；
- 将 EMQX 上限降至约 `384M`；
- 用 `tegrastats` 和 `docker stats` 持续观察内存。

这些调整需要修改安装目录中的 `compose.nano.yml`，修改前应备份原文件。

## 10. 离线更新

### 10.1 构建并传输新包

在 Mac 上生成新版本离线包，传到 Nano。Nano 不需要更新 Git 仓库，也不需要单独传输镜像。

### 10.2 更新安装

在新包的解压目录执行：

```bash
bash install.sh <Nano-IP> --update
```

更新模式会：

- 校验并加载新包中的全部镜像；
- 更新 Compose、SQL、脚本和服务配置；
- 保留 `$HOME/yoox-cloud-gcs/.env` 中的现有密码和现场配置；
- 自动把 `YOOX_VERSION` 更新为新包版本；
- 重新启动服务并运行烟雾测试。

如果更新后仍需启用可选服务：

```bash
bash install.sh <Nano-IP> --update --with-turn --with-monitoring
```

更新前建议备份 `.env` 和重要业务数据。不要通过复制容器目录替代数据库和对象存储的正式备份。

## 11. 回滚

保留上一版本的完整离线包。需要回滚时，解压旧包并执行：

```bash
cd yoox-cloud-gcs-nano-<old-version>
bash install.sh <Nano-IP> --update
```

旧包会重新加载旧镜像、恢复对应版本的 Compose 配置并自动修改 `YOOX_VERSION`，现有 `.env`
中的现场密码和地址配置仍会保留。

回滚后运行：

```bash
cd "$HOME/yoox-cloud-gcs"
bash scripts/smoke-test.sh .env
docker compose -f compose.yml -f compose.nano.yml --env-file .env ps
```

数据库结构发生不兼容变化时，镜像回滚不等于数据回滚，必须结合部署前的数据备份处理。

## 12. 镜像和磁盘清理

先查看占用：

```bash
docker system df
docker image ls --format '{{.Repository}}:{{.Tag}}  {{.ID}}  {{.Size}}'
```

只清理未使用的悬空镜像：

```bash
docker image prune -f
```

确认旧版本不再用于运行或回滚后，才可按完整标签删除：

```bash
docker image rm yoox/cloud-gcs-api:<old-version>
docker image rm yoox/cloud-gcs-web:<old-version>
docker image rm yoox/cloud-gcs-api-portal:<old-version>
```

不要删除正在运行容器使用的镜像，也不要使用带 `-v` 的清理命令处理本项目。

## 13. 在 Mac 上重新生成离线包

### 13.1 构建机要求

- Apple Silicon Mac，或 Docker 服务端为 `linux/arm64` 的其他构建机；
- Docker Desktop/Colima 已启动；
- 已克隆当前项目；
- 首次拉取缺失第三方镜像时构建机需要访问镜像仓库；
- 使用 JDK 17 运行仓库验证。

确认 Docker 服务端架构：

```bash
docker version --format '{{.Server.Os}}/{{.Server.Arch}}'
```

预期输出：

```text
linux/arm64
```

### 13.2 生成可直接安装的包

```bash
cd ~/git/yooxplore/Autel/YOOX_Cloud_GCS

./scripts/build-nano-offline-bundle.sh \
  --version nano-$(date +%Y%m%d)-$(git rev-parse --short HEAD) \
  --env-template .env.nano1
```

也可以使用 Makefile：

```bash
make nano-offline-bundle ENV_TEMPLATE=.env.nano1
```

输出位于：

```text
offline/dist/yoox-cloud-gcs-nano-<version>.tar.gz
```

构建脚本会自动：

1. 同步离线部署配置；
2. 校验 Compose；
3. 构建 API、Web、API Portal 和 MediaMTX；
4. 使用本地固定版本第三方镜像，缺失时拉取 arm64 镜像；
5. 检查全部镜像均为 `arm64/aarch64`；
6. 导出 17 个镜像；
7. 写入镜像清单、完整部署文档并封装为一个归档。

不希望把真实密钥写入包时，省略 `--env-template .env.nano1`。脚本默认使用
`.env.example`，目标 Nano 首次安装时会停下并提示填写占位符。

### 13.3 构建前验证

```bash
env JAVA_HOME=$(/usr/libexec/java_home -v 17) make verify
```

如果 Maven 实际使用 JDK 21/25，Lombok 注解处理可能失败。以 `mvn -version` 显示的 Java
版本为准，而不是只看 `java -version`。

## 14. 常见问题

### 14.1 安装时提示非 aarch64

```bash
uname -m
```

- `aarch64`：符合要求；
- `armv7l`：32 位系统，必须重装 64 位 JetPack；
- `x86_64`：不是目标 Nano 架构，不能使用此包。

### 14.2 `no space left on device`

```bash
df -h /
docker system df
```

外层归档、解压后的镜像与地图、安装后的目标地图和 Docker 镜像层会同时占用空间。
含全国地图首次安装建议预留至少 30 GB，并以实际包大小复核。
确认无用镜像后可执行：

```bash
docker image prune -f
```

不要删除数据卷。

### 14.3 Docker 权限不足

如果出现 `permission denied while trying to connect to the Docker daemon socket`，需要让当前用户
具备 Docker 权限，或在维护窗口使用具备权限的账号执行。修改用户组后通常需要重新登录。

### 14.4 安装脚本警告 IP 不属于本机

脚本仍会继续，但 `YOOX_PUBLIC_HOST` 会被写成传入值。先使用 `ip addr` 核对 IP；传错地址会
导致 Pilot、浏览器、MQTT、MinIO 或视频回调访问错误。建议中止后使用正确 IP 重新执行。

### 14.5 Compose 尝试拉取或构建镜像

正常的一键安装会在启动前检查全部 17 个镜像，不应联网拉取。出现拉取或构建通常表示绕过了
安装脚本、镜像导入失败，或 `.env` 中的 `YOOX_VERSION` 被手工改错。

```bash
cd "$HOME/yoox-cloud-gcs"
grep '^YOOX_VERSION=' .env
docker image ls --format '{{.Repository}}:{{.Tag}}' | grep -E '^(yoox/|mysql:|redis:|minio/|emqx/)'
```

最稳妥的修复方式是回到完整离线包目录，重新运行 `install.sh`。不要把 `docker pull` 当作离线
部署的修复步骤。

### 14.6 健康检查超时

查看状态和依赖服务日志：

```bash
cd "$HOME/yoox-cloud-gcs"
docker compose -f compose.yml -f compose.nano.yml --env-file .env ps -a
docker compose -f compose.yml -f compose.nano.yml --env-file .env \
  logs --tail=300 mysql redis minio emqx mediamtx api
```

常见原因包括端口占用、旧数据卷与新配置不兼容、磁盘不足、IP 配置错误或内存不足。

### 14.7 RTSP 推流失败或 UDP 不稳定

确认防火墙和交换网络允许：

```text
8554/tcp
8188/udp
8189/udp
8000/udp
8000/tcp
```

临时强制 RTSP TCP：

```dotenv
YOOX_RTSP_TRANSPORTS=tcp
```

修改后重启 MediaMTX。浏览器 WebRTC 仍会根据网络在 UDP/TCP ICE 候选间选择。

### 14.8 容器退出码 137

退出码 137 通常表示 OOM：

```bash
docker compose -f compose.yml -f compose.nano.yml --env-file .env ps -a
docker inspect <容器名> --format '{{.State.OOMKilled}} {{.State.ExitCode}}'
```

4 GB Nano 应关闭监控、降低视觉模型占用并按第 9.2 节压低服务限额。

### 14.9 第三方镜像是否需要拉取

目标 Nano 不需要拉取。MySQL、Redis、MinIO、EMQX、TURN 和监控组件都在包内。
只有构建离线包的 Mac 在本地缺少固定版本镜像时，打包脚本才会联网拉取。

## 15. 完成标准

满足以下条件后部署才算完成：

- Nano 架构为 `aarch64`；
- 安装脚本确认 17 个镜像全部存在；
- `$HOME/yoox-cloud-gcs/.env` 权限为 `600`；
- Compose 核心服务处于 `running/healthy`；
- 烟雾测试通过；
- Web、Pilot 网关和 API Portal 可访问；
- Pilot、MQTT、RTSP、WebRTC 完成真机验证；
- 未发生持续 OOM、磁盘写满或端口冲突；
- 初始 Web 密码已经修改；
- 离线包和 `.env` 已按敏感文件管理。

## 16. 参考资料

- [离线部署包 README](../offline/nano-deploy/README.md)
- [Linux 本地构建与部署](11-Linux本地构建与部署.md)
- [部署运维指南](08-部署运维指南.md)
- [配置指南](06-配置指南.md)
- [Docker image load](https://docs.docker.com/reference/cli/docker/image/load/)
- [Docker image save](https://docs.docker.com/reference/cli/docker/image/save/)
