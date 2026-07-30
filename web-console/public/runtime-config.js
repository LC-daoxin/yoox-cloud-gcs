// 运行时配置：部署后可直接修改本文件（无需重新构建前端）。
// 高德地图 Key 申请：https://console.amap.com/ -> 应用管理 -> Key（服务平台选择「Web端(JS API)」）。
// 使用 JS API 2.0 时，若 Key 开启了「安全密钥」，需一并填写 amapSecurityCode。
window.YOOX_RUNTIME = {
  amapKey: '24941ad04ae04fac73d32037b87ea023',
  amapSecurityCode: '',
  // 「接入配置」页展示给遥控器/Pilot 的设备 MQTT 账号密码
  deviceMqttUser: 'pilot',
  deviceMqttPassword: 'pilot123',
  // 遥控器登录、WebSocket 和 DRC MQTT WebSocket 的公网网关端口
  pilotGatewayPort: 9000
}
