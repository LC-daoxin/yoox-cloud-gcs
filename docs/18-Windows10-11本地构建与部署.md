# YOOX Cloud GCS Windows 10/11 本地构建与部署

> 状态：当前源码构建手册  
> 适用版本：1.10.0  
> 更新日期：2026-10-15

本手册用于在 Windows 10/11 x64 上通过 WSL2 和 Docker Desktop 构建、启动并验收
YOOX Cloud GCS。项目容器均为 Linux 容器；命令在 WSL2 Ubuntu 中执行，Docker Desktop
提供 Docker Engine 和 Compose。

本流程适合本地开发、演示和受控局域网联调。长期无人值守、严格宿主机监控或生产环境
建议使用 Linux 服务器，参见 [Linux 本地构建与部署](11-Linux本地构建与部署.md)。

## 1. 部署架构和支持范围

标准链路如下：

```text
Windows 10/11
├─ Docker Desktop（WSL 2 backend，Linux containers）
├─ WSL2 Ubuntu（Git、Make、Shell 脚本和 Docker CLI）
└─ Windows 物理网卡 IP（浏览器、APP 和遥控器的访问入口）
```

本手册不覆盖以下方案：

- Windows 容器模式；
- 在 WSL2 Ubuntu 内另行安装 `docker.io` 或 Docker Engine；
- Windows Server 上的 Docker Desktop；
- Windows on ARM 主机，本项目尚未对该组合完成验收。

## 2. 环境要求

### 2.1 Windows 主机

- Windows 10 22H2 x64 或 Docker Desktop 当前支持的 Windows 11 x64，建议优先使用 Windows 11。
- BIOS/UEFI 已启用硬件虚拟化。
- WSL 2.1.5 或更高版本，建议更新到当前稳定版。
- Docker Desktop for Windows，使用 WSL 2 backend 和 Linux containers。
- 至少 4 核 CPU、8 GB 内存和 30 GB 可用磁盘；同时运行 IDE 和监控栈时建议 16 GB 或更多内存。

Docker Desktop 的版本和 Windows 版本要求会变化，安装前应对照文末的官方支持矩阵。
组织内使用 Docker Desktop 时还应确认许可条款是否适用。

### 2.2 安装和检查 WSL2

使用管理员 PowerShell 执行：

```powershell
wsl --install -d Ubuntu
wsl --update
wsl --set-default-version 2
```

按系统提示重启 Windows，首次打开 Ubuntu 后创建 Linux 用户和密码。回到 PowerShell 检查：

```powershell
wsl --version
wsl --list --verbose
```

Ubuntu 所在行的 `VERSION` 应为 `2`。如果不是，执行：

```powershell
wsl --set-version Ubuntu 2
```

### 2.3 安装和配置 Docker Desktop

1. 安装 Docker Desktop for Windows。
2. 在 `Settings > General` 中启用 `Use the WSL 2 based engine`。
3. 在 `Settings > Resources > WSL Integration` 中启用 Ubuntu。
4. 确认 Docker Desktop 处于 Linux containers 模式。如果菜单显示
   `Switch to Linux containers`，先执行切换。
5. 启动 Docker Desktop，等待 Engine 状态正常。

在 Ubuntu 终端中检查：

```bash
docker version
docker compose version
docker info
```

命令应同时显示 Docker Client 和 Server，Compose 应为 v2。不要在 Ubuntu 中再安装一套
Docker Engine，否则容易出现 Docker 上下文、数据卷和端口归属冲突。

### 2.4 安装 WSL 基础工具

在 Ubuntu 终端中执行：

```bash
sudo apt-get update
sudo apt-get install -y git make curl ca-certificates
```

仅使用 Docker Compose 构建和运行服务时，不需要在 WSL2 中另行安装 Java、Maven 和 Node.js。

## 3. 将源码放入 WSL 文件系统

建议将仓库放在 Ubuntu 的 Linux 文件系统，不要直接从 `/mnt/c` 或 `/mnt/d` 进行镜像构建。
这样可以获得更稳定的文件权限、大小写语义和 bind mount 性能。

在 Ubuntu 中克隆仓库：

```bash
mkdir -p ~/src
cd ~/src
git config --global core.autocrlf input
git clone <repository-url> YOOX_Cloud_GCS
cd YOOX_Cloud_GCS
```

如果源码是 Windows 上的压缩包，先解压并复制到 `~/src/YOOX_Cloud_GCS`，然后在 Ubuntu 中进入
该目录。压缩包如果没有保留 Linux 执行权限，再执行 `chmod +x scripts/*.sh`。根目录应包含
`Makefile`、`compose.yml`、`cloud-service/` 和 `web-console/`。

## 4. 生成和配置 .env

