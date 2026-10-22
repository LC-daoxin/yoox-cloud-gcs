# YOOX Cloud GCS Windows 10/11 客户电脑离线部署实操手册

> 状态：现场部署操作手册\
> 更新日期：2026-10-19\
> 适用环境：Windows 10/11 x64、WSL2、Docker Desktop、完整离线交付包\
> 适用业务包：`yoox-cloud-gcs-nano-<version>.tar.gz`

本手册覆盖从客户电脑环境检查、离线安装 WSL2 和 Docker Desktop，到导入业务镜像、生成运行
配置、启动服务、修改双网卡地址、切换 RTSP UDP/TCP、重启后恢复服务、开放 Windows 防火墙和
排查 HTTP 502 的完整流程。命令分为 **Windows PowerShell** 和 **Ubuntu/WSL Bash** 两类，执行前
必须先确认当前提示符，不能混用。

本文以一次实际部署为贯穿示例：

| 项目 | 示例值 | 现场要求 |
| --- | --- | --- |
| Windows 用户 | `a207` | 按客户实际用户名替换 |
| 离线包目录 | `C:\Users\a207\Desktop\无人机` | 按实际目录替换 |
| 业务归档 | `yoox-cloud-gcs-nano-20261015-aa0e868-worktree-updated.tar.gz` | 必须使用实际文件名 |
| 遥控器侧网卡 IP | `134.9.16.154/24` | 遥控器必须可达 |
| 遥控器 IP | `134.9.16.61` | 防火墙规则可精确限制到该 IP |
| 上网/管理网卡 IP | `10.19.244.197/24` | 以现场 `ipconfig` 为准 |
| WSL 安装包 | `wsl.2.9.12.0.x64.msi` | 使用交付目录中的实际文件 |
| Ubuntu 根文件系统 | `Ubuntu-empty.tar` | 使用交付目录中的实际文件 |
| Docker Desktop 安装包 | `Docker Desktop Installer.exe` | 使用交付目录中的实际文件 |

Windows x64 通过 Docker Desktop 内置 QEMU 运行交付包中的 `linux/arm64` 镜像。这种环境适合
接口验证、演示和受控联调，不能替代 Jetson 上的性能、GPU、弱网或飞行安全验收。

## 1. 部署前规则

### 1.1 区分 PowerShell 与 Ubuntu Bash

Windows PowerShell 提示符示例：

```text
PS C:\Windows\system32>
```

Ubuntu/WSL Bash 提示符示例：

```text
root@DESKTOP-XXXX:/root/yoox-cloud-gcs#
```

以下命令只能在 Windows 管理员 PowerShell 中执行：

- `Get-NetTCPConnection`、`Get-NetIPAddress`、`Get-NetFirewallRule`；
- `New-NetFirewallRule`、`Set-NetFirewallProfile`；
- `dism.exe`、`msiexec.exe`；
- `Test-NetConnection`、`ipconfig`。

以下命令只能在 Ubuntu/WSL Bash 中执行：

- `docker compose`、`docker image inspect`、`docker run`；
- `grep`、`sed`、`awk`、`tar`、`gunzip`；
- `bash scripts/preflight.sh`、`bash scripts/smoke-test.sh`。

如果误在 Ubuntu 中执行 PowerShell 命令，会出现 `command not found`。此时输入 `exit` 返回
PowerShell；不要按照 Ubuntu 的提示安装名为 `wsl` 的 Linux 软件包。

### 1.2 数据与安全约束

- 不执行 `docker compose down -v`，否则会删除 MySQL、Redis、MinIO 和 EMQX 数据卷；
- 不执行 `docker system prune -a`，否则可能删除离线镜像；
- 启动时始终保留 `--no-build --pull never`，禁止源码构建和联网拉取；
- 不把完整 `.env`、密码、License 或业务归档发到公开聊天、工单或网盘；
- Windows 防火墙只对受信网段或确定的遥控器 IP 放行，不要长期关闭防火墙；
- 真机飞行测试必须在授权空域、有现场安全保障且能够人工接管的条件下进行。

## 2. 检查离线交付物

客户电脑的离线目录至少应包含：

```text
18-Windows10-11本地构建与部署.md
Docker Desktop Installer.exe
Ubuntu-empty.tar
wsl.2.9.12.0.x64.msi
yoox-cloud-gcs-nano-<version>.tar.gz
```

NVIDIA Windows 驱动不是本次 Docker Desktop 核心部署的必需项。只有现场明确需要 Windows GPU
功能并完成驱动兼容性评审时才安装，不要为了运行本离线包而默认安装或升级显卡驱动。

在 Windows PowerShell 中列出文件完整名称：

```powershell
$bundleDir = Join-Path ([Environment]::GetFolderPath('Desktop')) '无人机'
Get-ChildItem -LiteralPath $bundleDir | Format-List Name,FullName,Length
```

文件资源管理器可能隐藏 `.tar`、`.gz`、`.msi` 等扩展名，后续命令必须使用 PowerShell 输出的
完整名称。

## 3. 检查 Windows、硬件和网络

### 3.1 打开管理员 PowerShell

