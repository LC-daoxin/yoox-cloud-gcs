# YOOX Cloud GCS Jetson Nano Git 部署与迁移

本文说明如何把已经通过离线包部署的 Jetson Nano，迁移为从 GitHub 仓库拉取源码、构建和更新的部署方式。

适用仓库（公开仓库，支持 HTTPS 或 SSH 克隆）：

```text
https://github.com/LC-daoxin/yoox-cloud-gcs.git
git@github.com:LC-daoxin/yoox-cloud-gcs.git
```

示例设备与目录：

```text
设备：jetson@192.168.2.28
项目根目录：/home/jetson/1_projects
新 Git 部署目录：/home/jetson/1_projects/yoox-cloud-gcs
旧生产部署目录：/home/jetson/1_projects/yoox-cloud-gcs-nano
旧源码副本：/home/jetson/1_projects/YOOX_Cloud_GCS
```

## 1. 迁移原则

迁移过程中遵守以下原则：

1. 不在两个非 Git 旧目录中执行 `git init`、`git reset` 或强制覆盖。
2. 先克隆到第三个新目录，完成构建和配置验证后再切换。
3. `.env` 只从当前生产目录复制，不提交到 Git。
4. 保持 `COMPOSE_PROJECT_NAME=yoox-cloud-gcs`，以复用现有 Docker 数据卷。
5. 每次构建使用独立的 `YOOX_VERSION`，保留旧镜像用于回滚。
6. 任何时候都不要执行 `docker compose down -v`，该命令会删除数据卷。
7. 新部署稳定前保留 `yoox-cloud-gcs-nano`，不要立即删除或覆盖。

## 2. 迁移前确认当前部署来源

登录 Nano：

```bash
ssh jetson@192.168.2.28
cd /home/jetson/1_projects
```

确认目录状态：

```bash
for project_dir in YOOX_Cloud_GCS yoox-cloud-gcs-nano; do
  printf '\n== %s ==\n' "$project_dir"
  test -d "$project_dir/.git" && echo 'Git 仓库' || echo '非 Git 目录'
  test -f "$project_dir/.env" && echo '.env 存在' || echo '.env 不存在'
done
```

确认正在运行容器的 Compose 工作目录：

```bash
docker inspect yoox-cloud-gcs-api-1 \
  --format '{{index .Config.Labels "com.docker.compose.project.working_dir"}}'
```

迁移前预期输出：

```text
/home/jetson/1_projects/yoox-cloud-gcs-nano
```

如果输出不同，应以实际运行目录为准，不要继续照搬本文的旧目录路径。

## 3. 为仓库配置 SSH 访问（可选）

仓库为公开仓库，HTTPS 克隆无需任何认证，适合大多数 Nano 场景。以下 SSH Deploy Key
配置仅在需要 SSH 克隆或有特殊网络要求时使用。

生产设备推荐使用仓库专用的只读 Deploy Key，不要在 Nano 上保存个人 GitHub 密码或把访问令牌写进 Git URL。

创建 SSH 目录和密钥：

```bash
mkdir -p /home/jetson/.ssh
chmod 700 /home/jetson/.ssh

ssh-keygen \
  -t ed25519 \
  -f /home/jetson/.ssh/id_ed25519_yoox_cloud_gcs \
  -C 'jetson@yahboom yoox-cloud-gcs'

chmod 600 /home/jetson/.ssh/id_ed25519_yoox_cloud_gcs
chmod 644 /home/jetson/.ssh/id_ed25519_yoox_cloud_gcs.pub
```

手工更新时建议为私钥设置口令。需要无人值守拉取时可以留空，但必须限制 Nano 的登录权限。

显示公钥：

```bash
cat /home/jetson/.ssh/id_ed25519_yoox_cloud_gcs.pub
```

在 GitHub 仓库中进入：

```text
Settings → Deploy keys → Add deploy key
```

填写：

- Title：`jetson-yahboom-nano`
- Key：粘贴上一步公钥
- 不勾选 `Allow write access`

