# YOOX Cloud GCS Windows 10/11 Docker Desktop 离线镜像部署

> 状态：离线镜像部署手册
> 适用交付物：`yoox-cloud-gcs-nano-<version>.tar.gz`
> 样例交付物：`yoox-cloud-gcs-nano-20261015-aa0e868-worktree-updated.tar.gz`
> 更新日期：2026-10-16

本手册用于把已经打包完成的 YOOX Cloud GCS 离线镜像包运行在 Windows 10/11 x64 的
Docker Desktop 上。整个业务部署过程不使用项目源码、Maven/npm 或 Makefile 命令；所有业务
镜像只从交付归档导入。

离线包可以放在客户电脑上任意可由 WSL 读取的本地目录，不要求存在 `D:\Code`。
以下仅为样例归档位置；执行第 4 节时，必须使用实际收到的文件名和实际存放路径：

```text
D:\Code\yoox-cloud-gcs-nano-20261015-aa0e868-worktree-updated.tar.gz
```

归档内含 Compose 配置、环境模板、数据库初始化 SQL、脚本，以及 17 个 `linux/arm64`
Docker 镜像。目标镜像原本用于 Jetson Nano/Orin；Windows x64 Docker Desktop 通过内置 QEMU
进行 ARM64 模拟。因此本方案适合本地接口验证、演示和受控联调，不可替代 Jetson 上的性能、
GPU、资源、弱网或飞控安全验收。

## 1. 部署架构和限制

```text
Windows 10/11 x64
├─ Docker Desktop（WSL 2 backend、Linux containers、linux/amd64 服务端）
├─ WSL2 Ubuntu（解压、配置检查、docker load、Compose 和烟雾测试）
├─ QEMU（Docker Desktop 内置，用于运行 linux/arm64 镜像）
└─ Windows 物理网卡 IPv4（浏览器、APP 和遥控器访问入口）
```

本手册不支持：

- Windows containers 模式；
- 在 Ubuntu 内另行安装 `docker.io` 或 Docker Engine；
- Windows Server 上的 Docker Desktop；
- 把 ARM64 模拟结果当作 Jetson 交付验收结果；
- 在离线包缺镜像时自动联网拉取或从源码补构建。

包内 `install.sh` 会要求宿主机为 `aarch64`，因此不能直接在 Windows x64 的 Ubuntu WSL 中
运行。本手册使用与它等价的手工步骤，但保留架构保护，不修改 `install.sh`。

## 2. 环境准备

### 2.1 主机要求

- Windows 10/11 x64，BIOS/UEFI 已开启硬件虚拟化；
- WSL 2.1.5 或更高版本；
- Docker Desktop，使用 WSL 2 backend 和 Linux containers；
- 至少 4 核 CPU、8 GB 内存，建议 16 GB；
- C 盘建议至少预留 20 GB，归档所在磁盘和 WSL 文件系统还应各预留约 10 GB。

ARM64 模拟比原生运行慢，尤其是 MySQL、EMQX 和 Java API 首次启动。不要为此环境应用
`compose.nano.yml` 的 Nano 资源上限。

### 2.2 安装 WSL2 和 Ubuntu

使用管理员 PowerShell：

```powershell
wsl --install -d Ubuntu --web-download
wsl --update
wsl --set-default-version 2
```

`--web-download` 直接从微软下载组件，适合 Microsoft Store 不可用或普通安装长时间无进展的
环境。安装过程要求重启时，应保存工作并重启 Windows。

如果 `wsl --install` 只提示未安装 WSL 并以退出码 `50` 结束，使用管理员 PowerShell 启用组件：

```powershell
dism.exe /online /enable-feature /featurename:Microsoft-Windows-Subsystem-Linux /all /norestart
dism.exe /online /enable-feature /featurename:VirtualMachinePlatform /all /norestart
```

DISM 返回 `3010` 表示成功但必须重启。重启后再次执行：

```powershell
wsl --install -d Ubuntu --web-download
wsl --update
```

首次从开始菜单打开 Ubuntu，等待初始化完成，并按提示创建 Linux 用户名和密码。密码输入时不
显示字符是正常现象。看到 Linux shell 提示符后检查：

