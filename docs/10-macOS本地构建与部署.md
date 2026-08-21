# YOOX Cloud GCS macOS 本地构建与部署

本文用于在 macOS 上完成源码构建验证，并通过 Docker Compose 部署完整的本地服务。

## 1. 环境要求

### 1.1 仅运行本地服务

- macOS 13 或更高版本。
- Docker Desktop 4.x，包含 Docker Engine 24+ 和 Docker Compose v2。
- Git、Make 和 Curl。
- 建议为 Docker Desktop 分配至少 4 核 CPU、8 GB 内存和 30 GB 可用磁盘。

如果只通过 Docker Compose 启动服务，不需要在 macOS 上单独安装 Java、Maven和 Node.js；
容器镜像会使用 JDK 17、Maven 3.9 和 Node.js 22 完成构建。

### 1.2 源码构建验证

除上述工具外，还需要：

- JDK 17。
- Maven 3.8 或更高版本。
- Node.js 22 和 npm。

可以使用 Homebrew 安装：

```bash
brew install openjdk@17 maven node@22
sudo ln -sfn "$(brew --prefix)/opt/openjdk@17/libexec/openjdk.jdk" \
  /Library/Java/JavaVirtualMachines/openjdk-17.jdk
export PATH="$(brew --prefix node@22)/bin:$PATH"
```

检查环境：

```bash
docker version
docker compose version
git --version
make --version
JAVA_HOME=$(/usr/libexec/java_home -v 17) java -version
mvn -version
node --version
npm --version
```

执行 Maven 时如果默认 Java 不是 17，请在当前终端设置：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH="$JAVA_HOME/bin:$PATH"
```

## 2. 获取源码

进入项目目录：

```bash
cd /path/to/YOOX_Cloud_GCS
```

确认根目录包含 `Makefile`、`compose.yml`、`cloud-service/` 和 `web-console/`。

## 3. 初始化本地配置

生成本地配置文件：

```bash
make init
```

该命令只在 `.env` 不存在时复制 `.env.example`，不会覆盖已有配置。编辑 `.env` 并完成以下设置：

1. 将所有包含 `change_me` 或 `replace_with` 的值替换掉。
2. `YOOX_JWT_SECRET` 至少使用 32 个字符的随机值。
3. 仅由本机浏览器访问时使用 `YOOX_PUBLIC_HOST=127.0.0.1`。
4. APP、遥控器或同一局域网内的其他设备需要访问时，设置为 Mac 的局域网 IP，例如
   `YOOX_PUBLIC_HOST=192.168.1.20`，不能使用 `127.0.0.1`。
5. 真机接入时填写有效的 `YOOX_CLOUD_APP_ID`、`YOOX_CLOUD_APP_KEY` 和
   `YOOX_CLOUD_APP_LICENSE`。只验证平台界面时可使用明确标注为本地测试的非空值，但无法完成真机登录。
6. 保持 `YOOX_MQTT_USERNAME=yoox-cloud`、`YOOX_DEVICE_MQTT_USERNAME=pilot` 和
   `YOOX_DEVICE_MQTT_PASSWORD=pilot123`，这些值与当前 ACL 和 Pilot 初始化配置一致。

查看 Mac 的局域网地址，可按实际使用的网络接口执行：

```bash
ipconfig getifaddr en0
```

保护本地配置并执行预检：

```bash
chmod 600 .env
make preflight
```

预检会检查 Docker、Compose、`.env` 必填项、JWT 长度、MQTT 固定账号和 Compose 配置。

## 4. 源码构建与验证

### 4.1 一次性完整验证

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
make verify
```

`make verify` 会依次执行配置预检、后端 Maven 打包和前端生产构建。

### 4.2 分开构建

后端打包：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
mvn -B -DskipTests clean package -pl cloud-service -am
```

运行后端测试：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
mvn -B test
```

前端构建：

```bash
cd web-console
npm ci
npm run build
cd ..
```

构建产物位于各 Maven 模块的 `target/` 和 `web-console/dist/`，不要提交这些目录。

## 5. 部署完整本地服务

启动 Docker Desktop，等待状态变为 Running，然后在项目根目录执行：

```bash
make up
```

该命令会预检配置、构建镜像、后台启动核心服务，并等待健康检查通过。首次构建需要下载基础镜像和
Maven/npm 依赖，耗时取决于网络和本机性能。

检查容器并进行验收：

```bash
make ps
make smoke
```

核心入口：

| 功能 | 默认地址 |
| --- | --- |
| Web 控制台 | `http://127.0.0.1:8080` |
| Pilot 登录和 WebSocket 网关 | `http://127.0.0.1:9000` |
| 在线 API 文档 | `http://127.0.0.1:8081` |
| API 健康检查 | `http://127.0.0.1:8080/actuator/health` |
| Swagger UI | `http://127.0.0.1:8080/swagger-ui/index.html` |