1. 右键 Windows“开始”按钮；
2. 选择“Windows PowerShell（管理员）”或“终端（管理员）”；
3. 在 UAC 提示中选择“是”；
4. 确认窗口标题包含“管理员”。

### 3.2 执行环境检查

```powershell
Write-Host "`n=== Windows ==="
Get-CimInstance Win32_OperatingSystem |
  Select-Object Caption,Version,OSArchitecture |
  Format-List

Write-Host "`n=== CPU 与虚拟化 ==="
Get-CimInstance Win32_Processor |
  Format-List Name,VirtualizationFirmwareEnabled,SecondLevelAddressTranslationExtensions,NumberOfCores,NumberOfLogicalProcessors

Write-Host "`n=== 内存 ==="
Get-CimInstance Win32_ComputerSystem |
  Select-Object @{N='MemoryGB';E={[math]::Round($_.TotalPhysicalMemory / 1GB, 1)}} |
  Format-List

Write-Host "`n=== C 盘空间 ==="
Get-PSDrive C |
  Select-Object @{N='UsedGB';E={[math]::Round($_.Used / 1GB, 1)}},@{N='FreeGB';E={[math]::Round($_.Free / 1GB, 1)}} |
  Format-List

Write-Host "`n=== WSL 功能 ==="
Get-WindowsOptionalFeature -Online -FeatureName Microsoft-Windows-Subsystem-Linux |
  Format-List FeatureName,State

Write-Host "`n=== 虚拟机平台功能 ==="
Get-WindowsOptionalFeature -Online -FeatureName VirtualMachinePlatform |
  Format-List FeatureName,State

Write-Host "`n=== WSL 状态 ==="
wsl --version
wsl --status
wsl -l -v

Write-Host "`n=== Docker ==="
Get-Command docker -ErrorAction SilentlyContinue | Select-Object Source
Get-Process 'Docker Desktop' -ErrorAction SilentlyContinue | Select-Object ProcessName,Id

Write-Host "`n=== IPv4 ==="
ipconfig
```

最低要求：

- Windows 10/11 x64；
- `VirtualizationFirmwareEnabled=True`；
- `SecondLevelAddressTranslationExtensions=True`；
- 至少 4 核 CPU、8 GB 内存，建议 16 GB；
- C 盘建议至少保留 20 GB，本次示例实际预留约 188 GB；
- 当前物理网卡地址明确，不能选择 `vEthernet`、Docker 或 WSL 的 `172.x.x.x` 地址。

尚未安装新版 WSL 时，`wsl --version` 可能打印帮助文本。这不是业务故障，继续执行第 4 节的
离线 WSL 安装。

## 4. 离线安装 WSL2

### 4.1 启用 Windows 功能并安装 WSL MSI

在管理员 PowerShell 中执行，先按现场路径修改 `$bundleDir`：

```powershell
$bundleDir = 'C:\Users\a207\Desktop\无人机'

dism.exe /online /enable-feature /featurename:Microsoft-Windows-Subsystem-Linux /all /norestart
dism.exe /online /enable-feature /featurename:VirtualMachinePlatform /all /norestart

