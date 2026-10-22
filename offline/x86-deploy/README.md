# YOOX Cloud GCS Linux x86_64 完全离线包

适用 Intel/AMD 64 位机器，Docker 28.1.1 可用，需要 Docker Compose v2。
所有镜像均为 `linux/amd64`，包含业务服务、第三方依赖、可选 TURN/监控服务和全国离线地图。
本次交付的 MinIO/mc 由原 RELEASE 标签的官方源码重建为 amd64 镜像，版本保持不变；
对应源码、构建参数和说明位于 `sources/minio/`，镜像使用 `yoox/minio` / `yoox/minio-mc` 名称。
本包不包含 Docker 安装程序；请提前安装 Docker、Compose v2、bash、curl 和 unzip。
建议至少 4 核、8 GB 内存、40 GB 可用空间（含 ZIP、解压文件、镜像、地图和数据）。

## Linux 安装

1. 在 ZIP 所在目录运行 `sha256sum -c yoox-cloud-gcs-<版本>.zip.sha256`。
2. 运行 `unzip yoox-cloud-gcs-<版本>.zip`，进入解压目录。
3. 编辑 `.env.template`，填写所有 `change_me` / `replace_with` 项，包括设备云应用 ID、Key、License。
   服务密码请使用独立的随机值，JWT 密钥至少 32 字符；不要修改预设 MQTT 用户名和设备默认密码。
4. 运行 `bash install.sh <服务器局域网IP>`，如 `bash install.sh 192.168.1.100`。

安装目录为 `$HOME/yoox-cloud-gcs`。脚本检查主机与 Docker 架构，验证全国地图，导入并校验镜像，
生成 `.env`，启动服务并检查 HTTP、OpenAPI、Pilot 网关、WebSocket 和离线地图。
整个部署过程禁止在线拉取镜像或构建。

控制台：`http://<服务器IP>:8080`；Pilot 上云地址：`http://<服务器IP>:9000`；
API 文档：`http://<服务器IP>:8081`。初始账号为 `admin / Yoox@123456`，首次登录后修改密码。

更新已有安装：`bash install.sh <服务器IP> --update`，保留已有 `.env` 和数据卷，仅更新镜像版本、配置及地图。
可追加 `--with-turn` 或 `--with-monitoring` 启用包内可选服务。

## 安装停在 4/6：配置仍含占位值

若出现 `Replace every change_me/replace_with value in .env`，或新版提示“实际部署配置仍有未填写的占位值”，
请修改安装日志所列的**部署目录 `.env`**。`.env.template` 只在首次创建部署配置时复制一次；
即使首次安装失败，已生成的 `.env` 仍然存在，重跑安装会自动保留它，不会重新导入模板。
更新模式只更新其中的镜像版本，不会用命令行 IP 覆盖已有 `YOOX_PUBLIC_HOST`。

普通用户安装通常使用 `$HOME/yoox-cloud-gcs/.env`；root 或 sudo 安装可能使用
`/root/yoox-cloud-gcs/.env`，以日志中的绝对路径为准。以下命令以 root 部署为例：

```bash
sudo -i
cd /root/yoox-cloud-gcs
cp -p .env ".env.bak.$(date +%Y%m%d-%H%M%S)"
grep -nE '=(.*change_me|replace_with)' .env | cut -d= -f1
nano .env
bash scripts/preflight.sh .env
```

按变量名补齐占位值；可从已填写的 `.env.template` 逐项复制对应值，不要整份覆盖部署配置。
已有数据库或其他持久化服务应沿用原凭据。不要删除 `.env` 或数据卷来强制首次安装。
JWT 密钥至少 32 字符，可选 TURN/监控配置中的占位值也必须处理；校验通过不代表设备授权或网络可达。

若镜像加载和地图安装已成功，校验通过后在部署目录继续第 5、6 步，无需重新加载镜像：

```bash
docker compose --env-file .env up -d --no-build --pull never --remove-orphans --wait --wait-timeout 300 &&
bash scripts/smoke-test.sh .env
```

也可在修复 `.env` 后，以原安装用户回到离线包目录重跑 `install.sh`；启用了可选 profile 的部署需保留原参数。

## Windows 10/11 x64

先安装 WSL2、Ubuntu、Docker Desktop，启用 Linux containers 与 Ubuntu 的 WSL 集成。
在 Ubuntu 终端确认 `uname -m` 为 `x86_64`，`docker info` 为 Linux/x86_64 或 Linux/amd64，
`docker compose version` 为 v2，再按上述步骤操作。建议把包放在 WSL 的 Linux 文件系统中解压。
这是原生 amd64 包，无需 ARM 模拟，不要设置 `DOCKER_DEFAULT_PLATFORM=linux/arm64`。
Docker Desktop 环境把 `.env.template` 中 `YOOX_RTSP_TRANSPORTS` 改为 `tcp`。
填入设备可访问的 Windows 局域网 IP，并允许 Windows 防火墙访问相关端口；WSL 内 IP 检查提示可能是正常的。
实际无人机 MQTT、推流和 WebRTC 仍需在目标网络中验证。

## 重新构建

在源码仓库、JDK 17 环境运行：

```bash
python3 scripts/build-x86-offline-bundle.py --version x86_64-YYYYMMDD-版本
```

默认先执行 `make verify`，再以其 Java JAR 和前端静态文件构建 amd64 运行时镜像。
默认使用 `.env` 校验构建配置，包内配置来自 `.env.example`；可用 `--env-template` 指定部署模板。
复用本次源码重建的 MinIO 镜像时，追加 `--minio-source-dir <sources/minio目录>`。
输出为 `offline/dist/*.zip` 及 SHA-256 校验文件。
