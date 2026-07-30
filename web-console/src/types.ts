export interface ApiEnvelope<T> {
  code: number
  message?: string
  data: T
}

export interface SessionUser {
  user_id: string
  username: string
  workspace_id: string
  user_type: number
  access_token: string
  mqtt_addr?: string
  mqtt_username?: string
  mqtt_password?: string
}

export interface Workspace {
  workspace_id: string
  workspace_name: string
  workspace_desc?: string
  platform_name?: string
}

export interface Device {
  device_sn: string
  device_name?: string
  nickname?: string
  child_sn?: string
  aircraft_sn?: string
  remote_controller_sn?: string
  domain?: number
  type?: number
  sub_type?: number
  firmware_version?: string
  bound_status?: boolean
  status?: boolean | string
  login_time?: string
  bound_time?: string
  children?: Device
  [key: string]: unknown
}

export interface CapacityVideo {
  id: string
  index: string
  type: string
  switch_video_types?: string[]
}

export interface CapacityCamera {
  id: string
  device_sn: string
  name: string
  index: string
  type: string
  videos_list: CapacityVideo[]
}

export interface CapacityDevice {
  sn: string
  name: string
  cameras_list: CapacityCamera[]
}

// 设备 OSD 遥测（WebSocket biz_code=gateway_osd / device_osd 的 host 字段，snake_case）
export interface OsdHost {
  latitude?: number
  longitude?: number
  height?: number
  elevation?: number
  attitude_head?: number
  horizontal_speed?: number
  vertical_speed?: number
  home_distance?: number
  capacity_percent?: number
  battery?: { capacity_percent?: number }
  [key: string]: unknown
}

export interface DeviceTelemetry {
  sn: string
  host: OsdHost
}

// 地图上的一架飞行器实时状态
export interface LiveDrone {
  sn: string
  name: string
  longitude: number
  latitude: number
  height: number
  elevation: number
  heading: number
  speed: number
  battery: number
  updatedAt: number
  track: [number, number][]
}