### 4.1 确定 Windows 局域网地址

如果 APP、遥控器或其他电脑需要访问服务，在 PowerShell 或 CMD 中执行：

```powershell
ipconfig
```

使用当前 Wi-Fi 或以太网物理网卡的 IPv4 地址，例如 `192.168.1.50`。不要使用
`vEthernet (WSL)`、Docker 虚拟网卡的地址或 WSL 内部 `172.x.x.x` 地址。仅本机浏览器验证时
可使用 `127.0.0.1`。

### 4.2 初始化配置

在 Ubuntu 的项目根目录执行：

```bash
make init
nano .env
```

完成以下配置：

1. 将所有包含 `change_me` 或 `replace_with` 的值替换掉。
2. `YOOX_JWT_SECRET` 至少使用 32 个字符，密码和密钥应使用独立随机值。
3. 将 `YOOX_PUBLIC_HOST` 设为上一节确定的 Windows 物理网卡 IPv4 地址。
4. 将 `YOOX_RTSP_TRANSPORTS` 设为 `tcp`。Docker Desktop 的端口转发层下，设备侧
   RTSP 使用 TCP 更稳定。
5. 真机接入时填写有效的 `YOOX_CLOUD_APP_ID`、`YOOX_CLOUD_APP_KEY` 和
   `YOOX_CLOUD_APP_LICENSE`。
6. 保持 `YOOX_MQTT_USERNAME=yoox-cloud`、`YOOX_DEVICE_MQTT_USERNAME=pilot` 和
   `YOOX_DEVICE_MQTT_PASSWORD=pilot123`，这些值与当前 ACL 和 Pilot 初始化配置一致。

关键网络配置示例：

```dotenv
YOOX_PUBLIC_HOST=192.168.1.50
YOOX_RTSP_TRANSPORTS=tcp
```

如果主机有 Wi-Fi 和有线网卡，可按 [配置指南](06-配置指南.md) 分别设置
`YOOX_PUBLIC_RTSP_HOST`、`YOOX_MINIO_EXTERNAL_ENDPOINT`、`YOOX_WEBRTC_ADDITIONAL_HOSTS`
和 `YOOX_DRC_MQTT_HOST`。

### 4.3 预检

```bash
chmod 600 .env
make preflight
```

预检会检查 Docker、Compose、`.env` 必填项、JWT 长度、MQTT 固定账号和 Compose 配置。
请勿将 `.env` 发送到聊天、工单或提交到 Git。

## 5. 构建并启动服务

在 Ubuntu 的项目根目录执行：

```bash
make up
```

`make up` 会运行预检、构建应用镜像、启动核心容器，并等待健康检查。首次启动需要
下载基础镜像、Maven 和 npm 依赖，耗时取决于网络、磁盘和 Docker Desktop 可用资源。

检查容器并执行冒烟测试：

```bash
make ps
make smoke
```

核心入口：

| 功能 | 本机地址 | 局域网地址示例 |
| --- | --- | --- |
| Web 控制台 | `http://127.0.0.1:8080` | `http://192.168.1.50:8080` |
| Pilot 登录和 WebSocket 网关 | `http://127.0.0.1:9000` | `http://192.168.1.50:9000` |
| 在线 API 文档 | `http://127.0.0.1:8081` | `http://192.168.1.50:8081` |
| API 健康检查 | `http://127.0.0.1:8080/actuator/health` | `http://192.168.1.50:8080/actuator/health` |
| Swagger UI | `http://127.0.0.1:8080/swagger-ui/index.html` | `http://192.168.1.50:8080/swagger-ui/index.html` |

初始化 Web 账号为 `admin / Yoox@123456`，仅用于首次登录。开始局域网联调前应修改密码。

## 6. Windows 防火墙和局域网接入

### 6.1 需要的端口

| 端口 | 协议 | 用途 | 建议范围 |
| --- | --- | --- | --- |
| 8080 | TCP | Web 控制台和 API | 本机或受信局域网 |
| 9000 | TCP | Pilot 登录、WebSocket 和 DRC MQTT over WebSocket | 受信局域网 |
| 1883 | TCP | 设备 MQTT | 受信局域网 |
| 8554 | TCP | 设备 RTSP 推流 | 受信局域网 |
| 8000 | UDP/TCP | WebRTC ICE 媒体 | 受信局域网 |
| 9100 | TCP | MinIO S3 API，航线和媒体上下传 | 受信局域网 |
| 8081 | TCP | API 文档 | 可选 |

Windows 上已设置 `YOOX_RTSP_TRANSPORTS=tcp` 时，不需要向局域网开放 RTSP RTP/RTCP
UDP 端口 `8188/8189`。`19000`、MySQL、Redis、MinIO Console 和 EMQX Dashboard 只应保持本机访问。