测试连接：

```bash
ssh \
  -T \
  -i /home/jetson/.ssh/id_ed25519_yoox_cloud_gcs \
  -o IdentitiesOnly=yes \
  git@github.com
```

看到以下信息说明认证成功：

```text
You've successfully authenticated, but GitHub does not provide shell access.
```

GitHub 不提供 SSH Shell，因此测试成功时命令退出码也可能是 `1`。

## 4. 克隆正式 Git 工作目录

确认目标目录不存在：

```bash
test ! -e /home/jetson/1_projects/yoox-cloud-gcs
```

克隆仓库（推荐 HTTPS，无需认证）：

```bash
cd /home/jetson/1_projects

git clone \
  https://github.com/LC-daoxin/yoox-cloud-gcs.git \
  yoox-cloud-gcs
```

如果网络环境只能使用 SSH，需先完成第 3 节的 Deploy Key 配置，然后改用 SSH 克隆：

```bash
GIT_SSH_COMMAND='ssh -i /home/jetson/.ssh/id_ed25519_yoox_cloud_gcs -o IdentitiesOnly=yes' \
  git clone \
  git@github.com:LC-daoxin/yoox-cloud-gcs.git \
  yoox-cloud-gcs
```

检查仓库：

```bash
git remote -v
git branch --show-current
git log -1 --oneline
git status --short
```

首次克隆后 `git status --short` 应无输出。

## 5. 迁移 `.env` 配置

从正在运行的旧生产目录复制 `.env`：

```bash
cp -p \
  /home/jetson/1_projects/yoox-cloud-gcs-nano/.env \
  /home/jetson/1_projects/yoox-cloud-gcs/.env

chmod 600 /home/jetson/1_projects/yoox-cloud-gcs/.env
```

确认 `.env` 不会被 Git 提交：

```bash
cd /home/jetson/1_projects/yoox-cloud-gcs
git check-ignore .env
```

预期输出为 `.env`。如果没有输出，必须先修复 `.gitignore`，不能继续部署。

编辑 `.env`：

```bash
nano .env
```

至少确认以下字段：

```dotenv
COMPOSE_PROJECT_NAME=yoox-cloud-gcs
YOOX_PUBLIC_HOST=192.168.2.28
```

获取当前提交号：

```bash
git rev-parse --short HEAD
```

将输出写入独立镜像版本。例如提交号为 `abcdef1`：

```dotenv
YOOX_VERSION=nano-abcdef1
```

不要把 `.env` 替换成 `.env.example`；旧 `.env` 中包含当前设备正在使用的数据库、Redis、MQTT、MinIO、RTSP、JWT 和设备云配置。

## 6. 备份当前配置和数据库

创建带时间戳的备份目录：

```bash
backup_dir="/home/jetson/1_projects/backups/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$backup_dir"
chmod 700 "$backup_dir"
```

备份生产 `.env` 和最终 Compose 配置：

```bash
cp -p \
  /home/jetson/1_projects/yoox-cloud-gcs-nano/.env \
  "$backup_dir/yoox-cloud-gcs.env"

cd /home/jetson/1_projects/yoox-cloud-gcs-nano
docker compose --env-file .env config > "$backup_dir/compose.resolved.yml"
```

备份 MySQL：

```bash
docker exec yoox-cloud-gcs-mysql-1 sh -c \
  'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --events "$MYSQL_DATABASE"' \
  > "$backup_dir/mysql.sql"

test -s "$backup_dir/mysql.sql"
```

记录现有容器和镜像：

```bash
docker ps --format '{{.Names}}|{{.Image}}|{{.Status}}' \
  > "$backup_dir/containers.txt"
```

只有 `mysql.sql` 非空且 `.env` 已保存后，才进入构建和切换步骤。

## 7. 验证新目录配置

```bash
cd /home/jetson/1_projects/yoox-cloud-gcs

make preflight
docker compose --env-file .env config -q
```

检查新旧 Compose 使用相同项目名：