```powershell
wsl --version
wsl --list --verbose
wsl -d Ubuntu -- uname -m
```

Ubuntu 所在行应为 `VERSION 2`，最后一条命令在 Windows x64 上应输出 `x86_64`。

### 2.3 安装和配置 Docker Desktop

1. 安装 Docker Desktop for Windows，优先选择 `Per-user installation`。
2. 首次启动接受许可协议；账号登录不是本地运行容器的必需条件，可以选择 `Skip`。
3. 在 `Settings > General` 选择 WSL 2 backend。
4. 在 `Settings > Resources > WSL Integration` 勾选
   `Enable integration with my default WSL distro`；如果 Ubuntu 的独立开关可用，也将其打开。
5. 点击 `Apply & restart`，等待 Docker Desktop 重启完成。
6. 确认使用 Linux containers。如果菜单显示 `Switch to Linux containers`，先完成切换。
7. 等待 Dashboard 显示 Docker Engine 正常运行。

在 Ubuntu 中检查：

```bash
docker version
docker compose version
docker info --format 'server={{.ServerVersion}} os={{.OSType}} arch={{.Architecture}}'
```

命令必须同时显示 Client 和 Server；服务端应为 Linux。不要在 Ubuntu 内再安装一套 Docker
Engine，否则会造成 context、端口和数据卷归属混乱。

### 2.4 安装最小工具

离线部署不需要 Git、Java、Maven 或 Node.js。Ubuntu 通常已经具备 `tar` 和 `gzip`；烟雾
测试还需要 `curl`：

```bash
sudo apt-get update
sudo apt-get install -y curl ca-certificates
```

这一步只安装系统工具。业务镜像仍完全来自离线包。

## 3. 确定 Windows 地址

### 3.1 确定局域网 IPv4

在 PowerShell 执行：

```powershell
ipconfig
```

选择当前 Wi-Fi 或以太网物理网卡的 IPv4，例如 `192.168.88.206`。不要使用
`vEthernet (WSL)`、Docker 虚拟网卡或 WSL 内部 `172.x.x.x` 地址。该值稍后写入
`YOOX_PUBLIC_HOST`。

只在本机浏览器验证时可使用 `127.0.0.1`，但 APP、遥控器和其他电脑将无法通过该地址访问。

不要把离线包上传公共网盘；包内 `.env.template` 可能包含部署密码或设备云凭据。

## 4. 复制和解压

部署流程默认提供的归档文件完整，不执行 SHA256 哈希校验。以下命令均在 **Ubuntu 终端**中
执行，不是在 PowerShell 中执行。

### 4.1 按实际位置设置离线包路径

1. 在 Windows 文件资源管理器中找到收到的 `.tar.gz` 离线包，使用“复制文件地址”，或在文件
   属性中确认完整路径。路径必须包含文件名，不能只填所在文件夹。
2. 把下面 `wslpath -u` 后单引号内的样例，替换为该文件的 **Windows 完整路径**。
   如果复制结果自带外层双引号，去掉这对双引号，但保留命令中的单引号。
3. `wslpath -u` 会转换为当前 Ubuntu 可用的 Linux 路径，客户不必把文件移动到 `D:\Code`。
   路径中的空格和中文应原样保留，不要去掉引号。

```bash
# 必须替换为客户电脑上的实际完整路径；下面只是示例。
WINDOWS_ARCHIVE="$(wslpath -u 'D:\Code\yoox-cloud-gcs-nano-20261015-aa0e868-worktree-updated.tar.gz')" || {
  printf '路径转换失败，请检查 Windows 完整路径。\n' >&2
  exit 1
}

[ -f "$WINDOWS_ARCHIVE" ] && [ -r "$WINDOWS_ARCHIVE" ] || {
  printf '找不到或无法读取离线包：%s\n请检查盘符、目录、完整文件名和 WSL 访问权限。\n' "$WINDOWS_ARCHIVE" >&2
  exit 1
}

BUNDLE_NAME="${WINDOWS_ARCHIVE##*/}"
WORK_DIR="$HOME/yoox-offline"
printf '将使用离线包：%s\n' "$WINDOWS_ARCHIVE"
```