### 6.2 创建防火墙规则

如果遥控器或局域网客户端无法访问端口，使用管理员 PowerShell 创建仅限私有网络和本地子网的
入站规则：

```powershell
$yooxTcpPorts = '8080','9000','1883','8554','8000','9100'
$yooxUdpPorts = '8000'

New-NetFirewallRule `
  -DisplayName 'YOOX Cloud GCS TCP' `
  -Direction Inbound `
  -Action Allow `
  -Profile Private `
  -RemoteAddress LocalSubnet `
  -Protocol TCP `
  -LocalPort $yooxTcpPorts

New-NetFirewallRule `
  -DisplayName 'YOOX Cloud GCS WebRTC UDP' `
  -Direction Inbound `
  -Action Allow `
  -Profile Private `
  -RemoteAddress LocalSubnet `
  -Protocol UDP `
  -LocalPort $yooxUdpPorts
```

如果需要从其他电脑访问 API 文档，再单独开放 `8081/tcp`。不要为了排障关闭整个 Windows
防火墙，也不要将上述端口直接暴露到公网。

### 6.3 验证局域网访问

从同一局域网的另一台 Windows 电脑执行：

```powershell
Test-NetConnection -ComputerName 192.168.1.50 -Port 8080
Test-NetConnection -ComputerName 192.168.1.50 -Port 9000
Test-NetConnection -ComputerName 192.168.1.50 -Port 1883
Test-NetConnection -ComputerName 192.168.1.50 -Port 8554
Test-NetConnection -ComputerName 192.168.1.50 -Port 9100
```

将示例 IP 替换为实际 Windows 主机地址，预期 `TcpTestSucceeded` 为 `True`。UDP 和完整媒体
链路不能仅依靠 `Test-NetConnection` 验收，还应实测 RTSP 首帧、WebRTC 播放和弱网恢复。

标准 Docker Desktop 方案已通过 Compose 发布端口到 Windows 主机，不需要另行创建
`netsh interface portproxy` 规则。

## 7. 源码构建验证

只部署容器时可跳过本节。如果在 Windows/WSL2 主机上修改了源码，应在 Ubuntu 中额外安装：

- JDK 17；
- Maven 3.8 或更高版本；
- Node.js 22 和 npm。

Java 和 Maven 可以从 Ubuntu 软件源安装：

```bash
sudo apt-get update
sudo apt-get install -y openjdk-17-jdk maven
```

Node.js 22 应使用组织认可的软件源或版本管理器安装。检查工具链：

```bash
java -version
javac -version
mvn -version
node --version
npm --version
```

完整验证：

```bash
make verify
```

`make verify` 会执行配置预检、后端 Maven 打包和前端生产构建。如果需要运行后端测试，再执行：

```bash
mvn -B test
```

## 8. 日常操作

所有命令均在 WSL2 Ubuntu 的项目根目录执行：

```bash
# 查看状态
make ps

# 持续查看最近日志
make logs

# 重建 Web 和 API
docker compose --env-file .env up -d --build web api

# 停止并移除容器，保留命名数据卷
make down

# 再次启动
make up
```

不要使用 `docker compose down -v` 进行普通停机，`-v` 会删除 MySQL、Redis、MinIO、EMQX
等命名卷中的本地数据。Docker Desktop 的工厂重置、卸载和虚拟磁盘损坏也可能导致数据丢失，
重要环境应使用数据库导出和对象存储同步做定期备份。

## 9. 可选服务和 Windows 限制

### 9.1 监控栈

```bash
make up-monitoring
```

Promtail、cAdvisor 和 Node Exporter 在 Docker Desktop 中看到的是 Linux VM 的容器日志、资源和文件系统，
不是完整的 Windows 主机指标。因此 Windows 上的 `monitoring` profile 主要用于功能验证，不应作为
Windows 主机生产监控的唯一数据源。

### 9.2 TURN

跨 NAT 的 WebRTC 需要 TURN 时：

```bash
docker compose --env-file .env --profile turn up -d
```

启用前应设置强 `YOOX_TURN_PASSWORD`，并按需开放 `3478/tcp`、`3478/udp` 和
`49160-49200/udp`。TURN 对公网地址、NAT 和防火墙有额外要求，不应在未经安全评审时直接开放。

## 10. 常见问题

### Ubuntu 中找不到 docker 命令

在 Docker Desktop 的 `Settings > Resources > WSL Integration` 中启用当前 Ubuntu，然后执行：

```powershell
wsl --shutdown
```

重新启动 Docker Desktop 和 Ubuntu。

### 显示只有 Docker Client 或无法连接 daemon

