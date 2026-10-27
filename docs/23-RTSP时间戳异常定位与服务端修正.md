# RTSP 时间戳异常定位与服务端修正

更新日期：2026-10-27。事实来源：Nano 入流/出流 RTP 头采样、MediaMTX 会话 API、
VLC 统计与日志、`scripts/rtsp-normalizer.sh`。

## 已确认的故障位置

本次样本为设备发布的 H.264、960×720、约 29.8 fps 视频。直接用 VLC 播放原流时大量
丢帧，并出现 `picture is too late to be displayed`，迟到判断达到几十秒。
这不是已测量的视频端到端延迟，而是播放器时间轴计算异常的表现。

在 Nano 物理网卡上只读采样 RTP/RTCP 头得到：

| 检查位置 | 实际接收帧率 | 相邻帧 RTP 时间戳增量中位数 |
| --- | --- | --- |
| 上游发布端 → Nano，UDP 入流 | 约 29.78 fps | 33578 |
| Nano → 原流 VLC 读者，UDP 出流 | 约 29.72 fps | 33578 |
| 修正流 → RTSP 读者 | 约 29.76 fps | 3022 |

入流与原流出流保留了相同的帧时间戳；SSRC 和序列号有重写，不能声称完整 RTP 头完全一致。
SDP 宣告 `H264/90000`，而设备视频帧时间戳的增长速度与微秒计数相符。
同时，上游 RTCP Sender Report 中的 NTP/RTP 映射按约 90000 Hz 增长，
与媒体包约百万刻度每秒的增量不一致。异常在设备进入 Nano 的媒体包中已经存在。

当前证据定位到上游发布链路，不能仅凭这些数据进一步区分摄像头、遥控器固件或推流 SDK
中的具体代码。Java `LiveStreamServiceImpl` 下发推流地址、视频标识和画质控制，
不参与这条 RTP 媒体包的生成与转发。MediaMTX 保留了上游的异常时间戳。

