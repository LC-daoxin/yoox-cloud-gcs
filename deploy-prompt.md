# YOOX Cloud GCS 部署提示词

> 供 AI Agent 快速理解本项目的部署架构、环境配置和操作流程。

## 项目概述

YOOX Cloud GCS 是道通（Autel）无人机云平台，支持 APP 上云、设备管理、虚拟座舱远程控制、航线任务、媒体管理和系统运维。

- **后端**: Java 17 + Spring Boot + MyBatis-Plus + EMQX(MQTT) + Redis + MySQL + MinIO
- **前端**: Vue 3 + TypeScript + Vite + 高德地图
- **部署**: Docker Compose 单机部署
- **协议**: MQTT(设备接入) / HTTP(管理API) / WebSocket(实时推送) / RTSP+WHEP(视频直播)

## 环境信息

### 本地开发环境（当前已部署）

| 项目 | 值 |
|------|-----|
| 访问地址 | http://127.0.0.1:8080 |
| 管理员账号 | admin / Yoox@123456 |
| Pilot 账号 | pilot / pilot123 |
| API 健康检查 | http://127.0.0.1:19000/actuator/health |
| EMQX Dashboard | http://127.0.0.1:18083 (admin / yoox_emqx_local_dev) |
| MinIO Console | http://127.0.0.1:9001 |
| MQTT 端口 | 1883 |
| RTSP 端口 | 8554 |
| 设备 MQTT 凭据 | pilot / pilot123 |

### 云服务器（生产环境）

| 项目 | 值 |
|------|-----|
| 服务器 | ubuntu@124.220.168.49，密码 20260727@Yoox |
| 项目路径 | /home/ubuntu/yoox-cloud-gcs |
| 访问地址 | http://124.220.168.49:8080 |
| 管理员账号 | admin / Yoox@123456 |
| SSH 方式 | sshpass -p '20260727@Yoox' ssh ubuntu@124.220.168.49 |

## 本地部署步骤

```bash
# 1. 确保 Docker Desktop 已启动
docker ps  # 验证 daemon 运行

# 2. 初始化配置（首次）
make init  # 从 .env.example 复制 .env

# 3. 编辑 .env，替换所有 change_me / replace_with 值
#    本地开发密码可随意填写，如 yoox_db_local_dev

# 4. 构建并启动
docker compose --env-file .env build
docker compose --env-file .env up -d --wait --wait-timeout 300

# 5. 验证
curl http://127.0.0.1:8080/healthz          # 应返回 ok
curl http://127.0.0.1:19000/actuator/health  # 应返回 {"status":"UP",...}
```

## 服务架构（Docker Compose）

```
web (nginx:80) ──→ api (Java:9000) ──→ mysql:3306
  │                  │                ──→ redis:6379
  │                  │                ──→ emqx:1883 (MQTT)
  │                  │                ──→ minio:9000 (对象存储)
  │                  └──→ mediamtx:8554 (RTSP/WHEP 视频)
  └──→ emqx:8083 (MQTT WebSocket)
```

### 容器列表

| 服务 | 镜像 | 端口（宿主机） | 说明 |
|------|------|----------------|------|
| web | yoox/cloud-gcs-web | 8080→80, 9000→80 | 前端 + nginx 反向代理 |
| api | yoox/cloud-gcs-api | 19000→9000（仅本地） | Java 后端 |
| mysql | mysql:8.4.6 | 内部 3306 | 数据库 |
| redis | redis:7.4.9-alpine | 内部 6379 | 缓存 |
| emqx | emqx/emqx:5.8.9 | 1883, 18083（仅本地） | MQTT 代理 |
| minio | minio/minio | 9001（仅本地） | 对象存储 |
| mediamtx | yoox/mediamtx:1.19.3 | 8554, 8189/udp | 视频流 |
| api-portal | yoox/cloud-gcs-api-portal | 8081 | API 文档 |

## 关键文件

| 文件 | 用途 |
|------|------|
| `compose.yml` | Docker Compose 编排 |
| `Dockerfile` | Java 后端镜像（Maven 多阶段构建） |
| `web-console/Dockerfile` | 前端镜像（Node 构建 + nginx） |
| `web-console/nginx.conf` | 前端 nginx 反向代理配置 |
| `.env` | 环境变量（密码、端口、凭据） |
| `sql/cloud_api.sql` | 数据库初始化脚本 |
| `web-console/public/runtime-config.js` | 前端运行时配置（高德Key、MQTT凭据） |
| `cloud-service/src/main/resources/application.yml` | Spring Boot 配置 |

## 部署到云服务器

```bash
# 1. 同步代码（排除构建产物和敏感文件）
sshpass -p '20260727@Yoox' rsync -avz --delete \
  --exclude='.git' --exclude='node_modules' --exclude='target' \
  --exclude='srs' --exclude='web-console/dist' --exclude='.env' \
  ./ ubuntu@124.220.168.49:/home/ubuntu/yoox-cloud-gcs/

# 2. 远程构建并重启
sshpass -p '20260727@Yoox' ssh ubuntu@124.220.168.49 \
  "cd /home/ubuntu/yoox-cloud-gcs && docker compose build api web && docker compose up -d api web"

# 3. 验证
sshpass -p '20260727@Yoox' ssh ubuntu@124.220.168.49 \
  "docker compose ps && curl -sS http://127.0.0.1:19000/actuator/health"
```

### ⚠️ 部署注意事项

1. **根 Dockerfile 是 Java 后端**（Maven 构建），`web-console/Dockerfile` 是前端（Node 构建），不要混淆
2. **服务器 .env 不要覆盖**，rsync 时排除 `.env`
3. **YOOX_PUBLIC_HOST** 本地用 `127.0.0.1`，服务器用公网 IP
4. **runtime-config.js** 通过 volume 挂载，修改后无需重建前端镜像
5. 前端 nginx 将 `/manage|/map|/media|/wayline|/storage|/control|/actuator` 代理到 `api:9000`
6. 设备 MQTT 共享凭据：`pilot / pilot123`（EMQX built_in_database 认证）

## 常用运维命令

```bash
make ps          # 查看容器状态
make logs        # 查看日志（tail -200）
make down        # 停止所有服务
make up          # 重新构建并启动
make smoke       # 冒烟测试

# 单独重建某个服务
docker compose build api && docker compose up -d api
docker compose build web && docker compose up -d web

# 查看 API 日志
docker compose logs api --tail 100 -f

# 进入容器调试
docker exec -it yoox-cloud-gcs-api-1 sh
docker exec -it yoox-cloud-gcs-mysql-1 mysql -uyoox -p cloud_sample
```

## 数据库

- 数据库名：`cloud_sample`
- 用户：`yoox` / 密码见 `.env` 中 `YOOX_DB_PASSWORD`
- 初始化脚本：`sql/cloud_api.sql`（首次启动自动执行）
- 关键表：`manage_device`（设备）、`manage_user`（用户）、`manage_workspace`（工作空间）、`manage_device_dictionary`（设备型号字典）