例如，在 WSL 默认挂载配置下且相应盘符已挂载时，`C:\Users\客户\Downloads` 对应
`/mnt/c/Users/客户/Downloads`，`D:\交付 包` 对应 `/mnt/d/交付 包`，文件名接在目录后。
上述命令使用 `wslpath` 自动转换，无需手动拼接 `/mnt/<盘符>/`。

如果提示文件不存在，先修正路径并重新执行本节；不要继续复制或加载镜像。此处仅检查文件是否
存在且可读，不做 SHA256 校验。网络共享、尚未在 WSL 中挂载的移动盘或仅在线的云盘文件，建议
先复制或下载到本机可读取的目录。

归档文件名可能带 `-updated`，也可能因交付版本不同而变化。以实际收到的文件名为准，**不要
根据归档文件名猜测镜像版本**：第 6 节会从包内 `VERSION` 读取正确的 `YOOX_VERSION`。

### 4.2 复制和解压离线包

把大文件复制到 WSL 文件系统后再解压和加载，避免长期从 Windows 挂载目录（例如 `/mnt/c`
或 `/mnt/d`）读取镜像层：

```bash
mkdir -p "$WORK_DIR"
cp "$WINDOWS_ARCHIVE" "$WORK_DIR/$BUNDLE_NAME"
cd "$WORK_DIR"
PACKAGE_DIR="$(tar -tzf "$BUNDLE_NAME" | sed -n 's|^\([^/][^/]*\)/.*|\1|p' | head -n 1)"
[ -n "$PACKAGE_DIR" ] || {
  printf '无法识别归档根目录: %s\n' "$BUNDLE_NAME" >&2
  exit 1
}
tar xzf "$BUNDLE_NAME"
cd "$PACKAGE_DIR"
```

GNU tar 可能输出多条
`Ignoring unknown extended header keyword 'LIBARCHIVE.xattr.com.apple.provenance'`。这是打包端写入的
macOS 扩展属性提示，只要命令退出码为 `0` 且下列文件结构存在，就不影响部署。

确认包结构：

```bash
test -f VERSION
test -f IMAGE-MANIFEST.txt
test -f .env.template
test -f deploy/compose.yml
test -f deploy/scripts/preflight.sh
test -f deploy/scripts/smoke-test.sh
test -n "$(find images -maxdepth 1 -name '*.tar.gz' -print -quit)"

printf '版本: '; cat VERSION
printf '镜像数量: '; grep -cve '^[[:space:]]*$' IMAGE-MANIFEST.txt
```

样例包应显示版本 `nano-20261015-aa0e868-worktree` 和 17 个镜像。

## 5. 导入并验证 ARM64 镜像

加载 `images/` 下的镜像归档：

```bash
for image_archive in images/*.tar.gz; do
  printf '加载 %s\n' "$image_archive"
  gunzip -c "$image_archive" | docker load
done
```

逐项验证 `IMAGE-MANIFEST.txt` 中声明的镜像：

```bash
while IFS= read -r image; do
  [ -n "$image" ] || continue
  docker image inspect "$image" >/dev/null || {
    printf '缺少镜像: %s\n' "$image" >&2
    exit 1
  }
done < IMAGE-MANIFEST.txt
```

验证 Docker Desktop 的 ARM64 模拟器。预期输出 `aarch64`：

```bash
docker run --rm --platform linux/arm64 redis:7.4.9-alpine uname -m
```

如果出现 `exec format error`、容器立即退出或没有输出，不要继续启动整套服务，先处理第 10 节
的架构模拟问题。

## 6. 生成运行目录和配置

仍在离线包解压目录中执行：

```bash
PACKAGE_VERSION="$(tr -d '\r\n' < VERSION)"
DEPLOY_DIR="$HOME/yoox-cloud-gcs"

[ ! -e "$DEPLOY_DIR" ] || {
  printf '部署目录已存在，请先确认它是否属于现有环境：%s\n' "$DEPLOY_DIR" >&2
  exit 1
}

cp -a deploy "$DEPLOY_DIR"
cp .env.template "$DEPLOY_DIR/.env"
chmod +x "$DEPLOY_DIR/scripts/preflight.sh" "$DEPLOY_DIR/scripts/smoke-test.sh"
chmod 600 "$DEPLOY_DIR/.env"

# 如果当前 WSL 默认用户是 root，归档可能保留打包机的 UID/GID，统一改回 root。
if [ "$(id -u)" -eq 0 ]; then
  chown -R root:root "$DEPLOY_DIR"
fi
```

