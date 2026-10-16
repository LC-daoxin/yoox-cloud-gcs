# DRC MQTT 连接失败分析

## 1. 问题现象

客户端日志显示 DRC MQTT 连接失败：

```text
[SYUavDrcMqtt] 创建客户端
url=mqtt://192.168.2.2:1883
raw=ws://192.168.2.2:9000/mqtt
tls=false

AsyncMqtt Connect Fail code: -1, message: TCP/TLS connect failure
[MQTT] 连接失败: TCP/TLS connect failure
DRC MQTT 连接失败
```

## 2. 分析结论

本次故障发生在 **TCP 网络连接阶段**。客户端未能与 `192.168.2.2:1883` 建立 TCP 连接，尚未进入 MQTT 用户认证和协议握手阶段。

同时，日志反映出一个关键的协议转换问题：

```text
服务端下发：ws://192.168.2.2:9000/mqtt
客户端实际连接：mqtt://192.168.2.2:1883
```

服务端下发的是 **MQTT over WebSocket** 地址，但客户端在创建连接时将其转换成了普通 **MQTT over TCP** 地址。这两条连接链路并不等价。

当前服务端链路设计为：

```text
DRC 客户端
    │
    │ WebSocket: ws://192.168.2.2:9000/mqtt
    ▼
Nginx :9000
    │
    │ WebSocket 反向代理
    ▼
EMQX WebSocket Listener :8083/mqtt
```

而客户端实际尝试的是：

```text
DRC 客户端
    │
    │ MQTT/TCP: 192.168.2.2:1883
    ▼
EMQX TCP Listener :1883
```

因此，直接失败原因可能是以下一种或多种：

1. `192.168.2.2:1883` 没有服务监听；
2. Docker 没有正确映射 `1883` 端口；
3. EMQX 容器未正常运行；
4. 服务器防火墙拦截了 TCP `1883`；
5. 遥控器所在网络无法路由到 `192.168.2.2:1883`；
6. 客户端底层 MQTT 组件不支持 WebSocket，因而错误地将 `ws://...:9000/mqtt` 转换成了 `mqtt://...:1883`。

## 3. 为什么不是账号或证书问题

日志中的：

```text
MQTTAsync_connect rc = 0
```

只表示异步连接请求已成功提交，不代表 MQTT 已经连接成功。

随后出现：

```text
TCP/TLS connect failure
```

说明 TCP 连接本身没有建立成功。此时客户端还没有收到 MQTT `CONNACK`，因此本次失败不是用户名、密码、Client ID 或 Topic ACL 导致的。

此外，日志明确显示：

```text
tls=false
```

所以也不是 TLS 证书校验失败。错误信息中的 `TCP/TLS` 是底层库共用的通用错误描述。

`ws\://` 中的反斜杠属于日志或字符串转义现象；客户端已经正确提取出 `192.168.2.2`，不是本次故障的主要原因。

## 4. 建议排查方法

在服务器上执行：

```bash
cd /home/jetson/yoox-cloud-gcs
docker compose --env-file .env -f compose.yml -f compose.nano.yml ps emqx web
sudo ss -lntp | grep -E ':(1883|9000)\b'
docker compose --env-file .env -f compose.yml -f compose.nano.yml logs --tail=200 emqx web
```

普通 Linux 源码部署不使用 `compose.nano.yml`；在对应项目目录执行时删除第二个 `-f` 参数。

预期结果：

- Web/Nginx 服务正常监听 `9000`；
- EMQX 的 `1883` 端口已映射到宿主机；
- EMQX 容器状态为 `healthy`；
- 日志中没有 listener 启动失败或配置加载失败。

从遥控器所在网络测试端口：

```bash
nc -vz 192.168.2.2 9000
nc -vz 192.168.2.2 1883
```

如果 `9000` 成功而 `1883` 失败，就与当前日志表现完全一致：登录及 WebSocket 网关可达，但客户端转换后的 MQTT/TCP 端口不可达。

## 5. 推荐处理方案

### 方案一：客户端保持 WebSocket 地址（推荐）

修改 `SYUavDrcMqtt` 的地址处理逻辑，直接使用服务端下发的地址：

```text
ws://192.168.2.2:9000/mqtt
```

不要将其转换为：

```text
mqtt://192.168.2.2:1883
```

同时确认客户端底层 MQTT 库编译时启用了 MQTT over WebSocket 支持。

这是推荐方案，因为当前 DRC 的短时 JWT 认证和访问控制配置在 EMQX WebSocket listener 上。

### 方案二：为 DRC 单独提供 MQTT/TCP Listener

如果设备端 MQTT 组件确实不支持 WebSocket，可以在服务端新增一个专用于 DRC 的 MQTT/TCP 端口，并在该 listener 上配置与 DRC 相匹配的 JWT 认证和 ACL。

不建议直接复用现有 `1883`，因为该端口当前主要用于设备 MQTT，认证方式和权限模型可能与 DRC 短时凭据不同。

## 6. 最终结论

本次故障的直接原因是：

> 客户端没有按照服务端下发的 `ws://192.168.2.2:9000/mqtt` 建立 WebSocket MQTT 连接，而是转换成 `mqtt://192.168.2.2:1883`；随后 `192.168.2.2:1883` 的 TCP 建连失败。

应优先修复客户端的协议转换逻辑，让 DRC MQTT 保持使用 WebSocket 地址。即使后续打通 `1883`，如果没有同步配置 DRC JWT 认证，连接仍可能在下一阶段因认证失败而无法使用。