$wslMsi = Join-Path $bundleDir 'wsl.2.9.12.0.x64.msi'
$process = Start-Process msiexec.exe -Wait -PassThru -ArgumentList @(
  '/i',
  "`"$wslMsi`"",
  '/passive',
  '/norestart'
)
Write-Host 'WSL MSI 退出码：' $process.ExitCode
```

两条 DISM 命令应显示“操作成功完成”。MSI 退出码 `0` 表示成功，`3010` 表示成功但必须重启。
完成后保存其他工作并正常重启 Windows。

### 4.2 重启后验证 WSL

重新打开 PowerShell：

```powershell
wsl --version
wsl --status
wsl -l -v
```

预期 WSL 版本不低于 2.1.5，默认版本为 2。尚未导入 Ubuntu 时，提示“没有已安装的分发”是正常
状态。

### 4.3 导入离线 Ubuntu

以下操作会把 Ubuntu 安装到当前 Windows 用户的本地应用数据目录。先检查是否已存在同名发行版
或目录，存在时不要覆盖：

```powershell
$bundleDir = 'C:\Users\a207\Desktop\无人机'
$ubuntuDir = Join-Path $env:LOCALAPPDATA 'WSL\Ubuntu'

wsl -l -v
Test-Path $ubuntuDir
```

确认没有现有 `Ubuntu` 发行版且目录不存在后执行：

```powershell
New-Item -ItemType Directory -Force -Path (Split-Path $ubuntuDir) | Out-Null

wsl --import Ubuntu `
  "$ubuntuDir" `
  "$bundleDir\Ubuntu-empty.tar" `
  --version 2

if ($LASTEXITCODE -ne 0) {
  throw "Ubuntu 导入失败，退出码：$LASTEXITCODE"
}

wsl --set-default Ubuntu
wsl -l -v
wsl -d Ubuntu -u root -- uname -m
wsl -d Ubuntu -u root -- sh -lc 'cat /etc/os-release | sed -n "1,4p"'
```

验收结果：

- `Ubuntu` 的 `VERSION` 为 `2`；
- `uname -m` 输出 `x86_64`；
- 显示 Ubuntu 版本信息。本次示例为 Ubuntu 20.04.6 LTS。

## 5. 离线安装 Docker Desktop

### 5.1 安装并重启 Windows

在管理员 PowerShell 中执行：

```powershell
$dockerInstaller = 'C:\Users\a207\Desktop\无人机\Docker Desktop Installer.exe'

Start-Process -FilePath $dockerInstaller `
  -Wait `
  -ArgumentList @(
    'install',
    '--accept-license',
    '--backend=wsl-2'
  )

Test-Path "$env:ProgramFiles\Docker\Docker\Docker Desktop.exe"
```

安装结束后应显示 `True`。如果安装程序提示必须重启，选择 `Close and restart`；不要在要求重启
时直接尝试启动 Docker Engine。

### 5.2 首次启动与 WSL Integration

Windows 重启后：

1. 从开始菜单打开 Docker Desktop；
2. 接受许可协议；
3. Docker 账号登录不是本地部署必需项，可选择 `Skip`；
4. 等待左下角显示 `Engine running`；
5. 进入 `Settings > Resources > WSL Integration`；
6. 开启 `Enable integration with my default WSL distro`；
7. 开启 `Ubuntu`；
8. 点击 `Apply & restart`，再次等待 `Engine running`。

Docker Desktop 必须使用 Linux containers 和 WSL 2 backend。不要在 Ubuntu 中另行安装
`docker.io` 或 Docker Engine。

### 5.3 从 Ubuntu 验证 Docker

在 PowerShell 中执行：

```powershell
wsl -d Ubuntu -u root -- docker version
wsl -d Ubuntu -u root -- docker compose version
wsl -d Ubuntu -u root -- docker info --format 'server={{.ServerVersion}} os={{.OSType}} arch={{.Architecture}}'
```

必须同时看到 Docker Client 和 Server，服务端 `os=linux`，架构为 `x86_64` 或 `amd64`。

## 6. 确定单网卡或双网卡地址

在 Windows PowerShell 中执行：

```powershell
ipconfig
Get-NetIPConfiguration |
  Where-Object { $_.NetAdapter.Status -eq 'Up' -and $_.IPv4Address } |
  Select-Object InterfaceAlias,@{N='IPv4';E={$_.IPv4Address.IPAddress}},IPv4DefaultGateway
```

本次双网卡示例：

| 用途 | Windows IP | 对端/网关 |
| --- | --- | --- |
| 遥控器和设备网段 | `134.9.16.154/24` | 遥控器 `134.9.16.61` |
| 上网和管理网段 | `10.19.244.197/24` | 网关 `10.19.244.155` |

同一台 Windows 主机的 Docker 端口发布到 `0.0.0.0` 后，两个物理网卡都可以作为入口，但返回给
设备的绝对地址必须是设备可达的网卡地址。设备专用网卡若不需要跨网段路由，通常不应再配置第二
个默认网关和 DNS，以免形成两个默认路由；是否移除必须先与现场网络管理员确认。

## 7. 复制并解压业务离线包

### 7.1 进入 Ubuntu

在 PowerShell 中执行：

```powershell
wsl -d Ubuntu -u root
```

后续本节命令全部在 Ubuntu Bash 中执行。

### 7.2 复制归档到 WSL 文件系统

```bash
set -eu

WINDOWS_ARCHIVE='/mnt/c/Users/a207/Desktop/无人机/yoox-cloud-gcs-nano-20261015-aa0e868-worktree-updated.tar.gz'
WORK_DIR='/root/yoox-offline'

test -f "$WINDOWS_ARCHIVE"
test -r "$WINDOWS_ARCHIVE"

BUNDLE_NAME="${WINDOWS_ARCHIVE##*/}"
mkdir -p "$WORK_DIR"
cp "$WINDOWS_ARCHIVE" "$WORK_DIR/$BUNDLE_NAME"

cd "$WORK_DIR"
PACKAGE_DIR="$(tar -tzf "$BUNDLE_NAME" |
  sed -n 's|^\([^/][^/]*\)/.*|\1|p' |
  head -n 1)"

test -n "$PACKAGE_DIR"
printf '归档根目录：%s\n' "$PACKAGE_DIR"

tar xzf "$BUNDLE_NAME"
cd "$PACKAGE_DIR"
```

从 Windows 挂载目录复制约 1.5 GB 文件并解压可能需要几分钟。GNU tar 提示
`Ignoring unknown extended header keyword` 时，只要命令最终成功即可继续。

### 7.3 验证包结构

```bash
test -f VERSION
test -f IMAGE-MANIFEST.txt
test -f .env.template
test -f deploy/compose.yml
test -f deploy/scripts/preflight.sh
test -f deploy/scripts/smoke-test.sh
test -n "$(find images -maxdepth 1 -name '*.tar.gz' -print -quit)"

printf '版本：'
cat VERSION
printf '镜像数量：'
grep -cve '^[[:space:]]*$' IMAGE-MANIFEST.txt
printf '解压目录大小：'
du -sh .
```

样例包应显示版本 `nano-20261015-aa0e868-worktree` 和 17 个镜像。归档文件名中的 `updated`
不是镜像版本的一部分，运行版本必须读取包内 `VERSION`。

## 8. 导入并验证 ARM64 镜像

### 8.1 导入全部镜像

仍在归档解压目录中执行：

```bash
set -euo pipefail

