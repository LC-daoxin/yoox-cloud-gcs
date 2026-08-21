# YOOX Cloud GCS Linux 本地构建与部署

本文用于在 Linux 工作站或单机服务器上完成源码构建验证，并通过 Docker Compose 部署完整的
YOOX Cloud GCS 本地服务。命令以 Ubuntu/Debian 为例，其他发行版请使用对应的软件包管理器。

## 1. 环境要求

### 1.1 主机资源

- Linux x86_64 或 arm64。
- 建议至少 4 核 CPU、8 GB 内存和 30 GB 可用磁盘。
- Docker Engine 24+，Docker Compose v2 插件。
- Git、Make 和 Curl。
- 主机时间同步，建议使用 `Asia/Shanghai` 时区。

只通过 Docker Compose 部署服务时，不需要在主机安装 Java、Maven 和 Node.js；容器构建阶段会
使用 JDK 17、Maven 3.9 和 Node.js 22。

### 1.2 安装基础工具

```bash
sudo apt-get update
sudo apt-get install -y git make curl ca-certificates
```

按照 Docker 官方针对当前发行版的说明安装 Docker Engine 和 Compose v2 插件。安装后检查：

```bash
sudo systemctl enable --now docker
docker version
docker compose version
```

如需让当前用户免 `sudo` 使用 Docker：

```bash
sudo usermod -aG docker "$USER"
```

退出并重新登录后生效。`docker` 用户组具备接近 root 的主机权限，只应加入受信任的运维用户。

### 1.3 源码构建工具

需要在 Linux 主机直接执行 `make verify` 时，额外安装：

- JDK 17。
- Maven 3.8 或更高版本。
- Node.js 22 和 npm。

Ubuntu/Debian 可先安装 Java 和 Maven：

```bash
sudo apt-get install -y openjdk-17-jdk maven
```

Node.js 应通过组织认可的软件源或版本管理器安装 22.x，不要使用发行版中版本过旧的 Node.js。

检查版本：

```bash
java -version
javac -version
mvn -version
node --version
npm --version
```

如果存在多个 JDK，可为当前终端设置 JDK 17：

```bash
export JAVA_HOME=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")
export PATH="$JAVA_HOME/bin:$PATH"
```

## 2. 获取源码

进入项目目录：

```bash
cd /path/to/YOOX_Cloud_GCS
```

确认根目录包含 `Makefile`、`compose.yml`、`cloud-service/` 和 `web-console/`。

## 3. 初始化本地配置

```bash
make init
```

编辑 `.env` 并完成以下设置：

1. 替换所有包含 `change_me` 或 `replace_with` 的值。
2. `YOOX_JWT_SECRET` 至少使用 32 个字符的随机值。
3. 仅在 Linux 本机浏览器访问时可使用 `YOOX_PUBLIC_HOST=127.0.0.1`。
4. 局域网 APP、遥控器或其他电脑需要访问时，使用 Linux 主机的局域网 IP 或可解析域名。
5. 真机接入时填写有效的 `YOOX_CLOUD_APP_ID`、`YOOX_CLOUD_APP_KEY` 和
   `YOOX_CLOUD_APP_LICENSE`。只验证平台界面时可使用明确标注为本地测试的非空值，但无法完成真机登录。
6. 保持 `YOOX_MQTT_USERNAME=yoox-cloud`、`YOOX_DEVICE_MQTT_USERNAME=pilot` 和
   `YOOX_DEVICE_MQTT_PASSWORD=pilot123`，这些值与当前 ACL 和 Pilot 初始化配置一致。

查看主机地址：

```bash
hostname -I
```

保护配置并执行预检：

```bash
chmod 600 .env
make preflight
```

预检会检查 Docker、Compose、`.env` 必填项、JWT 长度、MQTT 固定账号和 Compose 配置。

## 4. 源码构建与验证

### 4.1 一次性完整验证

```bash
make verify
```

`make verify` 会依次执行配置预检、后端 Maven 打包和前端生产构建。

### 4.2 分开构建

后端打包：

```bash
mvn -B -DskipTests clean package -pl cloud-service -am
```

运行后端测试：

