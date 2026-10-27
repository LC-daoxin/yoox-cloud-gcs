# RTSP 低码率转码服务

更新日期：2026-10-27。事实来源：`compose.transcode.yml`、`deploy/rtsp-transcoder/transcode.py`。
适用于有剩余 CPU 资源的 x86 Linux Docker 主机；当前实现使用 FFmpeg / libx264 软编码。

状态：可选方案，未部署，容器镜像验证未完成。本次已验收的 VLC 修复使用无损时间戳修正，
不是降低码率；定位证据和已部署方案见
[RTSP 时间戳异常定位与服务端修正](23-RTSP时间戳异常定位与服务端修正.md)。

## 工作方式

```text
设备 → 原始 RTSP → MediaMTX ┬→ 原始 WHEP → 网页
                         └→ FFmpeg 缩放、重新编码 → MediaMTX /原路径-low → VLC
```

每个转码容器处理一路视频。默认输出 H.264、YUV420p、最高 1280×720、25 fps，
目标视频码率 1500 kbps，VBV 最大码率 2000 kbps、缓冲 3000 kbit，无音频。
保持画面宽高比且不放大小尺寸输入；25 fps 为固定输出帧率，低帧率输入可能重复帧。
码率不包含 RTSP/RTP/TCP 开销，简单画面可能显著低于目标码率。
每秒一个关键帧，禁用 B 帧，使用 `zerolatency` 编码配置。

原始流、现有网页播放地址和设备推流配置均不需要变更。`-low` 是该服务保留的输出后缀，
启动前确认没有其他发布者使用同一路径；不要用 `-low` 路径作为输入。
`scripts/rtsp-normalizer.sh` 使用 `-c:v copy`，只能整理时间戳，不会降低分辨率或码率。

转码能降低下游带宽和客户端解码压力，但会增加服务器负载和端到端延迟。
如果卡顿来自丢包、异常时间戳或播放器设置，降码率不保证解决；需要原流和转码流对照。
仅连接 MediaMTX 的媒体读写接口，不发送飞控指令，但 CPU 争抢仍可能影响同机业务。

## 配置与启动

先确保原始 RTSP 已在现有 MediaMTX 中发布，且服务端允许 RTSP TCP。
在仓库根目录的现有 `.env` 中增加以下配置，保留原有凭据：

```dotenv
# 实际路径是原 RTSP URL 中 8554/ 后的内容，例如设备 SN 与镜头索引组合。
YOOX_TRANSCODE_SOURCE_PATH=demo-camera-0
YOOX_TRANSCODE_WIDTH=1280
YOOX_TRANSCODE_HEIGHT=720
YOOX_TRANSCODE_FPS=25
YOOX_TRANSCODE_BITRATE_KBPS=1500
YOOX_TRANSCODE_THREADS=2
```

服务复用现有 `YOOX_RTSP_USERNAME` / `YOOX_RTSP_PASSWORD` 发布权限，
在 Compose 网络内访问 `mediamtx:8554`，不新增宿主机端口或数据卷。
`SOURCE_PATH` 必填；尺寸为正偶数，码率单位为 kbps。完整变量名前缀均为 `YOOX_TRANSCODE_`。
线程数同时用于输入解码和输出编码；容器默认 CPU 上限 2 核、内存上限 768 MB，
这不是实时性能保证，高分辨率输入仍须现场压测。

仅在明确需要部署时执行以下命令；它们只创建或更新这个可选容器：

```bash
docker compose -f compose.yml -f compose.transcode.yml --env-file .env config --quiet
docker compose -f compose.yml -f compose.transcode.yml --env-file .env build rtsp-transcoder
docker compose -f compose.yml -f compose.transcode.yml --env-file .env up -d --no-deps rtsp-transcoder
```

需要已有 GCS 栈及其 `yoox-net` 网络。镜像首次构建需要访问 Debian 镜像和软件源；
离线交付应先在相同架构的联网机器构建并导出 `yoox/rtsp-transcoder:local`，
目标机导入后跳过 build。此可选镜像未加入现有 Nano/x86 离线包生成流程。

VLC 播放地址改为原路径加 `-low`，使用对外实际 RTSP 端口：

```bash
vlc --rtsp-tcp --network-caching=300 "rtsp://<server-ip>:8554/demo-camera-0-low"
```

如需进一步减负，可改为 854×480、20 fps、800 kbps 后重新执行上面的 `up` 命令。
参数改变后容器会重新创建，原始流仍由原发布者提供。关闭 VLC 不会自动停止转码。

## 验证与停止

```bash
docker compose -f compose.yml -f compose.transcode.yml --env-file .env logs --tail=100 rtsp-transcoder
docker compose -f compose.yml -f compose.transcode.yml --env-file .env stats --no-stream rtsp-transcoder
ffprobe -v error -rtsp_transport tcp -show_entries stream=codec_name,width,height,r_frame_rate \
  -of json "rtsp://<server-ip>:8554/demo-camera-0-low"
```

容器 Running 只表示进程在运行，不证明输出已可解码；以 ffprobe 和实际持续播放为准。
在同一客户端用相同 TCP/缓存设置比较原流和 `-low` 流，并观察 VLC 丢帧、CPU 占用及延迟。
如果转码持续耗尽 CPU 或延迟不断增大，降低输出参数或迁移到独立服务器。
输入长时间无数据时 FFmpeg 使用 15 秒 RTSP I/O 超时；进程退出后由 Docker 重启，
原流未发布时可能反复重试，Docker 的重启退避会延迟恢复。恢复后需重新验证首帧。

停止该服务即可释放转码资源：

```bash
docker compose -f compose.yml -f compose.transcode.yml --env-file .env stop rtsp-transcoder
```

当前配置仅提供软编码。NVIDIA 独显、Intel 核显或 Jetson 的硬件转码需要额外的驱动、
设备映射和对应编码器配置，不会因为宿主机有 GPU 就自动启用。

## 本次本机验证记录

2026-10-27，使用临时本机 MediaMTX 1.20.1 与 FFmpeg 8.0.1 生成 1080p/30 fps
测试视频，经过本服务脚本发布新 RTSP 流，录制 5 秒后由 ffprobe 检查：H.264、
1280×720、25 fps、视频码率约 1.53 Mbps。停止转码后原始发布进程仍在运行。
非法输入路径、奇数输出尺寸和零帧率均被拒绝。Compose 配置校验与 JDK 17 下的
`make verify` 通过。以上不代表目标 x86 主机性能、现场 VLC 卡顿原因或真机延迟已验收；
本次没有启动完整 GCS 栈或执行现场 `make smoke`。
本机 Docker 镜像构建在 Debian 依赖下载阶段因下载缓慢被主动停止，未完成容器镜像验证。
部署前仍须完成镜像构建及目标机持续播放验收。

参考：[MediaMTX 转码说明](https://mediamtx.org/docs/features/remuxing-reencoding-compression)。