TOTAL="$(find images -maxdepth 1 -name '*.tar.gz' | wc -l)"
COUNT=0

for image_archive in images/*.tar.gz; do
  COUNT=$((COUNT + 1))
  printf '\n[%d/%d] 加载 %s\n' "$COUNT" "$TOTAL" "$image_archive"
  gunzip -c "$image_archive" | docker load
done
```

镜像导入可能需要 10 至 30 分钟。期间不要关闭 Docker Desktop、Ubuntu 窗口或让电脑休眠。

### 8.2 验证镜像清单

```bash
while IFS= read -r image; do
  [ -n "$image" ] || continue
  docker image inspect "$image" >/dev/null
  printf 'OK  %s\n' "$image"
done < IMAGE-MANIFEST.txt

printf '已验证镜像数：'
grep -cve '^[[:space:]]*$' IMAGE-MANIFEST.txt
```

所有声明的镜像都必须显示 `OK`。

### 8.3 验证 QEMU ARM64 模拟

```bash
docker run --rm \
  --pull=never \
  --platform linux/arm64 \
  redis:7.4.9-alpine \
  uname -m
```

首次模拟启动可能需要 1 至 3 分钟，最终必须输出 `aarch64`。如果命令粘贴后停留在多行输入状态，
确认最后一行没有反斜杠，再按一次回车。超过 5 分钟无输出时按 `Ctrl+C`，检查 Docker Desktop
是否仍为 `Engine running`。

## 9. 生成部署目录和 Windows 兼容配置

### 9.1 创建运行目录和 `.env`

仍在归档解压目录中执行：

```bash
set -eu

PACKAGE_VERSION="$(tr -d '\r\n' < VERSION)"
DEPLOY_DIR='/root/yoox-cloud-gcs'
WINDOWS_LAN_IP='134.9.16.154'

if [ -e "$DEPLOY_DIR" ]; then
  printf '部署目录已存在，请先确认现有环境：%s\n' "$DEPLOY_DIR" >&2
  exit 1
fi

cp -a deploy "$DEPLOY_DIR"
cp .env.template "$DEPLOY_DIR/.env"
chmod +x "$DEPLOY_DIR/scripts/preflight.sh" "$DEPLOY_DIR/scripts/smoke-test.sh"
chmod 600 "$DEPLOY_DIR/.env"
chown -R root:root "$DEPLOY_DIR"

sed -i "s|^YOOX_PUBLIC_HOST=.*|YOOX_PUBLIC_HOST=$WINDOWS_LAN_IP|" "$DEPLOY_DIR/.env"
sed -i "s|^YOOX_VERSION=.*|YOOX_VERSION=$PACKAGE_VERSION|" "$DEPLOY_DIR/.env"
sed -i 's|^YOOX_RTSP_TRANSPORTS=.*|YOOX_RTSP_TRANSPORTS=tcp|' "$DEPLOY_DIR/.env"
```

Windows Docker Desktop 默认使用 RTSP TCP。只有现场明确要求测试 UDP，并同时开放 RTP/RTCP
端口时，才按第 14 节切换。

### 9.2 创建 Windows x64 兼容覆盖文件

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

该文件只用于 Windows x64/QEMU 环境：Redis 需要忽略模拟器误报的 ARM64 COW 警告，Java API
需要更长的首次启动宽限期。原生 Jetson/ARM64 不使用此覆盖文件。

### 9.3 检查配置

```bash
cd "$DEPLOY_DIR"
export DOCKER_DEFAULT_PLATFORM=linux/arm64

printf '\n=== 非敏感配置 ===\n'
grep -E '^(COMPOSE_PROJECT_NAME|YOOX_PUBLIC_HOST|YOOX_VERSION|YOOX_RTSP_TRANSPORTS)=' .env

printf '\n=== 尚未替换的实际配置项 ===\n'
awk -F= '!/^[[:space:]]*#/ && /change_me|replace_with/ {print NR ":" $1}' .env

printf '\n=== JWT 长度 ===\n'
awk -F= '$1 == "YOOX_JWT_SECRET" {print length($2)}' .env

bash scripts/preflight.sh .env

docker compose --env-file .env \
  -f compose.yml \
  -f compose.windows-x64.yml \
  config --quiet
```

要求：

- 没有尚未替换的实际配置项；
- `YOOX_JWT_SECRET` 至少 32 个字符；
- `YOOX_MQTT_USERNAME=yoox-cloud`；
- `YOOX_DEVICE_MQTT_USERNAME=pilot`；
- `YOOX_DEVICE_MQTT_PASSWORD=pilot123`；
- 真机接入时，云端 App ID、App Key 和 License 有效；
- `YOOX_RTSP_TRANSPORTS` 符合现场传输方案；
- `preflight` 和 Compose 配置检查均通过。

不要把完整 `.env` 输出到聊天或工单。

## 10. 配置双网卡地址

### 10.1 地址角色

双网卡环境需要分别考虑设备侧和浏览器侧地址：

| 配置 | 用途 | 示例选择 |
| --- | --- | --- |
| `YOOX_PUBLIC_HOST` | 默认对外地址 | 遥控器主要从 `134.9.16.154` 接入时填该地址 |
| `YOOX_PUBLIC_RTSP_HOST` | 下发给设备的 RTSP 推流地址 | `134.9.16.154` |
| `YOOX_MINIO_EXTERNAL_ENDPOINT` | 设备下载 KMZ、上传媒体 | `http://134.9.16.154:9100` |
| `YOOX_WEBRTC_ADDITIONAL_HOSTS` | MediaMTX 对浏览器公布的 ICE 地址 | `134.9.16.154,10.19.244.197` |
| `YOOX_DRC_MQTT_HOST` | 浏览器 DRC MQTT over WebSocket 地址 | 选择实际运行驾驶舱浏览器所在网段 |
| `YOOX_MQTT_ADVERTISED_FOLLOW_REQUEST_HOST` | 登录返回的 MQTT 地址跟随本次请求主机名 | 双网卡保持 `true` |

如果驾驶舱浏览器位于管理网段 `10.19.244.0/24`，把 `YOOX_DRC_MQTT_HOST` 设为
`10.19.244.197`；如果驾驶舱运行在遥控器侧浏览器，则设为 `134.9.16.154`。

### 10.2 安全写入或更新 `.env`

```bash
cd /root/yoox-cloud-gcs
cp .env ".env.bak.$(date +%Y%m%d-%H%M%S)"

set_env() {
  key="$1"
  value="$2"
  if grep -q "^${key}=" .env; then
    sed -i "s|^${key}=.*|${key}=${value}|" .env
  elif grep -q "^#[[:space:]]*${key}=" .env; then
    sed -i "s|^#[[:space:]]*${key}=.*|${key}=${value}|" .env
  else
    printf '%s=%s\n' "$key" "$value" >> .env
  fi
}

set_env YOOX_PUBLIC_HOST 134.9.16.154
set_env YOOX_PUBLIC_RTSP_HOST 134.9.16.154
set_env YOOX_MINIO_EXTERNAL_ENDPOINT http://134.9.16.154:9100
set_env YOOX_WEBRTC_ADDITIONAL_HOSTS 134.9.16.154,10.19.244.197
set_env YOOX_DRC_MQTT_HOST 134.9.16.154
set_env YOOX_MQTT_ADVERTISED_FOLLOW_REQUEST_HOST true

grep -E '^(YOOX_PUBLIC_HOST|YOOX_PUBLIC_RTSP_HOST|YOOX_MINIO_EXTERNAL_ENDPOINT|YOOX_WEBRTC_ADDITIONAL_HOSTS|YOOX_DRC_MQTT_HOST|YOOX_MQTT_ADVERTISED_FOLLOW_REQUEST_HOST)=' .env
```

修改主机地址后不能只执行 `docker compose restart`；`restart` 不会重新读取 `.env`。必须按第 13
节执行 `docker compose up -d`，让受影响容器按新环境变量重新创建。

## 11. 首次启动服务

### 11.1 验证 Compose 需要的镜像

```bash
cd /root/yoox-cloud-gcs
export DOCKER_DEFAULT_PLATFORM=linux/arm64

docker compose --env-file .env \
  -f compose.yml \
  -f compose.windows-x64.yml \
  config --images |
  sort -u

while IFS= read -r image; do
  [ -n "$image" ] || continue
  docker image inspect "$image" >/dev/null
  printf 'OK  %s\n' "$image"
done < <(
  docker compose --env-file .env \
    -f compose.yml \
    -f compose.windows-x64.yml \
    config --images |
    sort -u
)
```

### 11.2 启动核心服务

```bash
set +e
docker compose --env-file .env \
  -f compose.yml \
  -f compose.windows-x64.yml \
  up -d \
  --no-build \
  --pull never \
  --remove-orphans \
  --wait \
  --wait-timeout 600
UP_RESULT=$?
set -e

printf '启动退出码：%s\n' "$UP_RESULT"

docker compose --env-file .env \
  -f compose.yml \
  -f compose.windows-x64.yml \
  ps -a
```

ARM64 模拟下首次启动通常需要 5 至 10 分钟。正常状态：

- `api`、`web`、`api-portal` 为 `Up ... (healthy)`；
- `mysql`、`redis`、`minio`、`emqx`、`mediamtx` 为 `Up ... (healthy)`；
- `mysql-init`、`minio-init`、`emqx-init` 为 `Exited (0)`；
- 启动退出码为 `0`。

三个 `init` 容器是一次性任务，`Exited (0)` 表示成功，不需要保持运行。

### 11.3 烟雾测试

```bash
bash scripts/smoke-test.sh .env
```

预期最后显示：

```text
YOOX Cloud GCS HTTP smoke test passed: http://127.0.0.1:8080
YOOX Pilot gateway smoke test passed: http://127.0.0.1:9000
```

### 11.4 访问入口

以遥控器网卡 `134.9.16.154` 为例：

| 功能 | 地址 |
| --- | --- |
| Web 控制台 | `http://134.9.16.154:8080` |
| Pilot/遥控器入口 | `http://134.9.16.154:9000` |
| Pilot 健康检查 | `http://134.9.16.154:9000/actuator/health` |
| API Portal | `http://134.9.16.154:8081` |
| Swagger UI | `http://134.9.16.154:8080/swagger-ui/index.html` |

初始 Web 账号为 `admin / Yoox@123456`。首次登录后由客户本人立即修改密码，不要在远程聊天中
发送新密码。

## 12. 配置 Windows 防火墙

### 12.1 创建受限放行规则

以下命令在 Windows 管理员 PowerShell 中执行。示例只允许遥控器 `134.9.16.61` 访问，不向
其他网段或公网开放：

```powershell
New-NetFirewallRule `
  -DisplayName 'YOOX Cloud GCS TCP - Controller LAN' `
  -Direction Inbound `
  -Action Allow `
  -Protocol TCP `
  -LocalAddress 134.9.16.154 `
  -LocalPort 8080,8081,9000,1883,8554,8000,9100 `
  -RemoteAddress 134.9.16.61 `
  -Profile Any `
  -EdgeTraversalPolicy Allow

New-NetFirewallRule `
  -DisplayName 'YOOX Cloud GCS UDP - Controller LAN' `
  -Direction Inbound `
  -Action Allow `
  -Protocol UDP `
  -LocalAddress 134.9.16.154 `
  -LocalPort 8000,8188,8189 `
  -RemoteAddress 134.9.16.61 `
  -Profile Any `
  -EdgeTraversalPolicy Allow
```

如果有多个受信设备，把 `-RemoteAddress` 改为经过现场评审的网段，例如
`134.9.16.0/24`。不要改成 `Any`。

### 12.2 验证监听和本机直连

```powershell
Get-NetTCPConnection -LocalPort 9000 -State Listen |
  Format-Table LocalAddress,LocalPort,OwningProcess

Test-NetConnection 134.9.16.154 -Port 9000 -InformationLevel Detailed

curl.exe --noproxy '*' --connect-timeout 5 -i `
  http://134.9.16.154:9000/actuator/health

netstat -ano | findstr ':9000'
```

预期 `TcpTestSucceeded=True`、HTTP 返回 `200`，并看到 `0.0.0.0:9000` 或 `[::]:9000`
处于 `LISTENING`。

### 12.3 防火墙开启时遥控器无法访问

如果关闭 Windows 防火墙后遥控器立即能访问，说明服务与链路正常，问题是防火墙策略。不要把
“永久关闭防火墙”作为解决方案，应重新启用防火墙并检查本地规则是否允许生效：

```powershell
Set-NetFirewallProfile -Profile Domain,Private,Public -Enabled True

Get-NetFirewallProfile |
  Format-Table Name,Enabled,DefaultInboundAction,AllowInboundRules,AllowLocalFirewallRules
```

如果 `AllowLocalFirewallRules=False`，本地创建的规则被组策略忽略，需要客户网络管理员调整组策略。
如果该值允许本地规则但仍被拦截，检查是否存在优先级更高的显式阻止规则，以及火绒等第三方安全
软件的网络防护日志。

### 12.4 确认请求是否到达 Windows

在管理员 PowerShell 中执行：

```powershell
pktmon filter remove
pktmon filter add YOOX9000 -t TCP -p 9000
pktmon start --etw -m real-time
```

保持窗口运行，在遥控器浏览器访问一次健康接口，然后按 `Ctrl+C` 并清理过滤器：

```powershell
pktmon stop
pktmon filter remove
```

- 能看到来自遥控器 IP 的数据包：请求已到达 PC，检查 Windows/第三方防火墙；
- 完全看不到请求：检查遥控器代理/VPN、子网掩码、交换机隔离、网线和路由。

## 13. 修改 `.env` 并使配置生效

### 13.1 修改前备份

```bash
cd /root/yoox-cloud-gcs
cp .env ".env.bak.$(date +%Y%m%d-%H%M%S)"
```

### 13.2 修改公共 IP

```bash
sed -i \
  's/^YOOX_PUBLIC_HOST=.*/YOOX_PUBLIC_HOST=134.9.16.154/' \
  .env