设置实际 Windows 物理网卡地址。下面的 IP 只是示例，必须替换：

```bash
WINDOWS_LAN_IP='192.168.88.206'

sed -i "s|^YOOX_PUBLIC_HOST=.*|YOOX_PUBLIC_HOST=$WINDOWS_LAN_IP|" "$DEPLOY_DIR/.env"
sed -i "s|^YOOX_VERSION=.*|YOOX_VERSION=$PACKAGE_VERSION|" "$DEPLOY_DIR/.env"
sed -i 's|^YOOX_RTSP_TRANSPORTS=.*|YOOX_RTSP_TRANSPORTS=tcp|' "$DEPLOY_DIR/.env"
```

检查 `.env`：

1. 所有 `change_me` 和 `replace_with` 已替换；
2. `YOOX_JWT_SECRET` 至少 32 个字符；
3. 真机接入时，设备云 App ID、App Key 和 License 有效；
4. 保持 `YOOX_MQTT_USERNAME=yoox-cloud`；
5. 保持 `YOOX_DEVICE_MQTT_USERNAME=pilot`；
6. 保持 `YOOX_DEVICE_MQTT_PASSWORD=pilot123`；
7. Windows 下保持 `YOOX_RTSP_TRANSPORTS=tcp`。

只输出非敏感配置进行确认：

```bash
grep -E '^(COMPOSE_PROJECT_NAME|YOOX_PUBLIC_HOST|YOOX_VERSION|YOOX_RTSP_TRANSPORTS)=' \
  "$DEPLOY_DIR/.env"
```

不要把完整 `.env` 粘贴到聊天、工单或公开日志。

### 6.1 创建 Windows x64 兼容覆盖文件

该离线包全部是 `linux/arm64` 镜像。在 Windows x64/QEMU 上有两个已验证的兼容差异：

- Redis 会把模拟环境误判为存在 `ARM64-COW-BUG` 并主动退出；
- Java API 首次启动约需 2 至 3 分钟，镜像内置的 60 秒健康检查宽限期不够。

仅在 **Windows x64 Docker Desktop 运行该 ARM64 包** 时创建以下覆盖文件。原生 Jetson/ARM64
部署不要使用它：

```bash
cat > "$DEPLOY_DIR/compose.windows-x64.yml" <<'YAML'
services:
  redis:
    command:
      - sh
      - -ec
      - >-
        exec redis-server --appendonly yes
        --requirepass "$${YOOX_REDIS_PASSWORD}"
        --ignore-warnings ARM64-COW-BUG

  api:
    healthcheck:
      test:
        - CMD-SHELL
        - curl -fsS http://127.0.0.1:9000/actuator/health || exit 1
      interval: 20s
      timeout: 5s
      retries: 15
      start_period: 5m
YAML
```

覆盖项只放宽模拟环境的启动等待，并未改变 API 健康端点。Redis 的警告忽略项只适用于已先通过
第 5 节一次性 `aarch64` 运行测试的 QEMU 环境。

## 7. 离线启动和验收

进入部署目录并执行预检：

```bash
cd "$DEPLOY_DIR"
export DOCKER_DEFAULT_PLATFORM=linux/arm64
bash scripts/preflight.sh .env
docker compose --env-file .env \
  -f compose.yml -f compose.windows-x64.yml config --quiet
```

列出 Compose 需要的镜像，并确认镜像均已导入：

```bash
docker compose --env-file .env \
  -f compose.yml -f compose.windows-x64.yml config --images | sort -u

while IFS= read -r image; do
  [ -n "$image" ] || continue
  docker image inspect "$image" >/dev/null || {
    printf 'Compose 缺少镜像: %s\n' "$image" >&2
    exit 1
  }
done < <(docker compose --env-file .env \
  -f compose.yml -f compose.windows-x64.yml config --images | sort -u)
```

仅使用本地镜像启动核心服务：

