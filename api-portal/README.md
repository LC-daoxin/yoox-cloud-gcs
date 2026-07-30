# YOOX Cloud GCS 上云 API 在线文档门户

一个零构建依赖的纯静态文档站，用于在线呈现 YOOX Cloud GCS 的上云 API 文档。
布局采用文档站常见的三栏结构（顶部导航 + 左侧目录树 + 右侧内容与页内锚点），
交互（页面切换、页内目录、搜索过滤、代码复制、移动端抽屉）由原生 JS 实现。

## 内容章节

- 基础介绍：平台简介、系统架构、接口通用约定、登录鉴权、快速开始
- 功能集合 · 遥控器功能：控制权、DRC 指令飞行、指令控制、云台与负载
- API 介绍 · 航线文件格式标准：KMZ/WPML 文件结构、核心元素、航线与任务 API
- API 介绍 · Enterprise 上云：接入架构、应用凭据、MQTT Topic、消息信封、上线数据流

> 全部内容基于本项目（YOOX Cloud GCS）自身的 API 实现原创编写，可结合运行实例的
> `/swagger-ui/index.html` 与 `/v3/api-docs` 一起查阅。

## 目录结构

```
api-portal/
├── index.html          # 单页文档站（结构 + 全部内容）
├── assets/
│   ├── styles.css      # 文档站样式
│   └── app.js          # 导航、目录、搜索、复制等交互
├── Dockerfile          # nginx 静态服务镜像
├── nginx.conf          # 静态站点 nginx 配置
└── README.md
```

## 本地预览

任意静态服务器即可，例如：

```bash
cd api-portal
python3 -m http.server 8081
# 打开 http://127.0.0.1:8081
```

## 容器部署

已集成到根目录 `compose.yml` 的 `api-portal` 服务，随主栈一起启动：

```bash
docker compose --env-file .env up -d --build api-portal
# 访问 http://<YOOX_PUBLIC_HOST>:<YOOX_API_PORTAL_PORT>
```

端口由 `.env` 中的 `YOOX_API_PORTAL_PORT`（默认 `8081`）控制。