grep -E '^(YOOX_PUBLIC_HOST|YOOX_VERSION|YOOX_RTSP_TRANSPORTS)=' .env
```

### 13.3 修改后检查和应用

```bash
export DOCKER_DEFAULT_PLATFORM=linux/arm64

bash scripts/preflight.sh .env

docker compose --env-file .env \
  -f compose.yml \
  -f compose.windows-x64.yml \
  config --quiet

docker compose --env-file .env \
  -f compose.yml \
  -f compose.windows-x64.yml \
  up -d \
  --no-build \
  --pull never \
  --remove-orphans \
  --wait \
  --wait-timeout 600

docker compose --env-file .env \
  -f compose.yml \
  -f compose.windows-x64.yml \
  ps -a

bash scripts/smoke-test.sh .env
```

必须使用 `up -d` 重新创建配置发生变化的容器。`docker compose restart` 只重启现有容器，不会
重新加载 `.env`。

## 14. 切换 RTSP TCP 或 UDP

### 14.1 Windows 默认使用 TCP

Windows Docker Desktop 的 RTSP UDP 转发可能丢包或不稳定，默认保持：

```text
YOOX_RTSP_TRANSPORTS=tcp
```

切换为 TCP：

```bash
cd /root/yoox-cloud-gcs
cp .env ".env.bak.$(date +%Y%m%d-%H%M%S)"
sed -i 's/^YOOX_RTSP_TRANSPORTS=.*/YOOX_RTSP_TRANSPORTS=tcp/' .env
```

### 14.2 按现场要求测试 UDP

仅使用 UDP：

```bash
sed -i 's/^YOOX_RTSP_TRANSPORTS=.*/YOOX_RTSP_TRANSPORTS=udp/' .env
```

允许 UDP 优先并回退 TCP：

```bash
sed -i 's/^YOOX_RTSP_TRANSPORTS=.*/YOOX_RTSP_TRANSPORTS=udp,tcp/' .env
```

使用 UDP 时必须发布并放行：

- TCP `8554`：RTSP 控制连接；
- UDP `8188`：RTP；
- UDP `8189`：RTCP；
- UDP/TCP `8000`：WebRTC ICE。

### 14.3 重建 MediaMTX 使传输协议生效

```bash
export DOCKER_DEFAULT_PLATFORM=linux/arm64