```bash
grep '^COMPOSE_PROJECT_NAME=' \
  /home/jetson/1_projects/yoox-cloud-gcs-nano/.env \
  /home/jetson/1_projects/yoox-cloud-gcs/.env
```

两侧都应为：

```text
COMPOSE_PROJECT_NAME=yoox-cloud-gcs
```

## 8. 在 Nano 上构建镜像

Nano 构建时间较长，并可能与正在运行的业务争用内存。应选择设备不执行飞行任务的维护窗口。

建议分开构建：

```bash
cd /home/jetson/1_projects/yoox-cloud-gcs

docker compose --env-file .env build api
docker compose --env-file .env build web
docker compose --env-file .env build api-portal
docker compose --env-file .env build mediamtx
```

构建过程中不要同时运行其他占用大量内存的 YOLO、FFmpeg 或模型转换任务。

构建完成后确认新版本镜像存在：

```bash
docker image ls --format '{{.Repository}}:{{.Tag}}' | grep 'nano-'
```

长期使用时，更推荐在 ARM64 CI 或专用构建机生成镜像并推送到私有镜像仓库，让 Nano 只执行 `docker compose pull`。

## 9. 切换到 Git 工作目录

新旧目录保持相同 Compose 项目名时，以下命令会替换容器并复用已有命名数据卷：

```bash
cd /home/jetson/1_projects/yoox-cloud-gcs

docker compose --env-file .env up \
  -d \
  --remove-orphans \
  --wait \
  --wait-timeout 300
```

不要在切换前执行旧目录的 `docker compose down`，也不要使用 `-v`。

查看状态和日志：

```bash
docker compose --env-file .env ps
docker compose --env-file .env logs --tail=200 api web emqx mediamtx
```

确认容器已切换到新目录：

```bash
docker inspect yoox-cloud-gcs-api-1 \
  --format '{{index .Config.Labels "com.docker.compose.project.working_dir"}}'
```

预期输出：

```text
/home/jetson/1_projects/yoox-cloud-gcs
```

## 10. 部署验证

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

还应进行以下真机验证：

1. Web 管理端能够登录。
2. Pilot/遥控器能够登录并保持在线。
3. MQTT OSD 持续到达。
4. 无人机和相机拓扑正确。
5. RTSP 推流正常。
6. WebRTC 页面播放正常且延迟可接受。
7. 云台、相机和航线接口按安全条件验证。

## 11. 回滚

如果新版本在验证阶段失败，回到旧部署目录：

```bash
cd /home/jetson/1_projects/yoox-cloud-gcs-nano

docker compose --env-file .env up \
  -d \
  --remove-orphans \
  --wait \
  --wait-timeout 300
```

确认回滚后的工作目录：

```bash
docker inspect yoox-cloud-gcs-api-1 \
  --format '{{index .Config.Labels "com.docker.compose.project.working_dir"}}'
```

预期重新输出：

```text
/home/jetson/1_projects/yoox-cloud-gcs-nano
```

普通应用回滚不需要还原 MySQL。只有确认新版本执行了不兼容的数据迁移时，才应在停止写入、评估数据损失并取得明确批准后恢复数据库备份。

## 12. 日常 Git 更新流程

每次更新前确认工作树干净：

```bash
cd /home/jetson/1_projects/yoox-cloud-gcs
git status --short
```

`.env` 不应出现在输出中。存在其他本地改动时，不要直接拉取，应先确认改动来源。

查看远端更新：

```bash
git fetch origin
git log --oneline --decorate HEAD..origin/main
```

只允许快进更新：

```bash
git pull --ff-only
```

更新 `.env` 中的 `YOOX_VERSION=nano-<提交短号>`，然后执行：

```bash
make preflight
docker compose --env-file .env config -q

docker compose --env-file .env build api web api-portal mediamtx
docker compose --env-file .env up \
  -d \
  --remove-orphans \
  --wait \
  --wait-timeout 300

make smoke
```

生产 Nano 不应向 GitHub 推送代码。需要修改源码时，在开发机创建分支、测试并合并，再由 Nano 使用只读 Deploy Key 拉取。