```bash
docker compose --env-file .env \
  -f compose.yml -f compose.windows-x64.yml up -d \
  --no-build \
  --pull never \
  --remove-orphans \
  --wait \
  --wait-timeout 600
```

这里必须保留 `--no-build --pull never`：

- `--no-build` 禁止 Compose 使用包内遗留的 `build:` 段尝试源码构建；
- `--pull never` 禁止缺镜像时联网拉取；
- 任一镜像缺失都应回到第 5 节重新加载，而不是临时构建或拉取。

检查状态并运行烟雾测试：

```bash
docker compose --env-file .env \
  -f compose.yml -f compose.windows-x64.yml ps -a
bash scripts/smoke-test.sh .env
```

ARM64 模拟首次启动可能明显慢于原生环境。不要叠加 `compose.nano.yml`，否则 Nano 的 CPU/内存
限制可能导致模拟环境持续超时。

## 8. 访问地址和端口

| 功能 | 本机地址 | 局域网地址示例 |
| --- | --- | --- |
| Web 控制台 | `http://127.0.0.1:8080` | `http://192.168.88.206:8080` |
| Pilot 网关 | `http://127.0.0.1:9000` | `http://192.168.88.206:9000` |
| API Portal | `http://127.0.0.1:8081` | `http://192.168.88.206:8081` |
| API 健康检查 | `http://127.0.0.1:8080/actuator/health` | `http://192.168.88.206:8080/actuator/health` |
| Swagger UI | `http://127.0.0.1:8080/swagger-ui/index.html` | `http://192.168.88.206:8080/swagger-ui/index.html` |

初始化 Web 账号为 `admin / Yoox@123456`，仅用于首次登录，登录后应立即修改密码。

核心端口：

| 端口 | 协议 | 用途 | 建议范围 |
| --- | --- | --- | --- |
| 8080 | TCP | Web 控制台和 API | 本机或受信局域网 |
| 9000 | TCP | Pilot、WebSocket、DRC MQTT over WebSocket | 受信局域网 |
| 1883 | TCP | 设备 MQTT | 受信局域网 |
| 8554 | TCP | RTSP | 受信局域网 |
| 8000 | UDP/TCP | WebRTC ICE | 受信局域网 |
| 9100 | TCP | MinIO S3 API | 受信局域网 |
| 8081 | TCP | API Portal | 可选 |

Windows 使用 RTSP TCP 时，不需要开放 `8188/8189` UDP。`19000`、MySQL、Redis、MinIO
Console 和 EMQX Dashboard 应保持本机访问。

仅当局域网客户端确实无法访问时，使用管理员 PowerShell 创建限定为私有网络和本地子网的规则：

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

不要关闭整个 Windows 防火墙，也不要把端口直接暴露到公网。

## 9. 日常操作和离线更新

每次打开新的 Ubuntu shell 后，先设置平台并进入部署目录：

```bash
export DOCKER_DEFAULT_PLATFORM=linux/arm64
cd "$HOME/yoox-cloud-gcs"
```

常用命令：

```bash
# 状态
docker compose --env-file .env \
  -f compose.yml -f compose.windows-x64.yml ps -a

# 最近日志
docker compose --env-file .env \
  -f compose.yml -f compose.windows-x64.yml logs --tail=200

# 持续查看 API 日志
docker compose --env-file .env \
  -f compose.yml -f compose.windows-x64.yml logs -f api

# 停止容器但保留数据卷
docker compose --env-file .env \
  -f compose.yml -f compose.windows-x64.yml down

# 使用已导入镜像重新启动
docker compose --env-file .env \
  -f compose.yml -f compose.windows-x64.yml up -d \
  --no-build --pull never --remove-orphans --wait --wait-timeout 600
```

禁止执行 `docker compose down -v`；`-v` 会删除 MySQL、Redis、MinIO、EMQX 等命名卷。

### 9.1 使用新离线包更新

1. 按第 4、5 节解压并加载新包镜像。
2. 备份现有 `$HOME/yoox-cloud-gcs/.env` 和业务数据。
3. 保留 `.env`，更新新包中的 Compose、脚本、SQL 和部署配置。
4. 把 `.env` 中 `YOOX_VERSION` 改为新包 `VERSION`。
5. 保留或按第 6.1 节重建 `compose.windows-x64.yml`。
6. 使用 `--no-build --pull never` 重新启动并执行烟雾测试。