bash scripts/preflight.sh .env

docker compose --env-file .env \
  -f compose.yml \
  -f compose.windows-x64.yml \
  config --quiet

docker compose --env-file .env \
  -f compose.yml \
  -f compose.windows-x64.yml \
  up -d \
  --no-deps \
  --no-build \
  --pull never \
  --force-recreate \
  --wait \
  --wait-timeout 180 \
  mediamtx

docker compose --env-file .env \
  -f compose.yml \
  -f compose.windows-x64.yml \
  ps mediamtx
```

切换协议后必须重新验证真机推流、直播首帧、持续播放和弱网恢复。UDP 出现无法推流、花屏、卡顿
或断流时，立即改回 `tcp` 并按本节重新创建 MediaMTX。

## 15. Windows 重启后的服务恢复

### 15.1 启动顺序

1. 登录 Windows；
2. 启动 Docker Desktop；
3. 等待 `Engine running`；
4. 打开 PowerShell 并进入 Ubuntu：

```powershell
wsl -d Ubuntu -u root
```

5. 在 Ubuntu 中检查服务：

```bash
export DOCKER_DEFAULT_PLATFORM=linux/arm64
cd /root/yoox-cloud-gcs

docker compose --env-file .env \
  -f compose.yml \
  -f compose.windows-x64.yml \
  ps -a