## 13. 旧目录处理

建议保留关系：

```text
/home/jetson/1_projects/yoox-cloud-gcs
  正式 Git 部署目录

/home/jetson/1_projects/yoox-cloud-gcs-nano
  旧生产回滚目录，稳定观察期内保留

/home/jetson/1_projects/YOOX_Cloud_GCS
  未被容器使用的旧源码副本
```

新版本稳定运行至少一个观察周期，并确认所有容器的 Compose 工作目录都已指向新 Git 目录后，可先把旧目录移动到归档目录：

```bash
mkdir -p /home/jetson/1_projects/archive

mv /home/jetson/1_projects/YOOX_Cloud_GCS \
  /home/jetson/1_projects/archive/YOOX_Cloud_GCS-pre-git

mv /home/jetson/1_projects/yoox-cloud-gcs-nano \
  /home/jetson/1_projects/archive/yoox-cloud-gcs-nano-pre-git
```

移动前再次确认没有容器绑定旧目录：

```bash
docker ps --format '{{.Names}}' | while read container_name; do
  docker inspect "$container_name" \
    --format '{{.Name}} {{index .Config.Labels "com.docker.compose.project.working_dir"}}'
done
```

不要在迁移当天删除回滚目录。Docker 命名卷与源码目录独立，但错误执行 `docker compose down -v` 仍会删除数据。

## 14. 常见问题

### 14.1 `Permission denied (publickey)`

检查 Deploy Key 是否添加到正确的私有仓库，并显式指定私钥测试：

```bash
ssh -v \
  -T \
  -i /home/jetson/.ssh/id_ed25519_yoox_cloud_gcs \
  -o IdentitiesOnly=yes \
  git@github.com
```

### 14.2 `.env` 缺少新版本变量

不要重新生成 `.env` 覆盖生产密钥。只比较变量名，找出 `.env.example` 中存在但生产 `.env` 尚未配置的键，避免把密钥内容打印到终端或日志：

```bash
comm -23 \
  <(sed -n 's/^\([A-Z][A-Z0-9_]*\)=.*/\1/p' .env.example | sort) \
  <(sed -n 's/^\([A-Z][A-Z0-9_]*\)=.*/\1/p' .env | sort)
```

### 14.3 `git pull --ff-only` 失败

先执行：

```bash
git status --short
git log --oneline --decorate --graph -10
```

不要使用 `git reset --hard`。生产目录有本地修改时，应先确认是否需要保存，再决定通过开发分支合并或重新克隆到新的暂存目录。

### 14.4 构建过程中 Nano 内存不足

停止不属于 Cloud GCS 的高内存任务，在维护窗口逐个构建镜像。不要在飞行任务、视频分析或 YOLO 推理期间进行完整构建。

### 14.5 新旧部署端口冲突

不要用不同 `COMPOSE_PROJECT_NAME` 同时启动两套完整服务。迁移方案通过相同项目名替换现有容器，不会并行占用相同端口。

## 15. 完成标准

满足以下条件后，Git 部署迁移才算完成：

- Nano 能使用只读 Deploy Key 拉取私有仓库。
- 正式目录是 `/home/jetson/1_projects/yoox-cloud-gcs`。
- `.env` 被 Git 忽略且权限为 `600`。
- `make preflight`、Compose 配置检查和 `make smoke` 通过。
- 容器 Compose 工作目录指向新 Git 目录。
- MySQL、Redis、MinIO 和 EMQX 数据卷保持不变。
- Pilot、MQTT、RTSP、WebRTC 和关键控制接口完成真机验证。
- 旧生产目录仍可用于短期回滚。

## 16. 参考资料

- [GitHub：管理 Deploy Key](https://docs.github.com/en/authentication/connecting-to-github-with-ssh/managing-deploy-keys)
- [Linux 本地构建与部署](11-Linux本地构建与部署.md)
- [部署运维指南](08-部署运维指南.md)
- [配置指南](06-配置指南.md)