确认 Docker Desktop 已启动且 Engine 状态正常。再检查：

```bash
docker context show
docker version
docker info
```

如果之前在 Ubuntu 内安装过独立 Docker Engine，应先识别当前 context 和 daemon 来源，不要在两套
Engine 之间反复切换数据卷。

### Docker Desktop 处于 Windows containers 模式

切换到 Linux containers 后重新执行 `docker version` 和 `make preflight`。本项目的 Dockerfile 和 Compose
服务不支持 Windows 容器模式。

### Shell 脚本报 CRLF 或解释器错误

典型报错包括 `/bin/sh^M: bad interpreter`。应在 WSL 的 Linux 文件系统中重新克隆仓库，并设置：

```bash
git config --global core.autocrlf input
```

不要用会强制把 shell 脚本转为 CRLF 的 Windows 编辑器保存脚本。

### 构建时无法下载镜像或依赖

先在 Docker Desktop 的 `Settings > Resources > Proxies` 中按组织网络配置代理，再分别检查：

```bash
curl -I https://registry-1.docker.io
docker pull busybox:1.37.0-musl
docker compose --env-file .env build
```

不要将代理密码写入仓库或 Dockerfile。

### 端口被占用

使用 PowerShell 检查端口：

```powershell
Get-NetTCPConnection -State Listen |
  Where-Object LocalPort -In 8080,8081,9000,1883,8554,8000,9100 |
  Sort-Object LocalPort
```

修改 `.env` 中对应的 `YOOX_*_PORT` 后重新执行 `make up`。遥控器、APP 和防火墙配置也要
同步修改。

### 本机能访问但局域网设备不能访问

依次检查：

1. Windows 当前网络配置文件是否为 `Private`。
2. `.env` 中 `YOOX_PUBLIC_HOST` 是否是 Windows 物理网卡当前 IPv4。
3. Windows 防火墙规则是否已启用，第三方安全软件是否拦截 Docker Desktop。
4. Wi-Fi 是否启用了客户端隔离，设备和 Windows 是否处于可互通的网段。
5. `docker compose --env-file .env ps` 是否显示目标端口已发布。

### RTSP 推流成功但没有画面

确认 `.env` 包含：

```dotenv
YOOX_RTSP_TRANSPORTS=tcp
```

然后重建相关服务：

```bash
docker compose --env-file .env up -d --build --force-recreate api web mediamtx
make smoke
```

再检查 `8554/tcp`、`8000/udp`、`8000/tcp` 防火墙规则，并使用 MediaMTX 日志确认 RTSP 是否有
视频包和浏览器是否建立 WHEP 会话。

### Windows 网络切换后设备离线

切换 Wi-Fi、手机热点或有线网络后，Windows 局域网 IP 可能变化。修改 `.env` 中相关地址并执行：

```bash
docker compose --env-file .env up -d --build --force-recreate api web mediamtx
make smoke
```

遥控器云服务登录地址、防火墙网络配置文件和其他客户端地址也要同步检查。

### 服务未通过健康检查

```bash
make ps
docker compose --env-file .env logs --tail=200 api web mysql redis emqx mediamtx
```

先处理第一个不健康的依赖服务，再重新执行 `make up` 和 `make smoke`。

## 11. 部署完成标准

- `make preflight` 通过；如果修改了源码，`make verify` 也已通过。
- `make ps` 中核心长驻容器为 `running/healthy`，初始化容器成功退出。
- `make smoke` 同时通过 HTTP 和 Pilot 网关检查。
- Windows 本机浏览器可以登录 Web，API 健康状态为 `UP`。
- 局域网联调时，其他设备可以访问 Windows 主机的 Pilot、MQTT、RTSP、MinIO 和 WebRTC 端口。
- 真机测试时已完成设备登录、遥测上报、RTSP 首帧、WebRTC 播放、DRC 控制权和失联安全策略验收。

真机飞行类测试必须在授权空域、具备现场安全保障且能人工接管的条件下进行。HTTP 超时不等于
指令未执行，必须先查询任务状态，禁止盲目重发飞控指令。

## 12. 官方参考

- [Microsoft WSL 安装指南](https://learn.microsoft.com/windows/wsl/install)
- [Microsoft WSL 网络说明](https://learn.microsoft.com/windows/wsl/networking)
- [Docker Desktop for Windows 安装和系统要求](https://docs.docker.com/desktop/setup/install/windows-install/)
- [Docker Desktop WSL 2 backend](https://docs.docker.com/desktop/features/wsl/)
- [Docker Desktop WSL 2 开发建议](https://docs.docker.com/desktop/features/wsl/use-wsl/)
- [Docker Desktop 网络说明](https://docs.docker.com/desktop/features/networking/networking-how-tos/)