```

核心容器使用 `restart: unless-stopped`，Docker Engine 完全启动后通常会自动恢复。如果所有核心服务
已经 `healthy`，无需重复执行 `up`。

### 15.2 重启后出现 `Exited (127)` 或反复重启

Docker Desktop 可能在 QEMU ARM64 模拟器完全就绪前恢复容器。先重新验证模拟器：

```bash
docker run --rm \
  --pull=never \
  --platform linux/arm64 \
  redis:7.4.9-alpine \
  uname -m
```

确认输出 `aarch64` 后执行：

```bash
docker compose --env-file .env \
  -f compose.yml \
  -f compose.windows-x64.yml \
  up -d \
  --no-build \
  --pull never \
  --remove-orphans \
  --wait \
  --wait-timeout 600

docker compose --env-file .env \
  -f compose.yml \
  -f compose.windows-x64.yml \
  ps -a

bash scripts/smoke-test.sh .env
```

建议在 Docker Desktop 设置中开启 `Start Docker Desktop when you sign in`。仍应等待 Engine 完全
运行后再做业务验收，不要把 Windows 登录完成等同于容器已经健康。

## 16. 常用运维命令

每次打开新的 Ubuntu shell 后先执行：

```bash
export DOCKER_DEFAULT_PLATFORM=linux/arm64
cd /root/yoox-cloud-gcs
```

查看状态：

```bash
docker compose --env-file .env \
  -f compose.yml \
  -f compose.windows-x64.yml \
  ps -a
```

查看最近日志：

```bash
docker compose --env-file .env \
  -f compose.yml \
  -f compose.windows-x64.yml \
  logs --tail=200
```

持续查看 API 日志：

```bash
docker compose --env-file .env \
  -f compose.yml \
  -f compose.windows-x64.yml \
  logs -f api
```

停止容器但保留数据卷：

```bash
docker compose --env-file .env \
  -f compose.yml \
  -f compose.windows-x64.yml \
  down
```

重新启动：

```bash
docker compose --env-file .env \
  -f compose.yml \
  -f compose.windows-x64.yml \
  up -d \
  --no-build \
  --pull never \
  --remove-orphans \
  --wait \
  --wait-timeout 600
```

## 17. 故障排查

### 17.1 Web 返回 HTTP 502

HTTP 502 表示 Web 容器已经收到请求，但后端 API 尚未就绪。ARM64 模拟下 Java API 首次启动可能
需要 2 至 5 分钟。检查：

```bash
docker compose --env-file .env \
  -f compose.yml \
  -f compose.windows-x64.yml \
  ps -a