不要通过 `docker pull`、源码构建或复制容器可写层来修复离线更新。

### 9.2 可选 TURN 和监控

包内已包含 TURN 和监控镜像，但 ARM64 模拟开销较高，默认不启用监控。按需启动时仍禁止
构建和拉取：

```bash
# TURN
docker compose --env-file .env \
  -f compose.yml -f compose.windows-x64.yml --profile turn up -d \
  --no-build --pull never --wait --wait-timeout 600

# 监控栈
docker compose --env-file .env \
  -f compose.yml -f compose.windows-x64.yml --profile monitoring up -d \
  --no-build --pull never --wait --wait-timeout 600
```

启用 TURN 前设置强密码，并评审公网地址、NAT 和防火墙配置。Docker Desktop 中的 Node
Exporter 和 cAdvisor 反映的是 Linux VM/容器，不是完整 Windows 主机指标。

## 10. 常见问题

### WSL 已安装，但 Ubuntu 命令一直无输出

从开始菜单打开 Ubuntu，完成首次初始化和 Linux 用户创建。看到 shell 提示符后再执行：

```powershell
wsl --list --verbose
wsl -d Ubuntu -- uname -m
```

如果 `wsl --list --verbose` 已显示 Ubuntu 为 `VERSION 2`，但上述命令和
`wsl --terminate Ubuntu` 都长时间无输出，检查是否仍残留最初的安装进程：

```powershell
$stuckInstall = Get-CimInstance Win32_Process |
  Where-Object {
    $_.Name -eq 'wsl.exe' -and
    $_.CommandLine -match '--install\s+-d\s+Ubuntu'
  }

$stuckInstall | Select-Object ProcessId,ParentProcessId,CommandLine
```

仅当命令行确实是上述 Ubuntu 安装、已至少 10 分钟无进展、且没有可见窗口等待用户名/密码
输入时，才结束这些安装进程；该操作不会注销或删除发行版：

```powershell
Stop-Process -Id $stuckInstall.ProcessId -Force
wsl --terminate Ubuntu
wsl -d Ubuntu -u root -- uname -m
```

随后重新从开始菜单打开 Ubuntu，补做 Linux 用户初始化。不要对其他 WSL/Docker 进程使用
`Stop-Process`。

### Ubuntu 中找不到 docker

在 Docker Desktop 的 `Settings > Resources > WSL Integration` 中启用
`Enable integration with my default WSL distro`。如果 Ubuntu 独立开关可用，也将其启用，然后
点击 `Apply & restart`。Docker Desktop 恢复后执行：

```powershell
wsl --terminate Ubuntu
wsl -d Ubuntu -- docker version
```

命令必须显示 Docker Server。不要在 Ubuntu 中安装 `docker.io`。

### Docker 只有 Client，或 daemon 无法连接

等待 Docker Desktop Dashboard 显示 Engine 正常，再检查：

```bash
docker context show
docker version
docker info
```

### ARM64 测试出现 `exec format error`

确认 Docker Desktop 使用 Linux containers，服务端架构为 `x86_64`/`amd64`，并使用显式平台：

```bash
docker run --rm --platform linux/arm64 redis:7.4.9-alpine uname -m
```

仍失败说明当前 Docker Desktop/QEMU 组合不能可靠运行该包。应改在 `aarch64` Jetson 上部署，
不要删除包内架构检查或把容器强制标记为 amd64。

### Compose 尝试拉取或构建

必须使用：

```bash
docker compose --env-file .env \
  -f compose.yml -f compose.windows-x64.yml up -d \
  --no-build --pull never
```

检查 `.env` 的 `YOOX_VERSION` 是否与包内 `VERSION` 完全一致，并重新执行第 5 节镜像清单验证。

### Redis 反复重启并提示 `ARM64-COW-BUG`

如果日志包含 `Redis will now exit to prevent data corruption` 和
`ignore-warnings ARM64-COW-BUG`，说明启动命令漏用了 Windows x64 兼容覆盖：