H.264 RTP 使用 90 kHz 时钟。约 33.58 ms 的一帧应该增加约 3022 刻度，
不能直接把约 33578 微秒写成 33578 个 RTP 刻度。根治需要上游修正时间单位，并使 RTP
与 RTCP 的时钟映射一致。参考 [RFC 6184](https://www.rfc-editor.org/info/rfc6184/)。

Nano 当时 NTP 未同步，系统时钟相对检查机慢约 74 秒；这是独立的运维问题，
不能用它解释上述约 11.1 倍的媒体时钟增量错误。本次未修改系统时钟或网络配置。

## 服务端兼容修复

`yoox-rtsp-normalizer.service` 从本机读取原流，经 FFmpeg `-c:v copy` 整理时间戳，
发布到 `<原路径>-normalized`。不重新编码，不改变分辨率、画质或原始播放地址；
浏览器可继续使用原流。VLC 需要切换到新地址，原地址本身仍保留上游时间戳。

脚本支持两种模式，配置在 `/etc/default/yoox-rtsp-normalizer`，不是根目录 `.env`：

- `wallclock`：默认模式，按接收时间重建时间戳，可受接收抖动影响。
- `rtp-microseconds`：仅对已抓包确认的微秒误填入 H264/90000 的流启用，
  使用 `setts=pts=PTS*9/100:dts=DTS*9/100` 换算时间戳，保留原帧间隔关系。

**正确的 90 kHz 流不能启用 `rtp-microseconds`。** 更换设备或升级推流固件后必须重新验证；
如果上游已经修复，应停用此兼容输出或调整模式，避免重复换算。
本次视频 `has_b_frames=0`；其他编码、B 帧或多轨音视频需另行验收。

服务依赖宿主机 Bash、Python 3 和支持 `setts` 的 FFmpeg；本次 Nano 使用 FFmpeg 4.4.2。
脚本兼容旧版 `-stimeout` 和新版 `-timeout` RTSP I/O 参数，15 秒 I/O 超时后退出，
由 systemd 重新连接。发布凭据从原 `.env` 读取并做 URL 编码，不另存密码。

## 部署与回滚

本次实际目录为 `/home/jetson/yoox-cloud-gcs`。模板中的运行用户、工作目录、
`YOOX_PROJECT_DIR` 和 `ExecStart` 必须与目标主机一致。

运行配置示例（用实际路径替换占位符）：

```dotenv
YOOX_RTSP_NORMALIZER_SOURCE_PATH=<drone-sn>-<payload-index>
YOOX_RTSP_NORMALIZER_TIMESTAMP_MODE=rtp-microseconds
```

部署前验证模式适用性，备份已有脚本、unit 和运行配置，再安装：

```bash
# 在目标部署目录操作。先创建 /etc/default/yoox-rtsp-normalizer 并填写实际配置。
sudo install -m 644 deploy/systemd/yoox-rtsp-normalizer.service \
  /etc/systemd/system/yoox-rtsp-normalizer.service
chmod +x scripts/rtsp-normalizer.sh
bash -n scripts/rtsp-normalizer.sh
sudo systemctl daemon-reload
sudo systemctl enable yoox-rtsp-normalizer
sudo systemctl restart yoox-rtsp-normalizer
systemctl show yoox-rtsp-normalizer -p ActiveState -p SubState -p NRestarts
```

本次部署前备份位置为部署目录的 `backups/rtsp-normalizer-<timestamp>/`。
仅新增宿主机 systemd 修正服务，原容器镜像、`.env` 和数据卷未替换，核心栈无需重建或重启。
回滚可先执行 `sudo systemctl disable --now yoox-rtsp-normalizer`，再恢复备份的脚本与配置；
原流仍然存在，但原时间戳异常也仍然存在。

## VLC 播放与延迟调整

播放地址：

```text
rtsp://<nano-ip>:8554/<drone-sn>-<payload-index>-normalized
```

Mac 上先退出已有 VLC 实例，再通过命令行传入单次参数；打开新 URL 本身不会自动更改缓存。
局域网 UDP 低延迟对照命令：

```bash
/Applications/VLC.app/Contents/MacOS/VLC \
  --no-rtsp-tcp --network-caching=50 --clock-jitter=0 \
  'rtsp://<nano-ip>:8554/<drone-sn>-<payload-index>-normalized'
```

如果出现花屏或丢包，先提高到 `--network-caching=150`，或改用 `--rtsp-tcp`。
缓存参数不是端到端延迟承诺；VLC 的解码、输出和 RTCP 初次同步还可能带来额外缓冲。
本机 VLC 3.0.21 是经 Rosetta 运行的 Intel 构建，日志确认使用 VideoToolbox 解码。

已将用户最终确认可接受的 UDP / 50 ms 参数保存为单次启动脚本，不覆盖其他媒体的全局偏好：

```bash
bash scripts/play-vlc-rtsp-mac.sh \
  'rtsp://<nano-ip>:8554/<drone-sn>-<payload-index>-normalized'
```

## 验证记录

- 本机合成 60 个 H.264 包，放大时间戳后再按 9/100 修正：编码包 SHA-256 全部保持一致，
  修正时间戳与原样本相差小于 1 ms；脚本语法与 `make verify` 通过。
- 线上修正流保持 H.264、960×720，帧间隔约 33.578 ms，RTP 增量中位数 3022。
- VLC 切换新地址后的约两分钟样本：显示 3550 帧，丢失 1 帧；这是当时的播放缓存设置，
  不应当作后续 150 ms / 50 ms 低缓存参数的统计结果。
- 同机回环读取原流和修正流，匹配 424 个 H.264 包哈希，到达时间差中位数约 35.76 ms，
  P95 约 61.12 ms；包含读者调度差异，不是玻璃到玻璃延迟。
- 部署后 HTTP/Pilot smoke 通过，原有八个容器均健康，三个既有 WebRTC 会话仍在持续发送。
  修正服务运行正常，检查时无异常重启，启动后早期资源样本约 2.5% CPU / 56 MiB RSS。
- 用户确认修正流流畅，但反馈默认播放延迟偏大；随后对 TCP/UDP 与低缓存参数进行了单独对照。
  最终确认 UDP、50 ms 网络缓存、`clock-jitter=0` 的流畅度与延迟均可接受，已保留该播放配置。
  最终低缓存实例约六分钟的统计为显示 10863 帧、丢失 24 帧；与前面的默认缓存样本分开记录。

查看 `systemctl show` 的状态字段即可；避免把含发布凭据的 FFmpeg 完整进程参数或未脱敏日志
复制到公开工单。新固件、新镜头、网络变化及长时间运行仍需重新验证。