curl -i http://127.0.0.1:19000/actuator/health

docker compose --env-file .env \
  -f compose.yml \
  -f compose.windows-x64.yml \
  logs --tail=200 api web
```

API 为 `healthy` 且健康接口返回 `200` 后，在浏览器按 `Ctrl+F5`。超过 10 分钟仍未健康时，继续
检查 MySQL、Redis、EMQX 和 API 日志，不要删除数据卷。

### 17.2 `127.0.0.1` 可访问但物理网卡 IP 不可访问

在 Windows 管理员 PowerShell 中执行：

```powershell
Get-NetIPAddress -AddressFamily IPv4 |
  Where-Object IPAddress -eq '134.9.16.154' |
  Format-Table InterfaceAlias,IPAddress,PrefixLength

Get-NetTCPConnection -LocalPort 9000 -State Listen |
  Format-Table LocalAddress,LocalPort,OwningProcess

Test-NetConnection 134.9.16.154 -Port 9000

curl.exe --noproxy '*' -i http://134.9.16.154:9000/actuator/health
```

- 本机直连返回 `200`、遥控器失败：检查 Windows/第三方防火墙、遥控器代理和交换机隔离；
- 浏览器失败但 `curl.exe --noproxy '*'` 成功：浏览器、系统代理或 VPN 没有绕过该地址；
- IP 查询无结果：重启后静态地址未绑定；
- 端口未监听：检查 Docker Desktop 和 Compose 状态。

`134.9.16.0/24` 形式的地址看似公网地址，部分代理软件可能不会把它自动识别为局域网。浏览器或
VPN 需要显式把 `134.9.16.154` 加入直连/绕过代理列表。

### 17.3 遥控器能够被 PC ping，但遥控器无法访问 PC

PC 主动 ping 遥控器只证明 PC 到遥控器的出站路径可用，不证明遥控器能够主动建立到 PC 的 TCP
连接。必须额外确认：

1. 遥控器能够主动 ping `134.9.16.154`；
2. 遥控器 IP 和掩码正确；
3. Windows 防火墙已启用受限放行规则；
4. 火绒等安全软件没有再次拦截；
5. 交换机或无线网络没有启用客户端隔离。

### 17.4 API、MySQL、EMQX 或 MediaMTX 异常

```bash
docker compose --env-file .env \
  -f compose.yml \
  -f compose.windows-x64.yml \
  logs --tail=300 mysql redis minio emqx mediamtx api web
```

先看第一个异常依赖，不要同时反复重启全部服务。API 启动日志持续推进且容器没有重启时，应继续
等待模拟器完成启动。

### 17.5 Compose 尝试拉取或构建

必须使用：

```bash
docker compose --env-file .env \
  -f compose.yml \
  -f compose.windows-x64.yml \
  up -d --no-build --pull never
```

检查 `.env` 中的 `YOOX_VERSION` 是否与包内 `VERSION` 完全一致，并重新验证
`IMAGE-MANIFEST.txt`。

### 17.6 Ubuntu 中找不到 Docker

在 Docker Desktop 的 `Settings > Resources > WSL Integration` 中启用默认发行版和 Ubuntu，
点击 `Apply & restart`，然后执行：

```powershell
wsl --terminate Ubuntu
wsl -d Ubuntu -u root -- docker version
```

不要在 Ubuntu 中安装另一套 Docker Engine。

## 18. 交付验收清单

- [ ] Windows 10/11 x64、虚拟化和 SLAT 已启用；
- [ ] WSL 版本满足要求，Ubuntu 为 WSL2，架构为 `x86_64`；
- [ ] Docker Desktop Linux Engine 和 Ubuntu WSL Integration 正常；
- [ ] 离线包结构完整，17 个 ARM64 镜像全部导入；
- [ ] QEMU 测试输出 `aarch64`；
- [ ] `.env` 的版本、IP、凭据和 RTSP 传输配置正确；
- [ ] 双网卡地址分别按设备侧和浏览器侧用途配置；
- [ ] `compose.windows-x64.yml` 已包含 Redis 和 API 兼容项；
- [ ] `preflight` 和 Compose 配置检查通过；
- [ ] 核心长驻容器全部 `healthy`，初始化容器全部 `Exited (0)`；
- [ ] 烟雾测试通过；
- [ ] Windows 本机和遥控器都能访问需要的端口；
- [ ] Windows 防火墙保持开启，规则只允许受信遥控器或网段；
- [ ] Windows 重启后完成 Docker、QEMU、Compose 和烟雾测试复验；
- [ ] 初始 Web 密码已由客户修改；
- [ ] 未把 Windows ARM64 模拟结果当作 Jetson 性能或飞行安全验收结果。

## 19. 关联文档

- [Windows 10/11 Docker Desktop 离线镜像部署](18-Windows10-11本地构建与部署.md)
- [配置指南](06-配置指南.md)
- [部署与运维指南](08-部署运维指南.md)
- [遥控器云服务频繁断连排查](09-遥控器云服务频繁断连排查.md)
- [Nano 完全离线快速部署](13-Nano快速部署.md)
- [Nano 离线部署包说明](../offline/nano-deploy/README.md)