```bash
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

在项目根目录执行：

```bash
make up
```

该命令会预检配置、构建镜像、后台启动核心服务，并等待健康检查通过。首次启动需要下载基础镜像和
构建依赖。

检查运行状态并执行冒烟测试：

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

## 6. 网络和防火墙

仅本机使用时无需向其他主机开放端口。APP、遥控器或局域网浏览器接入时，按实际用途开放：

| 端口 | 协议 | 用途 |
| --- | --- | --- |
| 8080 | TCP | Web 控制台和 API |
| 9000 | TCP | Pilot 登录、WebSocket 和 DRC MQTT over WebSocket |
| 8081 | TCP | 在线 API 文档，可选 |
| 1883 | TCP | 设备 MQTT |
| 8554 | TCP | 设备 RTSP 推流 |
| 8000 | UDP/TCP | WebRTC ICE 媒体（TCP 为 UDP 不可达时的回退） |
| 8188、8189 | UDP | RTSP 入流使用 UDP 时的 RTP/RTCP |

使用 UFW 时可按可信局域网网段限制来源，以下示例网段需要替换：

```bash
sudo ufw allow from 192.168.1.0/24 to any port 8080 proto tcp
sudo ufw allow from 192.168.1.0/24 to any port 9000 proto tcp
sudo ufw allow from 192.168.1.0/24 to any port 1883 proto tcp
sudo ufw allow from 192.168.1.0/24 to any port 8554 proto tcp
sudo ufw allow from 192.168.1.0/24 to any port 8000 proto udp
sudo ufw allow from 192.168.1.0/24 to any port 8000 proto tcp
sudo ufw allow from 192.168.1.0/24 to any port 8188 proto udp
sudo ufw allow from 192.168.1.0/24 to any port 8189 proto udp
```

不要直接将 MySQL、Redis、MinIO API 或内部 API 暴露到公网。Compose 已将 MinIO Console、EMQX
Dashboard 和 API 诊断端口绑定到 `127.0.0.1`。

## 7. 日常操作

```bash
# 查看容器状态
make ps

# 持续查看最近日志
make logs

# 重建并更新 Web 和 API
docker compose --env-file .env up -d --build web api

# 只重启 API
docker compose --env-file .env restart api

# 停止并移除容器，保留数据卷
make down

# 再次启动
make up
```

不要使用 `docker compose down -v` 进行普通停机，`-v` 会删除 MySQL、Redis、MinIO、EMQX
等命名卷中的本地数据。

## 8. 可选监控和 TURN

启动监控栈：

```bash
make up-monitoring
```

默认仅允许主机本地访问的监控入口包括 Grafana `3000`、Prometheus `9090`、Loki `3100`、
cAdvisor `8088`、Node Exporter `9100` 和 EMQX Dashboard `18083`。

跨 NAT 的 WebRTC 需要 TURN 时：

```bash
docker compose --env-file .env --profile turn up -d
```

## 9. 开机自启

Docker 服务应设置为开机启动：

```bash
sudo systemctl enable docker
```

Compose 中的长驻服务使用 `restart: unless-stopped`。主机异常重启后 Docker 会自动恢复这些容器；
仍应在重启后执行以下检查：

```bash
make ps
make smoke
```

## 10. 常见问题

### 当前用户无 Docker 权限

如果出现 `/var/run/docker.sock: permission denied`，确认用户已加入 `docker` 组并重新登录：

```bash
id
docker info
```

### 端口被占用

```bash
sudo ss -lntup | grep -E ':(8080|8081|9000|1883|8554|8000|8188|8189)\b'
```

修改 `.env` 中对应的 `YOOX_*_PORT` 后重新执行 `make up`，并同步修改设备侧地址。

### 磁盘空间不足

```bash
df -h
docker system df
```

先确认镜像和构建缓存确实不再使用，再做清理。不要删除 Compose 命名卷，否则会丢失业务数据。

### 服务未通过健康检查

```bash
make ps
docker compose --env-file .env logs --tail=200 api web mysql redis emqx mediamtx
```

从第一个不健康的依赖服务开始处理，再执行 `make up` 和 `make smoke`。

### APP 或遥控器无法连接

- 确认 `.env` 中的 `YOOX_PUBLIC_HOST` 不是 `127.0.0.1`。
- 从设备所在网络测试 Linux 主机的 `9000`、`1883` 和 `8554` 端口。
- 检查云服务地址是否为 `http://<Linux主机地址>:9000`。
- 检查防火墙、路由、客户端隔离和 MQTT Client ID 抢占。

## 11. 本地部署完成标准

- `make verify` 完成且无构建错误。
- `make ps` 中核心长驻容器为 `running/healthy`，初始化容器成功退出。
- `make smoke` 同时通过 HTTP 和 Pilot 网关检查。
- Web 可以登录，API 健康状态为 `UP`。
- 真机测试时，设备能够访问 Linux 主机的 Pilot、MQTT、RTSP 和 WebRTC 端口。
