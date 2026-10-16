# Jetson Nano Tailscale 异地访问指南

> 状态：当前运维手册  
> 适用环境：Ubuntu 22.04 / arm64 Jetson Nano 或 Jetson Orin Nano  
> 更新日期：2026-10-15

本文说明如何在没有公网 IP、无法配置路由器端口映射的环境中，使用 Tailscale
安全访问 Nano 的 SSH、GNOME 远程桌面和 YOOX Cloud GCS。Tailscale 只负责建立加密网络；
终端访问仍可使用 Nano 已有的 OpenSSH，不必启用 Tailscale SSH。

官方资料：

- [Tailscale Linux 安装](https://tailscale.com/docs/install/linux)
- [Tailscale 客户端下载](https://tailscale.com/docs/install)
- [Tailscale SSH](https://tailscale.com/kb/1193/tailscale-ssh)
- [Tailscale 子网路由器](https://tailscale.com/kb/1104/enable-ip-forwarding)
- [GNOME 远程桌面](https://help.gnome.org/gnome-help/sharing-desktop.html)

## 1. 方案选择

### 1.1 仅维护 Nano

只需在 Nano 和远程电脑上安装 Tailscale。完成后可使用 Nano 的 Tailscale IP
或 MagicDNS 名称访问：

- SSH 终端；
- Web 控制台和 API 文档；
- GNOME RDP 远程桌面；
- 其他明确允许的 Nano 本机端口。

这种模式不暴露现场局域网，攻击面最小，应作为默认方案。

### 1.2 完整访问 GCS、视频和遥控器网段

YOOX Cloud GCS 的 RTSP、WebRTC ICE、DRC MQTT 和 MinIO 可能下发现场局域网的绝对地址。
异地浏览器需要使用这些地址时，应把 Nano 配置为 Tailscale **子网路由器**，
并在 Tailscale 管理台批准必要的现场 CIDR。

不要为了异地访问将 `YOOX_PUBLIC_HOST`、设备 RTSP 地址全部改成 Tailscale IP；
这可能让未安装 Tailscale 的遥控器无法连接。

## 2. 前提条件

- Nano 能通过 Wi-Fi 或有线网访问 HTTPS 互联网。
- 安装时能使用显示器/键盘，或者已能在局域网内 SSH 登录 Nano。
- 拥有可用的 Tailscale 账号和管理权限。
- 远程电脑或手机能安装 Tailscale 客户端。
- Nano 时间和 DNS 正常。

安装前检查：

```bash
uname -m
cat /etc/os-release
ip -br -4 addr
ip route
getent ahostsv4 tailscale.com
curl -I --connect-timeout 5 https://tailscale.com/
```

预期 `uname -m` 输出 `aarch64`。如果 Nano 当前无法访问互联网，应先修复默认路由和
DNS，不要把安装失败误判为 Tailscale 账号问题。

## 3. 在 Nano 上安装

Tailscale 官方安装脚本支持 Ubuntu/Debian 和 arm64：

```bash
curl -fsSL https://tailscale.com/install.sh | sh
```

对 `curl | sh` 有审计限制的环境，应改用官方 Linux 安装文档中的发行版软件源步骤。

启动并设为开机自启：

```bash
sudo systemctl enable --now tailscaled
systemctl is-enabled tailscaled
systemctl is-active tailscaled
```

## 4. 首次认证

运行：

```bash
sudo tailscale up --hostname=yoox-nano
```

终端会输出一个 HTTPS 认证地址。在任意已联网浏览器中打开该地址，登录正确的
Tailscale 账号并批准 Nano。不要把认证 URL 发到无关群组或写入仓库。

检查：

```bash
tailscale status
tailscale ip -4
tailscale netcheck
```

记录 `tailscale ip -4` 输出的 `100.x.x.x` 地址。若 tailnet 启用 MagicDNS，也可使用
`yoox-nano` 或管理台显示的完整主机名。

## 5. 安装远程客户端

在 macOS、Windows、Linux、iOS 或 Android 设备上安装 Tailscale，并登录与 Nano 相同的
tailnet。安装入口见 [Tailscale 客户端下载](https://tailscale.com/docs/install)。

在远程电脑上检查：

```bash
tailscale status
tailscale ping yoox-nano
```

如果 MagicDNS 名称不可用，将 `yoox-nano` 替换为 Nano 的 `100.x.x.x` 地址。

## 6. SSH 异地访问

### 6.1 使用现有 OpenSSH（推荐）

Nano 保持现有 `sshd` 即可，不需要开启 Tailscale SSH：

```bash
ssh jetson@yoox-nano
# 或
ssh jetson@<tailscale-ip>
```

首次连接前应配置 SSH 密钥：

```bash
# 在远程电脑上
ssh-keygen -t ed25519
ssh-copy-id jetson@yoox-nano
ssh jetson@yoox-nano
```

确认密钥登录成功后，修改 Nano 的 SSH 配置：

```text
PasswordAuthentication no
PermitRootLogin no
```

修改后必须先执行 `sudo sshd -t`，校验通过再重载 `ssh` 服务。在新的 SSH
窗口确认密钥登录正常之前，不要关闭原会话。

### 6.2 可选：Tailscale SSH

需要使用 tailnet 身份和访问策略代管 SSH 时，可启用：

```bash
sudo tailscale set --ssh
```

启用前必须在 Tailscale 管理台核对 SSH 访问策略。该功能会接管到 Tailscale IP
的 22 端口，但不会修改 Nano 局域网上的 `sshd_config` 或 `authorized_keys`。

## 7. 访问 YOOX Cloud GCS

仅需管理页面和 API 时，可直接使用 Tailscale IP 或 MagicDNS：

| 功能 | 地址 |
| --- | --- |
| Web 控制台 | `http://yoox-nano:8080` |
| Pilot 网关 | `http://yoox-nano:9000` |
| API 文档 | `http://yoox-nano:8081` |
| MinIO S3 API | `http://yoox-nano:9100` |

对外分享前应通过 tailnet 访问策略限制可访问用户和端口。不要同时在公网路由器上
映射 `22`、`3389`、`8080`、`9000` 等端口。

### 7.1 完整驾驶舱、WHEP 和 DRC

若 GCS 返回的 WebRTC ICE、DRC MQTT 或媒体地址仍是 Nano 的现场局域网 IP，
远程客户端仅访问 `100.x.x.x` 可能出现 WHEP/ICE 或 DRC 失败。推荐使用子网路由，
让远程电脑直接访问 GCS 现有的局域网入口。

## 8. 配置子网路由（可选）

### 8.1 确认网段

在 Nano 上查看：

```bash
ip -br -4 addr
ip -4 route
```

记录需要远程访问的 CIDR，例如：

```text
<wifi-cidr>
<device-cidr>
```

只广告必要网段。如果只需通过 Nano 的 Wi-Fi 地址使用 GCS，不应额外暴露遥控器网段。

### 8.2 启用 IP 转发

```bash
sudo tee /etc/sysctl.d/99-tailscale.conf >/dev/null <<'EOF'
net.ipv4.ip_forward = 1
net.ipv6.conf.all.forwarding = 1
EOF
sudo sysctl -p /etc/sysctl.d/99-tailscale.conf
```

### 8.3 广告路由

```bash
sudo tailscale set --advertise-routes=<wifi-cidr>
```

确实需要异地访问遥控器网段时，才使用：

```bash
sudo tailscale set --advertise-routes=<wifi-cidr>,<device-cidr>
```

登录 Tailscale 管理台，在 Nano 的路由设置中批准这些 CIDR，并配置只允许授权管理员
访问的 grants/ACL。路由被广告但未批准，或策略不允许时，流量仍无法通过。

Windows、macOS、Android 和 iOS 客户端默认接受已批准的子网路由。Linux 客户端需要：

```bash
sudo tailscale set --accept-routes
```

配置后，远程浏览器使用现场的 Nano 局域网地址打开 `:8080`，以保持
RTSP、WebRTC ICE、DRC 和 MinIO 地址语义一致。

> 如果远程用户当前所在局域网与现场 CIDR 重叠，可能发生路由冲突。应调整其中一侧的网段，
> 或者另行设计 Tailscale IP 专用的 WebRTC/DRC 公告地址。

## 9. GNOME 远程桌面

在 Nano 的图形界面中打开“设置 → 系统/共享 → 远程桌面”（菜单名称因 GNOME 版本
可能不同），启用：

- 桌面共享；
- 远程控制；
- 独立且高强度的 RDP 凭据。

检查：

```bash
systemctl --user status gnome-remote-desktop.service
ss -lnt | grep ':3389'
```

在远程电脑的 RDP 客户端输入：

```text
yoox-nano:3389
```

GNOME 桌面共享可能依赖已登录的图形会话。无显示器、重启后尚未登录或 Wayland
兼容性问题下，应先使用 SSH 恢复，不要将 RDP 作为唯一管理通道。

## 10. 防火墙和访问策略

首选在 Tailscale 管理台使用 grants/ACL 限制用户、Nano 和目标端口。建议原则：

- SSH 只允许运维人员；
- RDP 只允许需要图形界面的人员；
- GCS 管理页面不向全部 tailnet 默认开放；
- 不向 tailnet 广告 `0.0.0.0/0`，除非明确要把 Nano 作为出口节点；
- 不在公网路由器上再做 SSH/RDP/GCS 端口映射。

如果 Nano 启用 UFW，还需核对 `tailscale0` 接口。例如只允许 Tailscale 进入 SSH：

```bash
sudo ufw allow in on tailscale0 to any port 22 proto tcp
```

其他端口应根据实际需求单独放行，不要直接放行所有入站流量。

## 11. 重启与验收

安装和访问策略完成后，在现场仍有本地恢复通道时重启 Nano：

```bash
sudo reboot
```

重启后从异地设备验证：

```bash
tailscale ping yoox-nano
ssh jetson@yoox-nano
```

然后验证：

1. `tailscaled` 处于 `active` 状态；
2. Nano 的 Tailscale IP 与 MagicDNS 名称可达；
3. SSH 密钥登录成功；
4. Web 控制台和 API 文档可访问；
5. 如启用子网路由，已批准的现场地址可达；
6. RDP 可用，或者已保留 SSH 作为恢复通道；
7. YOOX Cloud GCS 容器仍全部健康。

GCS 验证：

```bash
cd "$HOME/yoox-cloud-gcs"
docker compose -f compose.yml -f compose.nano.yml --env-file .env ps
bash scripts/smoke-test.sh .env
```

## 12. 故障排查

### 12.1 Nano 不在 tailnet 中

```bash
systemctl status tailscaled
tailscale status
journalctl -u tailscaled --since '-15 min'
getent ahostsv4 login.tailscale.com
curl -I --connect-timeout 5 https://login.tailscale.com/
```

若 `tailscale status` 显示需要认证，重新执行 `sudo tailscale up`，并在浏览器完成认证。

### 12.2 Tailscale 可达但 SSH 失败

```bash
tailscale ping yoox-nano
sudo systemctl status ssh
sudo ss -lntp | grep ':22'
sudo sshd -t
```

同时检查 tailnet 访问策略、Nano 防火墙和 SSH 用户名。

### 12.3 Web 可打开但 WHEP/DRC 失败

这通常表示 HTTP 通过 Tailscale IP 成功，但后续下发的现场局域网地址不可达。检查：

- Nano 是否广告正确的子网路由；
- 管理台是否已批准路由；
- 访问策略是否允许目标 CIDR 和端口；
- Linux 客户端是否启用 `--accept-routes`；
- 远程用户所在网段是否与现场 CIDR 重叠。

### 12.4 连接经 DERP 中继且速度较慢

```bash
tailscale status
tailscale netcheck
```

`relay`/DERP 连接仍能安全工作，但视频延迟和带宽可能不如点对点直连。检查两端 UDP、
NAT 类型和防火墙出站策略。

### 12.5 RDP 黑屏或重启后无法连接

确认 Nano 已建立图形会话、GNOME 远程桌面已启用且 3389 端口在监听。必须保留 SSH
通道，以便在图形会话故障时恢复。

## 13. 停用与回滚

仅断开 Tailscale，不卸载：

```bash
sudo tailscale down
```

停止广告子网路由：

```bash
sudo tailscale set --advertise-routes=
```

卸载客户端：

```bash
sudo apt-get remove tailscale
```

卸载前应确认仍有局域网 SSH、本地显示器或其他恢复通道。不要在异地唯一管理会话中
停止或卸载 Tailscale。

## 14. 安全检查清单

- [ ] Nano 和运维客户端都只加入授权 tailnet。
- [ ] SSH 已使用 Ed25519 密钥，默认密码已更换。
- [ ] 未在公网路由器暴露 SSH、RDP 或 GCS 端口。
- [ ] grants/ACL 仅允许必要的用户、设备和端口。
- [ ] 子网路由只广告必要 CIDR，没有无意暴露整个现场网络。
- [ ] Nano 重启后 Tailscale、SSH 和 GCS 服务都能恢复。
- [ ] 已记录设备密钥到期/重新认证流程，不依赖无人监管的永久凭据。