初始化 Web 账号为 `admin / Yoox@123456`，仅用于首次登录，本地共享或真机测试前应修改密码。

## 6. 局域网设备接入

当 APP 或遥控器接入 Mac 上的服务时：

1. `.env` 中的 `YOOX_PUBLIC_HOST` 必须是设备可访问的 Mac 局域网 IP。
2. macOS 防火墙提示 Docker 接受传入连接时选择允许。
3. 确认设备与 Mac 网络互通，且 Wi-Fi 未启用客户端隔离。
4. 遥控器云服务登录地址使用 `http://<Mac局域网IP>:9000`。
5. 设备还需要访问 MQTT `1883`和 RTSP `8554`；浏览器需要访问 WebRTC ICE
   `8000/udp` 或 `8000/tcp`。`8188/8189` 是 RTSP 入流在 UDP 模式下的 RTP/RTCP 端口，
   不是浏览器 WHEP 端口。
6. **Mac/Docker Desktop 的 RTSP 必须用 TCP 传输**：Docker Desktop 的 UDP 端口转发不可靠，
   设备推流的 UDP 媒体包会丢失导致推流失败。须在 `.env` 中添加（或确认已存在）：

   ```dotenv
   YOOX_RTSP_TRANSPORTS=tcp
   ```

   WebRTC 出流与设备 RTSP 入流互相独立。Compose 会在 `YOOX_WEBRTC_ICE_PORT`
   （默认 `8000`）同时发布 UDP 和 TCP；Docker Desktop 下 UDP ICE 失败时浏览器会自动回退 TCP。

   部署到 Linux/Jetson Nano 时**不要**设置该变量，保持默认 `udp,tcp`（优先低延迟 UDP，
   UDP 不通时自动回退 TCP）。

如果切换了网络或 Mac 的 IP 发生变化，修改 `.env` 后重建相关服务：

```bash
docker compose --env-file .env up -d --build --force-recreate api web mediamtx
make smoke
```

> **YOOX_PUBLIC_HOST 必须与 Mac 当前局域网 IP 保持一致。** 切换 Wi-Fi 或手机热点后，如果
> `YOOX_PUBLIC_HOST` 仍指向旧 IP，服务端下发给设备的 RTSP/MinIO 地址将无法连通，表现为推流失
> 败、媒体回传 314004（DOWNLOAD_KMZ 失败）。修改后必须重启 `api`、`web`、`mediamtx` 才能生效。

## 7. 日常操作

```bash
# 查看状态
make ps

# 持续查看最近日志
make logs

# 重建并更新 Web 和 API
docker compose --env-file .env up -d --build web api

# 停止并移除容器，保留数据卷
make down

# 再次启动
make up
```

不要使用 `docker compose down -v` 进行普通停机，`-v` 会删除 MySQL、Redis、MinIO、EMQX
等命名卷中的本地数据。

## 8. 可选服务

启动监控栈：

```bash
make up-monitoring
```

其中 cAdvisor、Node Exporter 和 Promtail 使用 Linux 主机路径；在 Docker Desktop 的 Linux VM
中看到的资源和日志不完全等同于 macOS 主机，因此 macOS 上主要用于功能验证，正式主机监控请在
Linux 上验收。

跨 NAT 的 WebRTC 需要 TURN 时：

```bash
docker compose --env-file .env --profile turn up -d
```

## 9. 常见问题

### Docker daemon 未运行

先启动 Docker Desktop，确认以下命令成功：

```bash
docker info
```

### Apple Silicon 构建较慢或镜像不兼容

本项目当前使用的核心基础镜像支持常见的 `arm64` 和 `amd64` 平台。若某个新增镜像只提供
`amd64`，Docker Desktop 会使用模拟运行，性能会下降；通过 `docker compose logs <服务名>`
确认具体服务，不要为整个项目强制单一平台。

### 端口被占用

检查默认端口：

```bash
lsof -nP -iTCP:8080 -iTCP:8081 -iTCP:9000 -iTCP:1883 -iTCP:8554
```

修改 `.env` 中对应的 `YOOX_*_PORT` 后重新执行 `make up`。设备侧配置也要同步修改。

### 服务未通过健康检查

```bash
make ps
docker compose --env-file .env logs --tail=200 api web mysql redis emqx mediamtx
```

先处理第一个不健康的依赖服务，再重新执行 `make up` 和 `make smoke`。

## 10. 本地部署完成标准

- `make verify` 完成且无构建错误。
- `make ps` 中核心长驻容器为 `running/healthy`，初始化容器成功退出。
- `make smoke` 同时通过 HTTP 和 Pilot 网关检查。
- Web 可以登录，API 健康状态为 `UP`。
- 真机测试时，设备能够访问 Mac 的 Pilot、MQTT、RTSP 和 WebRTC 端口。