```bash
docker compose --env-file .env \
  -f compose.yml -f compose.windows-x64.yml logs --tail=100 redis

docker compose --env-file .env \
  -f compose.yml -f compose.windows-x64.yml up -d \
  --no-build --pull never --wait --wait-timeout 600
```

先确认第 5 节的 QEMU 测试输出 `aarch64`，再使用第 6.1 节覆盖文件。不要把此忽略项加入原生
ARM64/Jetson 的基础 Compose。

### API 在启动阶段过早变为 unhealthy

该 ARM64 Java 镜像在本次 x64 实测中约 2 至 3 分钟才完成启动。确认
`compose.windows-x64.yml` 包含 API 的 `start_period: 5m`，并且每次 Compose 命令同时传入两个
`-f` 参数。可用以下命令查看进度：

```bash
docker compose --env-file .env \
  -f compose.yml -f compose.windows-x64.yml logs -f api
```

日志持续推进且容器没有重启时应继续等待，不要因为宽限期内暂时连不上 9000 端口就删除数据卷。

### 其他健康检查超时

ARM64 模拟可能很慢，先等待并查看第一个异常依赖：

```bash
docker compose --env-file .env \
  -f compose.yml -f compose.windows-x64.yml ps -a
docker compose --env-file .env \
  -f compose.yml -f compose.windows-x64.yml \
  logs --tail=300 mysql redis minio emqx mediamtx api web
```

不要叠加 `compose.nano.yml`。检查 Docker Desktop 至少分配 8 GB 内存，并确认没有端口冲突。

### 端口被占用

在 PowerShell 检查：

```powershell
Get-NetTCPConnection -State Listen |
  Where-Object LocalPort -In 8080,8081,9000,1883,8554,8000,9100 |
  Sort-Object LocalPort
```

修改 `.env` 中对应端口后重新启动，APP、遥控器和防火墙配置也要同步更新。

### 本机可访问但局域网设备不可访问

依次检查：

1. `YOOX_PUBLIC_HOST` 是 Windows 物理网卡当前 IPv4；
2. Windows 网络配置文件为 `Private`；
3. Docker Compose 已发布目标端口；
4. 防火墙规则仅限本地子网且已启用；
5. Wi-Fi 未开启客户端隔离。

## 11. 完成标准

- WSL2 Ubuntu 已初始化，Docker Desktop Linux Engine 可用；
- `IMAGE-MANIFEST.txt` 声明的 17 个镜像全部存在；
- ARM64 模拟测试输出 `aarch64`；
- `.env` 中版本、Windows 物理网卡 IP 和 RTSP TCP 配置正确；
- Windows x64 兼容覆盖文件已包含 Redis 警告处理和 API 5 分钟启动宽限期；
- `scripts/preflight.sh` 通过；
- Compose 同时加载基础文件和 `compose.windows-x64.yml`，并使用 `--no-build --pull never` 启动；
- 核心长驻容器为 `running/healthy`，初始化容器成功退出；
- `scripts/smoke-test.sh` 通过；
- Windows 本机能够访问 Web、Pilot 和 API Portal；
- 局域网联调时，客户端可访问所需端口；
- 初始 Web 密码已修改；
- 未把 Windows ARM64 模拟结果当作 Jetson 性能或飞行安全验收结果。

真机飞行类测试必须在授权空域、具备现场安全保障且能人工接管的条件下进行。HTTP 超时不代表
指令未执行，应先查询任务状态，禁止盲目重发飞控指令。

## 12. 参考资料

- [Nano 离线部署包说明](../offline/nano-deploy/README.md)
- [Nano 快速部署](13-Nano快速部署.md)
- [配置指南](06-配置指南.md)
- [部署运维指南](08-部署运维指南.md)
- [Microsoft WSL 安装指南](https://learn.microsoft.com/windows/wsl/install)
- [Docker Desktop for Windows](https://docs.docker.com/desktop/setup/install/windows-install/)
- [Docker Desktop WSL 2 backend](https://docs.docker.com/desktop/features/wsl/)
- [Docker 多平台与 QEMU 模拟](https://docs.docker.com/build/building/multi-platform/)
- [Docker image load](https://docs.docker.com/reference/cli/docker/image/load/)
