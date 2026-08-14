<script setup lang="ts">
import mqtt, { type MqttClient } from 'mqtt'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ApiError, del, get, listFrom, post, put } from '../services/api'
import { WhepPlayer, type WhepState } from '../services/whep'
import { deviceWs } from '../services/ws'
import { loadAMap, getAmapKey } from '../services/amap'
import { gcj02ToWgs84, wgs84ToGcj02 } from '../services/geo'
import {
  addInteractionLog,
  clearInteractionLogs,
  useInteractionLogs,
  type InteractionLogEntry,
  type InteractionTransport
} from '../services/interaction-log'
import { registerSessionCleanup } from '../services/session-cleanup'
import { useSessionStore } from '../stores/session'
import type { CapacityDevice, Device, DeviceTelemetry, OsdHost } from '../types'

interface Broker { address: string; username: string; password: string; client_id: string }
interface Acl { pub: string[]; sub: string[] }
interface PendingDrcExit {
  clientId: string
  dockSn: string
  workspaceId: string
  createdAt: number
}
interface DrcResumeMarker {
  dockSn: string
  workspaceId: string
  createdAt: number
}
interface LiveVideoId {
  drone_sn: string
  payload_index: string
}
interface StoredVideoPublishers {
  workspaceId: string
  publishers: Array<[string, LiveVideoId]>
}
type VideoStartOutcome = 'created' | 'preexisting' | 'ambiguous' | 'explicit-failure'
interface InFlightVideoStart {
  count: number
  ownedBefore: boolean
  mayHaveCreated: boolean
  stopRequested: boolean
  videoId: LiveVideoId
}
interface PendingDrcControlProbe {
  mqttGeneration: number
  client: MqttClient
  replyTopics: string[]
  requestId: string
  controlSeq: number
  publishedAt: number
  handshakeStep: 0 | 1
}
interface CockpitSource {
  key: string
  deviceSn: string
  cameraIndex: string
  videoIndex: string
  type: string
  switchVideoTypes: string[]
  label: string
}

interface DeviceCardTelemetry {
  battery: number
  rcBattery: number
  remainFlightTime: number
  modeCode: number
  rcSignal: number
}

interface PointFlightProgress {
  kind: 'takeoff' | 'flyto'
  status: string
  result: number
  taskId: string
  trackId: string
  remainingDistance: number
  remainingTime: number
  wayPointIndex: number
  plannedPathPoints: Array<{ latitude: number; longitude: number; height: number }>
}

// 航线任务进度（flighttask_progress 事件）。字段严格对应 Autel 上报参数，
// 航线任务不提供剩余距离/剩余时长/规划轨迹，故此处不含这些字段。
interface WaylineTaskProgress {
  jobId: string
  status: string
  currentStep: number
  percent: number
  wayPointIndex: number
  mediaCount: number
  flightId: string
  trackId: string
  resultCode: number
}

interface DetectedTarget {
  trackerId: string
  classId: number
  x: number
  y: number
  width: number
  height: number
}

interface HmsAlarm {
  hms_id?: string
  hmsId?: string
  tid?: string
  bid?: string
  sn: string
  level: number
  module: number
  key: string
  message_zh?: string
  messageZh?: string
  message_en?: string
  messageEn?: string
  create_time?: string
  createTime?: string
}

interface CockpitWayline {
  id: string
  name: string
  drone_model_key?: string
  payload_model_keys?: string[]
  template_types?: number[]
  update_time?: number
}

interface MapTargetValue {
  latitude: number
  longitude: number
  height: number
  maxSpeed: number
}

type LiveQuality = 2 | 3
type CockpitLayout = 'video' | 'balanced' | 'map'
type OperationPanelState = 'ground' | 'airborne' | 'task'
type PayloadShortcutCode = 'ArrowUp' | 'ArrowDown' | 'ArrowLeft' | 'ArrowRight'
type ControlSource = 'A' | 'B' | ''
type DrcConnectionState = 'idle' | 'connecting' | 'online' | 'degraded' | 'offline'
type MapTargetMode = 'flyto' | 'lookAt'
type DrcLandingMethod = 'drc_emergency_landing' | 'drc_force_landing'
const CONTROL_REQUEST_OPTIONS = { timeoutMs: 15_000 } as const
const LIVE_START_REQUEST_OPTIONS = { timeoutMs: 70_000 } as const
const DRC_CONTROL_INTERVAL_MS = 100
const DRC_PROBE_ACK_WINDOW_MS = 1_500
// 与键盘 Z“下降”使用同一满量程左摇杆输入。标准档下最终发布 h=-4 m/s，
// 实际下降速度与触地保护仍由飞控限制。
const CONTINUOUS_LANDING_STICK_Y = 1
const CONTINUOUS_LANDING_START_TIMEOUT_MS = 1_000
const CONTINUOUS_LANDING_ACK_TIMEOUT_MS = 2_000
const CONTINUOUS_LANDING_MOVEMENT_TIMEOUT_MS = 3_000
const CONTINUOUS_LANDING_ARM_TIMEOUT_MS = 8_000
const PENDING_DRC_EXIT_STORAGE_KEY = 'yoox.cockpit.pending-drc-exit'
const DRC_RESUME_STORAGE_KEY = 'yoox.cockpit.drc-resume'
const VIDEO_PUBLISHERS_STORAGE_KEY = 'yoox.cockpit.started-video-publishers'
const DRC_RESUME_MAX_AGE_MS = 5 * 60_000

const session = useSessionStore()
// A cockpit instance belongs to one authenticated workspace for its complete
// lifetime. Keep the value available while logout clears the reactive store.
const cockpitWorkspaceId = session.workspaceId
let cockpitCleanupToken = session.token
watch(() => session.token, (token) => {
  if (token) cockpitCleanupToken = token
})
const devices     = ref<Device[]>([])
const capacity    = ref<CapacityDevice[]>([])
const dockSn      = ref('')
// 初次加载可自动选择首台在线设备。当前设备掉线后只允许同一 SN 自动恢复画面，
// 绝不切换到其他设备，也不复用旧 DRC MQTT/控制权状态。
const allowAutomaticDockSelection = ref(true)
const dockSelectionPending = ref(false)
const selectedVideoId = ref('')
const state        = ref<'idle' | 'connecting' | 'active'>('idle')
const error        = ref('')
const videoError   = ref('')
const videoPlaying = ref(false)
const videoElement = ref<HTMLVideoElement>()
const videoBox     = ref<HTMLDivElement>()
// 驾驶舱默认使用可见光变焦。红外仅在用户主动切换后成为当前镜头，
// 避免 capacity 列表刚好把红外排在第一位时错误显示 1–16× 刻度。
const lens         = ref<'normal' | 'wide' | 'zoom' | 'ir'>('zoom')
// 设备 live_status 确认的“当前在播镜头”（status===1 的 video_type），空串表示尚未收到
const liveLensType = ref('')
const videoQuality = ref<LiveQuality>(2)
const reportedVideoQuality = ref<LiveQuality>()
const qualitySwitching = ref(false)
const layoutMode   = ref<CockpitLayout>('video')
const videoState   = ref<WhepState | 'idle'>('idle')
const videoBitrate = ref(0)
const videoSize    = ref('')
const videoAspectRatio = ref('16 / 9')
const videoRetrying = ref(false)
const flightAuthorityPending = ref(false)
const flightControlSource = ref<ControlSource>('')
const pendingDrcExit = ref<PendingDrcExit | undefined>(readPendingDrcExit())
const drcResumeMarker = ref<DrcResumeMarker | undefined>(readDrcResumeMarker())
const payloadAuthorityPending = ref(false)
const payloadAuthorityKeys = reactive(new Set<string>())
const payloadPressed = reactive(new Set<PayloadShortcutCode>())
const payloadCommandPending = ref(0)
const payloadZoomTarget = ref<number>()
const gimbalResetPending = ref(false)
const gimbalResetMode = ref<0 | 1 | 2 | 3>(0)
const cameraCommandPending = ref<'photo' | 'recording' | ''>('')
const cameraActionTip = ref('')
const targetDetectionEnabled = ref(false)
const targetDetectionPending = ref(false)
const targetDetectionSummaryCollapsed = ref(false)
const detectedTargets = ref<DetectedTarget[]>([])
const targetDetectionStats = computed(() => {
  const count = (...classIds: number[]) => detectedTargets.value.reduce(
    (total, target) => total + (classIds.includes(target.classId) ? 1 : 0), 0)
  return [
    { key: 'people', label: '人', count: count(4, 5) },
    { key: 'vehicle', label: '车', count: count(3, 6) },
    { key: 'boat', label: '船', count: count(2) },
    { key: 'drone', label: '无人机', count: count(34) },
    { key: 'hazard', label: '烟雾/火情', count: count(35, 36) }
  ]
})
const targetDetectionTotal = computed(() => targetDetectionStats.value.reduce(
  (total, stat) => total + stat.count, 0))
const targetDetectionLensLabel = computed(() => displayLens.value === 'ir' ? '红外' : '可见光')
const pointFlightProgress = ref<PointFlightProgress>()
const pointFlightNoticeVisible = ref(false)
const flyToStopPending = ref(false)
const waylineProgress = ref<WaylineTaskProgress>()
const waylinePausePending = ref(false)
const waylineResumePending = ref(false)
const waylineCancelPending = ref(false)
const drcEnterPending = ref(false)
const drcReconnectPending = ref(false)
const drcConnectionState = ref<DrcConnectionState>('idle')
const drcMqttConnected = ref(false)
const drcControlRejected = ref(false)
const drcControlFailure = ref('')
const drcStatusMessage = ref('尚未进入指令飞行模式')
const lastHeartbeatAckAt = ref(0)
const emergencyStopPending = ref(false)
const drcLandingPending = ref<DrcLandingMethod | ''>('')
const continuousLandingActive = ref(false)
const continuousLandingConfirmed = ref(false)
const continuousLandingMovementObserved = ref(false)
const continuousLandingArmed = ref(false)
const returnHomePending = ref(false)
const returnHomeCancelPending = ref(false)
const mapTargetMode = ref<MapTargetMode>()
const mapTargetPanelOpen = ref(false)
const mapTargetPending = ref(false)
const mapTarget = reactive({ latitude: 0, longitude: 0, height: 10, maxSpeed: 5 })
const mapTargetDrafts = reactive<Record<MapTargetMode, MapTargetValue>>({
  flyto: { latitude: 0, longitude: 0, height: 10, maxSpeed: 5 },
  lookAt: { latitude: 0, longitude: 0, height: 10, maxSpeed: 5 }
})
const pointFlightTarget = reactive({ latitude: 0, longitude: 0, height: 0 })
const recording    = ref(false)
const recordingSeconds = ref(0)
const reportedLive = ref(false)
const reusedPublisher = ref(false)
const startedVideoIds = restoreStartedVideoPublishers()
// A quality choice belongs to one device/payload publisher. A publisher starts
// in SD on its first visit; later visits and WHEP retries retain an explicit HD
// switch without leaking that preference to another device.
const preferredVideoQualities = new Map<string, LiveQuality>()
const deviceCardTelemetry = reactive<Record<string, DeviceCardTelemetry>>({})
const interactionLogs = useInteractionLogs()
const logOpen      = ref(false)
const hmsOpen      = ref(false)
const hmsLoading   = ref(false)
const hmsError     = ref('')
const hmsMarkingRead = ref(false)
const hmsAlarms    = ref<HmsAlarm[]>([])
const latestHms    = ref<HmsAlarm>()
const hmsHighestLevel = computed(() => hmsAlarms.value.reduce(
  (highest, alarm) => Math.max(highest, Number(alarm.level) || 0), 0))
const shortcutHelpOpen = ref(false)
const waylineTaskOpen = ref(false)
const waylineTaskLoading = ref(false)
const waylineTaskSubmitting = ref(false)
const waylineTaskError = ref('')
const waylineTaskNotice = ref('')
const waylineTaskDockSn = ref('')
const cockpitWaylines = ref<CockpitWayline[]>([])
const selectedWaylineId = ref('')
const waylineTaskConfirmed = ref(false)
const waylineTaskForm = reactive({
  rthAltitude: 100,
  minBatteryCapacity: 60,
  barrierSwitchState: 1,
  takeoffAltitude: 100,
  firstWaypointSpeed: 10,
  returnSpeed: 10
})
const logPaused    = ref(false)
const logTransport = ref<'ALL' | InteractionTransport>('ALL')
const logQuery     = ref('')
const pausedLogs   = ref<InteractionLogEntry[]>([])
const latency      = ref(0)
const telemetry    = reactive({
  altitude: 0, height: 0, speed: 0, verticalSpeed: 0, homeDistance: 0,
  satellites: 0, rtkNumber: 0, gpsQuality: 0, gpsFixed: 0, battery: 0,
  heading: 0, pitch: 0, roll: 0, latitude: 0, longitude: 0,
  rcLatitude: 0, rcLongitude: 0,
  gimbalReported: false, gimbalPitch: 0, gimbalYaw: 0,
  zoomFactor: 1, irZoomFactor: 1,
  remainFlightTime: -1, remainWorkTime: -1, windSpeed: -1, windDirection: -1,
  modeCode: -1, gearLevel: -1, rcLostAction: -1,
  taskRemainingDistance: -1, taskRemainingTime: -1, pointFlightActive: false,
  obstacleFront: -1, obstacleBack: -1, obstacleLeft: -1, obstacleRight: -1,
  obstacleUp: -1, obstacleDown: -1, radarEnabled: undefined as boolean | undefined,
  hsiUpdatedAt: 0,
  measureReported: false, measureState: -1, measureDistance: -1,
  measureLatitude: 0, measureLongitude: 0, measureAltitude: 0
})
const obstacleSegments = reactive({
  front: [-1, -1, -1, -1],
  rear: [-1, -1, -1, -1],
  left: [-1, -1, -1],
  right: [-1, -1, -1]
})
// 左侧设备栏折叠状态：点击把手在展开/收起间切换，列宽与内容做缓动动画
const railCollapsed = ref(false)
// 本地摇杆固定使用标准档位与默认灵敏度。
const flightSettings = reactive({ speedPreset: 'normal' as 'slow' | 'normal' | 'fast', controlScale: 1, yawScale: 1 })
const takeoffSettings = reactive({
  targetAgl: 2,
  maxSpeed: 5
})
const takeoffPending = ref(false)
const normalizedHeading = computed(() => ((telemetry.heading % 360) + 360) % 360)
// 可见光与红外使用独立倍率与范围；对数刻度在有限高度内保留低倍区辨识度。
const activeZoomMax = computed(() => displayLens.value === 'ir' ? 16 : 160)
const activeZoomMarks = computed<readonly number[]>(() =>
  displayLens.value === 'ir' ? [1, 2, 4, 8, 16] : [1, 3, 7, 14, 56, 112, 160])
const activeZoomFactor = computed(() => {
  const raw = displayLens.value === 'ir' ? telemetry.irZoomFactor : telemetry.zoomFactor
  return Math.max(1, Math.min(activeZoomMax.value, raw))
})
const zoomScalePosition = computed(() => {
  return Math.log(activeZoomFactor.value) / Math.log(activeZoomMax.value) * 100
})
const showZoomScale = computed(() => displayLens.value === 'zoom' || displayLens.value === 'ir')
const zoomScaleExpanded = ref(true)
function zoomMarkPosition(mark: number) {
  return Math.log(mark) / Math.log(activeZoomMax.value) * 100
}
const attitudeTransform = computed(() => {
  const pitchOffset = Math.max(-32, Math.min(32, telemetry.pitch)) * 0.7
  return `rotate(${-telemetry.roll}deg) translateY(${pitchOffset}px)`
})
const sticks       = reactive({ leftX: 0, leftY: 0, rightX: 0, rightY: 0 })
const player = new WhepPlayer()
const pressed = reactive(new Set<string>())
// 即使控制通道尚未就绪，也给物理键一次短暂的视觉反馈。该集合只负责 UI，
// 绝不会参与摇杆向量计算，避免“按键没亮”被误判为键盘监听失效。
const blockedPressed = reactive(new Set<string>())
let client: MqttClient | undefined
let broker: Broker | undefined
let acl: Acl | undefined
let drcPublishTopic = ''
let heartbeatTimer = 0
let heartbeatHealthTimer = 0
let controlTimer   = 0
let drcProbeFollowupTimer = 0
let drcProbeRetryTimer = 0
let topologyRefreshTimer = 0
let videoWatchdogTimer = 0
let videoReconnectTimer = 0
let topologyVideoReconnectReason = ''
let disconnectedDockSn = ''
let videoOperationGeneration = 0
let videoStartTask: Promise<void> | undefined
const inFlightVideoStarts = new Map<string, InFlightVideoStart>()
let zeroBitrateSince = 0
let videoConnectingSince = 0
let activeVideoPublisherKey = ''
let lastAutoVideoRetryAt = 0
let cameraActionTipTimer = 0
let errorDismissTimer = 0
let waylineTaskNoticeTimer = 0
let waylineTaskLoadSeq = 0
let hmsNoticeTimer = 0
let hmsLoadSeq = 0
let pointFlightNoticeTimer = 0
let payloadCommandQueue: Promise<void> = Promise.resolve()
let payloadGimbalLoop: Promise<void> | undefined
let targetReportTimer = 0
let heartbeatSeq = 0
let lastHeartbeatAckSeq = 0
let nativeHeartbeatAckReceived = false
let controlSeq = 0
let drcProbeHandshakeStep: 0 | 1 = 0
let lastControlVector = ''
let lastControlPublishAt = 0
let zeroControlPending = false
let drcConnectedAt = 0
let drcMqttGenerationCounter = 0
let activeDrcMqttGeneration = 0
let pendingDrcControlProbe: PendingDrcControlProbe | undefined
let drcAircraftSn = ''
let continuousLandingDockSn = ''
let continuousLandingAircraftSn = ''
let continuousLandingStartedAt = 0
let continuousLandingFirstPublishedAt = 0
let continuousLandingStartTimer = 0
let continuousLandingAckTimer = 0
let continuousLandingMovementTimer = 0
let continuousLandingArmTimer = 0
let continuousLandingArmedDockSn = ''
let continuousLandingArmedAircraftSn = ''
let continuousLandingArmedMqttGeneration = 0
let continuousLandingMqttGeneration = 0
let continuousLandingInitialAltitude = 0
const continuousLandingRequestIds = new Set<string>()
let lastJoystickInvalidEventAt = 0
let lastFlightAuthorityGrabAt = 0
let drcEnterPromise: Promise<boolean> | undefined
let drcLeavePromise: Promise<void> | undefined
let drcEnterCancelled = false
let lastPointFlightServerVersion = 0
let lastPointFlightEventVersion = 0

function readPendingDrcExit(): PendingDrcExit | undefined {
  try {
    const raw = window.sessionStorage.getItem(PENDING_DRC_EXIT_STORAGE_KEY)
    if (!raw) return undefined
    const value = JSON.parse(raw) as Partial<PendingDrcExit>
    if (!value.clientId || !value.dockSn || value.workspaceId !== cockpitWorkspaceId ||
        !Number.isFinite(value.createdAt) || Date.now() - Number(value.createdAt) > 3_600_000) {
      window.sessionStorage.removeItem(PENDING_DRC_EXIT_STORAGE_KEY)
      return undefined
    }
    return value as PendingDrcExit
  } catch {
    return undefined
  }
}

function rememberPendingDrcExit(clientId: string, exitDockSn: string) {
  pendingDrcExit.value = {
    clientId,
    dockSn: exitDockSn,
    workspaceId: cockpitWorkspaceId,
    createdAt: Date.now()
  }
}

function restoreStartedVideoPublishers(): Map<string, LiveVideoId> {
  const publishers = new Map<string, LiveVideoId>()
  try {
    const raw = window.sessionStorage.getItem(VIDEO_PUBLISHERS_STORAGE_KEY)
    if (!raw) return publishers
    const stored = JSON.parse(raw) as Partial<StoredVideoPublishers>
    if (!stored.workspaceId || stored.workspaceId !== cockpitWorkspaceId ||
        !Array.isArray(stored.publishers)) {
      window.sessionStorage.removeItem(VIDEO_PUBLISHERS_STORAGE_KEY)
      return publishers
    }
    for (const entry of stored.publishers.slice(0, 64)) {
      if (!Array.isArray(entry) || entry.length !== 2) continue
      const [key, videoId] = entry
      if (typeof key !== 'string' || !videoId ||
          typeof videoId.drone_sn !== 'string' ||
          typeof videoId.payload_index !== 'string' ||
          key !== `${videoId.drone_sn}/${videoId.payload_index}`) continue
      publishers.set(key, {
        drone_sn: videoId.drone_sn,
        payload_index: videoId.payload_index
      })
    }
  } catch { /* 存储不可用或数据损坏时从空集合开始 */ }
  return publishers
}

function persistStartedVideoPublishers() {
  try {
    if (startedVideoIds.size === 0) {
      window.sessionStorage.removeItem(VIDEO_PUBLISHERS_STORAGE_KEY)
      return
    }
    const stored: StoredVideoPublishers = {
      workspaceId: cockpitWorkspaceId,
      publishers: [...startedVideoIds.entries()]
    }
    window.sessionStorage.setItem(VIDEO_PUBLISHERS_STORAGE_KEY, JSON.stringify(stored))
  } catch { /* sessionStorage 不可用时仍保留当前页内所有权 */ }
}

watch(pendingDrcExit, (value) => {
  try {
    if (value) window.sessionStorage.setItem(PENDING_DRC_EXIT_STORAGE_KEY, JSON.stringify(value))
    else window.sessionStorage.removeItem(PENDING_DRC_EXIT_STORAGE_KEY)
  } catch { /* sessionStorage 不可用时仍保留当前页面内的重试状态 */ }
}, { immediate: true })
let pointFlightLoadSeq = 0
let pointFlightIdentityPending = false
let pointFlightServerBaseline = 0
let pointFlightRecoveryTimer = 0
let emergencyRequestId = ''
let emergencyStopTimer = 0
let drcLandingRequestId = ''
let drcLandingGatewaySn = ''
let drcLandingTimer = 0
let componentExiting = false
let pageUnloading = false
let componentExitPromise: Promise<void> | undefined
const unregisterSessionCleanup = registerSessionCleanup(exit)

function readDrcResumeMarker(): DrcResumeMarker | undefined {
  try {
    const raw = window.sessionStorage.getItem(DRC_RESUME_STORAGE_KEY)
    if (!raw) return undefined
    const value = JSON.parse(raw) as Partial<DrcResumeMarker>
    if (!value.dockSn || value.workspaceId !== cockpitWorkspaceId ||
        !Number.isFinite(value.createdAt) ||
        Date.now() - Number(value.createdAt) > DRC_RESUME_MAX_AGE_MS) {
      window.sessionStorage.removeItem(DRC_RESUME_STORAGE_KEY)
      return undefined
    }
    return value as DrcResumeMarker
  } catch {
    return undefined
  }
}

function rememberDrcResume(resumeDockSn: string) {
  drcResumeMarker.value = {
    dockSn: resumeDockSn,
    workspaceId: cockpitWorkspaceId,
    createdAt: Date.now()
  }
}

function clearDrcResume() {
  drcResumeMarker.value = undefined
}

watch(drcResumeMarker, (value) => {
  try {
    if (value) window.sessionStorage.setItem(DRC_RESUME_STORAGE_KEY, JSON.stringify(value))
    else window.sessionStorage.removeItem(DRC_RESUME_STORAGE_KEY)
  } catch { /* sessionStorage 不可用时退化为手动重新进入 DRC */ }
}, { immediate: true })

// 地图
const mapContainer = ref<HTMLDivElement>()
const mapSatellite = ref(true)
const hasMapKey    = computed(() => Boolean(getAmapKey()))
let AMapRef: any, map: any, droneMarker: any, remoteControllerMarker: any, droneTrail: any, mapTargetMarker: any, pointFlightTargetMarker: any, measuredTargetMarker: any
// 分开记录坐标与内容，坐标小幅变化时只移动标记，不重建标签 DOM。
let measuredTargetPositionKey = ''
let measuredTargetContentKey = ''
// 用户拖拽/缩放地图后停止自动跟随，只有主动点击“定位飞行器”才恢复。
let userInteracting = false
const trailPts: [number, number][] = []
let wsUnsub: (() => void) | undefined

function isOnlineDevice(device: Device): boolean {
  return device.status === true ||
    device.status === '1' ||
    String(device.status).toLowerCase() === 'online'
}

function isGatewayDevice(device: Device): boolean {
  const domain = Number(device.domain)
  return domain === 3 ||
    Boolean(
      device.aircraft_sn ||
      device.child_device_sn ||
      device.child_sn ||
      device.children?.device_sn
    )
}

const docks = computed(() => devices.value.filter((d) => isGatewayDevice(d) && isOnlineDevice(d)))
const selectedDock   = computed(() => docks.value.find((d) => d.device_sn === dockSn.value))
const selectedDeviceSns = computed(() => {
  const dock = selectedDock.value
  if (!dock) return new Set<string>()
  return new Set([
    dock.device_sn,
    dock.child_device_sn,
    dock.child_sn,
    dock.aircraft_sn,
    dock.children?.device_sn
  ].filter((sn): sn is string => Boolean(sn)))
})
const selectedAircraftSn = computed(() => {
  const dock = selectedDock.value
  return dock?.child_device_sn ||
    dock?.child_sn ||
    dock?.aircraft_sn ||
    dock?.children?.device_sn ||
    dock?.device_sn ||
    ''
})
const selectedAircraftOnline = computed(() => isAircraftOnlineForDock(selectedDock.value))

function dockAircraftSn(dock: Device) {
  return dock.child_device_sn ||
    dock.child_sn ||
    dock.aircraft_sn ||
    dock.children?.device_sn ||
    ''
}

function aircraftDeviceForDock(dock?: Device) {
  if (!dock) return undefined
  const aircraftSn = dockAircraftSn(dock)
  return devices.value.find((device) => device.device_sn === aircraftSn)
    ?? (dock.children?.device_sn === aircraftSn ? dock.children : undefined)
}

function isAircraftOnlineForDock(dock?: Device) {
  const aircraft = aircraftDeviceForDock(dock)
  return Boolean(aircraft && isOnlineDevice(aircraft))
}

function dockModelName(dock: Device) {
  const aircraftSn = dockAircraftSn(dock)
  const aircraft = devices.value.find((item) => item.device_sn === aircraftSn)
  const liveDevice = capacity.value.find((item) => item.sn === aircraftSn)
  return String(
    aircraft?.nickname ||
    aircraft?.device_name ||
    liveDevice?.name ||
    dock.children?.device_name ||
    dock.device_model ||
    dock.model ||
    '飞行器'
  )
}

function dockCallSign(dock: Device) {
  return String(
    dock.call_sign ||
    dock.callsign ||
    dock.aircraft_call_sign ||
    dockAircraftSn(dock) ||
    '未绑定呼号'
  )
}

function dockCardStats(dock: Device): DeviceCardTelemetry {
  const runtime = deviceCardTelemetry[dock.device_sn]
  if (!isAircraftOnlineForDock(dock)) {
    return {
      battery: -1,
      rcBattery: runtime?.rcBattery ?? -1,
      remainFlightTime: -1,
      modeCode: -1,
      rcSignal: runtime?.rcSignal ?? Number(dock.signal_quality ?? dock.sdr_quality ?? 0)
    }
  }
  if (runtime) return runtime
  return {
    battery: Number(dock.battery_capacity ?? dock.capacity_percent ?? 0),
    rcBattery: -1,
    remainFlightTime: Number(dock.remain_flight_time ?? -1),
    modeCode: Number(dock.mode_code ?? -1),
    rcSignal: Number(dock.signal_quality ?? dock.sdr_quality ?? 0)
  }
}

function dockStatusLabel(dock: Device) {
  if (!isAircraftOnlineForDock(dock)) return '离线'
  return modeLabel(dockCardStats(dock).modeCode)
}

function dockStatusTone(dock: Device) {
  if (!isAircraftOnlineForDock(dock)) return 'offline'
  const modeCode = dockCardStats(dock).modeCode
  if (modeCode < 0 || modeCode === 14) return 'offline'
  return [3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 15, 16, 17, 18, 20, 37, 39].includes(modeCode)
    ? 'flying'
    : 'standby'
}

function signalBars(signal: number) {
  if (signal <= 5) return Math.max(0, Math.min(4, Math.round(signal)))
  if (signal >= 80) return 4
  if (signal >= 50) return 3
  if (signal >= 20) return 2
  return signal > 0 ? 1 : 0
}

const sources = computed<CockpitSource[]>(() => (selectedAircraftOnline.value ? capacity.value : [])
  .filter((dev) => selectedDeviceSns.value.has(dev.sn))
  .flatMap((dev) => (dev.cameras_list ?? []).flatMap((cam) =>
    (cam.videos_list ?? []).map((video) => ({
      key: `${dev.sn}/${cam.index}/${video.index || video.type}`,
      deviceSn: dev.sn,
      cameraIndex: cam.index,
      videoIndex: video.index,
      type: video.type,
      switchVideoTypes: video.switch_video_types ?? [],
      label: `${dev.name || dev.sn} · ${cam.name || cam.index} · ${lensLabel(video.type)}`
    }))
  )))
const selectedSource = computed(() => sources.value.find((source) => source.key === selectedVideoId.value))
function videoPublisherKey(source: CockpitSource | undefined = selectedSource.value) {
  return source ? `${source.deviceSn}/${source.cameraIndex}` : ''
}

function selectPreferredVideoQuality(source: CockpitSource | undefined) {
  const publisherKey = videoPublisherKey(source)
  if (!publisherKey) {
    videoQuality.value = 2
    reportedVideoQuality.value = undefined
    return
  }
  if (!preferredVideoQualities.has(publisherKey)) {
    preferredVideoQualities.set(publisherKey, 2)
  }
  videoQuality.value = preferredVideoQualities.get(publisherKey) ?? 2
  reportedVideoQuality.value = undefined
}
const selectedMeasurementPayloadKey = computed(() => {
  const source = selectedSource.value
  return source ? `${source.deviceSn}/${source.cameraIndex}` : ''
})
const selectedPayloadSn = computed(() => {
  const aircraft = aircraftDeviceForDock(selectedDock.value)
  const payloads = (aircraft?.payloads_list ?? aircraft?.payloads ?? []) as Record<string, unknown>[]
  const payload = payloads.find((item) =>
    String(item.payload_index ?? item.payloadIndex ?? '') === selectedSource.value?.cameraIndex)
  return String(payload?.payload_sn ?? payload?.payloadSn ?? '')
})
const hasFlightAuthority = computed(() => flightControlSource.value === 'A')
const payloadAuthorityKey = computed(() => {
  const source = selectedSource.value
  return source ? `${dockSn.value}/${source.cameraIndex}` : ''
})
const hasPayloadAuthority = computed(() =>
  Boolean(payloadAuthorityKey.value && payloadAuthorityKeys.has(payloadAuthorityKey.value)))
const availableLenses = computed(() => {
  const source = selectedSource.value
  const siblingTypes = sources.value
    .filter((item) =>
      item.deviceSn === source?.deviceSn &&
      item.cameraIndex === source?.cameraIndex)
    .map((item) => item.type)
  const reportedTypes = [source?.type, ...(source?.switchVideoTypes ?? []), ...siblingTypes]
  const dualSensorTypes = reportedTypes.includes('zoom') || reportedTypes.includes('ir')
    ? ['zoom', 'ir']
    : []
  return [...new Set([...reportedTypes, ...dualSensorTypes])]
    .filter((value): value is 'normal' | 'wide' | 'zoom' | 'ir' =>
      typeof value === 'string' && ['normal', 'wide', 'zoom', 'ir'].includes(value))
})
// 交互与刻度以用户当前选择为准；设备确认值仅用于 live 圆点，避免状态上报延迟
// 导致已切到红外后仍沿用可见光倍率与 1–160× 刻度。
const displayLens = computed<'normal' | 'wide' | 'zoom' | 'ir'>(() => {
  const selected = lens.value
  if (selected === 'normal' || selected === 'wide' || selected === 'zoom' || selected === 'ir') return selected
  const reported = liveLensType.value
  return reported === 'normal' || reported === 'wide' || reported === 'zoom' || reported === 'ir'
    ? reported
    : 'zoom'
})
// 镜头按钮使用固定顺序，切换时只改变选中状态，避免按钮左右跳动。
const lensDisplayOrder = ['zoom', 'ir', 'wide', 'normal'] as const
const orderedLenses = computed(() =>
  lensDisplayOrder.filter((item) => availableLenses.value.includes(item)))
const active         = computed(() => state.value === 'active')
const drcLinkReady = computed(() =>
  active.value &&
  drcConnectionState.value === 'online' &&
  hasFlightAuthority.value &&
  Boolean(selectedDock.value) &&
  selectedAircraftOnline.value &&
  Boolean(drcAircraftSn) &&
  selectedAircraftSn.value === drcAircraftSn &&
  !dockSelectionPending.value &&
  !drcControlRejected.value &&
  drcMqttConnected.value &&
  Boolean(client?.connected))
const drcControlsReady = computed(() =>
  drcLinkReady.value &&
  operationPanelState.value !== 'ground' &&
  !emergencyStopPending.value &&
  !drcLandingPending.value)
const continuousLandingActionDisabled = computed(() =>
  !continuousLandingActive.value && (
    !drcControlsReady.value ||
    dockSelectionPending.value ||
    returnHomePending.value ||
    returnHomeCancelPending.value ||
    emergencyStopPending.value ||
    Boolean(drcLandingPending.value)
  ))
const flightAuthorityLabel = computed(() => {
  if (flightAuthorityPending.value) return '① 抢夺中…'
  if (hasFlightAuthority.value) return '① 飞行控制权已获取'
  return '① 抢夺飞行控制权'
})
const mapTargetValid = computed(() =>
  Number.isFinite(mapTarget.latitude) && mapTarget.latitude >= -90 && mapTarget.latitude <= 90 &&
  Number.isFinite(mapTarget.longitude) && mapTarget.longitude >= -180 && mapTarget.longitude <= 180 &&
  !(mapTarget.latitude === 0 && mapTarget.longitude === 0) &&
  Number.isFinite(mapTarget.height) && mapTarget.height >= 2 && mapTarget.height <= 10000)
const pointFlightMapActive = computed(() =>
  telemetry.pointFlightActive &&
  (pointFlightProgress.value?.kind === 'flyto' || telemetry.modeCode === 37))
const pointFlightTargetValid = computed(() =>
  Number.isFinite(pointFlightTarget.latitude) && pointFlightTarget.latitude >= -90 && pointFlightTarget.latitude <= 90 &&
  Number.isFinite(pointFlightTarget.longitude) && pointFlightTarget.longitude >= -180 && pointFlightTarget.longitude <= 180 &&
  !(pointFlightTarget.latitude === 0 && pointFlightTarget.longitude === 0))
const remoteControllerCoordinatesValid = computed(() =>
  Number.isFinite(telemetry.rcLatitude) &&
  telemetry.rcLatitude >= -90 && telemetry.rcLatitude <= 90 &&
  Number.isFinite(telemetry.rcLongitude) &&
  telemetry.rcLongitude >= -180 && telemetry.rcLongitude <= 180 &&
  !(telemetry.rcLatitude === 0 && telemetry.rcLongitude === 0))
const measureCoordinatesValid = computed(() =>
  Number.isFinite(telemetry.measureLatitude) &&
  telemetry.measureLatitude >= -90 && telemetry.measureLatitude <= 90 &&
  Number.isFinite(telemetry.measureLongitude) &&
  telemetry.measureLongitude >= -180 && telemetry.measureLongitude <= 180 &&
  !(telemetry.measureLatitude === 0 && telemetry.measureLongitude === 0))
const measureHasValidResult = computed(() => {
  const hasDistance = Number.isFinite(telemetry.measureDistance) && telemetry.measureDistance > 0
  return telemetry.measureReported && (hasDistance || measureCoordinatesValid.value)
})
const measureStatusLabel = computed(() => {
  if (!telemetry.measureReported) return '未上报'
  // 部分设备会在有效测距结果中错误上报 measure_target_error_state=0。
  // 距离或目标坐标有效时，以实际结果为准，避免显示“已关闭 128.0 m”这类矛盾状态。
  if (measureHasValidResult.value) return ''
  if (telemetry.measureState === 0) return '已关闭'
  if (telemetry.measureState === 1) return '已开启'
  if (telemetry.measureState === 2) return '距离过远'
  if (telemetry.measureState === 3) return '无信号'
  return `状态 ${telemetry.measureState}`
})
const measureTone = computed(() => {
  if (!telemetry.measureReported) return 'unknown'
  if (measureHasValidResult.value) return 'active'
  if ([2, 3].includes(telemetry.measureState)) return 'warn'
  return telemetry.measureState === 1 ? 'active' : 'idle'
})
// 仅用于视频 HUD 的距离分级，不代表飞控的制动阈值。
function obstacleRiskClass(distance: number) {
  if (distance < 0) return 'unknown'
  if (distance <= 3) return 'danger'
  if (distance <= 6) return 'warning'
  if (distance <= 10) return 'caution'
  return 'clear'
}
function nearestObstacleDistance(distances: number[]) {
  const detectedDistances = distances.filter((distance) => distance >= 0)
  return detectedDistances.length > 0 ? Math.min(...detectedDistances) : -1
}
const nearestObstacle = computed(() => ({
  front: nearestObstacleDistance(obstacleSegments.front),
  rear: nearestObstacleDistance(obstacleSegments.rear),
  left: nearestObstacleDistance(obstacleSegments.left),
  right: nearestObstacleDistance(obstacleSegments.right)
}))
const operationPanelState = computed<OperationPanelState>(() => {
  // 面板只能跟随飞机 OSD 状态。HTTP 指令已受理或任务状态未知，并不代表
  // 飞机已经离地；否则待机中的飞机会被错误切换到任务面板。
  if ([5, 20, 37, 39].includes(telemetry.modeCode)) return 'task'
  if ([3, 4, 6, 7, 8, 9, 10, 11, 12, 15, 16, 17, 18].includes(telemetry.modeCode)) return 'airborne'
  // 未知、离线、升级和起飞准备状态一律按地面处理，禁止非零摇杆输出；
  // DRC 通信与心跳链路本身仍可在待机时预先建立。
  return 'ground'
})
// 航线执行（WAYLINE=5 / KML_ROUTE=39）与指点飞行（POI=20 / FLY_TO_POINT=37）
// 共用任务面板，但数据来源与可用操作不同，需据此切换。
const taskIsWayline = computed(() => [5, 39].includes(telemetry.modeCode))
const obstacleHudVisible = computed(() =>
  active.value && operationPanelState.value !== 'ground')
const obstacleHudReady = computed(() =>
  obstacleHudVisible.value && telemetry.hsiUpdatedAt > 0 && telemetry.radarEnabled === true)
const obstacleHudStateLabel = computed(() => {
  if (telemetry.radarEnabled === false) return '避障已关闭'
  return '等待避障数据'
})
const selectedCockpitWayline = computed(() =>
  cockpitWaylines.value.find((wayline) => wayline.id === selectedWaylineId.value))
const waylineTaskFormValid = computed(() =>
  Number.isFinite(waylineTaskForm.rthAltitude) && waylineTaskForm.rthAltitude >= 20 && waylineTaskForm.rthAltitude <= 500 &&
  Number.isFinite(waylineTaskForm.minBatteryCapacity) && waylineTaskForm.minBatteryCapacity >= 15 && waylineTaskForm.minBatteryCapacity <= 100 &&
  Number.isFinite(waylineTaskForm.takeoffAltitude) && waylineTaskForm.takeoffAltitude >= 1 && waylineTaskForm.takeoffAltitude <= 1500 &&
  Number.isFinite(waylineTaskForm.firstWaypointSpeed) && waylineTaskForm.firstWaypointSpeed >= 1 && waylineTaskForm.firstWaypointSpeed <= 25 &&
  Number.isFinite(waylineTaskForm.returnSpeed) && waylineTaskForm.returnSpeed >= 1 && waylineTaskForm.returnSpeed <= 25)
const waylineTaskBlockedReason = computed(() => {
  if (!selectedDock.value || selectedDock.value.device_sn !== waylineTaskDockSn.value) {
    return '当前执行设备已离线或发生变化，请关闭弹窗后重新选择'
  }
  if (!selectedAircraftOnline.value) return '遥控器未连接飞机，不能执行航线任务'
  if (dockSelectionPending.value) return '正在切换设备，请稍候'
  if (pendingDrcExit.value) return '上次退出 DRC 尚未确认，请先完成退出'
  if (drcEnterPending.value || drcReconnectPending.value || state.value === 'connecting') {
    return 'DRC 状态正在切换，请稍候'
  }
  if (operationPanelState.value === 'task') return '当前飞机已有任务正在执行'
  if (telemetry.pointFlightActive) return '当前存在指点飞行任务，请先结束任务'
  if (!selectedCockpitWayline.value) return '请选择要执行的航线'
  if (!waylineTaskFormValid.value) return '请检查任务参数是否在允许范围内'
  if (!waylineTaskConfirmed.value) return '请先确认飞行安全检查项'
  return ''
})
const drcBlockedReason = computed(() => {
  if (!selectedDock.value || dockSelectionPending.value) return '请选择在线设备'
  if (!selectedAircraftOnline.value) return '遥控器未连接飞机'
  if (pendingDrcExit.value) return '上次退出 DRC 未确认：请先重试退出'
  if (emergencyStopPending.value) return '刹车悬停指令确认中，控制输出已暂停'
  if (drcLandingPending.value) return '降落指令确认中，控制输出已暂停'
  if (!hasFlightAuthority.value) return '第 1 步：先抢夺飞行控制权'
  if (drcReconnectPending.value) return '正在安全重连 DRC…'
  if (drcEnterPending.value || state.value === 'connecting') return '正在进入 DRC…'
  if (!active.value) return '第 2 步：进入 DRC 指令飞行模式'
  if (!drcMqttConnected.value || !client?.connected || drcConnectionState.value === 'offline') {
    return 'DRC 连接异常：请重连 DRC'
  }
  if (drcConnectionState.value === 'degraded') {
    return drcControlFailure.value || drcStatusMessage.value || 'DRC 控制异常：请重新抢权并重连 DRC'
  }
  if (drcConnectionState.value !== 'online' || lastHeartbeatAckAt.value < drcConnectedAt) {
    return '等待当前 DRC 会话心跳确认…'
  }
  if (!drcLinkReady.value) return '控制通道尚未就绪'
  if (continuousLandingActive.value) {
    if (!continuousLandingConfirmed.value) {
      return '持续降落启动中：正在下发下降杆量并等待设备回包'
    }
    return continuousLandingMovementObserved.value
      ? '持续降落中：OSD 已检测到下降，点击按钮或任意方向键可停止'
      : '持续降落中：设备已接收控制报文，正在等待下降遥测'
  }
  if (operationPanelState.value === 'ground') return '待机链路正常：心跳已连通，起飞前禁止非零摇杆输出'
  return ''
})
const drcActionLabel = computed(() => {
  if (pendingDrcExit.value) return state.value === 'connecting' ? '② 退出中…' : '② 重试退出 DRC'
  if (drcReconnectPending.value) return '② 重连中…'
  if (drcEnterPending.value || state.value === 'connecting') return '② DRC 连接中…'
  if (active.value && ['degraded', 'offline'].includes(drcConnectionState.value)) return '② 重连 DRC'
  if (active.value) return '② 退出 DRC'
  return '② 进入 DRC'
})
const drcActionDisabled = computed(() => {
  if (pendingDrcExit.value) return state.value === 'connecting'
  if (!selectedDock.value || !selectedAircraftOnline.value || dockSelectionPending.value || drcReconnectPending.value || drcEnterPending.value) return true
  if (active.value) return false
  return !hasFlightAuthority.value || state.value === 'connecting'
})
const directionControls = [
  { code: 'KeyQ', key: 'Q', label: '左旋转', icon: '↶', iconPosition: 'top' },
  { code: 'KeyW', key: 'W', label: '前进', icon: '⌃', iconPosition: 'top' },
  { code: 'KeyE', key: 'E', label: '右旋转', icon: '↷', iconPosition: 'top' },
  { code: 'KeyC', key: 'C', label: '上升', icon: '⬆', iconPosition: 'top' },
  { code: 'KeyA', key: 'A', label: '左移', icon: '‹', iconPosition: 'bottom' },
  { code: 'KeyS', key: 'S', label: '后退', icon: '⌄', iconPosition: 'bottom' },
  { code: 'KeyD', key: 'D', label: '右移', icon: '›', iconPosition: 'bottom' },
  { code: 'KeyZ', key: 'Z', label: '下降', icon: '⬇', iconPosition: 'bottom' }
] as const
const payloadShortcutControls: ReadonlyArray<{
  code: PayloadShortcutCode
  label: string
  description: string
  icon: string
  position: 'up' | 'down' | 'left' | 'right'
}> = [
  { code: 'ArrowUp', label: '云台上仰', description: '按住持续向上转动', icon: '▲', position: 'up' },
  { code: 'ArrowLeft', label: '云台向左', description: '按住持续向左转动', icon: '◀', position: 'left' },
  { code: 'ArrowRight', label: '云台向右', description: '按住持续向右转动', icon: '▶', position: 'right' },
  { code: 'ArrowDown', label: '云台下俯', description: '按住持续向下转动', icon: '▼', position: 'down' }
]
const gimbalResetOptions = [
  { value: 0, label: '回中', tip: '云台已回中' },
  { value: 1, label: '向下', tip: '云台已转向正下方' },
  { value: 2, label: '偏航回中', tip: '云台偏航已回中' },
  { value: 3, label: '向下 45°', tip: '云台已转向下方 45°' }
] as const
// camera_screen_drag 每次调用会让云台持续运动一小段时间。使用低速档，避免一次
// 点按就快速扫到俯仰限位；长按仍通过周期刷新实现连续、可控的微调。
const payloadGimbalPitchSpeed = 0.8
const payloadGimbalYawSpeed = 0.6
const payloadZoomDisplay = computed(() =>
  payloadZoomTarget.value ?? activeZoomFactor.value)
const filteredInteractionLogs = computed(() => {
  const source = logPaused.value ? pausedLogs.value : interactionLogs.value
  const query = logQuery.value.trim().toLowerCase()
  return source.filter((entry) => {
    if (logTransport.value !== 'ALL' && entry.transport !== logTransport.value) return false
    if (!query) return true
    return [
      entry.transport, entry.direction, entry.method, entry.path,
      entry.topic, entry.summary, JSON.stringify(entry.payload)
    ].some((value) => String(value ?? '').toLowerCase().includes(query))
  })
})

function toggleLogPause() {
  if (!logPaused.value) pausedLogs.value = [...interactionLogs.value]
  logPaused.value = !logPaused.value
}

function formatLogTime(timestamp: number) {
  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit', minute: '2-digit', second: '2-digit', fractionalSecondDigits: 3,
    hour12: false
  }).format(timestamp)
}

function formatLogPayload(payload: unknown) {
  if (payload === undefined) return ''
  if (typeof payload === 'string') return payload
  try { return JSON.stringify(payload, null, 2) } catch { return String(payload) }
}

function topicDescription(entry: InteractionLogEntry) {
  if (entry.summary) return entry.summary
  const topic = entry.topic ?? ''
  if (topic.endsWith('/services')) return '云服务向设备下发服务指令'
  if (topic.endsWith('/services_reply')) return '设备回复服务指令'
  if (topic.endsWith('/state')) return '设备状态变化上报'
  if (topic.endsWith('/osd')) return '设备 0.5Hz 定频数据上报'
  if (topic.endsWith('/events')) return '设备事件上报'
  return entry.path ?? '交互信息'
}

function resetPointFlightTracking() {
  window.clearTimeout(pointFlightRecoveryTimer)
  pointFlightRecoveryTimer = 0
  pointFlightIdentityPending = false
  pointFlightServerBaseline = 0
  lastPointFlightServerVersion = 0
  lastPointFlightEventVersion = 0
  // 使设备切换前尚未返回的状态请求失效；序号保持全局单调，避免切走再切回碰撞。
  pointFlightLoadSeq += 1
}

function syncSelections() {
  if (!dockSn.value && disconnectedDockSn &&
      docks.value.some((device) => device.device_sn === disconnectedDockSn)) {
    dockSn.value = disconnectedDockSn
    disconnectedDockSn = ''
    videoError.value = ''
    error.value = '原设备已重新上线，正在恢复实时画面'
  }
  const selectedDockStillOnline = docks.value.some((device) => device.device_sn === dockSn.value)
  if (!selectedDockStillOnline) {
    if (dockSn.value) {
      const disconnectedSn = dockSn.value
      disconnectedDockSn = disconnectedSn
      allowAutomaticDockSelection.value = false
      flightControlSource.value = ''
      // leaveDrc() runs synchronously until it starts the HTTP exit request, so the
      // old SN is captured before we clear the selection below.
      releaseKeys()
      void leaveDrc('gateway-offline')
      void stopVideo()
      dockSn.value = ''
      selectedVideoId.value = ''
      pointFlightProgress.value = undefined
      resetPointFlightTracking()
      flyToStopPending.value = false
      clearPointFlightTargetMarker(true)
      Object.assign(telemetry, {
        altitude: 0, height: 0, speed: 0, verticalSpeed: 0, homeDistance: 0,
        satellites: 0, rtkNumber: 0, gpsQuality: 0, gpsFixed: 0, battery: 0,
        heading: 0, pitch: 0, roll: 0, latitude: 0, longitude: 0,
        rcLatitude: 0, rcLongitude: 0,
        gimbalReported: false, gimbalPitch: 0, gimbalYaw: 0,
        zoomFactor: 1, irZoomFactor: 1,
        remainFlightTime: -1, remainWorkTime: -1,
        taskRemainingDistance: -1, taskRemainingTime: -1, pointFlightActive: false,
        modeCode: -1, gearLevel: -1, rcLostAction: -1,
        obstacleFront: -1, obstacleBack: -1, obstacleLeft: -1, obstacleRight: -1,
        obstacleUp: -1, obstacleDown: -1, radarEnabled: undefined, hsiUpdatedAt: 0,
        measureReported: false, measureState: -1, measureDistance: -1,
        measureLatitude: 0, measureLongitude: 0, measureAltitude: 0
      })
      clearMeasuredTargetMarker()
      clearRemoteControllerMarker()
      error.value = `设备 ${disconnectedSn} 已离线，控制通道已关闭，请手动选择在线设备`
    } else if (allowAutomaticDockSelection.value) {
      dockSn.value = docks.value[0]?.device_sn ?? ''
    }
  }
  if (!sources.value.some((source) => source.key === selectedVideoId.value)) {
    const defaultSource = sources.value.find((source) => source.type === 'zoom')
      ?? sources.value.find((source) => source.switchVideoTypes.includes('zoom'))
      ?? sources.value[0]
    selectedVideoId.value = defaultSource?.key ?? ''
    const supportedTypes = defaultSource
      ? [defaultSource.type, ...defaultSource.switchVideoTypes]
      : []
    const defaultLens = supportedTypes.includes('zoom') ? 'zoom' : defaultSource?.type
    if (defaultLens === 'normal' || defaultLens === 'wide' || defaultLens === 'zoom' || defaultLens === 'ir') {
      lens.value = defaultLens
    }
  }
  hydrateAuthorityState()
}

function controlSourceValue(value: unknown): ControlSource {
  const source = String(value ?? '').toUpperCase()
  return source === 'A' || source === 'B' ? source : ''
}

function joystickInvalidReasonMessage(notice: Record<string, unknown>) {
  const reason = Number(notice.reason ?? notice.result)
  const labels: Record<number, string> = {
    0: '遥控器失联；请先恢复遥控器链路',
    1: '低电量返航已触发；请勿强制重连 DRC',
    2: '低电量降落已触发；请勿强制重连 DRC',
    3: '飞行器靠近限飞区；请先脱离限飞区',
    4: '遥控器夺权（可能由返航或 B 控接管触发）；请重新抢权并重连 DRC'
  }
  return labels[reason] ?? String(notice.message || '设备未提供失效原因')
}

function receiveJoystickInvalid(notice: Record<string, unknown>) {
  if (notice.sn && String(notice.sn) !== dockSn.value) return
  const eventTimestamp = Number(notice.event_timestamp ?? notice.timestamp ?? 0)
  // WebSocket 重连可能补发进入当前 DRC 之前的事件。旧的“遥控器夺权”不能
  // 撤销刚刚重新获取的飞行权，也不能把新会话再次踢回第 1 步。
  if (eventTimestamp > 0 && (
    eventTimestamp <= lastJoystickInvalidEventAt ||
    (drcConnectedAt > 0 && eventTimestamp < drcConnectedAt)
  )) return
  if (eventTimestamp > 0) lastJoystickInvalidEventAt = eventTimestamp
  const reason = Number(notice.reason ?? notice.result)
  const reasonText = joystickInvalidReasonMessage(notice)
  const statusMessage = `DRC 控制异常：${reasonText}`
  const authorityLost = reason === 4

  addInteractionLog({
    transport: 'SYSTEM',
    direction: 'IN',
    summary: statusMessage,
    payload: { ...notice, reason }
  })

  // 所有原因都会立即停止 drone_control；只有 reason=4 明确表示控制权已经
  // 转交遥控器。低电量、限飞区等安全策略不能被误显示成“飞行权被夺走”。
  if (authorityLost) flightControlSource.value = ''
  drcControlRejected.value = true
  drcControlFailure.value = statusMessage
  drcConnectionState.value = 'degraded'
  drcStatusMessage.value = statusMessage
  releaseKeys()
  error.value = authorityLost
    ? `${statusMessage}；已停止控制输出，请重新抢夺飞行控制权并进入 DRC`
    : `${statusMessage}；已停止控制输出，请先处理设备安全状态`

  if (!active.value) return
  void leaveDrc('joystick-invalid').catch((leaveReason) => {
    const detail = leaveReason instanceof Error ? leaveReason.message : '退出 DRC 未确认'
    error.value = `${statusMessage}；${detail}`
  }).finally(() => {
    // leaveDrc 成功时会清理会话状态；重新锁存本次设备事件，避免界面误显示
    // 为普通“已退出”，并确保操作者必须重新完成抢权与进入 DRC 两个步骤。
    if (authorityLost) flightControlSource.value = ''
    drcControlRejected.value = true
    drcControlFailure.value = statusMessage
    drcConnectionState.value = 'degraded'
    drcStatusMessage.value = statusMessage
    if (authorityLost) {
      error.value = `${statusMessage}；控制权当前属于遥控器，请重新抢权后再进入 DRC`
    }
  })
}

function payloadAuthorityKeyFromSn(payloadSn: string): string {
  if (!payloadSn) return ''
  for (const dock of docks.value) {
    const aircraft = aircraftDeviceForDock(dock)
    const payloads = (aircraft?.payloads_list ?? aircraft?.payloads ?? []) as Record<string, unknown>[]
    const payload = payloads.find((item) =>
      String(item.payload_sn ?? item.payloadSn ?? item.sn ?? '') === payloadSn)
    const payloadIndex = String(payload?.payload_index ?? payload?.payloadIndex ?? '')
    if (payloadIndex) return `${dock.device_sn}/${payloadIndex}`
  }
  return ''
}

function hydrateAuthorityState() {
  const reportedFlightSource = controlSourceValue(selectedDock.value?.control_source)
  // Device topology can lag behind a successful flight_authority_grab reply. Do
  // not let that stale snapshot erase the locally confirmed A authority while
  // entering/using DRC (or during the short operator transition into DRC).
  // Realtime control_source_change and joystick-invalid events still override it.
  const protectConfirmedAuthority = flightControlSource.value === 'A' && (
    active.value || drcEnterPending.value || Date.now() - lastFlightAuthorityGrabAt < 30_000
  )
  if (!protectConfirmedAuthority) flightControlSource.value = reportedFlightSource
  const authorityKey = payloadAuthorityKey.value
  const payloadIndex = selectedSource.value?.cameraIndex
  if (!authorityKey || !payloadIndex) return
  const aircraft = aircraftDeviceForDock(selectedDock.value)
  const payloads = (aircraft?.payloads_list ?? aircraft?.payloads ?? []) as Record<string, unknown>[]
  const payload = payloads.find((item) =>
    String(item.payload_index ?? item.payloadIndex ?? '') === payloadIndex)
  const source = controlSourceValue(payload?.control_source ?? payload?.controlSource)
  if (source === 'A') payloadAuthorityKeys.add(authorityKey)
  else payloadAuthorityKeys.delete(authorityKey)
}

function lensLabel(value: string) {
  return ({ normal: '默认', wide: '广角', zoom: '变焦', ir: '红外', thermal: '热成像' } as Record<string, string>)[value] || value
}

async function selectDock(sn: string) {
  if (sn === dockSn.value || dockSelectionPending.value || state.value !== 'idle' ||
      flightAuthorityPending.value || payloadAuthorityPending.value || mapTargetPending.value ||
      drcEnterPending.value || payloadCommandPending.value > 0 || cameraCommandPending.value !== '' ||
      targetDetectionPending.value || takeoffPending.value || returnHomePending.value ||
      emergencyStopPending.value || drcLandingPending.value || flyToStopPending.value || videoState.value === 'connecting' ||
      videoRetrying.value || qualitySwitching.value) return
  const previousDockSn = dockSn.value
  dockSelectionPending.value = true
  try {
    // Switching the cockpit view only closes this browser's WHEP reader. Keep
    // the device publisher alive so switching back can reuse the existing
    // MediaMTX stream instead of issuing another device stop/start cycle.
    await detachVideoForDeviceSwitch()
    if (
      state.value !== 'idle' || drcEnterPending.value || flightAuthorityPending.value ||
      payloadAuthorityPending.value || mapTargetPending.value || payloadCommandPending.value > 0 ||
      takeoffPending.value || returnHomePending.value || emergencyStopPending.value ||
      drcLandingPending.value || flyToStopPending.value || videoRetrying.value || qualitySwitching.value ||
      dockSn.value !== previousDockSn ||
      !docks.value.some((device) => device.device_sn === sn)
    ) return
    allowAutomaticDockSelection.value = true
    disconnectedDockSn = ''
    dockSn.value = sn
    selectedVideoId.value = ''
    videoError.value = ''
    reportedLive.value = false
    targetDetectionEnabled.value = false
    detectedTargets.value = []
    pointFlightProgress.value = undefined
    hidePointFlightNotice()
    resetPointFlightTracking()
    flyToStopPending.value = false
    flightControlSource.value = ''
    mapTargetPanelOpen.value = false
    mapTargetMode.value = undefined
    if (mapTargetMarker) {
      map?.remove(mapTargetMarker)
      mapTargetMarker = undefined
    }
    clearPointFlightTargetMarker(true)
    Object.assign(mapTargetDrafts.flyto, { latitude: 0, longitude: 0, height: 10, maxSpeed: 5 })
    Object.assign(mapTargetDrafts.lookAt, { latitude: 0, longitude: 0, height: 10, maxSpeed: 5 })
    Object.assign(mapTarget, mapTargetDrafts.flyto)
    Object.assign(telemetry, {
      altitude: 0, height: 0, speed: 0, verticalSpeed: 0, homeDistance: 0,
      satellites: 0, rtkNumber: 0, gpsQuality: 0, gpsFixed: 0, battery: 0,
      heading: 0, pitch: 0, roll: 0, latitude: 0, longitude: 0,
      rcLatitude: 0, rcLongitude: 0,
      gimbalReported: false, gimbalPitch: 0, gimbalYaw: 0,
      zoomFactor: 1, irZoomFactor: 1,
      remainFlightTime: -1, remainWorkTime: -1,
      taskRemainingDistance: -1, taskRemainingTime: -1, pointFlightActive: false,
      modeCode: -1,
      obstacleFront: -1, obstacleBack: -1, obstacleLeft: -1, obstacleRight: -1,
      obstacleUp: -1, obstacleDown: -1, radarEnabled: undefined, hsiUpdatedAt: 0,
      measureReported: false, measureState: -1, measureDistance: -1,
      measureLatitude: 0, measureLongitude: 0, measureAltitude: 0
    })
    resetObstacleDistances()
    clearMeasuredTargetMarker()
    clearRemoteControllerMarker()
    syncSelections()
    // An unseen publisher starts at SD. A publisher visited earlier keeps its
    // explicit preference, so A(HD) -> B -> A resumes A in HD.
    selectPreferredVideoQuality(selectedSource.value)
    await loadPointFlightState(sn)
  } finally {
    dockSelectionPending.value = false
  }
  if (dockSn.value === sn && selectedVideoId.value && videoState.value === 'idle') {
    await nextTick()
    void startVideo()
  }
}

let lastMeasurementPayloadKey = ''
watch(selectedMeasurementPayloadKey, (payloadKey) => {
  // 同一负载切换广角/变焦/红外视频不应清空测距结果；只有真正切换负载时重置。
  if (!payloadKey) return
  const payloadChanged = Boolean(lastMeasurementPayloadKey && payloadKey !== lastMeasurementPayloadKey)
  lastMeasurementPayloadKey = payloadKey
  if (!payloadChanged) return
  Object.assign(telemetry, {
    measureReported: false, measureState: -1, measureDistance: -1,
    measureLatitude: 0, measureLongitude: 0, measureAltitude: 0
  })
  clearMeasuredTargetMarker()
})

watch(selectedVideoId, async (videoId, previousVideoId) => {
  payloadZoomTarget.value = undefined
  hydrateAuthorityState()
  if (!videoId || videoId === previousVideoId) return
  selectPreferredVideoQuality(selectedSource.value)
  await nextTick()
  if (videoState.value === 'idle') void startVideo()
}, { flush: 'post' })

watch(hasPayloadAuthority, (hasAuthority) => {
  if (!hasAuthority) releasePayloadControls()
})

watch(selectedAircraftOnline, (online, previousOnline) => {
  if (online) {
    if (telemetry.latitude !== 0 || telemetry.longitude !== 0) {
      updateMap(telemetry.longitude, telemetry.latitude, telemetry.heading)
    }
    void loadPointFlightState(dockSn.value)
    return
  }
  if (!previousOnline) return

  releaseKeys()
  releasePayloadControls()
  flightControlSource.value = ''
  for (const key of [...payloadAuthorityKeys]) {
    if (key.startsWith(`${dockSn.value}/`)) payloadAuthorityKeys.delete(key)
  }
  droneMarker?.hide?.()
  reportedLive.value = false
  videoError.value = '遥控器已与飞机断开连接，等待飞机重新上线'
  void stopVideo()
  Object.assign(telemetry, {
    altitude: 0, height: 0, speed: 0, verticalSpeed: 0, homeDistance: 0,
    satellites: 0, rtkNumber: 0, gpsQuality: 0, gpsFixed: 0, battery: 0,
    latitude: 0, longitude: 0, remainFlightTime: -1, modeCode: -1,
    radarEnabled: undefined, hsiUpdatedAt: 0
  })
  resetObstacleTelemetry()
  if (waylineTaskOpen.value) waylineTaskError.value = '遥控器已与飞机断开，航线任务不能执行'
  if (active.value || state.value === 'connecting') {
    void leaveDrc('aircraft-offline').catch((reason) => {
      error.value = reason instanceof Error ? reason.message : '飞机断开后退出 DRC 失败'
    })
  }
})

watch(pointFlightMapActive, () => {
  updatePointFlightTargetMarker()
})

// 待机状态允许预先建立 DRC/心跳链路，但绝不允许非零摇杆输出。落地或 OSD
// 短暂抖回地面时只归零，不主动退出 DRC，避免设备因云端停止心跳而立即超时。
watch(operationPanelState, (nextState) => {
  if (nextState !== 'ground') return
  cancelContinuousLandingArm()
  // 离开任务状态即清除航线进度，避免下次进入任务面板残留上一段任务的数据。
  waylineProgress.value = undefined

  if (continuousLandingActive.value) {
    const landed = telemetry.modeCode === 0
    stopContinuousLanding(
      landed ? 'aircraft-standby' : 'aircraft-ground-state',
      landed
        ? '飞机已进入待机，持续降落已自动停止并归零'
        : `飞机状态变为“${modeLabel(telemetry.modeCode)}”，持续降落已停止并归零`
    )
  }

  if (
    pressed.size > 0 ||
    sticks.leftX !== 0 || sticks.leftY !== 0 ||
    sticks.rightX !== 0 || sticks.rightY !== 0 ||
    lastControlVector !== '0/0/0/0'
  ) releaseKeys()

})

// 持续下降只属于建立它的当前 DRC 会话。心跳、MQTT、飞行权或在线状态任一
// 条件失效，都立即撤销本地锁存并尽力补发零杆量，禁止链路恢复后自行续降。
watch(drcLinkReady, (ready) => {
  if (!ready) {
    cancelContinuousLandingArm()
    if (continuousLandingActive.value) {
      stopContinuousLanding('drc-link-lost', 'DRC 控制链路中断，持续降落已停止并归零')
    }
  }
})

// DRC 会话绑定实际控制主题（遥控器接入使用飞机 SN，机场接入使用网关 SN）；
// 同一遥控器/网关重新挂载另一架飞机时，不能继续复用旧会话。
// 会把控制量发给新飞机。飞机绑定变化即关闭本地输出并退出当前 DRC。
watch(selectedAircraftSn, (aircraftSn) => {
  if (!drcAircraftSn || aircraftSn === drcAircraftSn) return
  const previousAircraftSn = drcAircraftSn
  drcEnterCancelled = true
  releaseKeys()
  error.value = `网关挂载飞机已由 ${previousAircraftSn} 变更为 ${aircraftSn || '未知'}，DRC 已关闭，请重新确认设备`
  void leaveDrc('aircraft-binding-change').catch((reason) => {
    error.value = reason instanceof Error ? reason.message : '飞机绑定变化后退出 DRC 失败'
  })
})

async function loadCockpitData() {
  const [deviceData, liveData] = await Promise.all([
    get<Device[]>(`/manage/api/v1/devices/${cockpitWorkspaceId}/devices`),
    get<CapacityDevice[]>('/manage/api/v1/live/capacity')
  ])
  devices.value = deviceData
  capacity.value = liveData
  const resumableDockSn = drcResumeMarker.value?.dockSn
  if (resumableDockSn && deviceData.some((device) =>
    device.device_sn === resumableDockSn && isGatewayDevice(device) && isOnlineDevice(device))) {
    dockSn.value = resumableDockSn
  }
  syncSelections()
  await loadPointFlightState(dockSn.value)
}

function scheduleTopologyRefresh(videoReconnectReason = '') {
  if (videoReconnectReason) topologyVideoReconnectReason = videoReconnectReason
  window.clearTimeout(topologyRefreshTimer)
  topologyRefreshTimer = window.setTimeout(() => {
    const reconnectReason = topologyVideoReconnectReason
    topologyVideoReconnectReason = ''
    const previousSourceKey = selectedSource.value?.key ?? ''
    void loadCockpitData()
      .then(() => {
        if (reconnectReason) {
          const sameSession = !!previousSourceKey && selectedSource.value?.key === previousSourceKey
          scheduleVideoReconnect(reconnectReason, 300, sameSession)
        }
      })
      .catch((reason) => {
        error.value = reason instanceof Error ? reason.message : '设备状态刷新失败'
      })
  }, 200)
}

// ────────── AMap ──────────

function droneIcon(hdg: number, isCtrl: boolean) {
  const c = isCtrl ? '#3fa9ff' : '#35d6a4'
  return `<div style="transform:rotate(${hdg}deg);filter:drop-shadow(0 2px 8px rgba(0,0,0,.65));line-height:0">
    <svg viewBox="0 0 24 24" width="40" height="40">
      <path d="M12 2L22 20.5L12 16L2 20.5Z" fill="${c}" stroke="rgba(255,255,255,.8)" stroke-width="1.4"/>
      <circle cx="12" cy="12" r="2.5" fill="white" opacity=".85"/>
    </svg></div>`
}

function remoteControllerIcon() {
  return `<div style="position:relative;width:30px;height:30px;filter:drop-shadow(0 3px 8px rgba(0,0,0,.75))">
    <span style="position:absolute;inset:0;display:grid;place-items:center;box-sizing:border-box;border:2px solid rgba(255,255,255,.9);border-radius:50%;background:#7968e8">
      <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="#fff" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
        <path d="M7 8h10c2 0 3 1.2 3.5 3.2l1 4.2c.4 1.7-1.5 2.9-2.8 1.8l-2.5-2.1H7.8l-2.5 2.1c-1.3 1.1-3.2-.1-2.8-1.8l1-4.2C4 9.2 5 8 7 8Z"/>
        <path d="M7 11v3M5.5 12.5h3M16.5 11.5h.1M18.5 13.5h.1"/>
      </svg>
    </span>
  </div>`
}

function clearRemoteControllerMarker() {
  if (!remoteControllerMarker) return
  map?.remove(remoteControllerMarker)
  remoteControllerMarker = undefined
}

function updateRemoteControllerMarker() {
  if (!map || !AMapRef) return
  if (!remoteControllerCoordinatesValid.value) {
    clearRemoteControllerMarker()
    return
  }
  const [gLng, gLat] = wgs84ToGcj02(telemetry.rcLongitude, telemetry.rcLatitude)
  if (!remoteControllerMarker) {
    remoteControllerMarker = new AMapRef.Marker({
      anchor: 'center', zIndex: 230,
      content: remoteControllerIcon(), position: [gLng, gLat]
    })
    map.add(remoteControllerMarker)
  } else {
    remoteControllerMarker.setPosition([gLng, gLat])
  }
}

function targetIcon(mode: MapTargetMode) {
  const color = mode === 'flyto' ? '#3fa9ff' : '#ffb04f'
  const label = mode === 'flyto' ? '指点飞行目标' : 'Look At 目标'
  return `<div style="position:relative;width:18px;height:18px;filter:drop-shadow(0 3px 8px rgba(0,0,0,.7))">
    <span style="position:absolute;left:50%;bottom:calc(100% + 4px);transform:translateX(-50%);padding:3px 7px;border:1px solid ${color};border-radius:5px;color:#fff;background:rgba(4,10,18,.88);font:10px sans-serif;white-space:nowrap">${label}</span>
    <span style="position:absolute;inset:0;box-sizing:border-box;border:3px solid #fff;border-radius:50%;background:${color}"></span>
  </div>`
}

function pointFlightTargetIcon() {
  return `<div style="position:relative;width:22px;height:22px;filter:drop-shadow(0 3px 9px rgba(0,0,0,.8))">
    <span style="position:absolute;left:50%;bottom:calc(100% + 5px);transform:translateX(-50%);padding:4px 8px;border:1px solid #35d6a4;border-radius:5px;color:#dffff5;background:rgba(3,20,17,.94);font:700 10px sans-serif;white-space:nowrap">指点飞行执行中</span>
    <span style="position:absolute;inset:0;box-sizing:border-box;border:3px solid #eafff8;border-radius:50%;background:#35d6a4;box-shadow:0 0 0 5px rgba(53,214,164,.16)"></span>
  </div>`
}

function measuredTargetIcon() {
  const distance = telemetry.measureDistance > 0 ? ` · ${telemetry.measureDistance.toFixed(1)} m` : ''
  return `<div style="position:relative;width:28px;height:28px;filter:drop-shadow(0 3px 8px rgba(0,0,0,.75))">
    <span style="position:absolute;left:50%;bottom:calc(100% + 5px);transform:translateX(-50%);padding:3px 7px;border:1px solid #35d6a4;border-radius:5px;color:#dffff5;background:rgba(4,18,18,.9);font:10px sans-serif;white-space:nowrap">测距目标${distance}</span>
    <span style="position:absolute;left:50%;top:0;width:2px;height:28px;transform:translateX(-50%);background:#35d6a4;box-shadow:0 0 0 1px rgba(255,255,255,.72)"></span>
    <span style="position:absolute;left:0;top:50%;width:28px;height:2px;transform:translateY(-50%);background:#35d6a4;box-shadow:0 0 0 1px rgba(255,255,255,.72)"></span>
  </div>`
}

function clearMeasuredTargetMarker() {
  measuredTargetPositionKey = ''
  measuredTargetContentKey = ''
  if (!measuredTargetMarker) return
  map?.remove(measuredTargetMarker)
  measuredTargetMarker = undefined
}

function updateMeasuredTargetMarker() {
  if (!map || !AMapRef) return
  if (!measureCoordinatesValid.value) {
    clearMeasuredTargetMarker()
    return
  }
  const [gLng, gLat] = wgs84ToGcj02(telemetry.measureLongitude, telemetry.measureLatitude)
  const positionKey = `${gLng.toFixed(6)},${gLat.toFixed(6)}`
  const contentKey = telemetry.measureDistance > 0 ? telemetry.measureDistance.toFixed(1) : ''
  if (!measuredTargetMarker) {
    measuredTargetMarker = new AMapRef.Marker({
      anchor: 'center', zIndex: 250,
      content: measuredTargetIcon(), position: [gLng, gLat]
    })
    map.add(measuredTargetMarker)
    measuredTargetPositionKey = positionKey
    measuredTargetContentKey = contentKey
    return
  }
  if (positionKey !== measuredTargetPositionKey) {
    measuredTargetPositionKey = positionKey
    measuredTargetMarker.setPosition([gLng, gLat])
  }
  if (contentKey !== measuredTargetContentKey) {
    measuredTargetContentKey = contentKey
    measuredTargetMarker.setContent(measuredTargetIcon())
  }
}

function updateMapTargetMarker() {
  const mode = mapTargetMode.value
  if (mode) Object.assign(mapTargetDrafts[mode], mapTarget)
  if (!map || !AMapRef || !mode || !mapTargetValid.value) {
    if (mapTargetMarker) {
      map?.remove(mapTargetMarker)
      mapTargetMarker = undefined
    }
    return
  }
  const [gLng, gLat] = wgs84ToGcj02(mapTarget.longitude, mapTarget.latitude)
  if (!mapTargetMarker) {
    mapTargetMarker = new AMapRef.Marker({
      anchor: 'center', zIndex: 260,
      content: targetIcon(mode), position: [gLng, gLat]
    })
    map.add(mapTargetMarker)
  } else {
    mapTargetMarker.setPosition([gLng, gLat])
    mapTargetMarker.setContent(targetIcon(mode))
  }
}

function clearPointFlightTargetMarker(resetTarget = false) {
  if (pointFlightTargetMarker) {
    map?.remove(pointFlightTargetMarker)
    pointFlightTargetMarker = undefined
  }
  if (resetTarget) Object.assign(pointFlightTarget, { latitude: 0, longitude: 0, height: 0 })
}

function updatePointFlightTargetMarker() {
  if (!map || !AMapRef || !pointFlightMapActive.value || !pointFlightTargetValid.value) {
    if (!pointFlightMapActive.value) clearPointFlightTargetMarker()
    return
  }
  const [gLng, gLat] = wgs84ToGcj02(pointFlightTarget.longitude, pointFlightTarget.latitude)
  if (!pointFlightTargetMarker) {
    pointFlightTargetMarker = new AMapRef.Marker({
      anchor: 'center', zIndex: 270,
      content: pointFlightTargetIcon(), position: [gLng, gLat]
    })
    map.add(pointFlightTargetMarker)
  } else {
    pointFlightTargetMarker.setPosition([gLng, gLat])
    pointFlightTargetMarker.setContent(pointFlightTargetIcon())
  }
}

function locatePointFlightTarget() {
  if (!map || !pointFlightTargetValid.value) return
  updatePointFlightTargetMarker()
  const [gLng, gLat] = wgs84ToGcj02(pointFlightTarget.longitude, pointFlightTarget.latitude)
  userInteracting = true
  map.setCenter([gLng, gLat])
}

function selectMapTarget(mode: MapTargetMode) {
  if (mapTargetMode.value) Object.assign(mapTargetDrafts[mapTargetMode.value], mapTarget)
  if (mode === 'flyto' && pointFlightMapActive.value && pointFlightTargetValid.value &&
      mapTargetDrafts.flyto.latitude === 0 && mapTargetDrafts.flyto.longitude === 0) {
    Object.assign(mapTargetDrafts.flyto, pointFlightTarget)
  }
  Object.assign(mapTarget, mapTargetDrafts[mode])
  mapTargetMode.value = mode
  mapTargetPanelOpen.value = true
  updateMapTargetMarker()
}

function handleMapTargetClick(event: { lnglat?: { getLng?: () => number; getLat?: () => number; lng?: number; lat?: number } }) {
  if (!mapTargetMode.value) return
  const lng = Number(event.lnglat?.getLng?.() ?? event.lnglat?.lng)
  const lat = Number(event.lnglat?.getLat?.() ?? event.lnglat?.lat)
  if (!Number.isFinite(lng) || !Number.isFinite(lat)) return
  const [wLng, wLat] = gcj02ToWgs84(lng, lat)
  mapTarget.longitude = Number(wLng.toFixed(6))
  mapTarget.latitude = Number(wLat.toFixed(6))
  mapTargetPanelOpen.value = true
  updateMapTargetMarker()
}

function clearMapTarget() {
  const mode = mapTargetMode.value
  if (mode) {
    Object.assign(mapTargetDrafts[mode], { latitude: 0, longitude: 0 })
    Object.assign(mapTarget, mapTargetDrafts[mode])
  }
  mapTargetMode.value = undefined
  mapTargetPanelOpen.value = false
  if (mapTargetMarker) {
    map?.remove(mapTargetMarker)
    mapTargetMarker = undefined
  }
}

function locateMeasuredTarget() {
  if (!measureCoordinatesValid.value) return
  updateMeasuredTargetMarker()
  const [gLng, gLat] = wgs84ToGcj02(telemetry.measureLongitude, telemetry.measureLatitude)
  userInteracting = true
  map?.setCenter([gLng, gLat])
}

async function initMap() {
  if (!hasMapKey.value || !mapContainer.value) return
  try {
    AMapRef = await loadAMap()
    map = new AMapRef.Map(mapContainer.value, {
      zoom: 16, center: [116.397, 39.909],
      layers: [new AMapRef.TileLayer.Satellite(), new AMapRef.TileLayer.RoadNet()],
      viewMode: '2D'
    })
    // 飞机是地图上的主操作对象，必须始终压过遥控器、测距和指点标记。
    droneMarker = new AMapRef.Marker({ anchor: 'center', content: droneIcon(0, false), zIndex: 1000 })
    // AMap rejects an empty or single-point Polyline. Create the trail only
    // after two valid aircraft positions have been collected.
    droneTrail = undefined
    map.add(droneMarker)
    updateRemoteControllerMarker()
    updateMeasuredTargetMarker()
    updatePointFlightTargetMarker()
    // 手动拖拽或缩放后保持用户选择的地图位置，不自动拉回飞机坐标。
    const pauseFollow = () => {
      userInteracting = true
    }
    map.on('dragging', pauseFollow)
    map.on('zoomchange', pauseFollow)
    map.on('dragstart', pauseFollow)
    map.on('click', handleMapTargetClick)
  } catch { /* 地图不可用 */ }
}

function updateMap(lng: number, lat: number, hdg: number) {
  if (!map || !AMapRef || !Number.isFinite(lng) || !Number.isFinite(lat) || (lng === 0 && lat === 0)) return
  const [gLng, gLat] = wgs84ToGcj02(lng, lat)
  droneMarker?.show?.()
  droneMarker?.setPosition([gLng, gLat])
  droneMarker?.setContent(droneIcon(hdg, active.value))
  // 用户拖拽/缩放期间不自动 setCenter，避免把地图拉回飞机坐标
  if (!userInteracting) map.setCenter([gLng, gLat])
  const last = trailPts[trailPts.length - 1]
  if (!last || Math.abs(last[0] - gLng) > 1e-5 || Math.abs(last[1] - gLat) > 1e-5) {
    trailPts.push([gLng, gLat])
    if (trailPts.length > 500) trailPts.shift()
    if (trailPts.length >= 2) {
      if (!droneTrail) {
        droneTrail = new AMapRef.Polyline({
          path: trailPts,
          strokeColor: '#3fa9ff',
          strokeWeight: 3,
          strokeOpacity: .85,
          zIndex: 100
        })
        map.add(droneTrail)
      } else {
        droneTrail.setPath(trailPts)
      }
    }
  }
}

function centerOnDrone() {
  if (map && (telemetry.longitude !== 0 || telemetry.latitude !== 0)) {
    const [gLng, gLat] = wgs84ToGcj02(telemetry.longitude, telemetry.latitude)
    // 点击定位时强制恢复跟随并居中到飞机坐标
    userInteracting = false
    map.setCenter([gLng, gLat])
  }
}

function toggleMapSat() {
  mapSatellite.value = !mapSatellite.value
  if (!map || !AMapRef) return
  if (mapSatellite.value) {
    map.setLayers([new AMapRef.TileLayer.Satellite(), new AMapRef.TileLayer.RoadNet()])
  } else {
    map.setLayers([new AMapRef.TileLayer()])
    map.setMapStyle('amap://styles/darkblue')
  }
}

function aircraftTelemetry(message: DeviceTelemetry): OsdHost | undefined {
  const host = message.host
  const aircraftSn = selectedAircraftSn.value
  if (!aircraftSn) return undefined
  const hostSn = String(host.sn ?? '')

  if (message.sn === aircraftSn || hostSn === aircraftSn) return host

  const drones = host.drone_list
  if (!Array.isArray(drones)) return undefined
  return drones.find((item) =>
    String((item as Record<string, unknown>).sn ?? '') === aircraftSn
  ) as OsdHost | undefined
}

function telemetryNumber(value: unknown, fallback: number): number {
  if (value === undefined || value === null || value === '') return fallback
  const number = Number(value)
  return Number.isFinite(number) ? number : fallback
}

function optionalTelemetryNumber(value: unknown): number | undefined {
  if (value === undefined || value === null || value === '') return undefined
  const number = Number(value)
  return Number.isFinite(number) ? number : undefined
}

function formatCoordinate(value: number): string {
  // 直接展示 WebSocket 回传的双精度数值，不固定小数位，避免再次舍入或补零。
  return Number.isFinite(value) ? String(value) : '--'
}

function updateRemoteControllerTelemetry(message: DeviceTelemetry) {
  if (!message.sn || message.sn !== dockSn.value) return
  const latitude = optionalTelemetryNumber(message.host?.latitude)
  const longitude = optionalTelemetryNumber(message.host?.longitude)
  if (latitude === undefined || longitude === undefined) return
  telemetry.rcLatitude = latitude
  telemetry.rcLongitude = longitude
  updateRemoteControllerMarker()
}

function formatGimbalAngle(value: number) {
  const normalized = Math.abs(value) < 0.05 ? 0 : value
  return `${normalized.toFixed(1)}°`
}

function applyTelemetry(message: DeviceTelemetry) {
  updateDeviceCardTelemetry(message)
  if (message.sn && !selectedDeviceSns.value.has(message.sn)) return
  // gateway_osd 的 host 坐标来自遥控器；飞行器坐标则由 device_osd 单独更新。
  updateRemoteControllerTelemetry(message)
  const data = aircraftTelemetry(message)
  if (!data) return
  const battery = data.battery as { capacity_percent?: number; remain_flight_time?: number } | undefined
  const position = data.position_state as {
    gps_number?: number
    rtk_number?: number
    quality?: number
    is_fixed?: number
  } | undefined
  const obstacle = data.obstacle_avoidance as Record<string, unknown> | undefined
  telemetry.altitude = telemetryNumber(data.elevation, telemetry.altitude)
  telemetry.height = telemetryNumber(data.height, telemetry.height)
  telemetry.speed = telemetryNumber(data.horizontal_speed, telemetry.speed)
  telemetry.verticalSpeed = telemetryNumber(data.vertical_speed, telemetry.verticalSpeed)
  telemetry.homeDistance = telemetryNumber(data.home_distance, telemetry.homeDistance)
  telemetry.satellites = telemetryNumber(data.gps_number ?? position?.gps_number, telemetry.satellites)
  telemetry.rtkNumber = telemetryNumber(position?.rtk_number, telemetry.rtkNumber)
  telemetry.gpsQuality = telemetryNumber(position?.quality, telemetry.gpsQuality)
  telemetry.gpsFixed = telemetryNumber(position?.is_fixed, telemetry.gpsFixed)
  telemetry.battery = telemetryNumber(battery?.capacity_percent ?? data.capacity_percent, telemetry.battery)
  telemetry.heading = telemetryNumber(data.attitude_head, telemetry.heading)
  telemetry.pitch = telemetryNumber(data.attitude_pitch, telemetry.pitch)
  telemetry.roll = telemetryNumber(data.attitude_roll, telemetry.roll)
  telemetry.latitude = telemetryNumber(data.latitude, telemetry.latitude)
  telemetry.longitude = telemetryNumber(data.longitude, telemetry.longitude)
  const cameras = Array.isArray(data.cameras)
    ? data.cameras as Record<string, unknown>[]
    : []
  // Java OSD 模型按协议序列化为 payload；兼容早期前端/模拟数据使用的 payloads。
  const reportedPayloads = data.payload ?? data.payloads
  const payloads = Array.isArray(reportedPayloads)
    ? reportedPayloads as Record<string, unknown>[]
    : []
  const payloadIndex = selectedSource.value?.cameraIndex
  const selectedCamera = cameras.find((camera) =>
    String(camera.payload_index ?? '') === payloadIndex) ?? cameras[0]
  // 按 payload_index 精确匹配当前相机的负载状态（实时变焦值的权威源）
  const selectedPayload = payloadIndex
    ? payloads.find((payload) => String(payload.payload_index ?? payload.payloadIndex ?? '') === payloadIndex) ?? payloads[0]
    : payloads[0]
  const payloadTelemetry = payloadIndex && data[payloadIndex] && typeof data[payloadIndex] === 'object'
    ? data[payloadIndex] as Record<string, unknown>
    : undefined
  const gimbalPitch = optionalTelemetryNumber(
    payloadTelemetry?.gimbal_pitch ?? payloadTelemetry?.gimbalPitch ??
    selectedPayload?.gimbal_pitch ?? selectedPayload?.gimbalPitch ??
    selectedCamera?.gimbal_pitch ?? selectedCamera?.gimbalPitch)
  const gimbalYaw = optionalTelemetryNumber(
    payloadTelemetry?.gimbal_yaw ?? payloadTelemetry?.gimbalYaw ??
    selectedPayload?.gimbal_yaw ?? selectedPayload?.gimbalYaw ??
    selectedCamera?.gimbal_yaw ?? selectedCamera?.gimbalYaw)
  // 网关 OSD 与飞行器 OSD 会交替到达；网关包不含云台字段，不能因此清空
  // 飞行器负载节点刚上报的角度。设备切换/离线时再统一重置。
  if (gimbalPitch !== undefined) {
    telemetry.gimbalReported = true
    telemetry.gimbalPitch = gimbalPitch
  }
  // gimbal_yaw 为绝对方位角（与 attitude_head 同基准）。
  if (gimbalYaw !== undefined) {
    telemetry.gimbalYaw = gimbalYaw
  }
  // 可见光与红外各自维护倍率，避免切换镜头后沿用另一镜头的范围与刻度。
  telemetry.zoomFactor = Math.max(1, Math.min(160, telemetryNumber(
    selectedCamera?.zoom_factor ??
    selectedPayload?.zoom_factor ??
    payloadTelemetry?.zoom_factor,
    telemetry.zoomFactor)))
  telemetry.irZoomFactor = Math.max(1, Math.min(16, telemetryNumber(
    selectedCamera?.ir_zoom_factor ??
    selectedPayload?.ir_zoom_factor ??
    payloadTelemetry?.ir_zoom_factor,
    telemetry.irZoomFactor)))
  const measureState = payloadTelemetry?.measure_target_error_state ?? selectedPayload?.measure_target_error_state
  const measureDistance = payloadTelemetry?.measure_target_distance ?? selectedPayload?.measure_target_distance
  const measureLatitude = payloadTelemetry?.measure_target_latitude ?? selectedPayload?.measure_target_latitude
  const measureLongitude = payloadTelemetry?.measure_target_longitude ?? selectedPayload?.measure_target_longitude
  const measureAltitude = payloadTelemetry?.measure_target_altitude ?? selectedPayload?.measure_target_altitude
  const measureReportedInMessage = [
    measureState, measureDistance, measureLatitude, measureLongitude, measureAltitude
  ].some((value) => value !== undefined && value !== null)
  // gateway_osd 与 device_osd 会交替到达，网关包通常不包含负载测距字段。
  // 缺少字段只表示“本包未上报”，不能清空上一包有效结果，否则视频提示和
  // 地图目标会在有效值与未上报之间持续闪烁。设备/视频源切换及离线时另行重置。
  if (measureReportedInMessage) {
    telemetry.measureReported = true
    telemetry.measureState = telemetryNumber(measureState, -1)
    telemetry.measureDistance = telemetryNumber(measureDistance, -1)
    telemetry.measureLatitude = telemetryNumber(measureLatitude, 0)
    telemetry.measureLongitude = telemetryNumber(measureLongitude, 0)
    telemetry.measureAltitude = telemetryNumber(measureAltitude, 0)
  }
  updateMeasuredTargetMarker()
  const recordingState = Number(selectedCamera?.recording_state)
  if (Number.isFinite(recordingState)) {
    recording.value = recordingState === 1
    recordingSeconds.value = recording.value
      ? telemetryNumber(selectedCamera?.record_time, recordingSeconds.value)
      : 0
  }
  telemetry.remainFlightTime = telemetryNumber(battery?.remain_flight_time, telemetry.remainFlightTime)
  telemetry.remainWorkTime = telemetryNumber(data.remain_work_time ?? data.remainWorkTime, telemetry.remainWorkTime)
  telemetry.taskRemainingDistance = telemetryNumber(
    data.task_remaining_distance ?? data.remaining_distance ?? data.fly_to_point_distance,
    telemetry.taskRemainingDistance)
  telemetry.taskRemainingTime = telemetryNumber(
    data.task_remaining_time ?? data.remaining_time ?? data.fly_to_point_time ?? data.remain_work_time,
    telemetry.taskRemainingTime)
  const pointFlightStatus = data.fly_to_point_status ?? data.point_flight_status
  if (pointFlightStatus !== undefined && pointFlightStatus !== null) {
    telemetry.pointFlightActive = !['false', '0', 'idle', 'finished', 'failed', 'canceled']
      .includes(String(pointFlightStatus).toLowerCase())
  }
  telemetry.windSpeed = telemetryNumber(data.wind_speed, telemetry.windSpeed)
  telemetry.windDirection = telemetryNumber(data.wind_direction, telemetry.windDirection)
  telemetry.modeCode = telemetryNumber(data.mode_code, telemetry.modeCode)
  observeContinuousLandingMovement()
  // 当前档位：仅只读展示，Cloud API 不支持下发切换；兼容 gear_level / gear 两种上报字段名
  telemetry.gearLevel = telemetryNumber(data.gear_level ?? data.gear, telemetry.gearLevel)
  // 失联动作：0 悬停 / 1 降落 / 2 返航，由飞行器 OSD 上报，仅只读展示
  telemetry.rcLostAction = telemetryNumber(data.rc_lost_action ?? data.rcLostAction, telemetry.rcLostAction)
  // 四向障碍距离（米），-1 表示无数据
  if (obstacle) {
    telemetry.obstacleFront = telemetryNumber(obstacle.front_distance ?? obstacle.front, telemetry.obstacleFront)
    telemetry.obstacleBack = telemetryNumber(obstacle.back_distance ?? obstacle.back, telemetry.obstacleBack)
    telemetry.obstacleLeft = telemetryNumber(obstacle.left_distance ?? obstacle.left, telemetry.obstacleLeft)
    telemetry.obstacleRight = telemetryNumber(obstacle.right_distance ?? obstacle.right, telemetry.obstacleRight)
  }
  if (telemetry.latitude && telemetry.longitude) {
    updateMap(telemetry.longitude, telemetry.latitude, telemetry.heading)
  }
}

function updateDeviceCardTelemetry(message: DeviceTelemetry) {
  const host = message.host as Record<string, unknown>
  const dock = docks.value.find((item) =>
    item.device_sn === message.sn ||
    dockAircraftSn(item) === message.sn)
  if (!dock) return

  const previous = deviceCardTelemetry[dock.device_sn]
  const aircraftSn = dockAircraftSn(dock)
  const hostSn = String(host.sn ?? '')
  const drones = Array.isArray(host.drone_list) ? host.drone_list : []
  const nestedAircraft = drones.find((item) =>
    String((item as Record<string, unknown>).sn ?? '') === aircraftSn
  ) as Record<string, unknown> | undefined
  const aircraft = nestedAircraft ||
    (message.sn === aircraftSn || hostSn === aircraftSn ? host : undefined)
  const battery = aircraft?.battery as Record<string, unknown> | undefined
  const wireless = host.wireless_link as Record<string, unknown> | undefined
  const isGatewayMessage = message.sn === dock.device_sn || hostSn === dock.device_sn

  deviceCardTelemetry[dock.device_sn] = {
    battery: telemetryNumber(
      battery?.capacity_percent ?? aircraft?.capacity_percent,
      previous?.battery ?? Number(dock.battery_capacity ?? dock.capacity_percent ?? 0)),
    rcBattery: telemetryNumber(
      isGatewayMessage ? host.capacity_percent : undefined,
      previous?.rcBattery ?? -1),
    remainFlightTime: telemetryNumber(
      battery?.remain_flight_time ?? aircraft?.remain_flight_time,
      previous?.remainFlightTime ?? -1),
    modeCode: telemetryNumber(
      aircraft?.mode_code,
      previous?.modeCode ?? -1),
    rcSignal: telemetryNumber(
      wireless?.sdr_quality ?? host.sdr_quality,
      previous?.rcSignal ?? 0)
  }
}

function applyLiveStatus(message: DeviceTelemetry) {
  if (message.sn && message.sn !== dockSn.value) return
  const host = message.host as Record<string, unknown>
  const statuses = host.live_status ?? host.liveStatus
  if (!Array.isArray(statuses)) return
  // 精确匹配当前相机的 video_id 前缀（sn/payload/），无选中源时退化为 sn/ 或全匹配
  const src = selectedSource.value
  const camPrefix = src ? `${src.deviceSn}/${src.cameraIndex}/` : ''
  const devPrefix = !camPrefix && (src?.deviceSn || message.sn) ? `${src?.deviceSn || message.sn}/` : ''
  const matchCamera = (vid: string) =>
    camPrefix ? vid.startsWith(camPrefix) : devPrefix ? vid.startsWith(devPrefix) : true
  // 设备确认在播的镜头：status===1 且属于当前相机
  const liveItem = statuses.find((item) => {
    const rec = item as Record<string, unknown>
    const on = rec.status === true || rec.status === 1
    if (!on) return false
    return matchCamera(String(rec.video_id ?? rec.videoId ?? ''))
  }) as Record<string, unknown> | undefined
  const liveType = liveItem ? String(liveItem.video_type ?? liveItem.videoType ?? '') : ''
  const liveQuality = Number(liveItem?.video_quality ?? liveItem?.videoQuality)
  // 实时更新当前在播镜头类型，驱动镜头组高亮与置顶
  liveLensType.value = liveType
  // live_status 只描述当前设备/当前 payload 的实际状态，不覆盖该 publisher 的
  // 用户偏好。否则 A 的高清上报会在切到 B 的首个 start 前污染默认标清参数。
  reportedVideoQuality.value = liveQuality === 2 || liveQuality === 3
    ? liveQuality
    : undefined
  const wasReportedLive = reportedLive.value
  reportedLive.value = !!liveItem
  if (!wasReportedLive && reportedLive.value) {
    scheduleVideoReconnect('设备直播状态已恢复', 200)
  }
}

function targetClassLabel(classId: number) {
  return ({
    2: '船', 3: '小车', 4: '行人', 5: '骑行者', 6: '大车',
    34: '无人机', 35: '烟雾', 36: '火'
  } as Record<number, string>)[classId] ?? `目标 ${classId}`
}

function normalizeTargetCoordinate(value: unknown) {
  const number = Number(value)
  if (!Number.isFinite(number)) return 0
  return Math.max(0, Math.min(1, number))
}

function applyTargetDetectionReport(data: Record<string, unknown>) {
  const sn = String(data.sn ?? data.gateway_sn ?? '')
  if (sn && sn !== dockSn.value) return
  const objects = Array.isArray(data.objs) ? data.objs as Record<string, unknown>[] : []
  detectedTargets.value = objects.flatMap((object) => {
    const box = object.bbox as Record<string, unknown> | undefined
    if (!box) return []
    const x = normalizeTargetCoordinate(box.x)
    const y = normalizeTargetCoordinate(box.y)
    const width = Math.min(1 - x, normalizeTargetCoordinate(box.w))
    const height = Math.min(1 - y, normalizeTargetCoordinate(box.h))
    if (width <= 0 || height <= 0) return []
    return [{
      trackerId: String(object.tracker_id ?? object.trackerId ?? ''),
      classId: Number(object.cls_id ?? object.clsId ?? 99),
      x, y, width, height
    }]
  })
  targetDetectionEnabled.value = true
  window.clearTimeout(targetReportTimer)
  targetReportTimer = window.setTimeout(() => { detectedTargets.value = [] }, 2_000)
}

function eventNumber(data: Record<string, unknown>, snake: string, camel: string, fallback = -1) {
  return telemetryNumber(data[snake] ?? data[camel], fallback)
}

function pointFlightTaskId(data: Record<string, unknown>) {
  return String(data.fly_to_id ?? data.flyToId ?? data.flight_id ?? data.flightId ?? '')
}

function pointFlightServerVersion(data: Record<string, unknown>) {
  const version = Number(data.updated_at ?? data.updatedAt ?? 0)
  return Number.isFinite(version) && version > 0 ? version : 0
}

function pointFlightEventVersion(data: Record<string, unknown>) {
  const version = Number(data.timestamp ?? 0)
  return Number.isFinite(version) && version > 0 ? version : 0
}

function pointFlightStatusIsTerminal(status: string) {
  return [
    'task_finish', 'wayline_cancel', 'wayline_failed', 'wayline_ok',
    'command_failed', 'cancel_confirmed'
  ].includes(status)
}

function pointFlightStatusIsDevicePhase(status: string) {
  return status.startsWith('wayline_') || status.startsWith('task_')
}

function pointFlightStatusIsInitialCommand(status: string) {
  return ['command_pending', 'command_accepted', 'command_unknown'].includes(status)
}

function clearPointFlightRecovery() {
  window.clearTimeout(pointFlightRecoveryTimer)
  pointFlightRecoveryTimer = 0
}

function hidePointFlightNotice() {
  window.clearTimeout(pointFlightNoticeTimer)
  pointFlightNoticeTimer = 0
  pointFlightNoticeVisible.value = false
}

function schedulePointFlightNoticeDismiss() {
  window.clearTimeout(pointFlightNoticeTimer)
  pointFlightNoticeTimer = window.setTimeout(() => {
    pointFlightNoticeTimer = 0
    pointFlightNoticeVisible.value = false
  }, 8_000)
}

function schedulePointFlightRecovery(expectedDockSn: string) {
  if (componentExiting || !expectedDockSn || !pointFlightIdentityPending || pointFlightRecoveryTimer) return
  pointFlightRecoveryTimer = window.setTimeout(async () => {
    pointFlightRecoveryTimer = 0
    if (componentExiting || dockSn.value !== expectedDockSn || !pointFlightIdentityPending) return
    const restored = await loadPointFlightState(expectedDockSn)
    if (!restored && !componentExiting && dockSn.value === expectedDockSn && pointFlightIdentityPending) {
      schedulePointFlightRecovery(expectedDockSn)
    }
  }, 1_500)
}

function beginPointFlightSubmission(kind: 'takeoff' | 'flyto') {
  clearPointFlightRecovery()
  window.clearTimeout(pointFlightNoticeTimer)
  pointFlightNoticeTimer = 0
  pointFlightNoticeVisible.value = true
  // 使提交前已经发出的状态 GET 失效，避免旧任务的迟到响应被当成本次任务身份。
  pointFlightLoadSeq += 1
  pointFlightIdentityPending = true
  pointFlightServerBaseline = lastPointFlightServerVersion
  lastPointFlightEventVersion = 0
  pointFlightProgress.value = {
    kind, status: 'command_pending', result: 0,
    taskId: '', trackId: '', remainingDistance: -1, remainingTime: -1, wayPointIndex: -1,
    plannedPathPoints: []
  }
  telemetry.taskRemainingDistance = -1
  telemetry.taskRemainingTime = -1
  telemetry.pointFlightActive = true
}

function markPointFlightUncertain(kind: 'takeoff' | 'flyto', message: string) {
  pointFlightIdentityPending = true
  pointFlightProgress.value = {
    kind, status: 'command_unknown', result: -1,
    taskId: '', trackId: '', remainingDistance: -1, remainingTime: -1, wayPointIndex: -1,
    plannedPathPoints: []
  }
  telemetry.taskRemainingDistance = -1
  telemetry.taskRemainingTime = -1
  telemetry.pointFlightActive = true
  error.value = message
  schedulePointFlightRecovery(dockSn.value)
}

function markPointFlightSubmissionFailed(kind: 'takeoff' | 'flyto', message: string) {
  clearPointFlightRecovery()
  pointFlightIdentityPending = false
  pointFlightServerBaseline = 0
  pointFlightProgress.value = {
    kind, status: 'command_failed', result: -1,
    taskId: '', trackId: '', remainingDistance: -1, remainingTime: -1, wayPointIndex: -1,
    plannedPathPoints: []
  }
  telemetry.pointFlightActive = false
  if (kind === 'flyto') clearPointFlightTargetMarker(true)
  error.value = message
}

function pointFlightFailureIsUncertain(reason: unknown) {
  return !(reason instanceof ApiError) || reason.status === 0
}

function applyPointFlightProgress(
  kind: 'takeoff' | 'flyto',
  data: Record<string, unknown>,
  source: 'server' | 'event'
): boolean {
  const sn = String(data.sn ?? '')
  if (sn && sn !== dockSn.value) return false
  const incomingTaskId = pointFlightTaskId(data)
  const incomingVersion = source === 'server'
    ? pointFlightServerVersion(data)
    : pointFlightEventVersion(data)
  const current = pointFlightProgress.value
  const status = String(data.status ?? '')
  const result = eventNumber(data, 'result', 'result', 0)
  const terminal = pointFlightStatusIsTerminal(status)
  const currentTerminal = pointFlightStatusIsTerminal(current?.status ?? '')
  const sameTask = Boolean(current?.taskId && incomingTaskId && current.taskId === incomingTaskId)

  if (source === 'server') {
    // Redis 状态使用服务端 updated_at，是“当前任务是谁”的权威来源；只与同一
    // 服务端时钟域比较，绝不拿设备 timestamp 决定任务切换。
    if (incomingVersion > 0 && lastPointFlightServerVersion > 0 &&
        incomingVersion < lastPointFlightServerVersion) return false
    if (pointFlightIdentityPending && (
      !incomingTaskId ||
      incomingVersion === 0 ||
      incomingVersion <= pointFlightServerBaseline
    )) return false
    if (sameTask && currentTerminal && !terminal) return false
    if (sameTask && pointFlightStatusIsDevicePhase(current?.status ?? '') &&
        pointFlightStatusIsInitialCommand(status)) return false

    const taskChanged = Boolean(incomingTaskId && current?.taskId !== incomingTaskId)
    if (taskChanged) lastPointFlightEventVersion = 0
    if (incomingVersion > 0) {
      lastPointFlightServerVersion = Math.max(lastPointFlightServerVersion, incomingVersion)
    }
    if (incomingTaskId) {
      pointFlightIdentityPending = false
      pointFlightServerBaseline = 0
      clearPointFlightRecovery()
    } else {
      // 缺少任务 ID 的服务端状态不能开放手动控制，也不能作为设备事件的
      // 任务身份。保持 unknown/active 并持续向权威状态接口恢复。
      pointFlightIdentityPending = true
      pointFlightServerBaseline = lastPointFlightServerVersion
      schedulePointFlightRecovery(dockSn.value)
    }
  } else {
    // 设备事件只能更新已由服务端确认的同一任务。身份未知或 ID 不匹配时保持
    // active/uncertain，拒绝旧终态，等待状态接口恢复。
    if (pointFlightIdentityPending || !current?.taskId || !incomingTaskId || !sameTask) {
      if (pointFlightIdentityPending) schedulePointFlightRecovery(dockSn.value)
      return false
    }
    if (incomingVersion > 0 && lastPointFlightEventVersion > 0 &&
        incomingVersion < lastPointFlightEventVersion) return false
    if (currentTerminal && !terminal) return false
    if (incomingVersion > 0) {
      lastPointFlightEventVersion = Math.max(lastPointFlightEventVersion, incomingVersion)
    }
    pointFlightNoticeVisible.value = true
  }

  const reportedActive = data.active
  const active = source === 'server' && !incomingTaskId
    ? true
    : (typeof reportedActive === 'boolean' ? reportedActive : !terminal)
  const plannedPathPoints = (Array.isArray(data.planned_path_points)
    ? data.planned_path_points
    : Array.isArray(data.plannedPathPoints) ? data.plannedPathPoints : [])
    .flatMap((point) => {
      if (!point || typeof point !== 'object') return []
      const value = point as Record<string, unknown>
      const latitude = Number(value.latitude)
      const longitude = Number(value.longitude)
      const height = Number(value.height)
      return Number.isFinite(latitude) && Number.isFinite(longitude) && Number.isFinite(height)
        ? [{ latitude, longitude, height }]
        : []
    })
  pointFlightProgress.value = {
    kind,
    status: source === 'server' && !incomingTaskId ? 'command_unknown' : status,
    result,
    taskId: incomingTaskId,
    trackId: String(data.track_id ?? data.trackId ?? ''),
    remainingDistance: eventNumber(data, 'remaining_distance', 'remainingDistance'),
    remainingTime: eventNumber(data, 'remaining_time', 'remainingTime'),
    wayPointIndex: eventNumber(data, 'way_point_index', 'wayPointIndex'),
    plannedPathPoints
  }
  telemetry.taskRemainingDistance = pointFlightProgress.value.remainingDistance
  telemetry.taskRemainingTime = pointFlightProgress.value.remainingTime
  telemetry.pointFlightActive = active
  if (kind === 'flyto') {
    const reportedTarget = plannedPathPoints[plannedPathPoints.length - 1]
    if (active && reportedTarget) Object.assign(pointFlightTarget, reportedTarget)
    if (active) updatePointFlightTargetMarker()
    else clearPointFlightTargetMarker(true)
  }
  if (kind === 'flyto' && (!active || status !== 'cancel_requested')) flyToStopPending.value = false
  const message = String(data.message ?? '')
  if (data.uncertain === true) {
    error.value = message || `${kind === 'takeoff' ? '一键起飞' : 'FlyTo'}指令结果尚未确认，请先核对任务状态，勿重复发送`
  } else if (result !== 0 || ['wayline_failed', 'command_failed', 'cancel_failed'].includes(status)) {
    error.value = message || `${kind === 'takeoff' ? '一键起飞' : 'FlyTo'}执行失败（${result}）`
  } else if (terminal && !currentTerminal && (source === 'event' || pointFlightNoticeVisible.value)) {
    showCameraActionTip(status === 'wayline_cancel' ? '飞向目标点任务已取消' : '飞行任务已完成')
  }
  if (terminal && pointFlightNoticeVisible.value) schedulePointFlightNoticeDismiss()
  return true
}

async function loadPointFlightState(expectedDockSn: string): Promise<boolean> {
  if (!expectedDockSn) return false
  const requestSeq = ++pointFlightLoadSeq
  try {
    const saved = await get<Record<string, unknown> | null>(
      `/control/api/v1/devices/${expectedDockSn}/jobs/point-flight/status`,
      CONTROL_REQUEST_OPTIONS)
    if (dockSn.value !== expectedDockSn) return false
    if (requestSeq !== pointFlightLoadSeq) {
      return Boolean(pointFlightProgress.value?.taskId && !pointFlightIdentityPending)
    }
    if (!saved) return false
    const kind = String(saved.kind ?? '')
    if (kind !== 'takeoff' && kind !== 'flyto') return false
    const applied = applyPointFlightProgress(kind, saved, 'server')
    return applied || Boolean(pointFlightProgress.value?.taskId && !pointFlightIdentityPending)
  } catch (reason) {
    if (dockSn.value === expectedDockSn && requestSeq === pointFlightLoadSeq) {
      error.value = reason instanceof Error ? `飞行任务状态同步失败：${reason.message}` : '飞行任务状态同步失败'
    }
    return false
  }
}

function pointFlightStatusLabel(status?: string) {
  return ({
    command_pending: '正在发送指令',
    command_accepted: '指令已受理',
    command_unknown: '指令结果待确认',
    command_failed: '指令发送失败',
    cancel_requested: '正在取消',
    cancel_unknown: '取消结果待确认',
    cancel_failed: '取消失败，可重试',
    task_ready: '准备起飞', task_finish: '起飞任务完成',
    wayline_progress: '飞向目标点', wayline_ok: '已到达目标点',
    wayline_cancel: '任务已取消', wayline_failed: '任务失败'
  } as Record<string, string>)[status ?? ''] ?? '等待事件'
}

// 航线任务状态（flighttask_progress.status），对应 Autel 上报枚举。
function waylineStatusLabel(status?: string) {
  return ({
    pending: '开始执行', sent: '已下发', in_progress: '执行中',
    paused: '已暂停', ok: '执行成功', partially_done: '部分完成',
    canceled: '取消或终止', failed: '失败', rejected: '拒绝', timeout: '超时'
  } as Record<string, string>)[status ?? ''] ?? '等待事件'
}

// 航线执行步骤（flighttask_progress.progress.current_step），仅取关键节点。
function waylineStepLabel(step?: number) {
  if (step == null || step < 0) return '--'
  return ({
    0: '初始状态', 1: '启动前检查', 3: '航线执行中', 4: '返航中',
    5: '等待任务下发', 7: '开机/开盖准备', 8: '等待飞控就绪', 9: '等待 RTK 上报',
    10: '检查 RTK', 14: '下载任务文件', 15: '任务文件上传中', 17: '起飞参数设置',
    18: 'flyto 起飞设置', 19: 'Home 点设置', 20: '触发执行航线', 21: '航线执行中',
    22: '返航', 23: '降落', 29: '获取媒体数量', 46: '上传图片', 47: '任务完成'
  } as Record<number, string>)[step] ?? `步骤 ${step}`
}

// 解析 flighttask_progress 事件（EventsReceiver 结构：{ result, output:{ ext, progress, status }, bid }）。
function applyWaylineProgress(data: Record<string, unknown>) {
  // 仅处理当前所选机巢的航线进度，避免多机巢时被其它设备事件覆盖。
  const sn = String(data.sn ?? '')
  if (sn && sn !== dockSn.value) return
  const jobId = String(data.bid ?? '')
  const output = (data.output ?? {}) as Record<string, any>
  const ext = (output.ext ?? {}) as Record<string, any>
  const progress = (output.progress ?? {}) as Record<string, any>
  const wayPointIndex = Number(ext.current_waypoint_index)
  const percent = Number(progress.percent)
  const currentStep = Number(progress.current_step)
  waylineProgress.value = {
    jobId,
    status: String(output.status ?? ''),
    currentStep: Number.isFinite(currentStep) ? currentStep : -1,
    percent: Number.isFinite(percent) ? percent : -1,
    wayPointIndex: Number.isFinite(wayPointIndex) ? wayPointIndex : -1,
    mediaCount: Number(ext.media_count ?? 0),
    flightId: String(ext.flight_id ?? jobId),
    trackId: String(ext.track_id ?? ''),
    resultCode: Number(data.result ?? 0)
  }
}

function resetWaylineTaskForm() {
  Object.assign(waylineTaskForm, {
    rthAltitude: 100,
    minBatteryCapacity: 60,
    barrierSwitchState: 1,
    takeoffAltitude: 100,
    firstWaypointSpeed: 10,
    returnSpeed: 10
  })
  waylineTaskConfirmed.value = false
}

function closeWaylineTask() {
  if (waylineTaskSubmitting.value) return
  waylineTaskOpen.value = false
  waylineTaskError.value = ''
  waylineTaskConfirmed.value = false
}

async function loadCockpitWaylines() {
  const requestSeq = ++waylineTaskLoadSeq
  waylineTaskLoading.value = true
  waylineTaskError.value = ''
  try {
    const data = await get<unknown>(
      `/wayline/api/v1/workspaces/${cockpitWorkspaceId}/waylines?page=1&page_size=100&order_by=update_time%20desc`)
    if (requestSeq !== waylineTaskLoadSeq) return
    cockpitWaylines.value = listFrom<CockpitWayline>(data)
    if (!cockpitWaylines.value.some((wayline) => wayline.id === selectedWaylineId.value)) {
      selectedWaylineId.value = cockpitWaylines.value[0]?.id ?? ''
    }
  } catch (reason) {
    if (requestSeq !== waylineTaskLoadSeq) return
    cockpitWaylines.value = []
    selectedWaylineId.value = ''
    waylineTaskError.value = reason instanceof Error ? reason.message : '航线列表加载失败'
  } finally {
    if (requestSeq === waylineTaskLoadSeq) waylineTaskLoading.value = false
  }
}

function openWaylineTask(dock: Device) {
  if (dockSelectionPending.value || dock.device_sn !== dockSn.value) return
  releaseKeys()
  releasePayloadControls()
  waylineTaskDockSn.value = dock.device_sn
  waylineTaskError.value = ''
  resetWaylineTaskForm()
  waylineTaskOpen.value = true
  void loadCockpitWaylines()
}

function formatWaylineUpdateTime(timestamp?: number) {
  if (!timestamp) return '更新时间未知'
  return new Date(timestamp).toLocaleString('zh-CN', { hour12: false })
}

async function startWaylineTask() {
  const file = selectedCockpitWayline.value
  const targetDockSn = waylineTaskDockSn.value
  if (!file || waylineTaskBlockedReason.value || waylineTaskSubmitting.value) return

  const drcExitNotice = active.value ? '\n当前 DRC 会话将先安全退出。' : ''
  if (!window.confirm(
    `确认由设备 ${targetDockSn} 立即执行航线“${file.name}”？${drcExitNotice}\n请确保空域、天气、现场人员和应急接管条件均已确认。`)) return

  waylineTaskSubmitting.value = true
  waylineTaskError.value = ''
  try {
    if (active.value || state.value === 'connecting') {
      await leaveDrc('wayline-task-start')
      if (pendingDrcExit.value) throw new Error('设备尚未确认退出 DRC，航线任务未下发，请先重试退出 DRC')
    }
    if (selectedDock.value?.device_sn !== targetDockSn || !selectedAircraftOnline.value) {
      throw new Error('执行设备已离线或发生变化，航线任务未下发')
    }
    // 不再限制仅待机可下发：EVO RC 支持手动飞行中直接执行航线（已有任务时仍由
    // waylineTaskBlockedReason 的 task 状态拦截防止重复下发）。

    await post(`/wayline/api/v1/workspaces/${cockpitWorkspaceId}/flight-tasks`, {
      name: `${file.name}-座舱任务`.slice(0, 64),
      file_id: file.id,
      dock_sn: targetDockSn,
      wayline_type: file.template_types?.[0] ?? 0,
      task_type: 0,
      rth_altitude: waylineTaskForm.rthAltitude,
      out_of_control_action: 0,
      min_battery_capacity: waylineTaskForm.minBatteryCapacity,
      min_storage_capacity: 1024,
      wayline_precision_type: 0,
      barrier_switch_state: waylineTaskForm.barrierSwitchState,
      takeoff_altitude: waylineTaskForm.takeoffAltitude,
      first_waypoint_speed: waylineTaskForm.firstWaypointSpeed,
      return_speed: waylineTaskForm.returnSpeed,
      media_upload_method: 0,
      alternate_land_point: { is_configured: 0 }
    })

    waylineTaskOpen.value = false
    waylineTaskConfirmed.value = false
    waylineTaskNotice.value = `航线“${file.name}”已下发，等待飞机进入任务状态`
    window.clearTimeout(waylineTaskNoticeTimer)
    waylineTaskNoticeTimer = window.setTimeout(() => {
      waylineTaskNotice.value = ''
      waylineTaskNoticeTimer = 0
    }, 7_000)
  } catch (reason) {
    waylineTaskError.value = reason instanceof Error ? reason.message : '航线任务下发失败'
  } finally {
    waylineTaskSubmitting.value = false
  }
}

function dismissError() {
  error.value = ''
}

watch(error, (message) => {
  window.clearTimeout(errorDismissTimer)
  errorDismissTimer = 0
  if (!message) return
  errorDismissTimer = window.setTimeout(() => {
    errorDismissTimer = 0
    error.value = ''
  }, 7_000)
})

function hmsIdentity(alarm: HmsAlarm) {
  return alarm.hms_id || alarm.hmsId || `${alarm.sn}:${alarm.key}`
}

function hmsMessage(alarm: HmsAlarm) {
  return alarm.message_zh || alarm.messageZh || alarm.message_en || alarm.messageEn || `未知告警（${alarm.key}）`
}

function hmsCreatedAt(alarm: HmsAlarm) {
  return alarm.create_time || alarm.createTime || ''
}

function hmsTimestamp(alarm: HmsAlarm) {
  const timestamp = Date.parse(hmsCreatedAt(alarm))
  return Number.isFinite(timestamp) ? timestamp : 0
}

function hmsLevelLabel(level: number) {
  return ({ 0: '通知', 1: '提醒', 2: '警告' } as Record<number, string>)[level] ?? `等级 ${level}`
}

function hmsModuleLabel(module: number) {
  return ({ 0: '飞行任务', 1: '设备管理', 2: '媒体', 3: 'HMS' } as Record<number, string>)[module] ?? `模块 ${module}`
}

function formatHmsTime(value: string) {
  if (!value) return '--'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

function mergeHmsAlarms(incoming: HmsAlarm[], fallbackSn = '') {
  const merged = new Map(hmsAlarms.value.map((alarm) => [hmsIdentity(alarm), alarm]))
  for (const alarm of incoming) {
    const normalized = { ...alarm, sn: alarm.sn || fallbackSn }
    merged.set(hmsIdentity(normalized), normalized)
  }
  hmsAlarms.value = [...merged.values()]
    .sort((left, right) => hmsTimestamp(right) - hmsTimestamp(left))
    .slice(0, 200)
}

async function loadHmsAlarms() {
  const sns = [...selectedDeviceSns.value]
  const requestSeq = ++hmsLoadSeq
  hmsError.value = ''
  if (!sns.length) {
    hmsAlarms.value = []
    return
  }
  hmsLoading.value = true
  try {
    const results = await Promise.all(sns.map((sn) =>
      get<HmsAlarm[]>(`/manage/api/v1/devices/${cockpitWorkspaceId}/devices/hms/${encodeURIComponent(sn)}`)))
    if (requestSeq !== hmsLoadSeq) return
    hmsAlarms.value = []
    results.forEach((alarms, index) => mergeHmsAlarms(Array.isArray(alarms) ? alarms : [], sns[index]))
  } catch (reason) {
    if (requestSeq === hmsLoadSeq) {
      hmsError.value = reason instanceof Error ? reason.message : '健康告警加载失败'
    }
  } finally {
    if (requestSeq === hmsLoadSeq) hmsLoading.value = false
  }
}

function receiveHms(data: unknown) {
  if (!data || typeof data !== 'object') return
  const message = data as Record<string, unknown>
  const sn = String(message.sn ?? '')
  if (sn && !selectedDeviceSns.value.has(sn)) return
  const host = Array.isArray(message.host) ? message.host as HmsAlarm[] : []
  if (!host.length) return
  mergeHmsAlarms(host, sn)
  latestHms.value = { ...host[0], sn: host[0].sn || sn }
  window.clearTimeout(hmsNoticeTimer)
  hmsNoticeTimer = window.setTimeout(() => { latestHms.value = undefined }, 8_000)
}

async function markHmsRead() {
  const sns = [...selectedDeviceSns.value]
  if (!sns.length || hmsMarkingRead.value) return
  hmsMarkingRead.value = true
  hmsError.value = ''
  try {
    await Promise.all(sns.map((sn) =>
      put(`/manage/api/v1/devices/${cockpitWorkspaceId}/devices/hms/${encodeURIComponent(sn)}`)))
    hmsAlarms.value = []
    latestHms.value = undefined
  } catch (reason) {
    hmsError.value = reason instanceof Error ? reason.message : '告警标记已读失败'
  } finally {
    hmsMarkingRead.value = false
  }
}

watch(() => [...selectedDeviceSns.value].sort().join(','), () => {
  void loadHmsAlarms()
}, { immediate: true })

// ────────── 生命周期 ──────────

onMounted(async () => {
  try {
    await loadCockpitData()
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '座舱数据加载失败'
  }
  // 订阅 WebSocket OSD（未进 DRC 时也能更新地图位置）
  wsUnsub = deviceWs.subscribe((msg) => {
    if (msg.biz_code === 'device_online' ||
        msg.biz_code === 'device_offline' ||
        msg.biz_code === 'device_update_topo' ||
        msg.biz_code === 'live_capacity') {
      const event = msg.data as Record<string, unknown> | undefined
      const eventSn = String(event?.sn ?? event?.device_sn ?? event?.deviceSn ?? '')
      const selectedVideoSn = selectedSource.value?.deviceSn ?? ''
      const targetsSelectedDevice = !eventSn ||
        eventSn === dockSn.value ||
        eventSn === selectedVideoSn ||
        selectedDeviceSns.value.has(eventSn)
      const reconnectReason = targetsSelectedDevice &&
        (msg.biz_code === 'device_online' || msg.biz_code === 'device_update_topo')
        ? '设备重新上线'
        : ''
      scheduleTopologyRefresh(reconnectReason)
    }
    if (msg.biz_code === 'gateway_osd' ||
        msg.biz_code === 'dock_osd' ||
        msg.biz_code === 'device_osd') {
      applyTelemetry(msg.data as DeviceTelemetry)
    }
    if (msg.biz_code === 'live_status') {
      applyLiveStatus(msg.data as DeviceTelemetry)
    }
    if (msg.biz_code === 'device_hms') {
      receiveHms(msg.data)
    }
    if (msg.biz_code === 'control_source_change') {
      const authority = msg.data as Record<string, unknown>
      const authoritySn = String(authority.sn ?? '')
      const authorityType = Number(authority.type)
      const source = controlSourceValue(authority.control_source ?? authority.controlSource)
      if (authorityType === 1 && (!authoritySn || authoritySn === dockSn.value)) {
        flightControlSource.value = source
        if (source === 'A') lastFlightAuthorityGrabAt = Date.now()
        if (source !== 'A' && (active.value || drcEnterPending.value)) {
          if (drcEnterPending.value) drcEnterCancelled = true
          if (source === 'B') {
            receiveJoystickInvalid({
              sn: authoritySn,
              reason: 4,
              event_timestamp: authority.event_timestamp ?? authority.timestamp ?? Date.now()
            })
          } else {
            releaseKeys()
            drcStatusMessage.value = '飞行控制权已转移，已停止键盘与摇杆输出'
            if (active.value) void leaveDrc('flight-authority-transferred')
          }
        }
      }
      if (authorityType === 2) {
        const authorityKey = payloadAuthorityKeyFromSn(authoritySn) ||
          (authoritySn && authoritySn === selectedPayloadSn.value ? payloadAuthorityKey.value : '')
        if (authorityKey) {
          if (source === 'A') payloadAuthorityKeys.add(authorityKey)
          else payloadAuthorityKeys.delete(authorityKey)
        }
      }
    }
    if (msg.biz_code === 'payload_authority_grab') {
      const reply = msg.data as Record<string, unknown>
      const gatewaySn = String(reply.gateway_sn ?? reply.gatewaySn ?? '')
      const payloadIndex = String(reply.payload_index ?? reply.payloadIndex ?? '')
      const authorityKey = gatewaySn && payloadIndex ? `${gatewaySn}/${payloadIndex}` : ''
      if (gatewaySn === dockSn.value) {
        if (reply.success === true || Number(reply.result) === 0) {
          if (authorityKey) payloadAuthorityKeys.add(authorityKey)
          error.value = ''
        } else {
          if (authorityKey) payloadAuthorityKeys.delete(authorityKey)
          error.value = String(reply.message || `负载控制权获取失败（${reply.result ?? '未知错误'}）`)
        }
      }
    }
    if (msg.biz_code === 'drc_status_notify') {
      const notice = msg.data as Record<string, unknown>
      if (!notice.sn || String(notice.sn) === dockSn.value) {
        const result = Number(notice.result ?? 0)
        const drcState = Number(notice.drc_state ?? notice.drcState ?? -1)
        drcStatusMessage.value = String(notice.message ?? (result === 0 ? '设备 DRC 状态已更新' : `DRC 异常 ${result}`))
        if (result !== 0 || drcState === 0) {
          drcConnectionState.value = result === 0 ? 'offline' : 'degraded'
          releaseKeys()
          if (active.value) void leaveDrc('device-drc-status')
        } else if (drcState === 1) {
          drcConnectionState.value = 'connecting'
        } else if (drcState === 2) {
          // 飞控拒绝是当前会话的安全锁存；普通 DRC 在线事件不能把 degraded
          // 覆盖回 online，必须完成重新抢权和安全重连后才清除。
          if (drcControlRejected.value) {
            drcConnectionState.value = 'degraded'
            drcStatusMessage.value = drcControlFailure.value ||
              '设备 DRC 在线，但当前飞控会话已被拒绝，请重新抢权并重连 DRC'
          } else {
            // 设备状态事件只能说明设备侧已连接；首个当前会话心跳回包前禁止开放飞控。
            drcConnectionState.value = drcMqttConnected.value
              ? (lastHeartbeatAckAt.value >= drcConnectedAt && drcConnectedAt > 0 ? 'online' : 'connecting')
              : 'offline'
            if (!drcMqttConnected.value) {
              drcStatusMessage.value = '设备 DRC 已连接，但本地 MQTT 通道离线'
            } else if (drcConnectionState.value === 'connecting') {
              drcStatusMessage.value = '设备 DRC 已连接，等待当前会话心跳确认'
            }
          }
        }
      }
    }
    if (msg.biz_code === 'drc_hsi_info_push') {
      const hsi = msg.data as Record<string, unknown>
      const hsiGatewaySn = String(hsi.sn ?? '')
      if (active.value && (!hsiGatewaySn || hsiGatewaySn === dockSn.value)) {
        applyHsiTelemetry(hsi)
      }
    }
    if (msg.biz_code === 'fly_to_point_progress') {
      applyPointFlightProgress('flyto', msg.data as Record<string, unknown>, 'event')
    }
    if (msg.biz_code === 'takeoff_to_point_progress') {
      applyPointFlightProgress('takeoff', msg.data as Record<string, unknown>, 'event')
    }
    if (msg.biz_code === 'flighttask_progress') {
      applyWaylineProgress(msg.data as Record<string, unknown>)
    }
    if (msg.biz_code === 'joystick_invalid_notify') {
      receiveJoystickInvalid(msg.data as Record<string, unknown>)
    }
    if (msg.biz_code === 'target_detect_result_report') {
      applyTargetDetectionReport(msg.data as Record<string, unknown>)
    }
  })
  void resumeDrcAfterReload()
  await initMap()
  videoWatchdogTimer = window.setInterval(monitorVideoBitrate, 1_000)
  window.addEventListener('keydown', handleKey)
  window.addEventListener('keyup', handleKey)
  window.addEventListener('blur', releaseKeys)
  window.addEventListener('beforeunload', markPageUnloading)
  window.addEventListener('pagehide', markPageUnloading)
  window.addEventListener('pageshow', markPageVisible)
  document.addEventListener('focusin', handleControlFocusIn)
  document.addEventListener('visibilitychange', handleVisibilityChange)
})

onBeforeUnmount(async () => {
  wsUnsub?.()
  window.clearTimeout(topologyRefreshTimer)
  window.clearTimeout(videoReconnectTimer)
  window.clearTimeout(cameraActionTipTimer)
  window.clearTimeout(errorDismissTimer)
  window.clearTimeout(waylineTaskNoticeTimer)
  window.clearTimeout(hmsNoticeTimer)
  window.clearTimeout(pointFlightNoticeTimer)
  window.clearTimeout(targetReportTimer)
  window.clearInterval(videoWatchdogTimer)
  window.removeEventListener('beforeunload', markPageUnloading)
  window.removeEventListener('pagehide', markPageUnloading)
  window.removeEventListener('pageshow', markPageVisible)
  try {
    if (pageUnloading && (active.value || state.value === 'connecting')) {
      preserveDrcForReload()
    } else {
      await exit()
    }
  } finally {
    // Keep the handler registered while an async unmount cleanup is in flight.
    // A global logout during that window must await the same idempotent promise.
    unregisterSessionCleanup()
    if (map) { map.destroy?.(); map = undefined }
  }
})

// ────────── DRC 控制 ──────────

function markPageUnloading() {
  pageUnloading = true
}

function markPageVisible() {
  // pagehide can be followed by pageshow when the browser restores this page
  // from BFCache. It is no longer unloading and must perform normal cleanup on
  // the next route change.
  pageUnloading = false
}

function preserveDrcForReload() {
  window.clearInterval(heartbeatTimer)
  window.clearInterval(heartbeatHealthTimer)
  window.clearInterval(controlTimer)
  releaseKeys()
  invalidateDrcMqttGeneration(client)
  try { client?.end(true) } catch { /* 页面正在卸载 */ }
  client = undefined
  drcMqttConnected.value = false
}

async function resumeDrcAfterReload() {
  const marker = drcResumeMarker.value
  if (!marker || marker.dockSn !== selectedDock.value?.device_sn ||
      pendingDrcExit.value || active.value || drcEnterPending.value) return
  drcStatusMessage.value = '检测到刷新前的 DRC 会话，正在恢复飞行权与控制通道…'
  const authorityReady = await grabFlightAuthority(marker.dockSn)
  if (!authorityReady) {
    clearDrcResume()
    return
  }
  const recovered = await enter()
  if (!recovered && !active.value) clearDrcResume()
}

function enter(): Promise<boolean> {
  if (componentExiting) return Promise.resolve(false)
  if (drcEnterPromise) return drcEnterPromise
  drcEnterPending.value = true
  const tracked = performEnter().finally(() => {
    drcEnterPending.value = false
    if (drcEnterPromise === tracked) drcEnterPromise = undefined
  })
  drcEnterPromise = tracked
  return tracked
}

function controlTargetValid(expectedDockSn: string) {
  return !componentExiting &&
    !dockSelectionPending.value &&
    selectedAircraftOnline.value &&
    dockSn.value === expectedDockSn &&
    selectedDock.value?.device_sn === expectedDockSn &&
    hasFlightAuthority.value
}

function enteringTargetValid(expectedDockSn: string, expectedAircraftSn: string) {
  return !drcEnterCancelled &&
    controlTargetValid(expectedDockSn) &&
    selectedAircraftSn.value === expectedAircraftSn
}

function isDrcOwnerConflict(reason: unknown): boolean {
  const message = reason instanceof Error ? reason.message : String(reason ?? '')
  return /(?:session|lease) belongs to another owner|does not belong to this user/i.test(message)
}

async function performEnter(): Promise<boolean> {
  if (active.value) return drcControlsReady.value
  if (!selectedDock.value || !selectedAircraftOnline.value || dockSelectionPending.value || state.value === 'connecting' || drcLeavePromise) return false
  if (pendingDrcExit.value) {
    drcStatusMessage.value = '上次退出 DRC 尚未确认，请先执行“② 重试退出 DRC”'
    return false
  }
  // 飞行权和 DRC 是两个独立、可核验的步骤。进入 DRC 不再隐式抢权，避免
  // 用户按下方向键时同时触发多个异步飞控操作。
  if (!hasFlightAuthority.value) {
    drcStatusMessage.value = '请先执行“① 抢夺飞行控制权”'
    return false
  }
  const enteringDockSn = dockSn.value
  const enteringAircraftSn = selectedAircraftSn.value
  let enteringBroker: Broker | undefined
  let enteringAcl: Acl | undefined
  let deviceDrcEnterAttempted = false
  drcEnterCancelled = false
  drcControlRejected.value = false
  drcControlFailure.value = ''
  state.value = 'connecting'
  drcConnectionState.value = 'connecting'
  drcStatusMessage.value = '正在申请 DRC 通道并连接 MQTT…'
  error.value = ''
  if (!enteringTargetValid(enteringDockSn, enteringAircraftSn)) {
    state.value = 'idle'
    drcConnectionState.value = 'idle'
    error.value = '设备或飞行控制权状态已变化，已取消进入 DRC'
    return false
  }
  try {
    enteringBroker = await post<Broker>(`/control/api/v1/workspaces/${cockpitWorkspaceId}/drc/connect`, {
      client_id: '', expire_sec: 3600
    }, CONTROL_REQUEST_OPTIONS)
    if (!enteringTargetValid(enteringDockSn, enteringAircraftSn)) throw new Error('设备或飞行控制权状态已变化，已取消进入 DRC')
    deviceDrcEnterAttempted = true
    enteringAcl = await post<Acl>(`/control/api/v1/workspaces/${cockpitWorkspaceId}/drc/enter`, {
      client_id: enteringBroker.client_id, dock_sn: enteringDockSn, expire_sec: 3600,
      device_info: { osd_frequency: 10, hsi_frequency: 1 }
    }, CONTROL_REQUEST_OPTIONS)
    rememberDrcResume(enteringDockSn)
    if (!enteringTargetValid(enteringDockSn, enteringAircraftSn)) throw new Error('设备或飞行控制权状态已变化，已取消进入 DRC')
    broker = enteringBroker
    acl = enteringAcl
    drcPublishTopic = resolveDrcPublishTopic(enteringAcl, enteringDockSn)
    await connectMqtt(enteringBroker, enteringAcl, enteringAircraftSn)
    if (!enteringTargetValid(enteringDockSn, enteringAircraftSn)) throw new Error('设备或飞行控制权状态已变化，已取消进入 DRC')
    drcAircraftSn = enteringAircraftSn
    state.value = 'active'
    drcConnectionState.value = 'connecting'
    drcStatusMessage.value = 'DRC 通道已连接，等待心跳回包'
    drcConnectedAt = Date.now()
    lastHeartbeatAckAt.value = 0
    heartbeatSeq = 0
    lastHeartbeatAckSeq = 0
    nativeHeartbeatAckReceived = false
    lastControlPublishAt = 0
    zeroControlPending = false
    heartbeat()
    heartbeatTimer = window.setInterval(heartbeat, 1000)
    heartbeatHealthTimer = window.setInterval(checkHeartbeatHealth, 1000)
    controlTimer = window.setInterval(publishControl, DRC_CONTROL_INTERVAL_MS)
    if (selectedVideoId.value) void startVideo()
    return true
  } catch (reason) {
    const enterError = reason instanceof Error ? reason.message : '进入虚拟座舱失败'
    error.value = enterError
    state.value = 'connecting'
    drcConnectionState.value = 'connecting'
    let exitUnconfirmed = false
    if (drcLeavePromise) {
      try {
        await drcLeavePromise
        exitUnconfirmed = (drcConnectionState.value as DrcConnectionState) === 'degraded'
      } catch (exitReason) {
        exitUnconfirmed = true
        const exitMessage = exitReason instanceof Error ? exitReason.message : '设备退出 DRC 未确认'
        error.value = `${enterError}；${exitMessage}`
      }
    } else if (deviceDrcEnterAttempted && enteringBroker) {
      await post(`/control/api/v1/workspaces/${cockpitWorkspaceId}/drc/exit`, {
        client_id: enteringBroker.client_id, dock_sn: enteringDockSn, expire_sec: 3600,
        device_info: { osd_frequency: 10, hsi_frequency: 1 }
      }, CONTROL_REQUEST_OPTIONS).catch((exitReason) => {
        // 进入请求在服务端完成会话重绑前失败时，当前浏览器并不是租约所有者。
        // 不把这种预期拒绝记录为“退出未确认”，否则会错误阻止用户再次进入。
        if (isDrcOwnerConflict(exitReason)) return
        exitUnconfirmed = true
        rememberPendingDrcExit(enteringBroker!.client_id, enteringDockSn)
        const exitMessage = exitReason instanceof Error ? exitReason.message : '设备退出 DRC 未确认'
        error.value = `${enterError}；设备退出 DRC 未确认：${exitMessage}`
      })
    }
    state.value = 'idle'
    drcConnectionState.value = exitUnconfirmed ? 'degraded' : 'offline'
    drcStatusMessage.value = exitUnconfirmed
      ? '进入失败，本地通道已关闭，但设备退出未确认'
      : enterError
    const failedClient = client
    invalidateDrcMqttGeneration(failedClient)
    client = undefined
    failedClient?.end(true)
    drcMqttConnected.value = false
    broker = undefined
    acl = undefined
    drcPublishTopic = ''
    drcAircraftSn = ''
    clearDrcResume()
    return false
  }
}

function resolveDrcPublishTopic(permissions: Acl, gatewaySn: string) {
  const expected = `thing/product/${gatewaySn}/drc/down`
  const topic = permissions.pub.find((item) => item === expected)
  if (!topic) {
    throw new Error(`DRC 权限缺少遥控器网关控制 Topic：${expected}`)
  }
  return topic
}

function resolveBrowserDrcAddress(address: string) {
  let resolved: URL
  try {
    resolved = new URL(address)
  } catch {
    throw new Error(`DRC MQTT 地址无效：${address}`)
  }
  if (resolved.protocol !== 'ws:' && resolved.protocol !== 'wss:') {
    throw new Error(`浏览器不支持 DRC MQTT 协议：${resolved.protocol}`)
  }

  // 后端配置的公网/LAN 主机仍需原样下发给飞行器；浏览器则必须通过
  // 当前页面实际可达的主机连接同一网关，否则从 localhost 打开驾驶舱时
  // 会错误尝试连接飞行器使用的 LAN 地址，导致心跳和按键控制均不可用。
  resolved.hostname = window.location.hostname
  if (window.location.protocol === 'https:' && resolved.protocol === 'ws:') {
    resolved.protocol = 'wss:'
  }
  return resolved.toString()
}

function clearDrcControlProbeHandshake() {
  window.clearTimeout(drcProbeFollowupTimer)
  drcProbeFollowupTimer = 0
  window.clearTimeout(drcProbeRetryTimer)
  drcProbeRetryTimer = 0
  pendingDrcControlProbe = undefined
  drcProbeHandshakeStep = 0
}

function restartDrcControlProbeHandshake() {
  clearDrcControlProbeHandshake()
  // A retry is still the same zero vector. Keep advancing its sequence because
  // the device may have consumed the previous frame even when its ACK was lost.
  // Only an actual vector change starts again at seq=0.
  zeroControlPending = true
}

function activateDrcMqttGeneration(mqttClient: MqttClient, resetControlSequence = true) {
  clearDrcControlProbeHandshake()
  activeDrcMqttGeneration = ++drcMqttGenerationCounter
  if (resetControlSequence) {
    controlSeq = 0
    lastControlVector = ''
  }
  return activeDrcMqttGeneration
}

function invalidateDrcMqttGeneration(mqttClient?: MqttClient) {
  if (mqttClient && pendingDrcControlProbe && pendingDrcControlProbe.client !== mqttClient) return
  clearDrcControlProbeHandshake()
  activeDrcMqttGeneration = 0
}

function connectMqtt(config: Broker, permissions: Acl, aircraftSn: string) {
  return new Promise<void>((resolve, reject) => {
    drcMqttConnected.value = false
    const browserAddress = resolveBrowserDrcAddress(config.address)
    const mqttClient = mqtt.connect(browserAddress, {
      clientId: config.client_id, username: config.username, password: config.password,
      reconnectPeriod: 1500, connectTimeout: 8000, clean: true
    })
    client = mqttClient
    let mqttGeneration = activateDrcMqttGeneration(mqttClient)
    const isCurrentClient = () => client === mqttClient
    let settled = false
    const resolveOnce = () => {
      if (settled) return
      settled = true
      resolve()
    }
    const rejectOnce = (reason: unknown) => {
      if (settled) return
      settled = true
      reject(reason instanceof Error ? reason : new Error(String(reason)))
    }
    const timeout = window.setTimeout(() => {
      rejectOnce(new Error(isCurrentClient() ? 'MQTT DRC 连接超时' : 'MQTT DRC 会话已取消'))
    }, 9000)
    mqttClient.once('connect', () => {
      if (!isCurrentClient()) {
        mqttClient.end(true)
        rejectOnce(new Error('MQTT DRC 会话已取消'))
        return
      }
      try {
        drcMqttConnected.value = true
        mqttClient.on('message', (topic, payload) => {
          if (!isCurrentClient() || selectedAircraftSn.value !== aircraftSn) return
          handleMessage(topic, payload, aircraftSn, mqttClient, mqttGeneration)
        })
        mqttClient.on('connect', () => {
          if (!isCurrentClient()) return
          drcMqttConnected.value = true
          if (state.value === 'active') {
            // 每次传输层重连都是新的心跳代际。断线前的 ACK 不能再次证明当前
            // MQTT 链路可控，否则一个 DRC 状态事件就会提前开放非零输出。
            drcConnectedAt = Date.now()
            lastHeartbeatAckAt.value = 0
            lastHeartbeatAckSeq = heartbeatSeq
            nativeHeartbeatAckReceived = false
            drcConnectionState.value = 'connecting'
            drcStatusMessage.value = 'DRC MQTT 已重连，等待心跳确认'
            releaseKeys()
            heartbeat()
          }
        })
        mqttClient.on('reconnect', () => {
          if (!isCurrentClient()) return
          // Transport reconnect is not a new REST DRC session. Preserve the
          // last vector/sequence so a zero-vector retry cannot replay seq=0.
          mqttGeneration = activateDrcMqttGeneration(mqttClient, false)
          drcMqttConnected.value = false
          drcConnectionState.value = 'connecting'
          drcStatusMessage.value = 'DRC MQTT 正在重连…'
        })
        mqttClient.on('offline', () => {
          if (!isCurrentClient()) return
          clearDrcControlProbeHandshake()
          drcMqttConnected.value = false
          drcConnectionState.value = 'offline'
          drcStatusMessage.value = 'DRC MQTT 已离线'
          releaseKeys()
        })
        addInteractionLog({
          transport: 'MQTT',
          direction: 'INFO',
          summary: 'DRC MQTT 已连接',
          payload: {
            address: browserAddress,
            configuredAddress: browserAddress === config.address ? undefined : config.address,
            subscribe: permissions.sub,
            publish: permissions.pub,
            activePublishTopic: drcPublishTopic
          }
        })
        mqttClient.subscribe(permissions.sub, { qos: 0 }, (subscribeError, granted = []) => {
          if (!isCurrentClient()) {
            rejectOnce(new Error('MQTT DRC 会话已取消'))
            return
          }
          const deniedTopics = granted.filter((item) => item.qos === 128).map((item) => item.topic)
          if (subscribeError || deniedTopics.length > 0) {
            const detail = subscribeError?.message
              ?? `无权订阅控制回执主题：${deniedTopics.join(', ')}`
            drcMqttConnected.value = false
            mqttClient.end(true)
            window.clearTimeout(timeout)
            rejectOnce(new Error(`MQTT DRC 订阅失败：${detail}`))
            return
          }
          window.clearTimeout(timeout)
          resolveOnce()
        })
      } catch (reason) {
        window.clearTimeout(timeout)
        rejectOnce(reason)
      }
    })
    mqttClient.on('close', () => {
      if (!isCurrentClient()) {
        rejectOnce(new Error('MQTT DRC 会话已取消'))
        return
      }
      drcMqttConnected.value = false
      invalidateDrcMqttGeneration(mqttClient)
      if (state.value === 'active') {
        drcConnectionState.value = 'offline'
        drcStatusMessage.value = 'DRC MQTT 连接已关闭'
        releaseKeys()
      }
      rejectOnce(new Error('MQTT DRC 连接已关闭'))
    })
    mqttClient.once('error', (reason) => {
      window.clearTimeout(timeout)
      if (!isCurrentClient()) {
        rejectOnce(new Error('MQTT DRC 会话已取消'))
        return
      }
      rejectOnce(reason)
    })
  })
}

function envelope(method: string, data: unknown) {
  const id = typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`
  return {
    id,
    payload: JSON.stringify({ tid: id, bid: id, timestamp: Date.now(), method, data })
  }
}

function publish(method: string, data: unknown = ''): string | undefined {
  // Autel DRC 文档中的 {gateway_sn} 是遥控器/机场网关 SN，不是子设备无人机
  // SN。ACL 可能为旧会话同时包含两个 Topic，因此不能再依赖数组第 1 项。
  const topic = drcPublishTopic
  if (!client?.connected || !topic) return undefined
  const outgoing = envelope(method, data)
  addInteractionLog({
    transport: 'MQTT',
    direction: 'OUT',
    topic,
    summary: method,
    payload: JSON.parse(outgoing.payload)
  })
  try {
    client.publish(topic, outgoing.payload, { qos: 0 })
    return outgoing.id
  } catch {
    drcMqttConnected.value = false
    drcConnectionState.value = 'offline'
    return undefined
  }
}

function heartbeat() {
  publish('heart_beat', { seq: ++heartbeatSeq, timestamp: Date.now() })
  // Autel Cloud API marks heartBeatDown as unsupported for RC gateways. Some RC
  // firmware therefore consumes/ignores heart_beat without echoing it. Send a
  // zero-vector control probe while the current MQTT generation is still being
  // verified (and continuously in standby). Without this airborne bootstrap,
  // drcControlsReady blocks drone_control while waiting for an ACK that affected
  // RC firmware never sends, so the first DRC entry can deadlock until reconnect.
  if (operationPanelState.value === 'ground' ||
      drcConnectionState.value !== 'online' ||
      lastHeartbeatAckAt.value < drcConnectedAt) {
    publishControl(true)
  }
}

function checkHeartbeatHealth() {
  if (telemetry.hsiUpdatedAt > 0 && Date.now() - telemetry.hsiUpdatedAt > 3_500) {
    resetObstacleTelemetry()
  }
  if (!active.value) return
  const baseline = lastHeartbeatAckAt.value || drcConnectedAt
  if (baseline > 0 && Date.now() - baseline > 4_000 && drcConnectionState.value !== 'degraded') {
    drcConnectionState.value = 'degraded'
    drcStatusMessage.value = '超过 4 秒未收到 DRC 链路回包，已停止控制输出'
    lastHeartbeatAckAt.value = 0
    restartDrcControlProbeHandshake()
    releaseKeys()
  }
}

function hsiBoolean(value: unknown): boolean | undefined {
  if (value === true || value === 1 || value === '1' || value === 'true') return true
  if (value === false || value === 0 || value === '0' || value === 'false') return false
  return undefined
}

function exactInteger(value: unknown): number | undefined {
  if (typeof value === 'number') {
    return Number.isSafeInteger(value) ? value : undefined
  }
  if (typeof value !== 'string') return undefined
  const text = value.trim()
  if (!/^-?\d+$/.test(text)) return undefined
  const parsed = Number(text)
  return Number.isSafeInteger(parsed) ? parsed : undefined
}

function hsiDistance(value: unknown): number {
  if (value === null || value === undefined || value === '') return -1
  const distanceMeters = Number(value)
  // HsiInfoPush in this project defines DRC distances in metres. Keep
  // -1/invalid as unavailable and preserve 0 as a valid contact distance.
  return Number.isFinite(distanceMeters) && distanceMeters >= 0 ? distanceMeters : -1
}

function hsiSegmentDistances(data: Record<string, unknown>, prefix: string, count: number) {
  return Array.from({ length: count }, (_, offset) => {
    const index = offset + 1
    return hsiDistance(data[`${prefix}${index}_distance`] ?? data[`${prefix}${index}Distance`])
  })
}

function nearestHsiDistance(distances: number[]): number {
  const detected = distances.filter((distance) => distance >= 0)
  return detected.length ? Math.min(...detected) : -1
}

function resetObstacleDistances() {
  obstacleSegments.front.splice(0, obstacleSegments.front.length, -1, -1, -1, -1)
  obstacleSegments.rear.splice(0, obstacleSegments.rear.length, -1, -1, -1, -1)
  obstacleSegments.left.splice(0, obstacleSegments.left.length, -1, -1, -1)
  obstacleSegments.right.splice(0, obstacleSegments.right.length, -1, -1, -1)
  telemetry.obstacleFront = -1
  telemetry.obstacleBack = -1
  telemetry.obstacleLeft = -1
  telemetry.obstacleRight = -1
  telemetry.obstacleUp = -1
  telemetry.obstacleDown = -1
}

function resetObstacleTelemetry() {
  resetObstacleDistances()
  telemetry.radarEnabled = undefined
  telemetry.hsiUpdatedAt = 0
}

function applyHsiTelemetry(data: Record<string, unknown>) {
  telemetry.radarEnabled = hsiBoolean(data.radar_enable ?? data.radarEnable)
  telemetry.hsiUpdatedAt = Date.now()
  if (telemetry.radarEnabled === false) {
    resetObstacleDistances()
    return
  }
  const front = hsiSegmentDistances(data, 'front', 4)
  const rear = hsiSegmentDistances(data, 'rear', 4)
  const left = hsiSegmentDistances(data, 'left', 3)
  const right = hsiSegmentDistances(data, 'right', 3)
  obstacleSegments.front.splice(0, obstacleSegments.front.length, ...front)
  obstacleSegments.rear.splice(0, obstacleSegments.rear.length, ...rear)
  obstacleSegments.left.splice(0, obstacleSegments.left.length, ...left)
  obstacleSegments.right.splice(0, obstacleSegments.right.length, ...right)
  telemetry.obstacleFront = nearestHsiDistance(front)
  telemetry.obstacleBack = nearestHsiDistance(rear)
  telemetry.obstacleLeft = nearestHsiDistance(left)
  telemetry.obstacleRight = nearestHsiDistance(right)
  telemetry.obstacleUp = hsiDistance(data.up_distance ?? data.upDistance)
  telemetry.obstacleDown = hsiDistance(data.down_distance ?? data.downDistance)
}

/** 速度档位映射：slow/normal/fast → 缩放因子 */
const speedPresetScale = computed(() => {
  if (flightSettings.speedPreset === 'slow') return 0.5
  if (flightSettings.speedPreset === 'fast') return 1.5
  return 1.0
})
/** 综合速度倍率 = 档位 × 用户灵敏度 */
const effectiveScale = computed(() => {
  return speedPresetScale.value * flightSettings.controlScale
})
const effectiveYawScale = computed(() => {
  return speedPresetScale.value * flightSettings.yawScale
})

function publishControl(forceZero = false) {
  if (forceZero) zeroControlPending = true
  const awaitingCurrentGenerationAck = drcConnectedAt > 0 &&
    lastHeartbeatAckAt.value < drcConnectedAt
  if (forceZero && awaitingCurrentGenerationAck &&
      pendingDrcControlProbe?.mqttGeneration === activeDrcMqttGeneration) {
    if (Date.now() - pendingDrcControlProbe.publishedAt <= DRC_PROBE_ACK_WINDOW_MS) {
      // Never replace a still-valid handshake frame.
      return
    }
    // A lost first or second ACK must not make the operator enter DRC twice.
    // Restart the two-frame zero verification in this generation while controls
    // remain locked; the unchanged zero vector keeps an increasing sequence.
    restartDrcControlProbeHandshake()
  }
  if (operationPanelState.value === 'ground' && lastControlVector !== '0/0/0/0') {
    zeroControlPending = true
  }
  const shouldPublishZero = zeroControlPending
  if (!shouldPublishZero && !drcControlsReady.value) return
  if (shouldPublishZero && (!active.value || !client?.connected)) return
  const now = Date.now()
  if (lastControlPublishAt > 0 && now - lastControlPublishAt < DRC_CONTROL_INTERVAL_MS) return
  const s = effectiveScale.value
  const ys = effectiveYawScale.value
  const clamp = (value: number, min: number, max: number) => Math.max(min, Math.min(max, value))
  // 持续降落是显式锁存模式，不依赖可能被 blur/keyup/摇杆回中改写的临时 sticks。
  // 只要仍是启动它的同一 DRC 设备，会与长按 Z 一样持续得到 leftY=1；
  // 手动方向接管、链路失效、设备变化和落地则通过 stopContinuousLanding 退出。
  const landingSessionMatches = continuousLandingDockSn === selectedDock.value?.device_sn &&
    continuousLandingAircraftSn === selectedAircraftSn.value &&
    continuousLandingMqttGeneration === activeDrcMqttGeneration &&
    drcPublishTopic === `thing/product/${continuousLandingDockSn}/drc/down`
  if (!shouldPublishZero && continuousLandingActive.value && !landingSessionMatches) {
    stopContinuousLanding(
      'target-or-session-changed',
      'DRC 会话或目标设备已变化，持续降落已停止并归零'
    )
    return
  }
  const landingVectorActive = !shouldPublishZero &&
    continuousLandingActive.value &&
    landingSessionMatches
  const controlSticks = landingVectorActive
    ? { leftX: 0, leftY: CONTINUOUS_LANDING_STICK_Y, rightX: 0, rightY: 0 }
    : sticks
  const vector = {
    x: shouldPublishZero ? 0 : +clamp(controlSticks.rightY * -17 * s, -17, 17).toFixed(2),
    y: shouldPublishZero ? 0 : +clamp(controlSticks.rightX * 17 * s, -17, 17).toFixed(2),
    h: shouldPublishZero ? 0 : +clamp(controlSticks.leftY * -5 * s, -4, 5).toFixed(2),
    w: shouldPublishZero ? 0 : +clamp(controlSticks.leftX * 90 * ys, -90, 90).toFixed(2)
  }
  const vectorKey = `${vector.x}/${vector.y}/${vector.h}/${vector.w}`
  if (vectorKey !== lastControlVector) {
    controlSeq = 0
    lastControlVector = vectorKey
  } else {
    controlSeq += 1
  }
  const requestId = publish('drone_control', {
    seq: controlSeq,
    ...vector,
    freq: 10, delay_time: 300
  })
  if (requestId) {
    lastControlPublishAt = now
    if (landingVectorActive && vector.h < 0) {
      continuousLandingRequestIds.add(requestId)
      while (continuousLandingRequestIds.size > 64) {
        const oldestRequestId = continuousLandingRequestIds.values().next().value
        if (typeof oldestRequestId !== 'string') break
        continuousLandingRequestIds.delete(oldestRequestId)
      }
      if (continuousLandingFirstPublishedAt < continuousLandingStartedAt) {
        continuousLandingFirstPublishedAt = now
        window.clearTimeout(continuousLandingStartTimer)
        continuousLandingStartTimer = 0
        addInteractionLog({
          transport: 'SYSTEM',
          direction: 'OUT',
          summary: '持续降落首帧已发布',
          payload: { requestId, vector, freq: 10, delay_time: 300 }
        })
        window.clearTimeout(continuousLandingAckTimer)
        continuousLandingAckTimer = window.setTimeout(() => {
          continuousLandingAckTimer = 0
          if (!continuousLandingActive.value || continuousLandingConfirmed.value) return
          stopContinuousLanding(
            'ack-timeout',
            '设备未确认持续下降杆量，已安全停止；请重连 DRC 后重试'
          )
          error.value = '持续降落启动失败：2 秒内未收到下降杆量成功回包，控制量已归零'
        }, CONTINUOUS_LANDING_ACK_TIMEOUT_MS)
      }
    }
    if (shouldPublishZero) {
      zeroControlPending = false
      if (awaitingCurrentGenerationAck && client && activeDrcMqttGeneration > 0 && drcConnectedAt > 0) {
        const probe: PendingDrcControlProbe = {
          mqttGeneration: activeDrcMqttGeneration,
          client,
          replyTopics: drcPublishTopic
            ? [drcPublishTopic.replace(/\/down$/, '/up')]
            : [],
          requestId,
          controlSeq,
          publishedAt: now,
          handshakeStep: drcProbeHandshakeStep
        }
        pendingDrcControlProbe = probe
        scheduleDrcControlProbeRetry(probe)
      }
    }
  }
}

async function publishFinalZeroControl(mqttClient: MqttClient | undefined, topic: string | undefined) {
  if (!mqttClient?.connected || !topic) return
  const elapsed = Date.now() - lastControlPublishAt
  const waitMs = lastControlPublishAt > 0
    ? Math.max(0, DRC_CONTROL_INTERVAL_MS - elapsed)
    : 0
  if (waitMs > 0) await delay(waitMs)

  const vectorKey = '0/0/0/0'
  if (vectorKey !== lastControlVector) {
    controlSeq = 0
    lastControlVector = vectorKey
  } else {
    controlSeq += 1
  }
  const outgoing = envelope('drone_control', {
    seq: controlSeq,
    x: 0, y: 0, h: 0, w: 0,
    freq: 10, delay_time: 300
  })
  addInteractionLog({
    transport: 'MQTT',
    direction: 'OUT',
    topic,
    summary: 'drone_control · 退出前归零',
    payload: JSON.parse(outgoing.payload)
  })
  await new Promise<void>((resolve) => {
    let settled = false
    const done = () => {
      if (settled) return
      settled = true
      window.clearTimeout(timeout)
      resolve()
    }
    const timeout = window.setTimeout(done, 350)
    try {
      // QoS 0 的 callback 表示数据已经交给传输层；等待它再关闭连接，避免
      // client.end(true) 把最后一帧零速度丢在本地缓冲区。
      mqttClient.publish(topic, outgoing.payload, { qos: 0 }, done)
    } catch {
      done()
    }
  })
  lastControlPublishAt = Date.now()
  zeroControlPending = false
}

async function closeMqttClientGracefully(mqttClient: MqttClient | undefined) {
  if (!mqttClient) return
  await new Promise<void>((resolve) => {
    let settled = false
    const done = () => {
      if (settled) return
      settled = true
      window.clearTimeout(timeout)
      resolve()
    }
    const timeout = window.setTimeout(() => {
      try { mqttClient.end(true) } catch { /* 已关闭 */ }
      done()
    }, 500)
    try {
      mqttClient.end(false, {}, done)
    } catch {
      done()
    }
  })
}

function drcControlProbeMatches(
  message: Record<string, unknown>,
  data: Record<string, unknown>,
  output: Record<string, unknown>,
  topic: string,
  mqttClient: MqttClient,
  mqttGeneration: number,
  receivedAt = Date.now()
) {
  const probe = pendingDrcControlProbe
  if (!probe || probe.client !== mqttClient || mqttClient !== client ||
      probe.mqttGeneration !== mqttGeneration || mqttGeneration !== activeDrcMqttGeneration ||
      !probe.replyTopics.includes(topic) || probe.publishedAt < drcConnectedAt ||
      receivedAt < probe.publishedAt || receivedAt - probe.publishedAt > DRC_PROBE_ACK_WINDOW_MS) {
    return false
  }
  const rawSeq = output.seq ?? data.seq
  const replySeq = exactInteger(rawSeq)

  const replyIds = [
    message.tid, message.bid,
    (message.data as Record<string, unknown> | undefined)?.tid,
    (message.data as Record<string, unknown> | undefined)?.bid,
    data.tid, data.bid,
    output.tid, output.bid
  ].map((value) => String(value ?? '')).filter(Boolean)

  // 相关性优先级：显式 tid/bid 回显能唯一标识本探针。Autel EVO RC 固件回复
  // drone_control 时恒返回 output.seq=0（不回显发送的 seq），因此只有在回包完全
  // 不带 tid/bid 时才回退到严格的 seq 匹配。上方的 ACK 时间窗仍防重放。
  if (replyIds.length > 0) {
    return replyIds.includes(probe.requestId)
  }
  return replySeq !== undefined && replySeq === probe.controlSeq
}

function scheduleDrcControlProbeRetry(probe: PendingDrcControlProbe) {
  window.clearTimeout(drcProbeRetryTimer)
  drcProbeRetryTimer = window.setTimeout(() => {
    drcProbeRetryTimer = 0
    if (pendingDrcControlProbe !== probe || probe.client !== client || !client?.connected ||
        !active.value || probe.mqttGeneration !== activeDrcMqttGeneration ||
        lastHeartbeatAckAt.value >= drcConnectedAt) return
    // Do not depend on the one-second heartbeat cadence here: retry exactly
    // after the bounded ACK window so a lost response cannot require another
    // operator click (or leave the first DRC entry stuck near two seconds).
    restartDrcControlProbeHandshake()
    publishControl(true)
  }, DRC_PROBE_ACK_WINDOW_MS)
}

function scheduleSecondDrcControlProbe(firstProbe: PendingDrcControlProbe) {
  window.clearTimeout(drcProbeRetryTimer)
  drcProbeRetryTimer = 0
  drcProbeHandshakeStep = 1
  drcStatusMessage.value = '零杆链路已确认 1/2，正在验证连续回包…'
  window.clearTimeout(drcProbeFollowupTimer)
  const waitMs = Math.max(0, DRC_CONTROL_INTERVAL_MS - (Date.now() - lastControlPublishAt))
  drcProbeFollowupTimer = window.setTimeout(() => {
    drcProbeFollowupTimer = 0
    if (client !== firstProbe.client || !client?.connected || !active.value ||
        activeDrcMqttGeneration !== firstProbe.mqttGeneration ||
        drcConnectionState.value === 'online' || lastHeartbeatAckAt.value >= drcConnectedAt) return
    publishControl(true)
  }, waitMs)
}

function drcControlResultLabel(result: number) {
  const known: Record<number, string> = {
    319030: '无飞行控制权',
    319033: '控制包序号小于上一包',
    319034: '控制包到达时已超过 delay_time',
    319045: '飞机处于暂停态'
  }
  return known[result] ? `${result}：${known[result]}` : String(result)
}

function handleMessage(
  _topic: string,
  payload: Uint8Array,
  sessionAircraftSn: string,
  mqttClient: MqttClient,
  mqttGeneration: number
) {
  try {
    const message = JSON.parse(new TextDecoder().decode(payload))
    addInteractionLog({
      transport: 'MQTT',
      direction: 'IN',
      topic: _topic,
      summary: String(message.method ?? 'DRC MQTT 消息'),
      payload: message
    })
    const data = message.data?.data ?? message.data ?? {}
    const rawResult = message.data?.result ?? data.result
    const hasExplicitResult = rawResult !== undefined && rawResult !== null && rawResult !== ''
    const parsedResult = hasExplicitResult ? exactInteger(rawResult) : undefined
    const result = parsedResult ?? Number.NaN
    const output = message.data?.output ?? data.output ?? data
    if (message.method === 'heart_beat') {
      const timestamp = exactInteger(output.timestamp ?? data.timestamp)
      const echoedSeq = exactInteger(output.seq ?? data.seq)
      if (timestamp === undefined || timestamp < drcConnectedAt ||
          echoedSeq === undefined || echoedSeq <= lastHeartbeatAckSeq || echoedSeq > heartbeatSeq) return
      lastHeartbeatAckSeq = echoedSeq
      const heartbeatSucceeded = !hasExplicitResult || result === 0
      if (!heartbeatSucceeded) {
        drcConnectionState.value = 'degraded'
        drcStatusMessage.value = Number.isFinite(result)
          ? `心跳回包异常（${result}）`
          : '心跳回包 result 无效'
        lastHeartbeatAckAt.value = 0
        restartDrcControlProbeHandshake()
        releaseKeys()
        return
      }
      nativeHeartbeatAckReceived = true
      latency.value = Math.max(0, Date.now() - timestamp)
      if (drcControlRejected.value) {
        drcConnectionState.value = 'degraded'
        drcStatusMessage.value = drcControlFailure.value || '飞行控制仍被设备拒绝，正在重试零杆探针'
        return
      }
      lastHeartbeatAckAt.value = Date.now()
      drcConnectionState.value = 'online'
      clearDrcControlProbeHandshake()
      drcStatusMessage.value = '心跳正常'
      if (pressed.size > 0 && operationPanelState.value !== 'ground') {
        syncSticksFromKeys()
      } else if (pressed.size > 0) {
        releaseKeys()
      }
    }
    if (message.method === 'hsi_info_push') {
      applyHsiTelemetry(data as Record<string, unknown>)
    }
    if (message.method === 'drone_control') {
      const waitingForGenerationAck = drcConnectedAt > 0 &&
        lastHeartbeatAckAt.value < drcConnectedAt
      // Once an acknowledged generation becomes degraded, only a validated
      // native heartbeat or an explicit MQTT reconnect may reopen controls.
      // Do not let an unrelated late control ACK recover it implicitly.
      if (!waitingForGenerationAck && drcConnectionState.value !== 'online') return
      const matchedProbe = pendingDrcControlProbe
      if (waitingForGenerationAck && !drcControlProbeMatches(
        message as Record<string, unknown>,
        data as Record<string, unknown>, output as Record<string, unknown>,
        _topic, mqttClient, mqttGeneration)) return
      if (waitingForGenerationAck && matchedProbe) {
        // Unlike native heart_beat, a control probe must explicitly report
        // result=0. Missing/malformed result stays pending until the bounded
        // timeout restarts the handshake.
        if (!hasExplicitResult || !Number.isFinite(result)) return
        if (result !== 0) {
          drcControlRejected.value = true
          drcConnectionState.value = 'degraded'
          drcStatusMessage.value = `设备拒绝零杆链路探针（${drcControlResultLabel(result)}），控制保持锁定并自动重试`
          drcControlFailure.value = drcStatusMessage.value
          error.value = drcStatusMessage.value
          // Keep this rejected probe pending until its 1.5 s retry deadline.
          // releaseKeys() remains safe because publishControl refuses to replace
          // a current pending probe; the retry continues the same vector's seq.
          releaseKeys()
          return
        }
        pendingDrcControlProbe = undefined
        if (matchedProbe.handshakeStep === 0) {
          scheduleSecondDrcControlProbe(matchedProbe)
          return
        }
        clearDrcControlProbeHandshake()
      }
      if (result === 0) {
        const replyIds = [
          message.tid, message.bid,
          (message.data as Record<string, unknown> | undefined)?.tid,
          (message.data as Record<string, unknown> | undefined)?.bid,
          data.tid, data.bid, output.tid, output.bid
        ].map((value) => String(value ?? '')).filter(Boolean)
        // 只接受本次持续降落实际下发帧的 tid/bid。无 ID 或旧会话回包不能
        // 证明当前下降控制已被设备接收，避免将延迟到达的普通控制 ACK 误配。
        const uniqueReplyIds = [...new Set(replyIds)]
        const expectedLandingReplyTopic = `thing/product/${continuousLandingDockSn}/drc/up`
        const landingReplyMatched = continuousLandingActive.value &&
          mqttClient === client &&
          mqttGeneration === activeDrcMqttGeneration &&
          mqttGeneration === continuousLandingMqttGeneration &&
          _topic === expectedLandingReplyTopic &&
          uniqueReplyIds.length > 0 &&
          uniqueReplyIds.every((requestId) => continuousLandingRequestIds.has(requestId))
        if (landingReplyMatched && !continuousLandingConfirmed.value) {
          window.clearTimeout(continuousLandingAckTimer)
          continuousLandingAckTimer = 0
          continuousLandingConfirmed.value = true
          addInteractionLog({
            transport: 'SYSTEM',
            direction: 'IN',
            summary: '持续降落控制报文已获设备成功回包',
            payload: { replyIds: uniqueReplyIds, topic: _topic, mqttGeneration, modeCode: telemetry.modeCode }
          })
          window.clearTimeout(continuousLandingMovementTimer)
          continuousLandingMovementTimer = window.setTimeout(() => {
            continuousLandingMovementTimer = 0
            if (!continuousLandingActive.value || continuousLandingMovementObserved.value) return
            const modeHint = [16, 17].includes(telemetry.modeCode)
              ? ''
              : `当前飞机状态为“${modeLabel(telemetry.modeCode)}”(mode=${telemetry.modeCode})，未上报虚拟摇杆/指令飞行状态；`
            const detail = `设备已返回 result=0，但 3 秒内 OSD 未检测到下降；${modeHint}请检查飞控暂停态、下视保护或 RC 固件兼容性`
            const movementSnapshot = {
              verticalSpeed: telemetry.verticalSpeed,
              initialAltitude: continuousLandingInitialAltitude,
              altitude: telemetry.altitude,
              modeCode: telemetry.modeCode
            }
            addInteractionLog({
              transport: 'SYSTEM',
              direction: 'ERROR',
              summary: '持续降落未检测到飞机运动',
              payload: movementSnapshot
            })
            stopContinuousLanding(
              'movement-timeout',
              '设备已接收控制报文，但未检测到飞机下降，已安全停止并归零'
            )
            error.value = `${detail}；持续降落已安全停止并归零`
          }, CONTINUOUS_LANDING_MOVEMENT_TIMEOUT_MS)
          showCameraActionTip('设备已接收持续下降控制报文，正在等待 OSD 下降遥测')
        }
        drcControlRejected.value = false
        drcControlFailure.value = ''
        if (drcConnectedAt > 0) {
          const receivedAt = Date.now()
          // A successful drone_control reply is a stronger end-to-end liveness
          // signal than an MQTT socket state: the RC consumed a current-session
          // downlink command and returned it on drc/up. RC firmware may omit or
          // intermittently echo heart_beat while flying, so count every successful
          // control ACK as link liveness to avoid false degraded/online flapping.
          lastHeartbeatAckAt.value = receivedAt
          latency.value = lastControlPublishAt > 0
            ? Math.max(0, receivedAt - lastControlPublishAt)
            : 0
          drcConnectionState.value = 'online'
          if (operationPanelState.value === 'ground') {
            drcStatusMessage.value = nativeHeartbeatAckReceived
              ? '待机链路正常：心跳与零杆量控制回包已连通'
              : '待机链路正常：heart_beat 已发送，零杆量控制回包已连通'
          } else {
            drcStatusMessage.value = nativeHeartbeatAckReceived
              ? '心跳与飞行控制回包正常'
              : '飞行控制回包正常（RC 兼容心跳）'
          }
        }
      } else {
        drcControlRejected.value = true
        drcConnectionState.value = 'degraded'
        drcStatusMessage.value = `设备拒绝飞行控制（${drcControlResultLabel(result)}），已停止输出`
        drcControlFailure.value = drcStatusMessage.value
        // 文档没有给出 result 对应表，非 0 也可能只是序号错误。先安全归零并
        // 重新锁定为当前代次探针验证；同一零向量的 seq 继续递增，只有从非零向量
        // 变为零向量时才从 0 开始。真正夺权由 joystick_invalid_notify(reason=4)
        // 或 control_source_change 判定。
        lastHeartbeatAckAt.value = 0
        restartDrcControlProbeHandshake()
        releaseKeys()
        error.value = drcStatusMessage.value
      }
    }
    if (message.method === 'drone_emergency_stop') {
      const replyIds = [message.tid, message.bid]
        .map((value) => String(value ?? ''))
        .filter(Boolean)
      if (!emergencyStopPending.value || !emergencyRequestId) return
      // Autel DRC 文档中的急停回包只有 method + data.result，部分 RC 固件不会
      // 回显 tid/bid。若设备提供了关联 ID，则必须匹配当前请求；若完全未提供，
      // 仅在本页面确有一个待确认急停请求时接收，避免合法 result=0 被误判超时。
      if (replyIds.length > 0 && !replyIds.includes(emergencyRequestId)) return
      window.clearTimeout(emergencyStopTimer)
      emergencyStopTimer = 0
      emergencyRequestId = ''
      emergencyStopPending.value = false
      if (result === 0) showCameraActionTip('设备已确认刹车悬停指令')
      else error.value = `刹车悬停失败（${result}）`
    }
    if (message.method === 'drc_emergency_landing' || message.method === 'drc_force_landing') {
      const method = message.method as DrcLandingMethod
      const expectedTopic = `thing/product/${drcLandingGatewaySn}/services_reply`
      // 诊断日志：任何被忽略的降落回包都在交互日志中记录原因，避免静默丢弃难以排查。
      const ignoreLanding = (reason: string) => {
        addInteractionLog({
          transport: 'MQTT',
          direction: 'INFO',
          topic: _topic,
          summary: `${method} 回包被忽略：${reason}`,
          payload: {
            reason,
            pending: drcLandingPending.value || '(无待确认降落请求)',
            expectedTopic,
            actualTopic: _topic,
            expectedRequestId: drcLandingRequestId || '(空)',
            replyTid: message.tid ?? null,
            replyBid: message.bid ?? null,
            result: hasExplicitResult ? result : '(无result)'
          }
        })
      }
      if (!drcLandingPending.value || drcLandingPending.value !== method) {
        ignoreLanding(drcLandingPending.value
          ? `待确认方法为 ${drcLandingPending.value}，与回包方法不一致`
          : '前端已超时或没有待确认的降落请求（可能为迟到回包）')
        return
      }
      if (!drcLandingRequestId || _topic !== expectedTopic) {
        ignoreLanding(!drcLandingRequestId
          ? '本地请求 ID 已清空'
          : `回包 topic 与期望不符（期望 ${expectedTopic}）`)
        return
      }
      const replyIds = [message.tid, message.bid]
        .map((value) => String(value ?? ''))
        .filter(Boolean)
      if (replyIds.length > 0 && !replyIds.includes(drcLandingRequestId)) {
        ignoreLanding(`回包 tid/bid（${replyIds.join('/')}）与请求 ID ${drcLandingRequestId} 不匹配`)
        return
      }
      window.clearTimeout(drcLandingTimer)
      drcLandingTimer = 0
      drcLandingRequestId = ''
      drcLandingGatewaySn = ''
      drcLandingPending.value = ''
      if (method === 'drc_emergency_landing') {
        if (result === 0) {
          showCameraActionTip('紧急降落指令调用成功：设备将避障并识别二维码降落（不代表已落地）')
        } else {
          error.value = `紧急降落指令调用失败（${result}）`
        }
      } else {
        if (result === 0) showCameraActionTip('强制降落指令调用成功（不代表已落地）')
        else error.value = `强制降落指令调用失败（${result}）`
      }
    }
    if (message.method === 'osd_info_push') {
      applyTelemetry({
        sn: sessionAircraftSn,
        host: {
          ...data,
          elevation: data.elevation ?? data.altitude,
          horizontal_speed: data.horizontal_speed ?? data.speed,
          gps_number: data.gps_number ?? data.satellites,
          capacity_percent: data.capacity_percent ?? data.battery,
          attitude_head: data.attitude_head ?? data.heading
        }
      })
    }
  } catch { /* 未知负载不影响控制链路 */ }
}

function observeContinuousLandingMovement() {
  if (!continuousLandingActive.value || !continuousLandingConfirmed.value ||
      continuousLandingMovementObserved.value) return
  const altitudeDrop = continuousLandingInitialAltitude - telemetry.altitude
  if (telemetry.verticalSpeed >= -0.15 && altitudeDrop < 0.15) return
  window.clearTimeout(continuousLandingMovementTimer)
  continuousLandingMovementTimer = 0
  continuousLandingMovementObserved.value = true
  addInteractionLog({
    transport: 'SYSTEM',
    direction: 'IN',
    summary: '持续降落已检测到飞机下降',
    payload: {
      verticalSpeed: telemetry.verticalSpeed,
      initialAltitude: continuousLandingInitialAltitude,
      altitude: telemetry.altitude,
      altitudeDrop
    }
  })
  showCameraActionTip('OSD 已检测到飞机下降；进入待机后将自动停止并归零')
}

function cancelContinuousLandingArm() {
  window.clearTimeout(continuousLandingArmTimer)
  continuousLandingArmTimer = 0
  continuousLandingArmed.value = false
  continuousLandingArmedDockSn = ''
  continuousLandingArmedAircraftSn = ''
  continuousLandingArmedMqttGeneration = 0
}

function stopContinuousLanding(reason: string, message = ''): boolean {
  cancelContinuousLandingArm()
  if (!continuousLandingActive.value) return false
  const gatewaySn = continuousLandingDockSn
  const aircraftSn = continuousLandingAircraftSn
  window.clearTimeout(continuousLandingStartTimer)
  continuousLandingStartTimer = 0
  window.clearTimeout(continuousLandingAckTimer)
  continuousLandingAckTimer = 0
  window.clearTimeout(continuousLandingMovementTimer)
  continuousLandingMovementTimer = 0
  continuousLandingActive.value = false
  continuousLandingConfirmed.value = false
  continuousLandingMovementObserved.value = false
  continuousLandingDockSn = ''
  continuousLandingAircraftSn = ''
  continuousLandingStartedAt = 0
  continuousLandingFirstPublishedAt = 0
  continuousLandingMqttGeneration = 0
  continuousLandingInitialAltitude = 0
  continuousLandingRequestIds.clear()
  pressed.clear()
  sticks.leftX = sticks.leftY = sticks.rightX = sticks.rightY = 0
  publishControl(true)
  addInteractionLog({
    transport: 'SYSTEM',
    direction: 'INFO',
    summary: `持续降落停止：${reason}`,
    payload: { reason, gatewaySn, aircraftSn, modeCode: telemetry.modeCode }
  })
  if (message) showCameraActionTip(message)
  return true
}

function toggleContinuousLanding() {
  if (continuousLandingActive.value) {
    stopContinuousLanding('operator-cancel', '持续降落已由操作者停止，控制量已归零')
    return
  }
  const gatewaySn = selectedDock.value?.device_sn ?? ''
  const aircraftSn = selectedAircraftSn.value
  if (continuousLandingActionDisabled.value || !gatewaySn || !aircraftSn) {
    showCameraActionTip(drcBlockedReason.value || '当前不能启动持续降落')
    return
  }
  // 原生 window.confirm 在 Electron/WebKit 中可能触发 window.blur；全局失焦
  // 安全联锁会随即 releaseKeys()，造成刚锁存的下降量立即归零。改用同一按钮
  // 的页内二次确认，仍保留真实离开页面/窗口时的归零保护。
  if (!continuousLandingArmed.value) {
    continuousLandingArmed.value = true
    continuousLandingArmedDockSn = gatewaySn
    continuousLandingArmedAircraftSn = aircraftSn
    continuousLandingArmedMqttGeneration = activeDrcMqttGeneration
    window.clearTimeout(continuousLandingArmTimer)
    continuousLandingArmTimer = window.setTimeout(cancelContinuousLandingArm, CONTINUOUS_LANDING_ARM_TIMEOUT_MS)
    showCameraActionTip('请在 8 秒内再次点击“确认持续降落”；启动后将持续到飞机进入待机')
    return
  }
  const armMatchesCurrentSession = continuousLandingArmedDockSn === gatewaySn &&
    continuousLandingArmedAircraftSn === aircraftSn &&
    continuousLandingArmedMqttGeneration === activeDrcMqttGeneration
  cancelContinuousLandingArm()
  if (!armMatchesCurrentSession || !drcControlsReady.value || selectedDock.value?.device_sn !== gatewaySn ||
      selectedAircraftSn.value !== aircraftSn) {
    error.value = 'DRC 控制链路、会话或目标设备已变化，持续降落未启动；请重新确认'
    return
  }

  releasePayloadControls()
  pressed.clear()
  // 固定下降量由 publishControl 的会话锁存分支生成；共享 sticks 始终保持
  // 零值，目标或会话守卫失配时绝不会回退成仍在下降的临时摇杆状态。
  sticks.leftX = sticks.leftY = sticks.rightX = sticks.rightY = 0
  continuousLandingDockSn = gatewaySn
  continuousLandingAircraftSn = aircraftSn
  continuousLandingMqttGeneration = activeDrcMqttGeneration
  continuousLandingStartedAt = Date.now()
  continuousLandingFirstPublishedAt = 0
  continuousLandingConfirmed.value = false
  continuousLandingMovementObserved.value = false
  continuousLandingInitialAltitude = telemetry.altitude
  window.clearTimeout(continuousLandingMovementTimer)
  continuousLandingMovementTimer = 0
  continuousLandingRequestIds.clear()
  continuousLandingActive.value = true
  window.clearTimeout(continuousLandingStartTimer)
  continuousLandingStartTimer = window.setTimeout(() => {
    continuousLandingStartTimer = 0
    if (!continuousLandingActive.value ||
        continuousLandingFirstPublishedAt >= continuousLandingStartedAt) return
    stopContinuousLanding(
      'first-frame-timeout',
      '持续降落未能发出下降杆量，已安全停止；请重连 DRC 后重试'
    )
    error.value = '持续降落启动失败：1 秒内未发布 h<0 的 drone_control，控制量已归零'
  }, CONTINUOUS_LANDING_START_TIMEOUT_MS)
  addInteractionLog({
    transport: 'SYSTEM',
    direction: 'OUT',
    summary: '持续降落已锁存：等待首帧 DRC 下降杆量',
    payload: {
      gatewaySn,
      aircraftSn,
      mqttGeneration: activeDrcMqttGeneration,
      modeCode: telemetry.modeCode,
      stick: { leftX: 0, leftY: CONTINUOUS_LANDING_STICK_Y, rightX: 0, rightY: 0 },
      expectedStandardVector: { x: 0, y: 0, h: -4, w: 0 }
    }
  })
  publishControl()
  showCameraActionTip('持续降落已锁存，正在发送下降杆量；点击按钮、方向键或刹车可停止')
}

function moveStick(side: 'left' | 'right', event: PointerEvent) {
  if (!drcControlsReady.value) return
  stopContinuousLanding('manual-stick-takeover', '已切换为手动摇杆控制，持续降落已停止')
  const target = event.currentTarget as HTMLElement
  if (event.type === 'pointerdown') target.setPointerCapture(event.pointerId)
  if (!target.hasPointerCapture(event.pointerId)) return
  const rect = target.getBoundingClientRect()
  const x = Math.max(-1, Math.min(1, (event.clientX - rect.left  - rect.width  / 2) / (rect.width  * .34)))
  const y = Math.max(-1, Math.min(1, (event.clientY - rect.top   - rect.height / 2) / (rect.height * .34)))
  if (side === 'left') { sticks.leftX  = x; sticks.leftY  = y }
  else                 { sticks.rightX = x; sticks.rightY = y }
}
function releaseStick(side: 'left' | 'right') {
  if (side === 'left') { sticks.leftX  = 0; sticks.leftY  = 0 }
  else                 { sticks.rightX = 0; sticks.rightY = 0 }
  publishControl()
}

function syncSticksFromKeys() {
  sticks.leftY  = Number(pressed.has('KeyZ')) - Number(pressed.has('KeyC'))
  sticks.leftX  = Number(pressed.has('KeyE')) - Number(pressed.has('KeyQ'))
  sticks.rightY = Number(pressed.has('KeyS')) - Number(pressed.has('KeyW'))
  sticks.rightX = Number(pressed.has('KeyD')) - Number(pressed.has('KeyA'))
}

function startDirectionalControl(code: string) {
  // 方向操作只消费已经建立并通过心跳确认的 DRC 会话。按键本身不再抢权、
  // 不再进入 DRC，也不会在异步流程完成后补发用户早已松开的动作。
  if (!drcControlsReady.value || operationPanelState.value === 'ground' || emergencyStopPending.value) return
  stopContinuousLanding('manual-direction-takeover', '已切换为手动方向控制，持续降落已停止')
  pressed.add(code)
  syncSticksFromKeys()
  publishControl()
}

function stopDirectionalControl(code: string) {
  pressed.delete(code)
  syncSticksFromKeys()
  publishControl()
}

function queuePayloadCommand(
  cmd: 'camera_screen_drag' | 'camera_focal_length_set' | 'gimbal_reset' | 'camera_look_at',
  data: Record<string, unknown>,
  allowAuthorityRelease = false
): Promise<boolean> {
  const gatewaySn = dockSn.value
  const payloadIndex = selectedSource.value?.cameraIndex
  if (
    !gatewaySn ||
    !payloadIndex ||
    dockSelectionPending.value ||
    (!hasPayloadAuthority.value && !allowAuthorityRelease)
  ) return Promise.resolve(false)

  let succeeded = false
  payloadCommandQueue = payloadCommandQueue
    .catch(() => undefined)
    .then(async () => {
      if (
        dockSelectionPending.value ||
        dockSn.value !== gatewaySn ||
        selectedSource.value?.cameraIndex !== payloadIndex ||
        (!allowAuthorityRelease && !payloadAuthorityKeys.has(`${gatewaySn}/${payloadIndex}`))
      ) return
      payloadCommandPending.value += 1
      try {
        await post(`/control/api/v1/devices/${gatewaySn}/payload/commands`, {
          cmd,
          data: { payload_index: payloadIndex, ...data }
        }, CONTROL_REQUEST_OPTIONS)
        succeeded = true
      } catch (reason) {
        if (cmd === 'camera_focal_length_set') payloadZoomTarget.value = undefined
        error.value = reason instanceof Error ? reason.message : '负载快捷控制指令失败'
      } finally {
        payloadCommandPending.value = Math.max(0, payloadCommandPending.value - 1)
      }
    })
  return payloadCommandQueue.then(() => succeeded)
}

function payloadPitchSpeed() {
  return (
    (payloadPressed.has('ArrowUp') ? payloadGimbalPitchSpeed : 0) -
    (payloadPressed.has('ArrowDown') ? payloadGimbalPitchSpeed : 0)
  )
}

function payloadYawSpeed() {
  return (
    (payloadPressed.has('ArrowRight') ? payloadGimbalYawSpeed : 0) -
    (payloadPressed.has('ArrowLeft') ? payloadGimbalYawSpeed : 0)
  )
}

function payloadGimbalMoving() {
  return payloadPitchSpeed() !== 0 || payloadYawSpeed() !== 0
}

async function runPayloadGimbalLoop() {
  try {
    while (true) {
      const pitchSpeed = payloadPitchSpeed()
      const yawSpeed = payloadYawSpeed()
      const sentAt = Date.now()
      await queuePayloadCommand('camera_screen_drag', {
        // 快捷键只控制云台，不联动机头；偏航使用低速微调，避免点按扫过大角度。
        locked: false,
        pitch_speed: pitchSpeed,
        yaw_speed: yawSpeed
      }, pitchSpeed === 0 && yawSpeed === 0)
      if (pitchSpeed === 0 && yawSpeed === 0) return
      // camera_screen_drag 是速度控制：按住期间按约 10 Hz 持续刷新，且不堆积未回包指令。
      await delay(Math.max(0, 100 - (Date.now() - sentAt)))
    }
  } finally {
    payloadGimbalLoop = undefined
    // 等待回包期间可能已切换方向，继续新的速度循环。
    if (payloadGimbalMoving()) syncPayloadGimbal()
  }
}

function syncPayloadGimbal() {
  if (!payloadGimbalLoop) payloadGimbalLoop = runPayloadGimbalLoop()
}

function adjustPayloadZoom(direction: -1 | 1) {
  const cameraType = displayLens.value === 'ir' ? 'ir' : 'zoom'
  const maxZoom = cameraType === 'ir' ? 16 : 160
  const steps = [1, 2, 3, 4, 5, 7, 10, 15, 16, 20, 30, 50, 80, 120, 160]
    .filter((value) => value <= maxZoom)
  const current = Math.max(1, Math.min(maxZoom,
    payloadZoomTarget.value ?? activeZoomFactor.value))
  // 设备上报值与请求档位存在小幅偏差（例如请求 3× 实际上报约 2.8×）。
  // 将接近档位的值视作已到达，避免下一次按键再次落到同一物理倍率。
  const stepTolerance = Math.max(0.25, current * 0.05)
  let target = current
  if (direction > 0) {
    target = steps.find((value) => value > current + stepTolerance) ?? steps[steps.length - 1]
  } else {
    for (let index = steps.length - 1; index >= 0; index -= 1) {
      if (steps[index] < current - stepTolerance) {
        target = steps[index]
        break
      }
    }
  }
  if (Math.abs(target - current) < 0.01) {
    showCameraActionTip(direction > 0 ? '已达到最大变焦倍率' : '已达到最小变焦倍率')
    return
  }
  payloadZoomTarget.value = target
  void queuePayloadCommand('camera_focal_length_set', {
    camera_type: cameraType,
    zoom_factor: target
  }).then((succeeded) => {
    if (succeeded) showCameraActionTip(`变焦 ${target}×`)
  })
}

async function adjustPayloadZoomFromShortcut(direction: -1 | 1) {
  const authorityKey = payloadAuthorityKey.value
  if (!authorityKey) {
    showCameraActionTip('当前没有可控制的相机负载')
    return
  }
  if (dockSelectionPending.value) {
    showCameraActionTip('设备切换中，请稍后重试变焦')
    return
  }
  if (!hasPayloadAuthority.value && !await grabPayloadAuthority()) {
    showCameraActionTip('未取得负载控制权，无法变焦')
    return
  }
  if (payloadAuthorityKey.value !== authorityKey || !hasPayloadAuthority.value) {
    showCameraActionTip('负载状态已变化，请重试变焦')
    return
  }
  adjustPayloadZoom(direction)
}

function startPayloadControl(code: PayloadShortcutCode) {
  if (dockSelectionPending.value || !hasPayloadAuthority.value || payloadPressed.has(code)) return
  payloadPressed.add(code)
  syncPayloadGimbal()
  const control = payloadShortcutControls.find((item) => item.code === code)
  if (control) showCameraActionTip(control.label)
}

function stopPayloadControl(code: PayloadShortcutCode) {
  if (!payloadPressed.delete(code)) return
  syncPayloadGimbal()
}

function releasePayloadControls() {
  const wasMovingGimbal = payloadPressed.size > 0
  payloadPressed.clear()
  if (wasMovingGimbal) syncPayloadGimbal()
}

async function resetPayloadGimbal() {
  if (dockSelectionPending.value || !hasPayloadAuthority.value || gimbalResetPending.value) return
  releasePayloadControls()
  gimbalResetPending.value = true
  const option = gimbalResetOptions.find((item) => item.value === gimbalResetMode.value)
    ?? gimbalResetOptions[0]
  const succeeded = await queuePayloadCommand('gimbal_reset', { reset_mode: option.value })
  if (succeeded) showCameraActionTip(option.tip)
  gimbalResetPending.value = false
}

function isEditableTarget(target: EventTarget | null) {
  return target instanceof HTMLElement && (
    ['INPUT', 'SELECT', 'TEXTAREA'].includes(target.tagName) || target.isContentEditable
  )
}

function handleControlFocusIn(event: FocusEvent) {
  if (isEditableTarget(event.target) && (pressed.size > 0 || payloadPressed.size > 0)) releaseKeys()
}

function handleKey(event: KeyboardEvent) {
  const payloadCode = event.code as PayloadShortcutCode
  const watched = ['KeyQ', 'KeyW', 'KeyE', 'KeyC', 'KeyA', 'KeyS', 'KeyD', 'KeyZ']

  // 航线任务弹窗打开时屏蔽全部飞行/负载快捷键，避免填写参数时误触控制。
  if (waylineTaskOpen.value) {
    if (event.type === 'keydown' && event.code === 'Escape') {
      event.preventDefault()
      closeWaylineTask()
    }
    return
  }

  // keyup 永远优先于输入框和飞行状态判断；否则按住方向键后聚焦输入框，或
  // 飞行状态切换，会吞掉松键事件并让旧控制向量持续发送。
  if (event.type === 'keyup') {
    blockedPressed.delete(event.code)
    if (payloadPressed.has(payloadCode)) {
      event.preventDefault()
      stopPayloadControl(payloadCode)
      return
    }
    if (watched.includes(event.code)) {
      if (pressed.has(event.code)) {
        event.preventDefault()
        stopDirectionalControl(event.code)
      }
      return
    }
    return
  }

  // 使用物理键位识别，避免不同键盘布局、Shift 状态或输入法改变 event.key。
  const helpKey = event.code === 'Slash' || event.key === '?' || event.key === '/'
  if (shortcutHelpOpen.value) {
    if (event.code === 'Escape' || helpKey) {
      event.preventDefault()
      shortcutHelpOpen.value = false
    }
    return
  }
  if (helpKey) {
    event.preventDefault()
    if (!event.repeat) shortcutHelpOpen.value = true
    return
  }

  // “/”和“?”是全局帮助快捷键。即使高度等输入框仍有焦点，也应优先打开说明，
  // 避免用户刚编辑完参数后按问号却没有任何反馈。
  if (isEditableTarget(event.target)) return

  if (['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(payloadCode)) {
    event.preventDefault()
    if (!event.repeat) startPayloadControl(payloadCode)
    return
  }
  // Space = 立即刹车悬停；键盘操作不弹确认框，避免延误制动。
  if (event.code === 'Space' && event.type === 'keydown') {
    event.preventDefault()
    if (!event.repeat && operationPanelState.value !== 'ground') void emergencyStop()
    return
  }

  if (event.repeat) return
  if (event.code === 'Digit1' || event.code === 'Numpad1') {
    event.preventDefault()
    void switchLens('zoom').then(() => {
      if (lens.value === 'zoom') showCameraActionTip('已切换变焦镜头')
    })
    return
  }
  if (event.code === 'Digit2' || event.code === 'Numpad2') {
    event.preventDefault()
    void switchLens('ir').then(() => {
      if (lens.value === 'ir') showCameraActionTip('已切换红外镜头')
    })
    return
  }
  if (event.code === 'Comma' || event.key === '<' || event.key === ',') {
    event.preventDefault()
    void adjustPayloadZoomFromShortcut(-1)
    return
  }
  if (event.code === 'Period' || event.key === '>' || event.key === '.') {
    event.preventDefault()
    void adjustPayloadZoomFromShortcut(1)
    return
  }
  if (event.code === 'KeyR') {
    event.preventDefault()
    toggleDeviceRecording()
    return
  }
  if (event.code === 'KeyF') {
    event.preventDefault()
    takeDevicePhoto()
    return
  }
  if (event.code === 'KeyB') {
    event.preventDefault()
    void toggleTargetDetection()
    return
  }
  if (event.code === 'KeyT') {
    event.preventDefault()
    selectMapTarget('flyto')
    return
  }
  if (!watched.includes(event.code)) return
  event.preventDefault()
  if (!drcControlsReady.value || operationPanelState.value === 'ground') {
    if (!event.repeat) {
      blockedPressed.add(event.code)
      window.setTimeout(() => blockedPressed.delete(event.code), 180)
      showCameraActionTip(drcBlockedReason.value)
    }
    return
  }
  if (!event.repeat) startDirectionalControl(event.code)
}
function releaseKeys() {
  releasePayloadControls()
  cancelContinuousLandingArm()
  stopContinuousLanding('control-release')
  pressed.clear()
  sticks.leftX = sticks.leftY = sticks.rightX = sticks.rightY = 0
  publishControl(true)
}

function handleVisibilityChange() {
  if (document.hidden) releaseKeys()
}

function applyVideoStats(stats: { bitrateKbps: number; width: number; height: number }) {
  videoBitrate.value = stats.bitrateKbps
  videoSize.value = stats.width && stats.height ? `${stats.width}×${stats.height}` : ''
  if (stats.width > 0 && stats.height > 0) {
    videoAspectRatio.value = `${stats.width} / ${stats.height}`
  }
  if (stats.bitrateKbps > 0) zeroBitrateSince = 0
  else if (!zeroBitrateSince) zeroBitrateSince = Date.now()
}

function syncVideoAspectRatio() {
  const width = videoElement.value?.videoWidth ?? 0
  const height = videoElement.value?.videoHeight ?? 0
  if (width > 0 && height > 0) videoAspectRatio.value = `${width} / ${height}`
}

function monitorVideoBitrate() {
  if (!videoPlaying.value) {
    zeroBitrateSince = 0
    if (videoRetrying.value) return
    const now = Date.now()
    if (videoState.value === 'connecting' || videoState.value === 'waiting') {
      if (!videoConnectingSince) videoConnectingSince = now
      if (now - videoConnectingSince >= 25_000 && now - lastAutoVideoRetryAt >= 20_000) {
        lastAutoVideoRetryAt = now
        void retryVideoPlayback(false)
      }
    } else if (
      videoState.value === 'idle' &&
      reportedLive.value &&
      selectedSource.value &&
      now - lastAutoVideoRetryAt >= 15_000
    ) {
      // Some firmware reports live_status before its encoder starts delivering
      // RTP. If the valid publisher arrives after the bounded start task ended,
      // keep probing WHEP instead of leaving the cockpit stuck on "启动直播".
      lastAutoVideoRetryAt = now
      void retryVideoPlayback(false)
    } else {
      videoConnectingSince = 0
    }
    return
  }
  videoConnectingSince = 0
  if (videoRetrying.value) {
    return
  }
  if (videoBitrate.value > 0) {
    zeroBitrateSince = 0
    return
  }
  if (!zeroBitrateSince) zeroBitrateSince = Date.now()
  const now = Date.now()
  if (now - zeroBitrateSince >= 7_000 && now - lastAutoVideoRetryAt >= 15_000) {
    lastAutoVideoRetryAt = now
    void retryVideoPlayback(false)
  }
}

function scheduleVideoReconnect(reason: string, delayMs = 300, force = false) {
  const sourceKey = selectedSource.value?.key
  if (componentExiting || dockSelectionPending.value || !sourceKey) return
  if (videoPlaying.value && videoBitrate.value > 0 &&
      videoState.value !== 'failed' && videoState.value !== 'disconnected') return
  if (!force && (videoRetrying.value || videoStartTask ||
      videoState.value === 'connecting' || videoState.value === 'waiting')) return

  window.clearTimeout(videoReconnectTimer)
  videoReconnectTimer = window.setTimeout(() => {
    videoReconnectTimer = 0
    if (componentExiting || dockSelectionPending.value ||
        selectedSource.value?.key !== sourceKey || videoRetrying.value) return
    if (videoPlaying.value && videoBitrate.value > 0 &&
        videoState.value !== 'failed' && videoState.value !== 'disconnected') return
    lastAutoVideoRetryAt = Date.now()
    addInteractionLog({
      transport: 'HTTP',
      direction: 'OUT',
      summary: `${reason}，主动重连视频`,
      payload: { source: sourceKey }
    })
    void retryVideoPlayback(false)
  }, delayMs)
}

async function retryVideoPlayback(manual = true) {
  if (dockSelectionPending.value || videoRetrying.value || !selectedSource.value) return
  const sourceKey = selectedSource.value.key
  videoRetrying.value = true
  videoError.value = manual
    ? '正在重新连接视频流…'
    : videoPlaying.value
      ? '检测到 0 kbps，正在自动重试…'
      : '视频连接超时，正在自动重试…'
  zeroBitrateSince = 0
  videoConnectingSince = 0
  try {
    // Invalidate an in-flight start task before closing its WHEP session. This
    // makes manual retry effective even while the old attempt still says
    // "connecting", without stopping an already-running device publisher.
    ++videoOperationGeneration
    videoStartTask = undefined
    await player.stop()
    if (componentExiting || selectedSource.value?.key !== sourceKey) return
    videoPlaying.value = false
    videoState.value = 'idle'
    videoBitrate.value = 0
    videoSize.value = ''
    reusedPublisher.value = false
    await delay(350)
    await startVideo()
  } finally {
    videoRetrying.value = false
  }
}

function videoOperationIsCurrent(operation: number, sourceKey: string) {
  return operation === videoOperationGeneration &&
    selectedSource.value?.key === sourceKey &&
    !componentExiting
}

function streamStartOutcomeUncertain(reason: unknown) {
  if (!(reason instanceof ApiError)) return true
  return reason.status === 0 || reason.status === 408 || reason.status >= 500 ||
    reason.code === 211001
}

function beginVideoStartRequest(publisherKey: string, videoId: LiveVideoId) {
  let pending = inFlightVideoStarts.get(publisherKey)
  if (!pending) {
    pending = {
      count: 0,
      ownedBefore: startedVideoIds.has(publisherKey),
      mayHaveCreated: false,
      stopRequested: false,
      videoId
    }
    inFlightVideoStarts.set(publisherKey, pending)
  }
  pending.count += 1
  // Until the mutating request settles, preserve a recovery record. A timeout
  // can mean that the device started even though this browser saw no response.
  if (!startedVideoIds.has(publisherKey)) {
    startedVideoIds.set(publisherKey, videoId)
    persistStartedVideoPublishers()
  }
}

async function finishVideoStartRequest(
  publisherKey: string,
  outcome: VideoStartOutcome
): Promise<boolean> {
  const pending = inFlightVideoStarts.get(publisherKey)
  if (!pending) return startedVideoIds.has(publisherKey)
  if (outcome === 'created' || outcome === 'ambiguous') pending.mayHaveCreated = true
  pending.count = Math.max(0, pending.count - 1)
  if (pending.count > 0) return pending.ownedBefore || pending.mayHaveCreated

  inFlightVideoStarts.delete(publisherKey)
  const ownsPublisher = pending.ownedBefore || pending.mayHaveCreated
  if (ownsPublisher) startedVideoIds.set(publisherKey, pending.videoId)
  else startedVideoIds.delete(publisherKey)
  persistStartedVideoPublishers()

  // All starts for this key have now settled. Only the last one may clean up,
  // which prevents an earlier response from stopping ahead of a later start.
  if ((componentExiting || pending.stopRequested) && ownsPublisher) {
    const stopped = await stopVideoPublisher(pending.videoId)
    if (stopped) {
      startedVideoIds.delete(publisherKey)
      persistStartedVideoPublishers()
    }
  }
  return ownsPublisher
}

async function stopVideoPublisher(videoId: LiveVideoId): Promise<boolean> {
  try {
    // The caller may be a late /start completion after the user has already
    // logged out locally. Retain the most recent cockpit token only for this
    // bounded resource cleanup so the new publisher is not leaked.
    await post('/manage/api/v1/live/streams/stop', { video_id: videoId }, {
      ...CONTROL_REQUEST_OPTIONS,
      authToken: cockpitCleanupToken
    })
    return true
  } catch {
    return false
  }
}

function startVideo(): Promise<void> {
  if (videoStartTask) return videoStartTask
  const sourceKey = selectedSource.value?.key
  if (dockSelectionPending.value || !sourceKey || videoState.value !== 'idle') {
    return Promise.resolve()
  }
  const operation = ++videoOperationGeneration
  const tracked = performStartVideo(operation, sourceKey).finally(() => {
    if (videoStartTask === tracked) videoStartTask = undefined
  })
  videoStartTask = tracked
  return tracked
}

async function performStartVideo(operation: number, sourceKey: string, retryAttempt = 0) {
  if (!videoOperationIsCurrent(operation, sourceKey) ||
      dockSelectionPending.value || videoState.value !== 'idle') return
  videoError.value = ''
  videoState.value = 'connecting'
  if (!videoConnectingSince) videoConnectingSince = Date.now()
  videoBitrate.value = 0
  videoSize.value = ''
  const source = selectedSource.value
  if (!source) return
  const videoId = { drone_sn: source.deviceSn, payload_index: source.cameraIndex }
  const publisherKey = `${videoId.drone_sn}/${videoId.payload_index}`
  let startRequestAcknowledged = false
  let explicitStartFailure = false
  const requestedQuality = preferredVideoQualities.get(publisherKey) ?? 2
  preferredVideoQualities.set(publisherKey, requestedQuality)
  videoQuality.value = requestedQuality
  activeVideoPublisherKey = publisherKey
  const updateVideoState = (value: WhepState) => {
    if (!videoOperationIsCurrent(operation, sourceKey)) return
    const wasPlaying = videoPlaying.value
    videoState.value = value
    if (wasPlaying && (value === 'failed' || value === 'disconnected')) {
      videoPlaying.value = false
      scheduleVideoReconnect(`WHEP ${value}`, 500, true)
    }
  }
  const updateVideoStats = (stats: { bitrateKbps: number; width: number; height: number }) => {
    if (videoOperationIsCurrent(operation, sourceKey)) applyVideoStats(stats)
  }
  try {
    await nextTick()
    if (!videoOperationIsCurrent(operation, sourceKey)) return
    if (!videoElement.value) throw new Error('视频容器不可用')

    // The MediaMTX path is deterministic. Probe it first so an already-running
    // device publisher can be consumed without sending stop/start commands.
    const existingWhepUrl = `/webrtc/${source.deviceSn}-${source.cameraIndex}/whep`
    const reusedExistingStream = await player.play(videoElement.value, existingWhepUrl, {
      timeoutMs: 2_500,
      onState: updateVideoState,
      onStats: updateVideoStats
    })
      .then(() => true)
      .catch(() => false)
    if (!videoOperationIsCurrent(operation, sourceKey)) return

    if (reusedExistingStream) {
      reusedPublisher.value = true
      videoConnectingSince = 0
      videoError.value = ''
      // Reusing WHEP must still apply this publisher's desired lens and quality.
      // An unseen publisher starts as SD; revisiting the same publisher and its
      // retries retain an explicit user-selected HD preference. Keep quality
      // controls locked until both defaults finish so an older SD update cannot
      // race with and overwrite a user's HD click.
      qualitySwitching.value = true
      try {
        await post('/manage/api/v1/live/streams/switch', {
          video_id: {
            drone_sn: source.deviceSn,
            payload_index: source.cameraIndex
          },
          video_type: lens.value
        }).catch((reason) => {
          if (videoOperationIsCurrent(operation, sourceKey)) {
            videoError.value = reason instanceof Error ? reason.message : '默认变焦镜头切换失败'
          }
        })
        if (!videoOperationIsCurrent(operation, sourceKey)) return
        const desiredQuality = preferredVideoQualities.get(publisherKey) ?? requestedQuality
        videoQuality.value = desiredQuality
        await post('/manage/api/v1/live/streams/update', {
          video_id: videoId,
          video_quality: desiredQuality
        }).catch((reason) => {
          if (videoOperationIsCurrent(operation, sourceKey)) {
            videoError.value = reason instanceof Error ? reason.message : '默认清晰度应用失败'
          }
        })
        if (!videoOperationIsCurrent(operation, sourceKey)) return
      } finally {
        qualitySwitching.value = false
      }
      videoPlaying.value = true
      return
    }

    // No playable publisher exists, so ask the selected device to start one.
    reusedPublisher.value = false
    videoState.value = 'connecting'
    beginVideoStartRequest(publisherKey, videoId)
    let response: {
      url: string
      reused?: boolean
      started_by_request?: boolean
      startedByRequest?: boolean
    }
    try {
      response = await post<{
        url: string
        reused?: boolean
        started_by_request?: boolean
        startedByRequest?: boolean
      }>('/manage/api/v1/live/streams/start', {
        video_id: videoId,
        url_type: 2,
        video_quality: requestedQuality,
        video_type: lens.value
      }, LIVE_START_REQUEST_OPTIONS)
      startRequestAcknowledged = true
      const startedByRequest = response.started_by_request ?? response.startedByRequest
      await finishVideoStartRequest(
        publisherKey,
        startedByRequest === false ? 'preexisting' : 'created')
    } catch (reason) {
      if (!startRequestAcknowledged) {
        explicitStartFailure = !streamStartOutcomeUncertain(reason)
        await finishVideoStartRequest(
          publisherKey,
          explicitStartFailure ? 'explicit-failure' : 'ambiguous')
      }
      throw reason
    }
    if (!videoOperationIsCurrent(operation, sourceKey)) return
    reusedPublisher.value = response.reused === true
    if (!response.url || response.url.toLowerCase().startsWith('rtsp://')) throw new Error('WHEP 播放地址未配置')
    let playbackError: unknown
    const playback = player.play(videoElement.value, response.url, {
      timeoutMs: 18_000,
      onState: updateVideoState,
      onStats: updateVideoStats
    })
      .then(() => true)
      .catch((reason) => {
        playbackError = reason
        return false
      })

    // EVO Max firmware can announce an H.264 RTSP track without sending media
    // packets until the camera encoder is nudged. Keep the WHEP reader alive
    // and temporarily change lenses after four seconds to force an IDR frame.
    const initialPlayback = await Promise.race([
      playback,
      delay(4_000).then(() => undefined)
    ])
    if (!videoOperationIsCurrent(operation, sourceKey)) return
    let recovered = false
    if (initialPlayback !== true) {
      recovered = await recoverVideoEncoder(source, lens.value, operation, sourceKey)
      if (!videoOperationIsCurrent(operation, sourceKey)) return
    }

    let connected = initialPlayback === true ? true : await playback
    if (!videoOperationIsCurrent(operation, sourceKey)) return
    if (!connected && recovered && videoElement.value) {
      connected = await player.play(videoElement.value, response.url, {
        timeoutMs: 10_000,
        onState: updateVideoState,
        onStats: updateVideoStats
      })
        .then(() => true)
        .catch((reason) => {
          playbackError = reason
          return false
        })
      if (!videoOperationIsCurrent(operation, sourceKey)) return
    }
    if (!connected) {
      throw playbackError instanceof Error
        ? playbackError
        : new Error('设备未产生可播放的视频帧')
    }
    videoPlaying.value = true
    videoConnectingSince = 0
  } catch (reason) {
    if (!videoOperationIsCurrent(operation, sourceKey)) return
    const message = reason instanceof Error ? reason.message : '主画面启动失败'
    // The first timeout may only mean a late encoder, so retry WHEP once without
    // disturbing the publisher. A second complete timeout means live_status can
    // be a stale "1"; stop that zombie session before the final start attempt.
    const restartPublisher = retryAttempt === 1 && startedVideoIds.has(publisherKey)
    await resetVideoSession(
      restartPublisher,
      () => videoOperationIsCurrent(operation, sourceKey),
      publisherKey)
    if (!videoOperationIsCurrent(operation, sourceKey)) return
    if (!explicitStartFailure && retryAttempt < 2 && selectedSource.value?.key === source.key) {
      videoError.value = restartPublisher
        ? `${message}，正在重建设备推流…`
        : `${message}，正在自动重试…`
      await delay(1_500 * (retryAttempt + 1))
      if (videoOperationIsCurrent(operation, sourceKey)) {
        await performStartVideo(operation, sourceKey, retryAttempt + 1)
        return
      }
    }
    videoError.value = message
  }
}

async function recoverVideoEncoder(
  source: CockpitSource,
  desiredLens: 'normal' | 'wide' | 'zoom' | 'ir',
  operation: number,
  sourceKey: string
) {
  const reported = sources.value
    .filter((item) =>
      item.deviceSn === source.deviceSn &&
      item.cameraIndex === source.cameraIndex)
    .flatMap((item) => [item.type, ...item.switchVideoTypes])
  const dualSensorFallback = desiredLens === 'zoom'
    ? ['ir']
    : desiredLens === 'ir'
      ? ['zoom']
      : []
  const candidates = [...new Set([...reported, ...dualSensorFallback])]
    .filter((value): value is 'normal' | 'wide' | 'zoom' | 'ir' =>
      typeof value === 'string' &&
      value !== desiredLens &&
      ['normal', 'wide', 'zoom', 'ir'].includes(value))

  for (const alternateLens of candidates) {
    // Abort before sending any further lens command once this operation has
    // been superseded (exit/retry/new source) — otherwise the delayed
    // switch-back below can land on the device after a newer session has
    // already started, leaving the picture stuck on the recovery lens.
    if (!videoOperationIsCurrent(operation, sourceKey)) return false
    let switchedToAlternate = false
    try {
      await post('/manage/api/v1/live/streams/switch', {
        video_id: {
          drone_sn: source.deviceSn,
          payload_index: source.cameraIndex
        },
        video_type: alternateLens
      })
      switchedToAlternate = true
      await delay(1_800)
      if (!videoOperationIsCurrent(operation, sourceKey)) return false
      await post('/manage/api/v1/live/streams/switch', {
        video_id: {
          drone_sn: source.deviceSn,
          payload_index: source.cameraIndex
        },
        video_type: desiredLens
      })
      // Do not expose buffered frames from the temporary recovery lens.
      // Give the aircraft encoder time to emit the first frame for the lens
      // actually selected by the operator before revealing the video element.
      await delay(1_200)
      return true
    } catch {
      // The switch-back failed after the device already moved to the
      // recovery lens — restore the desired lens before giving up so the
      // device isn't left parked on the alternate lens.
      if (switchedToAlternate && videoOperationIsCurrent(operation, sourceKey)) {
        await post('/manage/api/v1/live/streams/switch', {
          video_id: {
            drone_sn: source.deviceSn,
            payload_index: source.cameraIndex
          },
          video_type: desiredLens
        }).catch(() => undefined)
      }
    }
  }
  return false
}

async function resetVideoSession(
  stopDevice: boolean,
  isCurrent: () => boolean = () => true,
  publisherKey = activeVideoPublisherKey
) {
  await player.stop()
  if (!isCurrent()) return
  if (stopDevice) {
    const pending = inFlightVideoStarts.get(publisherKey)
    if (pending) pending.stopRequested = true
  }
  const source = selectedSource.value
  // An explicit stop must also work after this page reused a publisher that
  // was already running, even though that publisher is not in our ownership
  // map. Internal detach/retry paths pass stopDevice=false and never hit this.
  const videoId = startedVideoIds.get(publisherKey) ?? (
    stopDevice && source && videoPublisherKey(source) === publisherKey
      ? { drone_sn: source.deviceSn, payload_index: source.cameraIndex }
      : undefined)
  if (stopDevice && videoId) {
    const stopped = await stopVideoPublisher(videoId)
    if (stopped) {
      startedVideoIds.delete(publisherKey)
      persistStartedVideoPublishers()
    } else if (isCurrent()) {
      videoError.value = '停止设备推流未确认，已保留清理记录，请稍后重试'
    }
    if (!isCurrent()) return
  }
  if (activeVideoPublisherKey === publisherKey) activeVideoPublisherKey = ''
  videoPlaying.value = false
  videoState.value = 'idle'
  videoBitrate.value = 0
  videoSize.value = ''
  zeroBitrateSince = 0
  videoConnectingSince = 0
  reportedLive.value = false
  reusedPublisher.value = false
  liveLensType.value = ''
  reportedVideoQuality.value = undefined
}

async function detachVideoForDeviceSwitch() {
  window.clearTimeout(videoReconnectTimer)
  videoReconnectTimer = 0
  topologyVideoReconnectReason = ''
  const operation = ++videoOperationGeneration
  videoStartTask = undefined
  // Publisher ownership remains keyed by device/payload, so a later explicit
  // stop targets that device without leaking ownership into the next device.
  await resetVideoSession(false, () => operation === videoOperationGeneration)
}

async function stopVideo() {
  window.clearTimeout(videoReconnectTimer)
  videoReconnectTimer = 0
  topologyVideoReconnectReason = ''
  const operation = ++videoOperationGeneration
  videoStartTask = undefined
  await resetVideoSession(true, () => operation === videoOperationGeneration)
}

async function stopStartedVideoPublishers() {
  const publishers = [...startedVideoIds.entries()]
  const results = await Promise.all(publishers.map(async ([publisherKey, videoId]) => {
    // A start request can execute server-side after this page sends stop. Do
    // not stop or delete its recovery record until that request settles; its
    // success/error branch will perform a second, correctly ordered cleanup.
    if ((inFlightVideoStarts.get(publisherKey)?.count ?? 0) > 0) {
      return { publisherKey, stopped: false }
    }
    const stopped = await stopVideoPublisher(videoId)
    return { publisherKey, stopped }
  }))
  for (const result of results) {
    if (result.stopped) startedVideoIds.delete(result.publisherKey)
  }
  persistStartedVideoPublishers()
}
async function switchLens(value: 'normal' | 'wide' | 'zoom' | 'ir') {
  if (dockSelectionPending.value) return
  const previousLens = lens.value
  payloadZoomTarget.value = undefined
  lens.value = value
  if (!selectedSource.value || !videoPlaying.value) return
  try {
    await post('/manage/api/v1/live/streams/switch', {
      video_id: {
        drone_sn: selectedSource.value.deviceSn,
        payload_index: selectedSource.value.cameraIndex
      },
      video_type: value
    })
  } catch (reason) {
    lens.value = previousLens
    videoError.value = reason instanceof Error ? reason.message : '镜头切换失败'
  }
}

async function grabFlightAuthority(expectedDockSn = dockSn.value): Promise<boolean> {
  if (dockSelectionPending.value) return false
  if (hasFlightAuthority.value && dockSn.value === expectedDockSn) return true
  if (!selectedAircraftOnline.value || selectedDock.value?.device_sn !== expectedDockSn || flightAuthorityPending.value) {
    if (!selectedAircraftOnline.value) error.value = '遥控器未连接飞机，无法获取飞行控制权'
    return false
  }
  flightAuthorityPending.value = true
  error.value = ''
  try {
    await post(`/control/api/v1/devices/${expectedDockSn}/authority/flight`, {}, CONTROL_REQUEST_OPTIONS)
    if (dockSelectionPending.value || dockSn.value !== expectedDockSn || selectedDock.value?.device_sn !== expectedDockSn) {
      error.value = '设备已切换，原设备飞行控制权结果已忽略'
      return false
    }
    if (drcEnterPending.value && drcEnterCancelled) {
      error.value = '飞行控制权在 DRC 连接期间已转移，请重新操作'
      return false
    }
    // services_reply=0 表示设备已受理；control_source_change 仍会继续校准真实状态。
    flightControlSource.value = 'A'
    lastFlightAuthorityGrabAt = Date.now()
    return true
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '飞行控制权抢夺失败'
    return false
  } finally {
    flightAuthorityPending.value = false
  }
}

async function grabPayloadAuthority(): Promise<boolean> {
  const source = selectedSource.value
  const key = payloadAuthorityKey.value
  const gatewaySn = selectedDock.value?.device_sn
  if (dockSelectionPending.value) return false
  if (hasPayloadAuthority.value) return true
  if (!selectedAircraftOnline.value) {
    error.value = '遥控器未连接飞机，无法获取负载控制权'
    return false
  }
  if (!gatewaySn || !source || !key || payloadAuthorityPending.value) return false
  payloadAuthorityPending.value = true
  error.value = ''
  try {
    await post(`/control/api/v1/devices/${gatewaySn}/authority/payload`, {
      payload_index: source.cameraIndex
    }, CONTROL_REQUEST_OPTIONS)
    if (dockSelectionPending.value || selectedDock.value?.device_sn !== gatewaySn || payloadAuthorityKey.value !== key) {
      error.value = '设备或负载已切换，原负载控制权结果已忽略'
      return false
    }
    payloadAuthorityKeys.add(key)
    return true
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '负载控制权抢夺失败'
    return false
  } finally {
    payloadAuthorityPending.value = false
  }
}

async function switchQuality(value: LiveQuality) {
  if (dockSelectionPending.value || value === videoQuality.value || qualitySwitching.value) return
  const source = selectedSource.value
  if (!source) return
  const publisherKey = videoPublisherKey(source)
  const previousQuality = preferredVideoQualities.get(publisherKey) ?? videoQuality.value
  preferredVideoQualities.set(publisherKey, value)
  videoQuality.value = value
  if (!videoPlaying.value) return
  qualitySwitching.value = true
  videoError.value = ''
  try {
    await post('/manage/api/v1/live/streams/update', {
      video_id: {
        drone_sn: source.deviceSn,
        payload_index: source.cameraIndex
      },
      video_quality: value
    })
  } catch (reason) {
    preferredVideoQualities.set(publisherKey, previousQuality)
    if (videoPublisherKey() === publisherKey) {
      videoQuality.value = previousQuality
      videoError.value = reason instanceof Error ? reason.message : '清晰度切换失败'
    }
  } finally {
    qualitySwitching.value = false
  }
}

function showCameraActionTip(message: string) {
  cameraActionTip.value = message
  window.clearTimeout(cameraActionTipTimer)
  cameraActionTipTimer = window.setTimeout(() => { cameraActionTip.value = '' }, 2500)
}

async function runCameraCommand(
  action: 'photo' | 'recording',
  method: 'camera_photo_take' | 'camera_recording_start' | 'camera_recording_stop'
) {
  const source = selectedSource.value
  const gatewaySn = dockSn.value
  const sourceKey = payloadAuthorityKey.value
  if (dockSelectionPending.value || !gatewaySn || !source || !sourceKey || cameraCommandPending.value) return

  cameraCommandPending.value = action
  error.value = ''
  try {
    if (!hasPayloadAuthority.value && !await grabPayloadAuthority()) return
    if (dockSelectionPending.value || dockSn.value !== gatewaySn ||
        payloadAuthorityKey.value !== sourceKey || !payloadAuthorityKeys.has(sourceKey)) {
      throw new Error('设备或负载已切换，已取消相机指令')
    }
    await post(`/control/api/v1/devices/${gatewaySn}/payload/commands`, {
      cmd: method,
      data: { payload_index: source.cameraIndex }
    }, CONTROL_REQUEST_OPTIONS)
    if (method === 'camera_recording_start') {
      recording.value = true
      recordingSeconds.value = 0
      showCameraActionTip('设备已开始录像')
    } else if (method === 'camera_recording_stop') {
      recording.value = false
      recordingSeconds.value = 0
      showCameraActionTip('设备已停止录像')
    } else {
      showCameraActionTip('设备拍照成功')
    }
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '相机负载指令执行失败'
  } finally {
    cameraCommandPending.value = ''
  }
}

function takeDevicePhoto() {
  void runCameraCommand('photo', 'camera_photo_take')
}

function toggleDeviceRecording() {
  void runCameraCommand(
    'recording',
    recording.value ? 'camera_recording_stop' : 'camera_recording_start')
}

async function toggleTargetDetection() {
  const gatewaySn = dockSn.value
  const authorityKey = payloadAuthorityKey.value
  if (dockSelectionPending.value || !gatewaySn || !authorityKey || targetDetectionPending.value) return
  targetDetectionPending.value = true
  error.value = ''
  try {
    if (!hasPayloadAuthority.value && !await grabPayloadAuthority()) return
    if (dockSelectionPending.value || dockSn.value !== gatewaySn ||
        payloadAuthorityKey.value !== authorityKey || !payloadAuthorityKeys.has(authorityKey)) {
      throw new Error('设备或负载控制权状态已变化，已取消目标识别指令')
    }
    if (targetDetectionEnabled.value) {
      await del(`/control/api/v1/devices/${gatewaySn}/target-detection`, undefined, CONTROL_REQUEST_OPTIONS)
      targetDetectionEnabled.value = false
      detectedTargets.value = []
    } else {
      await post(`/control/api/v1/devices/${gatewaySn}/target-detection`, {
        ai_lens_type: displayLens.value === 'ir' ? 1 : 0,
        scene_type: 0,
        target_type_list: []
      }, CONTROL_REQUEST_OPTIONS)
      targetDetectionEnabled.value = true
    }
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '目标识别切换失败'
  } finally {
    targetDetectionPending.value = false
  }
}

function toggleVideoFullscreen() {
  if (document.fullscreenElement) void document.exitFullscreen()
  else void videoBox.value?.requestFullscreen()
}

function recordingTime(seconds: number) {
  const minutes = Math.floor(seconds / 60).toString().padStart(2, '0')
  return `${minutes}:${(seconds % 60).toString().padStart(2, '0')}`
}

function delay(ms: number) {
  return new Promise((resolve) => window.setTimeout(resolve, ms))
}

async function returnHome() {
  const targetDockSn = selectedDock.value?.device_sn
  if (dockSelectionPending.value || returnHomePending.value || emergencyStopPending.value ||
      drcLandingPending.value || continuousLandingActive.value || !targetDockSn ||
      !window.confirm('确认向当前设备下发返航指令？')) return
  returnHomePending.value = true
  error.value = ''
  try {
    if (!hasFlightAuthority.value && !await grabFlightAuthority(targetDockSn)) return
    if (!controlTargetValid(targetDockSn)) throw new Error('设备或飞行控制权状态已变化，已取消返航')
    await post(`/control/api/v1/devices/${targetDockSn}/jobs/return_home`, undefined, CONTROL_REQUEST_OPTIONS)
    showCameraActionTip('返航指令调用成功（不代表已返航或落地）')
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '返航指令调用失败'
  } finally {
    returnHomePending.value = false
  }
}

async function cancelReturnHome() {
  const targetDockSn = selectedDock.value?.device_sn
  if (dockSelectionPending.value || returnHomeCancelPending.value || emergencyStopPending.value ||
      drcLandingPending.value || continuousLandingActive.value || !targetDockSn ||
      !window.confirm('确认取消返航？取消后飞行器将在原地悬停。')) return
  returnHomeCancelPending.value = true
  error.value = ''
  try {
    if (!hasFlightAuthority.value && !await grabFlightAuthority(targetDockSn)) return
    if (!controlTargetValid(targetDockSn)) throw new Error('设备或飞行控制权状态已变化，已取消操作')
    await post(`/control/api/v1/devices/${targetDockSn}/jobs/return_home_cancel`, undefined, CONTROL_REQUEST_OPTIONS)
    showCameraActionTip('取消返航指令调用成功（飞行器将悬停，不代表已悬停）')
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '取消返航指令调用失败'
  } finally {
    returnHomeCancelPending.value = false
  }
}

// 航线暂停：PUT jobs/{jobId} status=0（PAUSE），设备回 flighttask_pause。
async function pauseWaylineJob() {
  const jobId = waylineProgress.value?.jobId
  if (!jobId || waylinePausePending.value || waylineResumePending.value || waylineCancelPending.value) return
  waylinePausePending.value = true
  error.value = ''
  try {
    await put(`/wayline/api/v1/workspaces/${cockpitWorkspaceId}/jobs/${encodeURIComponent(jobId)}`, { status: 0 })
    showCameraActionTip('航线暂停指令调用成功（等待设备确认）')
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '航线暂停指令调用失败'
  } finally {
    waylinePausePending.value = false
  }
}

// 航线恢复：PUT jobs/{jobId} status=1（RESUME），设备回 flighttask_recovery。
async function resumeWaylineJob() {
  const jobId = waylineProgress.value?.jobId
  if (!jobId || waylinePausePending.value || waylineResumePending.value || waylineCancelPending.value) return
  waylineResumePending.value = true
  error.value = ''
  try {
    await put(`/wayline/api/v1/workspaces/${cockpitWorkspaceId}/jobs/${encodeURIComponent(jobId)}`, { status: 1 })
    showCameraActionTip('航线恢复指令调用成功（等待设备确认）')
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '航线恢复指令调用失败'
  } finally {
    waylineResumePending.value = false
  }
}

// 取消任务：DELETE jobs?job_id={jobId}，设备回 flighttask_undo。
async function cancelWaylineJob() {
  const jobId = waylineProgress.value?.jobId
  if (!jobId || waylineCancelPending.value ||
      !window.confirm('确认取消当前航线任务？取消后飞行器将退出航线执行。')) return
  waylineCancelPending.value = true
  error.value = ''
  try {
    await del(`/wayline/api/v1/workspaces/${cockpitWorkspaceId}/jobs?job_id=${encodeURIComponent(jobId)}`)
    showCameraActionTip('取消航线任务指令调用成功（等待设备确认）')
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '取消航线任务指令调用失败'
  } finally {
    waylineCancelPending.value = false
  }
}

async function emergencyStop() {
  if (dockSelectionPending.value || emergencyStopPending.value || drcLandingPending.value || !selectedDock.value) return
  if (!drcControlsReady.value) {
    if (active.value) await leaveDrc('emergency-stop-reconnect')
    if (!await enter()) return
  }
  releaseKeys()
  emergencyStopPending.value = true
  const requestId = publish('drone_emergency_stop', {})
  if (!requestId) {
    emergencyStopPending.value = false
    error.value = 'DRC MQTT 未连接，刹车悬停指令未发送'
    return
  }
  window.clearTimeout(emergencyStopTimer)
  emergencyRequestId = requestId
  emergencyStopTimer = window.setTimeout(() => {
    if (emergencyStopPending.value && emergencyRequestId === requestId) {
      emergencyStopPending.value = false
      emergencyRequestId = ''
      emergencyStopTimer = 0
      showCameraActionTip('刹车悬停指令已发送，设备暂未返回确认')
    }
  }, 3_000)
}

async function drcLanding(method: DrcLandingMethod) {
  const gatewaySn = selectedDock.value?.device_sn
  if (dockSelectionPending.value || state.value === 'connecting' ||
      returnHomePending.value || emergencyStopPending.value || drcLandingPending.value ||
      continuousLandingActive.value || !gatewaySn) return
  if (operationPanelState.value === 'ground') {
    error.value = '飞机当前未处于飞行状态，未发送降落指令'
    return
  }
  const emergency = method === 'drc_emergency_landing'
  const confirmed = window.confirm(emergency
    ? '确认下发紧急降落？飞机将避障并识别二维码降落。设备返回 result=0 只表示指令调用成功，不代表已经落地。'
    : '高风险：确认下发强制降落？飞机将不考虑障碍物直接降落。请确认下方区域绝对安全；设备回复成功不代表已经落地。')
  if (!confirmed) return

  if (!drcLinkReady.value) {
    if (active.value) await leaveDrc(`${method}-reconnect`)
    if (!await enter()) return
  }
  if (!drcLinkReady.value || selectedDock.value?.device_sn !== gatewaySn) {
    error.value = 'DRC 控制链路或目标设备已变化，降落指令未发送'
    return
  }

  releaseKeys()
  drcLandingPending.value = method
  const requestId = publish(method, {})
  if (!requestId) {
    drcLandingPending.value = ''
    error.value = 'DRC MQTT 未连接，降落指令未发送'
    return
  }
  drcLandingRequestId = requestId
  drcLandingGatewaySn = gatewaySn
  window.clearTimeout(drcLandingTimer)
  drcLandingTimer = window.setTimeout(() => {
    if (drcLandingPending.value === method && drcLandingRequestId === requestId) {
      const label = method === 'drc_emergency_landing' ? '紧急降落' : '强制降落'
      drcLandingPending.value = ''
      drcLandingRequestId = ''
      drcLandingGatewaySn = ''
      drcLandingTimer = 0
      // 诊断日志：留存超时上下文，便于与 services_reply 迟到回包对账。
      addInteractionLog({
        transport: 'MQTT',
        direction: 'INFO',
        summary: `${method} 等待回包超时（5s）`,
        payload: {
          method,
          requestId,
          gatewaySn,
          expectedReplyTopic: `thing/product/${gatewaySn}/services_reply`,
          note: '未收到 services_reply；请核对本面板稍后是否出现同 tid 的迟到回包，以及后端 api 日志中的【services_reply】记录'
        }
      })
      showCameraActionTip(`${label}指令已发送，设备暂未返回调用结果`)
    }
  }, 5_000)
}

async function submitMapTarget() {
  const mode = mapTargetMode.value
  const normalizedMaxSpeed = Math.round(Number(mapTarget.maxSpeed))
  if (!mode || dockSelectionPending.value || mapTargetPending.value) return
  const targetDockSn = selectedDock.value?.device_sn
  if (!targetDockSn) {
    error.value = '当前没有已连接的设备'
    return
  }
  if (!selectedAircraftOnline.value) {
    error.value = '遥控器未连接飞机，不能发送地图目标指令'
    return
  }
  if (!mapTargetValid.value) {
    error.value = '请输入有效经纬度和 2–10000 m 的目标高度'
    return
  }
  if (mode === 'flyto' &&
      (!Number.isFinite(normalizedMaxSpeed) || normalizedMaxSpeed < 1 || normalizedMaxSpeed > 15)) {
    error.value = '指点飞行最大速度须为 1–15 m/s 的整数'
    return
  }
  if (mode === 'flyto' && telemetry.pointFlightActive) {
    error.value = '当前已有指点飞行任务，请先结束后再选择新目标'
    return
  }
  if (mode === 'flyto' && operationPanelState.value !== 'airborne') {
    error.value = '指点飞行仅可在飞机已起飞且无其他任务时启动'
    return
  }
  const action = mode === 'flyto' ? '启动指点飞行' : '执行 Look At'
  const heightName = mode === 'flyto' ? '目标相对高度' : '目标椭球高'
  if (mode === 'flyto') mapTarget.maxSpeed = normalizedMaxSpeed
  if (!window.confirm(
    `确认${action}？\n目标：${mapTarget.latitude.toFixed(6)}, ${mapTarget.longitude.toFixed(6)}\n${heightName}：${mapTarget.height.toFixed(1)} m` +
    (mode === 'flyto' ? `\n最大速度：${normalizedMaxSpeed} m/s` : '')
  )) return

  mapTargetPending.value = true
  error.value = ''
  try {
    if (mode === 'flyto') {
      if (!hasFlightAuthority.value && !await grabFlightAuthority(targetDockSn)) return
      if (!controlTargetValid(targetDockSn)) throw new Error('设备或飞行控制权状态已变化，已取消指点飞行')
      Object.assign(pointFlightTarget, {
        latitude: Number(mapTarget.latitude.toFixed(6)),
        longitude: Number(mapTarget.longitude.toFixed(6)),
        height: Number(mapTarget.height.toFixed(1))
      })
      beginPointFlightSubmission('flyto')
      updatePointFlightTargetMarker()
      await post(`/control/api/v1/devices/${targetDockSn}/jobs/fly-to-point`, {
        max_speed: normalizedMaxSpeed,
        points: [{
          latitude: Number(mapTarget.latitude.toFixed(6)),
          longitude: Number(mapTarget.longitude.toFixed(6)),
          height: Number(mapTarget.height.toFixed(1))
        }]
      }, CONTROL_REQUEST_OPTIONS)
      if (!await loadPointFlightState(targetDockSn)) {
        markPointFlightUncertain(
          'flyto',
          'FlyTo 指令已受理，但暂未取得任务 ID；已锁定手动控制并持续同步任务状态'
        )
      }
      showCameraActionTip('指点飞行指令已受理，等待设备进度事件')
      Object.assign(mapTargetDrafts.flyto, mapTarget)
      mapTargetPanelOpen.value = false
      mapTargetMode.value = undefined
      if (mapTargetMarker) {
        map?.remove(mapTargetMarker)
        mapTargetMarker = undefined
      }
      updatePointFlightTargetMarker()
    } else {
      if (!hasPayloadAuthority.value && !await grabPayloadAuthority()) return
      if (dockSelectionPending.value || selectedDock.value?.device_sn !== targetDockSn) {
        throw new Error('设备已切换，已取消 Look At')
      }
      const succeeded = await queuePayloadCommand('camera_look_at', {
        // 只转云台，不联动机身；payload_index 由 queuePayloadCommand 统一注入。
        locked: false,
        latitude: Number(mapTarget.latitude.toFixed(6)),
        longitude: Number(mapTarget.longitude.toFixed(6)),
        height: Number(mapTarget.height.toFixed(1))
      })
      if (!succeeded) {
        throw new Error(error.value || 'Look At 指令下发失败')
      }
      showCameraActionTip('Look At 原生指令执行成功')
    }
  } catch (reason) {
    const restored = mode === 'flyto' && pointFlightIdentityPending && targetDockSn === dockSn.value
      ? await loadPointFlightState(targetDockSn)
      : false
    if (!restored) {
      const message = reason instanceof Error ? reason.message : `${action}失败`
      if (mode === 'flyto' && pointFlightIdentityPending && pointFlightFailureIsUncertain(reason)) {
        markPointFlightUncertain(
          'flyto',
          `${message}；指令结果尚未确认，已锁定手动控制并持续同步`
        )
      } else if (mode === 'flyto' && pointFlightIdentityPending) {
        markPointFlightSubmissionFailed('flyto', message)
      } else {
        error.value = message
      }
    }
  } finally {
    mapTargetPending.value = false
  }
}

async function oneKeyTakeoff() {
  const targetDockSn = selectedDock.value?.device_sn
  if (dockSelectionPending.value || operationPanelState.value !== 'ground' ||
      takeoffPending.value || telemetry.pointFlightActive || !targetDockSn || !selectedAircraftOnline.value) return
  if (
    takeoffSettings.targetAgl < 2 || takeoffSettings.targetAgl > 1500 ||
    takeoffSettings.maxSpeed < 1 || takeoffSettings.maxSpeed > 15
  ) {
    error.value = '目标相对高度须为 2–1500 m'
    return
  }
  const hasAircraftPosition =
    Number.isFinite(telemetry.latitude) &&
    Number.isFinite(telemetry.longitude) &&
    telemetry.latitude >= -90 &&
    telemetry.latitude <= 90 &&
    telemetry.longitude >= -180 &&
    telemetry.longitude <= 180 &&
    !(telemetry.latitude === 0 && telemetry.longitude === 0)
  if (!hasAircraftPosition) {
    error.value = '尚未收到有效的飞机位置信息，请等待经纬度上报后重试'
    return
  }
  const summary = [
    `目标相对高度：${takeoffSettings.targetAgl} m`,
    ...(telemetry.satellites > 0
      ? [`GPS：${telemetry.satellites} 星 · 档位 Q${telemetry.gpsQuality}`]
      : []),
    `目标点：${telemetry.latitude.toFixed(6)}, ${telemetry.longitude.toFixed(6)}`
  ].join('\n')
  if (!window.confirm(`确认执行一键起飞？\n${summary}`)) return

  const targetLongitude = telemetry.longitude
  const targetLatitude = telemetry.latitude
  takeoffPending.value = true
  error.value = ''
  try {
    if (!hasFlightAuthority.value && !await grabFlightAuthority(targetDockSn)) return
    if (!controlTargetValid(targetDockSn)) throw new Error('设备或飞行控制权状态已变化，已取消一键起飞')
    beginPointFlightSubmission('takeoff')
    await post(`/control/api/v1/devices/${targetDockSn}/jobs/takeoff-to-point`, {
      target_longitude: targetLongitude,
      target_latitude: targetLatitude,
      target_height: Number(takeoffSettings.targetAgl.toFixed(1)),
      max_speed: takeoffSettings.maxSpeed
    }, CONTROL_REQUEST_OPTIONS)
    if (!await loadPointFlightState(targetDockSn)) {
      markPointFlightUncertain(
        'takeoff',
        '一键起飞指令已受理，但暂未取得任务 ID；已锁定手动控制并持续同步任务状态'
      )
    }
    showCameraActionTip('一键起飞指令已受理，等待设备进度事件')
  } catch (reason) {
    const restored = pointFlightIdentityPending && targetDockSn === dockSn.value
      ? await loadPointFlightState(targetDockSn)
      : false
    if (!restored) {
      const message = reason instanceof Error ? reason.message : '一键起飞指令失败'
      if (pointFlightIdentityPending && pointFlightFailureIsUncertain(reason)) {
        markPointFlightUncertain(
          'takeoff',
          `${message}；指令结果尚未确认，已锁定手动控制并持续同步`
        )
      } else if (pointFlightIdentityPending) {
        markPointFlightSubmissionFailed('takeoff', message)
      } else {
        error.value = message
      }
    }
  } finally {
    takeoffPending.value = false
  }
}

async function stopPointFlight() {
  const targetDockSn = selectedDock.value?.device_sn
  if (dockSelectionPending.value || !targetDockSn || flyToStopPending.value ||
      !selectedAircraftOnline.value ||
      pointFlightProgress.value?.kind !== 'flyto' || !telemetry.pointFlightActive ||
      !window.confirm('确认结束当前 FlyTo 飞向目标点任务？')) return
  flyToStopPending.value = true
  try {
    if (!hasFlightAuthority.value && !await grabFlightAuthority(targetDockSn)) {
      flyToStopPending.value = false
      return
    }
    if (!controlTargetValid(targetDockSn)) throw new Error('设备或飞行控制权状态已变化，已取消结束 FlyTo')
    await del(`/control/api/v1/devices/${targetDockSn}/jobs/fly-to-point`, undefined, CONTROL_REQUEST_OPTIONS)
    if (!await loadPointFlightState(targetDockSn)) {
      pointFlightProgress.value = { ...pointFlightProgress.value, status: 'cancel_requested' }
    }
    showCameraActionTip('取消指令已受理，等待设备结果事件')
  } catch (reason) {
    flyToStopPending.value = false
    if (!await loadPointFlightState(targetDockSn)) {
      error.value = reason instanceof Error ? reason.message : '结束 FlyTo 任务失败'
    }
  }
}

/** 检查键盘按键是否按下（响应式） */
function keyIsDown(code: string): boolean {
  return pressed.has(code) || blockedPressed.has(code)
}

/** 飞行模式码 → 中文标签 */
function modeLabel(code: number): string {
  const map: Record<number, string> = {
    0: '待机', 1: '起飞准备', 2: '起飞准备完毕', 3: '手动飞行',
    4: '自动起飞', 5: '航线飞行', 6: '全景拍照', 7: '智能跟随',
    8: 'ADS-B 避让', 9: '自动返航', 10: '自动降落',
    11: '强制降落', 12: '三桨叶降落', 13: '升级中',
    14: '未连接', 15: 'APAS', 16: '虚拟摇杆状态',
    17: '指令飞行', 18: 'RTK 固定', 19: '机场评估',
    20: '兴趣点', 37: '指点飞行', 39: 'KML 航线'
  }
  return map[code] ?? '未连接'
}

/**
 * 当前档位展示：gear_level 由设备 OSD 上报，Cloud API 不支持下发切换，
 * 因此仅做只读展示。-1 表示尚未收到数据。
 * 如需中文档位名，可在此按产品定义补充映射。
 */
function gearLabel(level: number): string {
  return level < 0 ? '--' : String(level)
}

/**
 * 失联动作展示：rc_lost_action 由飞行器 OSD 上报，仅只读展示。
 * 0 悬停 / 1 降落 / 2 返航，-1 表示尚未收到数据。
 */
function rcLostActionLabel(action: number): string {
  const map: Record<number, string> = { 0: '悬停', 1: '降落', 2: '返航' }
  return map[action] ?? '--'
}

/** 秒数 → mm:ss 格式，-1 表示无数据 */
function fmtDuration(seconds: number): string {
  if (seconds < 0) return '--:--'
  const m = Math.floor(seconds / 60)
  const s = Math.floor(seconds % 60)
  return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`
}

function fmtTaskDistance(meters: number): string {
  if (meters < 0) return '--'
  return meters >= 1000 ? `${(meters / 1000).toFixed(1)} km` : `${meters.toFixed(0)} m`
}

/** 障碍距离显示，-1 → '--' */
function fmtObstacle(distance: number): string {
  if (distance < 0) return '--'
  return distance.toFixed(1)
}

/** 风速显示 */
function fmtWind(speed: number): string {
  if (speed < 0) return '--'
  return speed.toFixed(1)
}

function leaveDrc(reason = 'unspecified'): Promise<void> {
  if (drcLeavePromise) return drcLeavePromise
  addInteractionLog({
    transport: 'SYSTEM',
    direction: 'INFO',
    summary: `DRC 退出触发：${reason}`,
    payload: { reason, state: state.value, drcConnectionState: drcConnectionState.value }
  })
  const tracked = performLeaveDrc().finally(() => {
    if (drcLeavePromise === tracked) drcLeavePromise = undefined
  })
  drcLeavePromise = tracked
  return tracked
}

async function performLeaveDrc() {
  clearDrcResume()
  window.clearInterval(heartbeatTimer)
  window.clearInterval(heartbeatHealthTimer)
  window.clearInterval(controlTimer)
  window.clearTimeout(emergencyStopTimer)
  emergencyStopTimer = 0
  emergencyRequestId = ''
  emergencyStopPending.value = false
  window.clearTimeout(drcLandingTimer)
  drcLandingTimer = 0
  drcLandingRequestId = ''
  drcLandingGatewaySn = ''
  drcLandingPending.value = ''
  const exitingBroker = broker
  const pendingExit = pendingDrcExit.value
  const exitClientId = exitingBroker?.client_id ?? pendingExit?.clientId ?? ''
  const exitingDockSn = exitingBroker ? dockSn.value : (pendingExit?.dockSn ?? '')
  const exitingClient = client
  const exitingTopic = drcPublishTopic
  invalidateDrcMqttGeneration(exitingClient)
  state.value = exitClientId && exitingDockSn ? 'connecting' : 'idle'
  drcConnectionState.value = 'offline'
  drcStatusMessage.value = '正在归零控制量并退出 DRC…'
  releaseKeys()
  await publishFinalZeroControl(exitingClient, exitingTopic)
  client = undefined
  broker = undefined
  acl = undefined
  drcPublishTopic = ''
  drcAircraftSn = ''
  await closeMqttClientGracefully(exitingClient)
  drcMqttConnected.value = false
  drcStatusMessage.value = '本地 DRC 通道已关闭，等待设备退出确认'

  let exitConfirmed = true
  if (exitClientId && exitingDockSn) {
    await post(`/control/api/v1/workspaces/${cockpitWorkspaceId}/drc/exit`, {
      client_id: exitClientId, dock_sn: exitingDockSn, expire_sec: 3600,
      device_info: { osd_frequency: 10, hsi_frequency: 1 }
    }, CONTROL_REQUEST_OPTIONS).catch((reason) => {
      // client key expired in Redis → session already cleaned up → treat as confirmed
      if (isDrcOwnerConflict(reason)) return
      exitConfirmed = false
      rememberPendingDrcExit(exitClientId, exitingDockSn)
      error.value = reason instanceof Error ? reason.message : '设备未确认退出 DRC，本地控制通道已关闭'
    })
    if (exitConfirmed && pendingDrcExit.value?.clientId === exitClientId &&
        pendingDrcExit.value.dockSn === exitingDockSn) {
      pendingDrcExit.value = undefined
    }
  }
  state.value = 'idle'
  drcConnectionState.value = exitConfirmed ? 'idle' : 'degraded'
  drcStatusMessage.value = exitConfirmed
    ? '已退出指令飞行控制模式'
    : '本地控制已关闭，但设备未确认退出 DRC'
  lastHeartbeatAckAt.value = 0
  drcConnectedAt = 0
  heartbeatSeq = 0
  lastHeartbeatAckSeq = 0
  nativeHeartbeatAckReceived = false
  lastControlVector = ''
  lastControlPublishAt = 0
  zeroControlPending = false
  if (exitConfirmed) {
    drcControlRejected.value = false
    drcControlFailure.value = ''
  }
  resetObstacleTelemetry()
}

async function requestLeaveDrc() {
  if (!active.value || !window.confirm('确认退出 DRC 指令飞行控制模式？退出后键盘和虚拟摇杆将停止。')) return
  await leaveDrc('operator-request').catch((reason) => {
    error.value = reason instanceof Error ? reason.message : '退出 DRC 失败'
  })
}

async function reconnectDrc() {
  if (!active.value || drcReconnectPending.value || drcEnterPending.value) return
  drcReconnectPending.value = true
  drcEnterCancelled = true
  releaseKeys()
  try {
    await leaveDrc('reconnect')
    if (!hasFlightAuthority.value) {
      drcStatusMessage.value = 'DRC 已安全退出，请重新抢夺飞行控制权'
      return
    }
    await enter()
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '重连 DRC 失败'
  } finally {
    drcReconnectPending.value = false
  }
}

function handleDrcAction() {
  if (pendingDrcExit.value) {
    void leaveDrc('pending-exit-retry')
    return
  }
  if (active.value && ['degraded', 'offline'].includes(drcConnectionState.value)) {
    void reconnectDrc()
    return
  }
  if (active.value) {
    void requestLeaveDrc()
    return
  }
  void enter()
}

function exit(): Promise<void> {
  if (!componentExitPromise) componentExitPromise = performExit()
  return componentExitPromise
}

async function performExit() {
  componentExiting = true
  drcEnterCancelled = true
  resetPointFlightTracking()
  window.removeEventListener('keydown', handleKey)
  window.removeEventListener('keyup', handleKey)
  window.removeEventListener('blur', releaseKeys)
  document.removeEventListener('focusin', handleControlFocusIn)
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  releaseKeys()
  if (drcEnterPromise) await drcEnterPromise.catch(() => false)
  await leaveDrc('component-unmount')
  // Device switching only detaches WHEP, but a real cockpit exit must release
  // every publisher started by this component so streams cannot become
  // unreachable after the in-memory ownership map is destroyed.
  window.clearTimeout(videoReconnectTimer)
  videoReconnectTimer = 0
  const operation = ++videoOperationGeneration
  videoStartTask = undefined
  await resetVideoSession(false, () => operation === videoOperationGeneration)
  await stopStartedVideoPublishers()
}

function toggleFullscreen() {
  if (document.fullscreenElement) void document.exitFullscreen()
  else void document.querySelector('.cockpit-pro')?.requestFullscreen()
}
</script>

<template>
  <div :class="['cockpit-pro', `layout-${layoutMode}`, { 'rail-collapsed': railCollapsed }]">
    <!-- ── 顶栏 ──────────────────────────────────────────── -->
    <header class="cockpit-bar">
      <div class="bar-left">
        <span :class="['mode-badge', { active }]">
          <i></i>{{ active ? '手动飞行 · DRC' : '虚拟座舱' }}
        </span>
        <button
          class="control-state-btn authority"
          :class="{ active: hasFlightAuthority }"
          :disabled="dockSelectionPending || !selectedDock || !selectedAircraftOnline || flightAuthorityPending || hasFlightAuthority"
          :title="hasFlightAuthority ? '第 1 步已完成：平台已取得飞行控制权' : '第 1 步：向设备发送 flight_authority_grab'"
          @click="grabFlightAuthority()">
          {{ flightAuthorityLabel }}
        </button>
        <button
          class="control-state-btn drc"
          :class="[`state-${drcConnectionState}`, { active }]"
          :disabled="drcActionDisabled"
          :title="drcBlockedReason"
          @click="handleDrcAction">
          {{ drcActionLabel }}
        </button>
        <span v-if="active" class="latency-badge" :class="{ warn: latency > 300 || drcConnectionState !== 'online' }">
          ♥ {{ latency }} ms
        </span>
        <span v-if="operationPanelState !== 'ground' && drcBlockedReason" class="control-state-reason" :class="{ ready: drcControlsReady }" :title="drcStatusMessage">
          {{ drcBlockedReason }}
        </span>
      </div>
      <div class="bar-right">
        <div class="layout-switch" role="group" aria-label="视图布局">
          <span>视图布局</span>
          <button
            :class="{ active: layoutMode === 'video' }"
            :aria-pressed="layoutMode === 'video'"
            title="扩大视频与飞行操控区域"
            @click="layoutMode = 'video'">视频优先</button>
          <button
            :class="{ active: layoutMode === 'map' }"
            :aria-pressed="layoutMode === 'map'"
            title="扩大地图区域"
            @click="layoutMode = 'map'">地图优先</button>
        </div>
        <button
          class="bar-btn hms-trigger"
          :class="[`level-${hmsHighestLevel}`, { active: hmsAlarms.length > 0 }]"
          :aria-label="`健康告警，共 ${hmsAlarms.length} 条未读`"
          @click="hmsOpen = true">
          ⚠ 告警 <span>{{ hmsLoading ? '…' : hmsAlarms.length }}</span>
        </button>
        <button class="bar-btn shortcut-help-trigger" title="快捷键说明 [/ 或 ?]" @click="shortcutHelpOpen = true">? 快捷键</button>
        <button class="bar-btn log-trigger" @click="logOpen = true">
          ◫ 日志 <span>{{ interactionLogs.length }}</span>
        </button>
        <button class="bar-btn" @click="toggleFullscreen">⛶ 全屏</button>
        <RouterLink to="/" class="bar-btn">← 退出</RouterLink>
      </div>
    </header>

    <div v-if="shortcutHelpOpen" class="shortcut-help-backdrop" @click.self="shortcutHelpOpen = false">
      <section class="shortcut-help-dialog" role="dialog" aria-modal="true" aria-label="快捷键说明">
        <header>
          <div><small>KEYBOARD CONTROL</small><h2>快捷键说明</h2></div>
          <button type="button" aria-label="关闭快捷键说明" @click="shortcutHelpOpen = false">×</button>
        </header>
        <figure class="shortcut-keyboard-image">
          <img src="/images/cockpit-keyboard-shortcuts.png" alt="虚拟座舱键盘快捷键布局说明">
        </figure>
        <footer>
          <span>方向键控制前需开启负载控制</span>
          <span>WSADQEZC 控制前需取得飞行控制权并进入 DRC</span>
        </footer>
      </section>
    </div>

    <div v-if="waylineTaskOpen" class="wayline-task-backdrop" @click.self="closeWaylineTask">
      <section class="wayline-task-dialog" role="dialog" aria-modal="true" aria-label="选择航线任务">
        <header class="wayline-task-head">
          <div>
            <small>WAYLINE FLIGHT</small>
            <h2>选择航线任务</h2>
            <p>由 {{ selectedDock ? dockModelName(selectedDock) : '当前设备' }}（{{ waylineTaskDockSn }}）立即执行</p>
          </div>
          <button type="button" aria-label="关闭航线任务弹窗" :disabled="waylineTaskSubmitting" @click="closeWaylineTask">×</button>
        </header>

        <form class="wayline-task-form" @submit.prevent="startWaylineTask">
          <div class="wayline-task-route-field">
            <label for="cockpit-wayline-select">已有航线</label>
            <div>
              <select id="cockpit-wayline-select" v-model="selectedWaylineId" :disabled="waylineTaskLoading || waylineTaskSubmitting || !cockpitWaylines.length" required>
                <option value="" disabled>{{ waylineTaskLoading ? '正在加载航线…' : '请选择航线' }}</option>
                <option v-for="wayline in cockpitWaylines" :key="wayline.id" :value="wayline.id">{{ wayline.name }}</option>
              </select>
              <button type="button" :disabled="waylineTaskLoading || waylineTaskSubmitting" @click="loadCockpitWaylines">
                {{ waylineTaskLoading ? '加载中…' : '刷新' }}
              </button>
            </div>
          </div>

          <article v-if="selectedCockpitWayline" class="wayline-task-route-card">
            <span class="wayline-route-icon" aria-hidden="true">⌁</span>
            <div>
              <strong>{{ selectedCockpitWayline.name }}</strong>
              <p>
                <span>飞机 {{ selectedCockpitWayline.drone_model_key || '未标注' }}</span>
                <span>负载 {{ selectedCockpitWayline.payload_model_keys?.join(', ') || '未标注' }}</span>
              </p>
              <small>{{ formatWaylineUpdateTime(selectedCockpitWayline.update_time) }}</small>
            </div>
          </article>
          <div v-else-if="waylineTaskLoading" class="wayline-task-empty">正在读取航线库…</div>
          <div v-else class="wayline-task-empty">
            <strong>航线库为空</strong>
            <span>请先在“航线任务”页面上传 KMZ 航线。</span>
          </div>

          <div class="wayline-task-grid">
            <label>返航高度（米）<input v-model.number="waylineTaskForm.rthAltitude" type="number" min="20" max="500" required /></label>
            <label>最低电量（%）<input v-model.number="waylineTaskForm.minBatteryCapacity" type="number" min="15" max="100" required /></label>
            <label>避障开关<select v-model.number="waylineTaskForm.barrierSwitchState"><option :value="1">开启避障</option><option :value="0">关闭避障</option></select></label>
            <label>起飞高度（米）<input v-model.number="waylineTaskForm.takeoffAltitude" type="number" min="1" max="1500" required /></label>
            <label>去首航点速度（m/s）<input v-model.number="waylineTaskForm.firstWaypointSpeed" type="number" min="1" max="25" required /></label>
            <label>返航速度（m/s）<input v-model.number="waylineTaskForm.returnSpeed" type="number" min="1" max="25" required /></label>
          </div>

          <div class="wayline-task-defaults">
            <span>立即执行</span><span>失联返航</span><span>GPS 航线</span><span>落地上传媒体</span>
          </div>
          <label class="wayline-task-confirm">
            <input v-model="waylineTaskConfirmed" type="checkbox" :disabled="waylineTaskSubmitting" />
            <span>我已确认航线、返航高度、空域、天气、现场人员和应急接管条件。</span>
          </label>
          <p v-if="active" class="wayline-task-drc-note">开始执行前将先归零控制量并安全退出当前 DRC 会话。</p>
          <p v-if="waylineTaskBlockedReason" class="wayline-task-blocked">{{ waylineTaskBlockedReason }}</p>
          <p v-if="waylineTaskError" class="wayline-task-error" role="alert">{{ waylineTaskError }}</p>

          <footer>
            <button type="button" class="wayline-task-cancel" :disabled="waylineTaskSubmitting" @click="closeWaylineTask">取消</button>
            <button
              type="submit"
              class="wayline-task-submit"
              :disabled="waylineTaskLoading || waylineTaskSubmitting || !!waylineTaskBlockedReason"
              :title="waylineTaskBlockedReason">
              {{ waylineTaskSubmitting ? '正在下发…' : active ? '退出 DRC 并开始执行' : '开始执行' }}
            </button>
          </footer>
        </form>
      </section>
    </div>

    <div v-if="hmsOpen" class="hms-backdrop" @click.self="hmsOpen = false">
      <section class="hms-dialog" role="dialog" aria-modal="true" aria-label="健康告警">
        <header class="hms-head">
          <div>
            <small>HEALTH MANAGEMENT SYSTEM</small>
            <h2>健康告警</h2>
            <p>当前设备及其飞行器的未读告警</p>
          </div>
          <div class="hms-head-actions">
            <button type="button" :disabled="hmsLoading" @click="loadHmsAlarms">刷新</button>
            <button
              type="button"
              :disabled="!hmsAlarms.length || hmsMarkingRead"
              @click="markHmsRead">
              {{ hmsMarkingRead ? '处理中…' : '全部标记已读' }}
            </button>
            <button class="hms-close" type="button" aria-label="关闭健康告警" @click="hmsOpen = false">×</button>
          </div>
        </header>
        <div v-if="hmsError" class="hms-error" role="alert">{{ hmsError }}</div>
        <div v-if="hmsLoading && !hmsAlarms.length" class="hms-empty">正在加载健康告警…</div>
        <div v-else-if="!hmsAlarms.length" class="hms-empty">
          <strong>暂无未读健康告警</strong>
          <span>设备上报 method=hms 后，新告警会在这里实时显示。</span>
        </div>
        <div v-else class="hms-list">
          <article v-for="alarm in hmsAlarms" :key="hmsIdentity(alarm)" :class="['hms-row', `level-${alarm.level}`]">
            <span class="hms-level">{{ hmsLevelLabel(alarm.level) }}</span>
            <div class="hms-detail">
              <strong>{{ hmsMessage(alarm) }}</strong>
              <p>
                <span>{{ alarm.sn || '未知设备' }}</span>
                <span>{{ hmsModuleLabel(alarm.module) }}</span>
                <time>{{ formatHmsTime(hmsCreatedAt(alarm)) }}</time>
              </p>
              <code>{{ alarm.key }}</code>
            </div>
          </article>
        </div>
      </section>
    </div>

    <Transition name="cockpit-toast">
      <div v-if="error" class="cockpit-error-toast" role="alert" aria-live="assertive">
        <span class="cockpit-error-icon" aria-hidden="true">!</span>
        <div>
          <strong>操作未完成</strong>
          <p>{{ error }}</p>
        </div>
        <button type="button" aria-label="关闭错误提示" @click="dismissError">×</button>
      </div>
    </Transition>

    <Transition name="cockpit-toast">
      <button v-if="waylineTaskNotice" type="button" class="wayline-task-success" aria-live="polite" @click="waylineTaskNotice = ''">
        <span aria-hidden="true">✓</span>
        <div><strong>航线任务已下发</strong><p>{{ waylineTaskNotice }}</p></div>
      </button>
    </Transition>

    <Transition name="cockpit-toast">
      <button
        v-if="latestHms"
        type="button"
        :class="['hms-live-notice', `level-${latestHms.level}`]"
        aria-live="assertive"
        @click="hmsOpen = true; latestHms = undefined">
        <span>⚠</span>
        <div>
          <small>{{ hmsLevelLabel(latestHms.level) }} · {{ latestHms.sn }}</small>
          <strong>{{ hmsMessage(latestHms) }}</strong>
        </div>
      </button>
    </Transition>

    <!-- ── 左侧边栏 ──────────────────────────────────────── -->
    <!-- 设备栏折叠把手：骑在 rail 右边缘，点击收起/展开 -->
    <button class="rail-toggle" :title="railCollapsed ? '展开设备栏' : '收起设备栏'"
      :aria-expanded="!railCollapsed" @click="railCollapsed = !railCollapsed">
      <span class="rail-toggle-ico">‹</span>
    </button>
    <aside class="session-rail">
      <div class="rail-head"><strong>设备列表</strong><span class="rail-cnt">{{ docks.length }}</span></div>
      <div class="rail-devices">
        <article v-for="dock in docks" :key="dock.device_sn"
          :class="['dev-card', { selected: dock.device_sn === dockSn }]"
          :aria-current="dock.device_sn === dockSn ? 'true' : undefined">
          <button
            class="dev-card-select"
            :disabled="dockSelectionPending || state !== 'idle' || drcEnterPending || flightAuthorityPending ||
              payloadAuthorityPending || mapTargetPending ||
              cameraCommandPending !== '' || targetDetectionPending || takeoffPending ||
              returnHomePending || emergencyStopPending || !!drcLandingPending || flyToStopPending ||
              videoState === 'connecting' || videoRetrying || qualitySwitching"
            @click="selectDock(dock.device_sn)">
            <span class="dev-card-head">
              <span class="dev-card-title">
                <span class="dev-role-status rc-online">
                  <svg viewBox="0 0 24 24" aria-hidden="true">
                    <path d="M7 8h10c2 0 3 1.2 3.5 3.2l1 4.2c.4 1.7-1.5 2.9-2.8 1.8l-2.5-2.1H7.8l-2.5 2.1c-1.3 1.1-3.2-.1-2.8-1.8l1-4.2C4 9.2 5 8 7 8Z" />
                    <path d="M7 11v3M5.5 12.5h3M16.5 11.5h.1M18.5 13.5h.1" />
                  </svg>
                  <small>遥控器</small><strong>在线</strong>
                </span>
                <span class="dev-status-sep"></span>
                <span :class="['dev-role-status', 'drone-status', dockStatusTone(dock)]">
                  <svg viewBox="0 0 24 24" aria-hidden="true">
                    <path d="M9 10h6l1.5 4h-9L9 10ZM12 10V7M6 8l3 2M18 8l-3 2M5 7h2M17 7h2M6 14l-2 3M18 14l2 3M3 18h3M18 18h3" />
                  </svg>
                  <small>无人机</small><strong>{{ dockStatusLabel(dock) }}</strong>
                </span>
              </span>
            </span>
            <span class="dev-card-body">
              <span class="dev-identity">
                <strong>{{ dockModelName(dock) }}</strong>
                <span></span>
                <em>{{ dockCallSign(dock) }}</em>
              </span>
              <span class="dev-vitals">
                <span class="dev-power-group">
                  <span class="dev-power-item" :aria-label="`遥控器电量 ${dockCardStats(dock).rcBattery < 0 ? '未知' : `${dockCardStats(dock).rcBattery.toFixed(0)}%`}`">
                    <small>RC</small>
                    <span class="dev-battery-icon"><i :style="{ width: `${Math.max(0, Math.min(100, dockCardStats(dock).rcBattery))}%` }"></i></span>
                    <strong>{{ dockCardStats(dock).rcBattery < 0 ? '--' : `${dockCardStats(dock).rcBattery.toFixed(0)}%` }}</strong>
                  </span>
                  <span class="dev-power-item" :aria-label="`无人机电量 ${dockCardStats(dock).battery < 0 ? '未知' : `${dockCardStats(dock).battery.toFixed(0)}%`}`">
                    <small>UAV</small>
                    <span class="dev-battery-icon"><i :style="{ width: `${Math.max(0, Math.min(100, dockCardStats(dock).battery))}%` }"></i></span>
                    <strong>{{ dockCardStats(dock).battery < 0 ? '--' : `${dockCardStats(dock).battery.toFixed(0)}%` }}</strong>
                  </span>
                </span>
                <b></b>
                <span>{{ fmtDuration(dockCardStats(dock).remainFlightTime) }}</span>
                <b></b>
                <span class="dev-signal-group" :aria-label="`遥控器信号 ${dockCardStats(dock).rcSignal}`">
                  <span class="dev-signal-unit"><small>RC</small><span class="dev-signal">
                    <i v-for="level in 4" :key="`rc-${level}`" :class="{ on: level <= signalBars(dockCardStats(dock).rcSignal) }"></i>
                  </span></span>
                </span>
              </span>
            </span>
          </button>
          <div class="dev-actions">
            <button
              class="dev-action-wayline"
              title="选择航线并立即执行"
              :disabled="dockSelectionPending || dock.device_sn !== dockSn || !isAircraftOnlineForDock(dock)"
              @click.stop="openWaylineTask(dock)">
              <span class="dev-action-icon" aria-hidden="true">⌁</span>
              <span>航线任务</span>
            </button>
            <button
              class="dev-action-locate"
              title="定位飞行器"
              :disabled="dock.device_sn !== dockSn || !isAircraftOnlineForDock(dock)"
              @click.stop="centerOnDrone">
              <span class="dev-action-icon" aria-hidden="true">⌖</span>
              <span>定位</span>
            </button>
            <button
              class="dev-action-stop"
              title="紧急制动"
              :disabled="dockSelectionPending || dock.device_sn !== dockSn || !active"
              @click.stop="emergencyStop">
              <span class="dev-action-icon stop" aria-hidden="true">■</span>
              <span>刹停</span>
            </button>
          </div>
        </article>
        <div v-if="!docks.length" class="rail-empty">暂无在线设备<br><small>请检查设备连接</small></div>
      </div>
    </aside>

    <!-- ── 中心：高德卫星地图 ─────────────────────────────── -->
    <section class="cockpit-map-section">
      <!-- AMap 渲染容器 -->
      <div ref="mapContainer" class="cx-canvas"></div>

      <!-- 地图工具组：右上角浮动控件（样式切换在上、定位在下） -->
      <div class="map-tools">
        <button class="map-tool" :class="{ active: mapSatellite }" :title="mapSatellite ? '切换为地图模式' : '切换为卫星模式'" @click="toggleMapSat">
          {{ mapSatellite ? '🛰' : '🗺' }}
        </button>
        <button class="map-tool" title="定位飞行器" @click="centerOnDrone">⊕</button>
        <button
          class="map-tool map-target-tool"
          :class="{ active: mapTargetMode === 'flyto', executing: pointFlightMapActive }"
          :title="pointFlightMapActive ? '指点飞行正在执行；点击查看任务目标' : '在地图上选择指点飞行目标'"
          @click="selectMapTarget('flyto')">航</button>
        <button
          class="map-tool map-target-tool"
          :class="{ active: mapTargetMode === 'lookAt' }"
          title="在地图上选择云台 Look At 目标"
          @click="selectMapTarget('lookAt')">瞄</button>
      </div>

      <div v-if="pointFlightMapActive" class="point-flight-map-status" :class="{ disconnected: !selectedAircraftOnline }" role="status">
        <i aria-hidden="true"></i>
        <div>
          <strong>{{ selectedAircraftOnline ? '指点飞行执行中' : '指点飞行状态待确认' }}</strong>
          <span>
            {{ selectedAircraftOnline ? pointFlightStatusLabel(pointFlightProgress?.status) : '飞机连接已中断' }}
            · 距离 {{ fmtTaskDistance(pointFlightProgress?.remainingDistance ?? telemetry.taskRemainingDistance) }}
            · 时间 {{ fmtDuration(pointFlightProgress?.remainingTime ?? telemetry.taskRemainingTime) }}
          </span>
        </div>
        <button v-if="pointFlightTargetValid" type="button" @click="locatePointFlightTarget">定位目标</button>
        <button type="button" class="stop" :disabled="!selectedAircraftOnline || flyToStopPending" @click="stopPointFlight">
          {{ flyToStopPending ? '结束中…' : '结束任务' }}
        </button>
      </div>

      <!-- 未配置 Key 时的备用指南针视图 -->
      <div v-if="!hasMapKey" class="cx-fallback">
        <div class="fb-compass" :style="{ transform: `rotate(${-telemetry.heading}deg)` }">
          <span class="fb-n">N</span>
          <span class="fb-s">S</span>
          <span class="fb-w">W</span>
          <span class="fb-e">E</span>
        </div>
        <div class="fb-arrow" :style="{ transform: `rotate(${telemetry.heading}deg)` }">▲</div>
        <p class="fb-tip">未配置高德地图 Key（runtime-config.js）</p>
      </div>

      <!-- 飞行数据叠加层（激活时显示） -->
      <div v-if="active" class="cx-hud">
        <div class="hud-chip"><span>ALT</span><strong>{{ telemetry.altitude.toFixed(1) }} m</strong></div>
        <div class="hud-chip"><span>AGL</span><strong>{{ telemetry.height.toFixed(1) }} m</strong></div>
        <div class="hud-chip"><span>SPD</span><strong>{{ telemetry.speed.toFixed(1) }} m/s</strong></div>
        <div class="hud-chip"><span>HDG</span><strong>{{ telemetry.heading.toFixed(0) }}°</strong></div>
        <div class="hud-chip" :class="{ 'bat-low': telemetry.battery < 25 }"><span>BAT</span><strong>{{ telemetry.battery.toFixed(0) }}%</strong></div>
        <div class="hud-chip"><span>GPS</span><strong>{{ telemetry.satellites }}</strong></div>
      </div>

      <!-- 无人机与测距目标坐标 -->
      <div
        v-if="telemetry.latitude !== 0 || telemetry.longitude !== 0 || remoteControllerCoordinatesValid || telemetry.measureReported"
        class="cx-location-stack">
        <div v-if="telemetry.latitude !== 0 || telemetry.longitude !== 0" class="cx-coords">
          <strong>无人机</strong>
          <span>{{ formatCoordinate(telemetry.latitude) }},&thinsp;{{ formatCoordinate(telemetry.longitude) }}</span>
        </div>
        <div v-if="remoteControllerCoordinatesValid" class="cx-coords is-remote-controller">
          <strong>遥控器</strong>
          <span>{{ formatCoordinate(telemetry.rcLatitude) }},&thinsp;{{ formatCoordinate(telemetry.rcLongitude) }}</span>
        </div>
        <div v-if="telemetry.measureReported" class="cx-measure-target">
          <span>
            <strong>目标</strong>
            {{ telemetry.measureLatitude && telemetry.measureLongitude
              ? `${telemetry.measureLatitude.toFixed(6)}, ${telemetry.measureLongitude.toFixed(6)}`
              : '坐标未上报' }}
          </span>
          <span><strong>绝对高度</strong>{{ telemetry.measureAltitude ? `${telemetry.measureAltitude.toFixed(1)} m` : '--' }}</span>
          <button
            v-if="measureCoordinatesValid"
            type="button"
            @click="locateMeasuredTarget">定位测距目标</button>
        </div>
      </div>

      <form v-if="mapTargetPanelOpen && mapTargetMode" class="map-target-panel" @submit.prevent="submitMapTarget">
        <header>
          <div>
            <small>{{ mapTargetMode === 'flyto' ? 'POINT FLIGHT' : 'CAMERA LOOK AT' }}</small>
            <strong>{{ mapTargetMode === 'flyto' ? '指点飞行目标' : '云台目标点' }}</strong>
          </div>
          <button type="button" title="清除目标点" @click="clearMapTarget">×</button>
        </header>
        <p>点击地图选点，或输入 WGS84 坐标。{{ mapTargetMode === 'lookAt' && pointFlightMapActive ? '当前指点飞行目标会保持显示。' : '' }}</p>
        <div class="map-target-coordinates">
          <label>经度<input v-model.number="mapTarget.longitude" type="number" min="-180" max="180" step="0.000001" @change="updateMapTargetMarker" /></label>
          <label>纬度<input v-model.number="mapTarget.latitude" type="number" min="-90" max="90" step="0.000001" @change="updateMapTargetMarker" /></label>
        </div>
        <div class="map-target-params">
          <label>{{ mapTargetMode === 'flyto' ? '目标相对高度' : '目标椭球高' }}
            <span><input v-model.number="mapTarget.height" type="number" min="2" max="10000" step="0.1" />m</span>
          </label>
          <label v-if="mapTargetMode === 'flyto'">最大速度
            <span><input v-model.number="mapTarget.maxSpeed" type="number" min="1" max="15" step="1" />m/s</span>
          </label>
        </div>
        <div class="map-target-actions">
          <span :class="{ valid: mapTargetValid }">{{ mapTargetValid ? '目标点有效' : '请选择有效目标点' }}</span>
          <button type="submit" :disabled="dockSelectionPending || !mapTargetValid || mapTargetPending || !selectedDock || !selectedAircraftOnline ||
            (mapTargetMode === 'flyto' && operationPanelState !== 'airborne') ||
            (mapTargetMode === 'flyto' && telemetry.pointFlightActive) ||
            (mapTargetMode === 'lookAt' && !selectedSource)">
            {{ mapTargetPending ? '发送中…' : mapTargetMode === 'flyto' ? '启动指点飞行' : '执行 Look At' }}
          </button>
        </div>
      </form>
    </section>

    <!-- ── 右侧面板：视频 + 遥测 + 操控 ─────────────────── -->
    <section
      class="flight-view"
      :class="{ 'has-payload-shortcuts': hasPayloadAuthority }">
      <!-- 镜头选择 + 视频源 -->
      <div class="lens-bar">
        <button
          class="payload-authority-switch"
          :class="{ active: hasPayloadAuthority }"
          :disabled="dockSelectionPending || !selectedSource || payloadAuthorityPending || hasPayloadAuthority"
          :aria-pressed="hasPayloadAuthority"
          :title="hasPayloadAuthority ? '已取得当前负载控制权' : '抢夺当前负载控制权'"
          @click="grabPayloadAuthority">
          <i></i>
          {{ payloadAuthorityPending ? '抢夺中…' : '负载控制' }}
        </button>
        <button
          class="target-detect-switch"
          :class="{ active: targetDetectionEnabled }"
          :disabled="dockSelectionPending || !selectedSource || targetDetectionPending"
          :aria-pressed="targetDetectionEnabled"
          :title="targetDetectionEnabled ? '关闭目标识别' : '开启目标识别并在视频中显示检测框'"
          @click="toggleTargetDetection">
          <i></i>{{ targetDetectionPending ? '切换中…' : '目标识别' }}
        </button>
        <div class="lens-group lens-switch">
          <button
            v-for="item in orderedLenses"
            :key="item"
            :class="{ active: displayLens === item, thermal: item === 'ir', live: liveLensType === item }"
            :disabled="dockSelectionPending"
            :title="liveLensType === item ? `${lensLabel(item)} · 设备当前在播` : lensLabel(item)"
            @click="switchLens(item)">
            <i v-if="liveLensType === item" class="lens-live-dot"></i>
            {{ lensLabel(item) }}
          </button>
        </div>
        <div class="lens-group quality-group">
          <button
            :class="{ active: videoQuality === 2 }"
            :disabled="dockSelectionPending || qualitySwitching || videoState === 'connecting' || videoState === 'waiting'"
            :title="reportedVideoQuality === 2 ? '设备当前为标清' : '切换为标清'"
            @click="switchQuality(2)">标清</button>
          <button
            :class="{ active: videoQuality === 3 }"
            :disabled="dockSelectionPending || qualitySwitching || videoState === 'connecting' || videoState === 'waiting'"
            :title="reportedVideoQuality === 3 ? '设备当前为高清' : '切换为高清'"
            @click="switchQuality(3)">高清</button>
        </div>
      </div>

      <!-- 视频画面 -->
      <div ref="videoBox" class="video-box" :style="{ aspectRatio: videoAspectRatio }">
        <video
          ref="videoElement"
          :class="{ ready: videoPlaying }"
          autoplay
          muted
          playsinline
          @loadedmetadata="syncVideoAspectRatio"
          @resize="syncVideoAspectRatio"></video>
        <div v-if="targetDetectionEnabled" class="target-detection-layer" aria-live="off">
          <div
            v-for="target in detectedTargets"
            :key="`${target.trackerId}-${target.classId}`"
            class="target-detection-box"
            :class="{ danger: [35, 36].includes(target.classId) }"
            :style="{
              left: `${target.x * 100}%`, top: `${target.y * 100}%`,
              width: `${target.width * 100}%`, height: `${target.height * 100}%`
            }">
            <span>{{ targetClassLabel(target.classId) }}<small v-if="target.trackerId"> #{{ target.trackerId }}</small></span>
          </div>
        </div>
        <section
          v-if="targetDetectionEnabled"
          class="target-detection-summary"
          :class="{ collapsed: targetDetectionSummaryCollapsed }"
          aria-label="目标识别统计">
          <button
            type="button"
            class="target-detection-summary-toggle"
            :aria-expanded="!targetDetectionSummaryCollapsed"
            :title="targetDetectionSummaryCollapsed ? '展开目标识别数量' : '收起目标识别数量'"
            @click.stop="targetDetectionSummaryCollapsed = !targetDetectionSummaryCollapsed">
            <span aria-hidden="true">{{ targetDetectionSummaryCollapsed ? '›' : '‹' }}</span>
            <strong>
              {{ targetDetectionLensLabel }}目标识别
              <em v-if="targetDetectionSummaryCollapsed">{{ targetDetectionTotal }}</em>
            </strong>
            <i aria-hidden="true"></i>
          </button>
          <div v-show="!targetDetectionSummaryCollapsed" class="target-detection-summary-counts">
            <span
              v-for="stat in targetDetectionStats"
              :key="stat.key"
              :class="[`type-${stat.key}`, { active: stat.count > 0 }]"
              :title="`${stat.label}：${stat.count}`">
              <b>{{ stat.label }}</b><em>{{ stat.count }}</em>
            </span>
          </div>
        </section>
        <div class="camera-actions">
          <button
            :title="recording ? '停止设备录像' : '开始设备录像'"
            :class="{ recording }"
            :disabled="dockSelectionPending || !selectedSource || cameraCommandPending !== '' || payloadAuthorityPending"
            @click="toggleDeviceRecording">
            {{ recording ? '■' : '▣' }}
          </button>
          <button
            title="设备单拍"
            :disabled="dockSelectionPending || !selectedSource || cameraCommandPending !== '' || payloadAuthorityPending"
            @click="takeDevicePhoto">●</button>
        </div>
        <button
          class="video-fullscreen-btn"
          title="视频全屏"
          aria-label="视频全屏"
          @click="toggleVideoFullscreen">⛶</button>
        <div v-if="cameraActionTip" class="camera-action-tip">{{ cameraActionTip }}</div>
        <div v-if="!videoPlaying" class="video-ph">
          <span>Y</span>
          <p>{{ videoError || (videoState === 'idle' ? '等待主视频画面' : '正在等待设备视频首帧…') }}</p>
          <button
            class="ghost small"
            :disabled="dockSelectionPending || !selectedVideoId || videoRetrying"
            @click="videoState === 'idle' ? startVideo() : retryVideoPlayback(true)">
            {{ videoRetrying ? '重试中…' : videoState === 'idle' ? '启动直播' : '重新连接' }}
          </button>
        </div>
        <div v-if="videoPlaying || recording" class="video-status-badges">
          <div v-if="videoPlaying" class="video-badge">LIVE</div>
          <div v-if="recording" class="recording-time">REC {{ recordingTime(recordingSeconds) }}</div>
        </div>
        <div
          v-if="obstacleHudVisible"
          class="video-obstacle-hud"
          :class="{ ready: obstacleHudReady, disabled: telemetry.radarEnabled === false }"
          aria-label="飞行避障信息">
          <div v-if="!obstacleHudReady" class="obstacle-hud-state">
            <i></i><span>{{ obstacleHudStateLabel }}</span>
          </div>
          <div v-if="obstacleHudReady" class="obstacle-rail obstacle-rail-front" aria-label="前方四路雷达">
            <span
              v-if="nearestObstacle.front >= 0"
              class="obstacle-rail-summary"
              :class="obstacleRiskClass(nearestObstacle.front)">机头 {{ fmtObstacle(nearestObstacle.front) }}m</span>
            <div
              v-for="(distance, index) in obstacleSegments.front"
              :key="`front-${index}`"
              class="obstacle-segment obstacle-segment-horizontal"
              :class="obstacleRiskClass(distance)"
              :aria-label="`前方 ${index + 1} 号雷达${distance >= 0 ? ` ${fmtObstacle(distance)} 米` : '无距离显示'}`">
              <i v-if="distance >= 0"></i>
            </div>
          </div>
          <div v-if="obstacleHudReady" class="obstacle-rail obstacle-rail-rear" aria-label="后方四路雷达">
            <span
              v-if="nearestObstacle.rear >= 0"
              class="obstacle-rail-summary"
              :class="obstacleRiskClass(nearestObstacle.rear)">机尾 {{ fmtObstacle(nearestObstacle.rear) }}m</span>
            <div
              v-for="(distance, index) in obstacleSegments.rear"
              :key="`rear-${index}`"
              class="obstacle-segment obstacle-segment-horizontal"
              :class="obstacleRiskClass(distance)"
              :aria-label="`后方 ${index + 1} 号雷达${distance >= 0 ? ` ${fmtObstacle(distance)} 米` : '无距离显示'}`">
              <i v-if="distance >= 0"></i>
            </div>
          </div>
          <div v-if="obstacleHudReady" class="obstacle-rail obstacle-rail-left" aria-label="左侧三路雷达">
            <span
              v-if="nearestObstacle.left >= 0"
              class="obstacle-rail-summary"
              :class="obstacleRiskClass(nearestObstacle.left)">{{ fmtObstacle(nearestObstacle.left) }}m</span>
            <div
              v-for="(distance, index) in obstacleSegments.left"
              :key="`left-${index}`"
              class="obstacle-segment obstacle-segment-vertical"
              :class="obstacleRiskClass(distance)"
              :aria-label="`左侧 ${index + 1} 号雷达${distance >= 0 ? ` ${fmtObstacle(distance)} 米` : '无距离显示'}`">
              <i v-if="distance >= 0"></i>
            </div>
          </div>
          <div v-if="obstacleHudReady" class="obstacle-rail obstacle-rail-right" aria-label="右侧三路雷达">
            <span
              v-if="nearestObstacle.right >= 0"
              class="obstacle-rail-summary"
              :class="obstacleRiskClass(nearestObstacle.right)">{{ fmtObstacle(nearestObstacle.right) }}m</span>
            <div
              v-for="(distance, index) in obstacleSegments.right"
              :key="`right-${index}`"
              class="obstacle-segment obstacle-segment-vertical"
              :class="obstacleRiskClass(distance)"
              :aria-label="`右侧 ${index + 1} 号雷达${distance >= 0 ? ` ${fmtObstacle(distance)} 米` : '无距离显示'}`">
              <i v-if="distance >= 0"></i>
            </div>
          </div>
          <div
            v-if="obstacleHudReady && (telemetry.obstacleUp >= 0 || telemetry.obstacleDown >= 0)"
            class="obstacle-vertical-pair">
            <span
              v-if="telemetry.obstacleUp >= 0"
              :class="obstacleRiskClass(telemetry.obstacleUp)">上方 {{ fmtObstacle(telemetry.obstacleUp) }}m</span>
            <span
              v-if="telemetry.obstacleDown >= 0"
              :class="obstacleRiskClass(telemetry.obstacleDown)">下方 {{ fmtObstacle(telemetry.obstacleDown) }}m</span>
          </div>
        </div>
        <div
          class="gimbal-pitch-hud"
          :class="{ unavailable: !telemetry.gimbalReported }"
          :title="telemetry.gimbalReported ? `云台俯仰角 ${formatGimbalAngle(telemetry.gimbalPitch)}` : '云台俯仰角未上报'"
          aria-label="云台俯仰角">
          <span>云台</span>
          <strong>{{ telemetry.gimbalReported ? formatGimbalAngle(telemetry.gimbalPitch) : '--' }}</strong>
        </div>
        <div
          v-if="showZoomScale"
          class="zoom-scale"
          :class="{ collapsed: !zoomScaleExpanded }"
          :aria-label="`当前${displayLens === 'ir' ? '红外' : '变焦'}倍率 ${activeZoomFactor.toFixed(1)} 倍，范围 1 至 ${activeZoomMax} 倍`">
          <button
            type="button"
            class="zoom-current"
            :style="zoomScaleExpanded ? { bottom: `${zoomScalePosition}%` } : undefined"
            :aria-expanded="zoomScaleExpanded"
            :aria-label="`${zoomScaleExpanded ? '收起' : '展开'}变焦刻度，当前 ${activeZoomFactor.toFixed(1)} 倍`"
            @click.stop="zoomScaleExpanded = !zoomScaleExpanded"><b>{{ activeZoomFactor.toFixed(1) }}×</b></button>
          <span v-if="zoomScaleExpanded" class="zoom-rail"></span>
          <span
            v-for="mark in zoomScaleExpanded ? activeZoomMarks : []"
            :key="mark"
            class="zoom-mark"
            :style="{ bottom: `${zoomMarkPosition(mark)}%` }">
            <strong>{{ mark }}×</strong><i></i>
          </span>
        </div>
        <div v-if="videoPlaying" class="video-metrics">
          <div class="video-metrics-actions">
            <button class="retry" :disabled="dockSelectionPending || videoRetrying" @click="retryVideoPlayback(true)">
              {{ videoRetrying ? '重试中…' : '重试' }}
            </button>
            <button @click="stopVideo">停止</button>
          </div>
          <div class="video-stream-meta">
            <span>{{ videoSize || '视频' }}</span>
            <span>{{ videoBitrate }} kbps</span>
            <span :class="{ confirmed: reportedLive || reusedPublisher }">
              {{ reusedPublisher ? '已有推流' : reportedLive ? '设备已确认' : 'WHEP 已播放' }}
            </span>
          </div>
          <div :class="['measure-overlay', `is-${measureTone}`]" aria-label="激光测距状态">
            <div class="measure-overlay-head">
              <span><i></i>测距 {{ measureStatusLabel }}</span>
              <strong>{{ telemetry.measureReported && telemetry.measureDistance > 0
                ? `${telemetry.measureDistance.toFixed(1)} m`
                : '--' }}</strong>
            </div>
          </div>
        </div>
      </div>

      <div class="control-deck">
        <!-- ═══ 左列：电池/时间/GPS/失联动作 ═══ -->
        <div class="deck-info-col">
          <div class="info-row">
            <span class="info-label">电池电量</span>
            <span class="info-val" :class="{ 'bat-warn': telemetry.battery < 25 }">
              🔋 {{ telemetry.battery.toFixed(1) }}%
            </span>
          </div>
          <div class="info-row">
            <span class="info-label">剩余飞行时间</span>
            <span class="info-val">{{ fmtDuration(telemetry.remainFlightTime) }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">GPS 搜星 / 质量</span>
            <span class="info-val">GPS {{ telemetry.satellites }} · Q{{ telemetry.gpsQuality }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">失联动作</span>
            <span class="info-val rclost-info"
              :class="[`rclost-${telemetry.rcLostAction}`, { unknown: telemetry.rcLostAction < 0 }]">
              <i class="rclost-dot"></i>{{ rcLostActionLabel(telemetry.rcLostAction) }}
            </span>
          </div>
        </div>

        <!-- ═══ 中区：姿态仪 + 罗盘 + 速度/高度带 ═══ -->
        <div class="deck-compass-zone">
          <span class="hdg-top">{{ String(Math.round(normalizedHeading) % 360).padStart(3, '0') }}°</span>
          <span
            v-if="active && telemetry.radarEnabled !== undefined"
            class="hsi-state"
            :class="{ enabled: telemetry.radarEnabled }"
            :title="`DRC 避障：前 ${fmtObstacle(telemetry.obstacleFront)} m，后 ${fmtObstacle(telemetry.obstacleBack)} m，左 ${fmtObstacle(telemetry.obstacleLeft)} m，右 ${fmtObstacle(telemetry.obstacleRight)} m，上 ${fmtObstacle(telemetry.obstacleUp)} m，下 ${fmtObstacle(telemetry.obstacleDown)} m`">
            避障 {{ telemetry.radarEnabled ? '开' : '关' }}
            <small v-if="telemetry.radarEnabled">↑{{ fmtObstacle(telemetry.obstacleUp) }} ↓{{ fmtObstacle(telemetry.obstacleDown) }}</small>
          </span>

          <span v-if="obstacleHudReady && telemetry.obstacleFront >= 0" class="obs obs-front" :class="{ warn: telemetry.obstacleFront < 5 }">
            {{ fmtObstacle(telemetry.obstacleFront) }} m
          </span>

          <div class="compass-core">
            <span v-if="obstacleHudReady && telemetry.obstacleLeft >= 0" class="obs obs-left" :class="{ warn: telemetry.obstacleLeft < 5 }">
              {{ fmtObstacle(telemetry.obstacleLeft) }} m
            </span>
            <div class="attitude-window">
              <div class="attitude-horizon" :style="{ transform: attitudeTransform }">
                <span class="attitude-sky"></span>
                <span class="attitude-ground"></span>
                <span class="horizon-line"></span>
                <span v-for="mark in 5" :key="mark" class="pitch-mark"
                  :style="{ top: `${50 + (mark - 3) * 11}%` }"></span>
              </div>
            </div>
            <div class="compass-ring" :style="{ transform: `rotate(${-normalizedHeading}deg)` }">
              <span class="cn">N</span><span class="cs">S</span>
              <span class="cw">W</span><span class="ce">E</span>
              <span v-for="t in 36" :key="t" class="tick" :style="{ transform: `rotate(${t * 10}deg)` }"></span>
            </div>
            <div class="aircraft-symbol"><b>▲</b></div>
            <span v-if="obstacleHudReady && telemetry.obstacleRight >= 0" class="obs obs-right" :class="{ warn: telemetry.obstacleRight < 5 }">
              {{ fmtObstacle(telemetry.obstacleRight) }} m
            </span>
          </div>

          <span v-if="obstacleHudReady && telemetry.obstacleBack >= 0" class="obs obs-back" :class="{ warn: telemetry.obstacleBack < 5 }">
            {{ fmtObstacle(telemetry.obstacleBack) }} m
          </span>

          <div class="tape tape-left">
            <span class="tape-val spd-val">{{ telemetry.speed.toFixed(1) }}</span>
            <span class="tape-unit">SPD<br>m/s</span>
            <span class="wind-readout" v-if="telemetry.windSpeed >= 0">
              WIND {{ telemetry.windSpeed.toFixed(1) }}
            </span>
          </div>

          <div class="tape tape-right">
            <div class="tape-vs">
              <span class="vs-bar" :class="{ up: telemetry.verticalSpeed > 0, down: telemetry.verticalSpeed < 0 }">
                <span class="vs-fill" :style="{ height: Math.min(100, Math.abs(telemetry.verticalSpeed) * 10) + '%' }"></span>
              </span>
              <span class="vs-val">{{ telemetry.verticalSpeed.toFixed(1) }}</span>
              <span class="vs-label">VS</span>
            </div>
            <div class="tape-alt">
              <span class="alt-val">{{ telemetry.altitude.toFixed(1) }}</span>
              <span class="alt-label">ALT<br>m</span>
            </div>
            <div class="tape-asl">
              <span class="asl-val">{{ telemetry.height.toFixed(1) }}</span>
              <span class="asl-label">ASL</span>
            </div>
            <div class="home-readout"><span class="home-icon">H</span>{{ telemetry.homeDistance.toFixed(1) }} m</div>
          </div>
        </div>

        <!-- ═══ 右区：按飞行状态切换操作内容 ═══ -->
        <div class="deck-control-zone">
          <div
            class="drc-readiness"
            :class="{ ready: drcLinkReady, blocked: !drcLinkReady }"
            role="status"
            :title="drcStatusMessage">
            <span v-if="drcBlockedReason" class="drc-readiness-copy"><i></i>{{ drcBlockedReason }}</span>
            <div class="drc-readiness-actions">
              <button
                :class="{ done: hasFlightAuthority }"
                :disabled="dockSelectionPending || !selectedDock || !selectedAircraftOnline || flightAuthorityPending || hasFlightAuthority"
                @click="grabFlightAuthority()">
                {{ flightAuthorityPending ? '① 抢夺中…' : hasFlightAuthority ? '① 飞行权已获取' : '① 抢夺飞行权' }}
              </button>
              <button
                :class="{ done: drcLinkReady, warn: active && !drcLinkReady }"
                :disabled="drcActionDisabled"
                @click="handleDrcAction">
                {{ drcActionLabel }}
              </button>
            </div>
          </div>
          <div v-if="operationPanelState === 'ground'" class="takeoff-side">
            <div class="takeoff-side-fields">
              <label>目标相对高度
                <span><input v-model.number="takeoffSettings.targetAgl" type="number" min="2" max="1500" step="0.1" />m</span>
                <small>范围 2–1500 m</small>
              </label>
            </div>
            <button class="takeoff-side-action" :disabled="dockSelectionPending || takeoffPending || telemetry.pointFlightActive || !selectedDock || !selectedAircraftOnline" @click="oneKeyTakeoff">
              <span>▲</span>{{ takeoffPending ? '起飞中…' : '一键起飞' }}
            </button>
            <p
              v-if="pointFlightNoticeVisible && pointFlightProgress?.kind === 'takeoff'"
              class="takeoff-progress-line"
              :title="`flight_id: ${pointFlightProgress.taskId || '--'} · track_id: ${pointFlightProgress.trackId || '--'}`">
              起飞事件：{{ pointFlightStatusLabel(pointFlightProgress.status) }}
              · 距离 {{ fmtTaskDistance(pointFlightProgress.remainingDistance) }}
              · 时间 {{ fmtDuration(pointFlightProgress.remainingTime) }}
            </p>
          </div>

          <!-- 航线执行任务面板：数据来自 flighttask_progress，操作为暂停/恢复/取消 -->
          <div v-else-if="operationPanelState === 'task' && taskIsWayline" class="wayline-side">
            <div class="task-side-summary wayline-summary">
              <div><span>任务状态</span><strong>{{ waylineStatusLabel(waylineProgress?.status) }}</strong></div>
              <div><span>执行步骤</span><strong>{{ waylineStepLabel(waylineProgress?.currentStep) }}</strong></div>
              <div><span>执行进度</span><strong>{{ (waylineProgress?.percent ?? -1) >= 0 ? waylineProgress?.percent + '%' : '--' }}</strong></div>
              <div><span>当前航点</span><strong>{{ (waylineProgress?.wayPointIndex ?? -1) >= 0 ? waylineProgress?.wayPointIndex : '--' }}</strong></div>
              <div><span>媒体文件</span><strong>{{ waylineProgress?.mediaCount ?? '--' }}</strong></div>
              <div><span>返回码</span><strong :class="{ 'danger-text': (waylineProgress?.resultCode ?? 0) !== 0 }">{{ waylineProgress?.resultCode ?? '--' }}</strong></div>
              <div class="wayline-summary-id" :title="`flight_id: ${waylineProgress?.flightId || '--'}`">
                <span>Flight ID</span><strong>{{ waylineProgress?.flightId || '--' }}</strong>
              </div>
            </div>
            <div class="wayline-actions">
              <button
                v-if="waylineProgress?.status !== 'paused'"
                class="deck-btn btn-wayline"
                :disabled="!waylineProgress?.jobId || waylinePausePending || waylineResumePending || waylineCancelPending"
                @click="pauseWaylineJob">
                {{ waylinePausePending ? '暂停中…' : '航线暂停' }}
              </button>
              <button
                v-else
                class="deck-btn btn-wayline"
                :disabled="!waylineProgress?.jobId || waylinePausePending || waylineResumePending || waylineCancelPending"
                @click="resumeWaylineJob">
                {{ waylineResumePending ? '恢复中…' : '航线恢复' }}
              </button>
              <button
                class="deck-btn btn-cancel"
                :disabled="!waylineProgress?.jobId || waylineCancelPending"
                @click="cancelWaylineJob">
                {{ waylineCancelPending ? '取消中…' : '取消任务' }}
              </button>
              <button class="deck-btn btn-rth" :disabled="dockSelectionPending || returnHomePending || returnHomeCancelPending || emergencyStopPending || !!drcLandingPending || continuousLandingActive" @click="returnHome">
                {{ returnHomePending ? '发送中…' : '返航' }}
              </button>
              <button
                class="deck-btn btn-cancel"
                :disabled="dockSelectionPending || returnHomeCancelPending || emergencyStopPending || !!drcLandingPending || continuousLandingActive"
                @click="cancelReturnHome">
                {{ returnHomeCancelPending ? '发送中…' : '取消返航' }}
              </button>
              <button class="deck-btn btn-stop" :disabled="dockSelectionPending || returnHomeCancelPending || emergencyStopPending || !!drcLandingPending" @click="emergencyStop">
                {{ emergencyStopPending ? '等待确认' : '刹车悬停' }}<br><small>[Space]</small>
              </button>
              <button
                class="deck-btn btn-continuous-land"
                :class="{ active: continuousLandingActive, armed: continuousLandingArmed }"
                :disabled="continuousLandingActionDisabled"
                :title="continuousLandingActive
                  ? '立即停止下降并发送零杆量'
                  : continuousLandingArmed
                    ? '再次点击确认启动；8 秒后自动取消'
                    : '通过当前 DRC 会话以 10 Hz 持续发送垂直下降杆量，进入待机后自动停止'"
                @click="toggleContinuousLanding">
                {{ continuousLandingActive
                  ? '停止持续降落'
                  : continuousLandingArmed ? '确认持续降落' : '持续降落' }}
              </button>
              <button
                class="deck-btn btn-emergency-land"
                :disabled="dockSelectionPending || returnHomePending || returnHomeCancelPending || emergencyStopPending || !!drcLandingPending || continuousLandingActive"
                title="避障并识别二维码降落"
                @click="drcLanding('drc_emergency_landing')">
                {{ drcLandingPending === 'drc_emergency_landing' ? '等待确认…' : '紧急降落' }}
              </button>
              <button
                class="deck-btn btn-force-land"
                :disabled="dockSelectionPending || returnHomePending || returnHomeCancelPending || emergencyStopPending || !!drcLandingPending || continuousLandingActive"
                title="不考虑障碍物直接强制降落"
                @click="drcLanding('drc_force_landing')">
                {{ drcLandingPending === 'drc_force_landing' ? '等待确认…' : '强制降落' }}
              </button>
            </div>
          </div>

          <div v-else-if="operationPanelState === 'task'" class="task-side">
            <div class="task-side-summary">
              <div :title="`flight_id: ${pointFlightProgress?.taskId || '--'}\ntrack_id: ${pointFlightProgress?.trackId || '--'}`">
                <span>任务状态</span><strong>{{ pointFlightStatusLabel(pointFlightProgress?.status) }}</strong>
              </div>
              <div><span>剩余距离</span><strong>{{ fmtTaskDistance(telemetry.taskRemainingDistance) }}</strong></div>
              <div><span>剩余时长</span><strong>{{ fmtDuration(telemetry.taskRemainingTime) }}</strong></div>
              <div><span>当前航点</span><strong>{{ (pointFlightProgress?.wayPointIndex ?? -1) >= 0 ? pointFlightProgress?.wayPointIndex : '--' }}</strong></div>
              <div><span>Flight ID</span><strong :title="pointFlightProgress?.taskId">{{ pointFlightProgress?.taskId || '--' }}</strong></div>
              <div><span>Track ID</span><strong :title="pointFlightProgress?.trackId">{{ pointFlightProgress?.trackId || '--' }}</strong></div>
              <div :title="pointFlightProgress?.plannedPathPoints.map((point) => `${point.latitude.toFixed(6)}, ${point.longitude.toFixed(6)}, ${point.height.toFixed(1)} m`).join('\n')">
                <span>规划轨迹</span><strong>{{ pointFlightProgress?.plannedPathPoints.length ?? 0 }} 点</strong>
              </div>
              <div><span>返回码</span><strong>{{ pointFlightProgress?.result ?? '--' }}</strong></div>
            </div>
            <div class="direction-grid">
              <button
                v-for="control in directionControls"
                :key="control.code"
                :class="[`icon-${control.iconPosition}`, { down: keyIsDown(control.code) }]"
                :title="drcControlsReady ? `${control.key} · ${control.label}` : drcBlockedReason"
                :aria-label="`${control.key} 键：${control.label}`"
                :disabled="!drcControlsReady"
                @pointerdown.prevent="startDirectionalControl(control.code)"
                @pointerup.prevent="stopDirectionalControl(control.code)"
                @pointercancel="stopDirectionalControl(control.code)"
                @pointerleave="stopDirectionalControl(control.code)">
                <span class="direction-icon" aria-hidden="true">{{ control.icon }}</span>
                <kbd>{{ control.key }}</kbd>
                <span class="sr-only">{{ control.label }}</span>
              </button>
            </div>
            <div class="deck-btns landing-actions">
              <button class="deck-btn btn-rth" :disabled="dockSelectionPending || returnHomePending || returnHomeCancelPending || emergencyStopPending || !!drcLandingPending || continuousLandingActive" @click="returnHome">
                {{ returnHomePending ? '发送中…' : '返航' }}
              </button>
              <button
                class="deck-btn btn-cancel"
                :disabled="dockSelectionPending || returnHomeCancelPending || emergencyStopPending || !!drcLandingPending || continuousLandingActive"
                @click="cancelReturnHome">
                {{ returnHomeCancelPending ? '发送中…' : '取消返航' }}
              </button>
              <button
                v-if="pointFlightProgress?.kind === 'flyto' && telemetry.pointFlightActive"
                class="deck-btn btn-cancel"
                :disabled="dockSelectionPending || flyToStopPending"
                @click="stopPointFlight">
                {{ flyToStopPending ? '取消中…' : '结束 FlyTo' }}
              </button>
              <button class="deck-btn btn-stop" :disabled="dockSelectionPending || returnHomeCancelPending || emergencyStopPending || !!drcLandingPending" @click="emergencyStop">
                {{ emergencyStopPending ? '等待确认' : '刹车悬停' }}<br><small>[Space]</small>
              </button>
              <button
                class="deck-btn btn-continuous-land"
                :class="{ active: continuousLandingActive, armed: continuousLandingArmed }"
                :disabled="continuousLandingActionDisabled"
                :title="continuousLandingActive
                  ? '立即停止下降并发送零杆量'
                  : continuousLandingArmed
                    ? '再次点击确认启动；8 秒后自动取消'
                    : '通过当前 DRC 会话以 10 Hz 持续发送垂直下降杆量，进入待机后自动停止'"
                @click="toggleContinuousLanding">
                {{ continuousLandingActive
                  ? '停止持续降落'
                  : continuousLandingArmed ? '确认持续降落' : '持续降落' }}
              </button>
              <button
                class="deck-btn btn-emergency-land"
                :disabled="dockSelectionPending || returnHomePending || returnHomeCancelPending || emergencyStopPending || !!drcLandingPending || continuousLandingActive"
                title="避障并识别二维码降落"
                @click="drcLanding('drc_emergency_landing')">
                {{ drcLandingPending === 'drc_emergency_landing' ? '等待确认…' : '紧急降落' }}
              </button>
              <button
                class="deck-btn btn-force-land"
                :disabled="dockSelectionPending || returnHomePending || returnHomeCancelPending || emergencyStopPending || !!drcLandingPending || continuousLandingActive"
                title="不考虑障碍物直接强制降落"
                @click="drcLanding('drc_force_landing')">
                {{ drcLandingPending === 'drc_force_landing' ? '等待确认…' : '强制降落' }}
              </button>
            </div>
          </div>

          <template v-else>
            <!-- 八方向飞行控制；仅在用户显式完成飞行权与 DRC 两步后开放 -->
            <div class="direction-grid">
              <button
                v-for="control in directionControls"
                :key="control.code"
                :class="[`icon-${control.iconPosition}`, { down: keyIsDown(control.code) }]"
                :title="drcControlsReady ? `${control.key} · ${control.label}` : drcBlockedReason"
                :aria-label="`${control.key} 键：${control.label}`"
                :disabled="!drcControlsReady"
                @pointerdown.prevent="startDirectionalControl(control.code)"
                @pointerup.prevent="stopDirectionalControl(control.code)"
                @pointercancel="stopDirectionalControl(control.code)"
                @pointerleave="stopDirectionalControl(control.code)">
                <span class="direction-icon" aria-hidden="true">{{ control.icon }}</span>
                <kbd>{{ control.key }}</kbd>
                <span class="sr-only">{{ control.label }}</span>
              </button>
            </div>
            <div class="deck-btns landing-actions">
              <button class="deck-btn btn-rth" :disabled="dockSelectionPending || returnHomePending || returnHomeCancelPending || emergencyStopPending || !!drcLandingPending || continuousLandingActive" @click="returnHome">
                {{ returnHomePending ? '发送中…' : '返航' }}
              </button>
              <button
                class="deck-btn btn-cancel"
                :disabled="dockSelectionPending || state === 'connecting' || returnHomeCancelPending || emergencyStopPending || !!drcLandingPending || continuousLandingActive"
                @click="cancelReturnHome">
                {{ returnHomeCancelPending ? '发送中…' : '取消返航' }}
              </button>
              <button class="deck-btn btn-stop" :disabled="dockSelectionPending || state === 'connecting' || returnHomeCancelPending || emergencyStopPending || !!drcLandingPending" @click="emergencyStop">
                {{ emergencyStopPending ? '等待确认' : '刹车悬停' }}<br><small>[Space]</small>
              </button>
              <button
                class="deck-btn btn-continuous-land"
                :class="{ active: continuousLandingActive, armed: continuousLandingArmed }"
                :disabled="continuousLandingActionDisabled"
                :title="continuousLandingActive
                  ? '立即停止下降并发送零杆量'
                  : continuousLandingArmed
                    ? '再次点击确认启动；8 秒后自动取消'
                    : '通过当前 DRC 会话以 10 Hz 持续发送垂直下降杆量，进入待机后自动停止'"
                @click="toggleContinuousLanding">
                {{ continuousLandingActive
                  ? '停止持续降落'
                  : continuousLandingArmed ? '确认持续降落' : '持续降落' }}
              </button>
              <button
                class="deck-btn btn-emergency-land"
                :disabled="dockSelectionPending || state === 'connecting' || returnHomePending || returnHomeCancelPending || emergencyStopPending || !!drcLandingPending || continuousLandingActive"
                title="避障并识别二维码降落"
                @click="drcLanding('drc_emergency_landing')">
                {{ drcLandingPending === 'drc_emergency_landing' ? '等待确认…' : '紧急降落' }}
              </button>
              <button
                class="deck-btn btn-force-land"
                :disabled="dockSelectionPending || state === 'connecting' || returnHomePending || returnHomeCancelPending || emergencyStopPending || !!drcLandingPending || continuousLandingActive"
                title="不考虑障碍物直接强制降落"
                @click="drcLanding('drc_force_landing')">
                {{ drcLandingPending === 'drc_force_landing' ? '等待确认…' : '强制降落' }}
              </button>
            </div>
          </template>
        </div>
      </div>

      <section
        v-if="hasPayloadAuthority"
        class="payload-shortcut-panel"
        aria-label="负载快捷控制">
        <div class="payload-shortcut-copy">
          <div class="payload-shortcut-heading">
            <span class="payload-shortcut-status"></span>
            <strong>负载快捷控制</strong>
          </div>
          <span>↑ ↓ ← → 云台方向</span>
          <span>, / &lt; 缩小 · . / &gt; 放大</span>
          <small>当前变焦 {{ payloadZoomDisplay.toFixed(payloadZoomDisplay < 10 ? 1 : 0) }}×</small>
          <div class="payload-gimbal-reset" aria-label="云台姿态重置">
            <span aria-hidden="true">◎</span>
            <select
              v-model.number="gimbalResetMode"
              :disabled="dockSelectionPending || gimbalResetPending"
              title="选择云台重置姿态"
              aria-label="云台重置姿态">
              <option v-for="option in gimbalResetOptions" :key="option.value" :value="option.value">
                {{ option.label }}
              </option>
            </select>
            <button
              :disabled="dockSelectionPending || gimbalResetPending"
              :title="`执行云台${gimbalResetOptions[gimbalResetMode].label}`"
              @click="resetPayloadGimbal">
              {{ gimbalResetPending ? '执行中…' : '执行' }}
            </button>
          </div>
        </div>
        <div class="payload-shortcut-pad" role="group" aria-label="云台与变焦方向控制">
          <button
            v-for="control in payloadShortcutControls"
            :key="control.code"
            :class="[
              'payload-shortcut-btn',
              `is-${control.position}`,
              { down: payloadPressed.has(control.code) }
            ]"
            :aria-label="`${control.label}：${control.description}`"
            :title="`${control.label}：${control.description}`"
            :disabled="dockSelectionPending"
            @pointerdown.prevent="startPayloadControl(control.code)"
            @pointerup.prevent="stopPayloadControl(control.code)"
            @pointercancel="stopPayloadControl(control.code)"
            @pointerleave="stopPayloadControl(control.code)">
            <span>{{ control.icon }}</span>
            <small>{{ control.label }}</small>
          </button>
          <div class="payload-shortcut-center" aria-hidden="true">
            <span>方向键</span>
            <kbd>KEY</kbd>
          </div>
        </div>
      </section>

    </section>

    <div v-if="logOpen" class="interaction-log-backdrop" @click.self="logOpen = false">
      <section class="interaction-log-dialog" role="dialog" aria-modal="true" aria-label="云端交互日志">
        <header class="interaction-log-head">
          <div>
            <p>REAL-TIME TRACE</p>
            <h2>云端交互日志</h2>
            <small>HTTP · WebSocket · MQTT 原始交互，敏感字段已自动脱敏</small>
          </div>
          <button class="log-close" aria-label="关闭日志" @click="logOpen = false">×</button>
        </header>

        <div class="interaction-log-tools">
          <select v-model="logTransport">
            <option value="ALL">全部协议</option>
            <option value="HTTP">HTTP</option>
            <option value="MQTT">MQTT</option>
            <option value="WebSocket">WebSocket</option>
            <option value="SYSTEM">系统</option>
          </select>
          <input v-model="logQuery" placeholder="搜索 Topic、接口或数据…" />
          <button :class="{ active: logPaused }" @click="toggleLogPause">
            {{ logPaused ? '继续滚动' : '暂停查看' }}
          </button>
          <button class="danger" @click="clearInteractionLogs">清空</button>
        </div>

        <div class="interaction-log-stats">
          <span :class="{ paused: logPaused }">{{ logPaused ? 'PAUSED' : 'LIVE' }}</span>
          显示 {{ filteredInteractionLogs.length }} / {{ interactionLogs.length }} 条
        </div>

        <div class="interaction-log-list">
          <details v-for="entry in filteredInteractionLogs" :key="entry.id" class="interaction-log-row">
            <summary>
              <time>{{ formatLogTime(entry.timestamp) }}</time>
              <b :class="`transport-${entry.transport.toLowerCase()}`">{{ entry.transport }}</b>
              <i :class="`direction-${entry.direction.toLowerCase()}`">
                {{ entry.direction === 'OUT' ? '→' : entry.direction === 'IN' ? '←' : entry.direction === 'ERROR' ? '!' : '•' }}
              </i>
              <code>{{ entry.topic || `${entry.method || ''} ${entry.path || ''}`.trim() }}</code>
              <span>{{ topicDescription(entry) }}</span>
              <em v-if="entry.status">{{ entry.status }}</em>
              <em v-if="entry.durationMs !== undefined">{{ entry.durationMs }}ms</em>
            </summary>
            <pre v-if="entry.payload !== undefined">{{ formatLogPayload(entry.payload) }}</pre>
            <p v-else>无数据载荷</p>
          </details>
          <div v-if="!filteredInteractionLogs.length" class="interaction-log-empty">
            暂无匹配的交互日志
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
/* ─── 全局布局 ────────────────────────────────────── */
.cockpit-pro {
  --rail-width: 320px;
  display: grid;
  grid-template:
    "bar bar bar" 48px
    "rail map flight" 1fr
    / var(--rail-width) 1fr 1fr;
  height: calc(100vh - 60px);
  min-height: 0;
  background: #090e16;
  overflow: hidden;
  position: relative;
  transition: grid-template-columns .32s cubic-bezier(.4, 0, .2, 1);
}
/* 均衡布局：地图略宽 */
.cockpit-pro.layout-balanced { grid-template-columns: var(--rail-width) 1.3fr 1fr; }
/* 视频优先：除设备栏外，地图与视频各占一半 */
.cockpit-pro.layout-video {
  grid-template-columns: var(--rail-width) minmax(0, 1fr) minmax(0, 1fr);
}
/* 地图优先：视频/操控区固定 360px，地图占据剩余空间 */
.cockpit-pro.layout-map { grid-template-columns: var(--rail-width) minmax(0, 1fr) 360px; }
/* 折叠态 */
.cockpit-pro.rail-collapsed { grid-template-columns: 0px 1fr 1fr; }
.cockpit-pro.rail-collapsed.layout-video {
  grid-template-columns: 0px minmax(0, 1fr) minmax(0, 1fr);
}
.cockpit-pro.rail-collapsed.layout-balanced { grid-template-columns: 0px 1.3fr 1fr; }
.cockpit-pro.rail-collapsed.layout-map { grid-template-columns: 0px minmax(0, 1fr) 360px; }
/* 折叠把手：骑在 rail 右边缘，left 跟随列宽平滑滑动 */
.rail-toggle {
  position: absolute; z-index: 25; top: 50%;
  left: calc(var(--rail-width) - 14px);
  transform: translateY(-50%);
  display: grid; place-items: center; width: 28px; height: 44px;
  border: 1px solid rgba(255,255,255,.14); border-radius: 8px;
  background: rgba(10,15,22,.92); color: #9fb2c4;
  cursor: pointer; backdrop-filter: blur(6px);
  box-shadow: 0 2px 10px rgba(0,0,0,.4);
  transition: left .32s cubic-bezier(.4, 0, .2, 1), background .15s, color .15s, border-color .15s;
}
.rail-toggle:hover { color: #eaf3ff; border-color: rgba(63,169,255,.5); background: rgba(20,28,40,.95); }
.rail-toggle-ico { display: inline-block; font-size: 16px; line-height: 1; transition: transform .32s cubic-bezier(.4, 0, .2, 1); }
.cockpit-pro.rail-collapsed .rail-toggle { left: 2px; }
.cockpit-pro.rail-collapsed .rail-toggle-ico { transform: rotate(180deg); }

/* ─── 顶栏 ────────────────────────────────────────── */
.cockpit-bar {
  grid-area: bar;
  display: flex; align-items: center; justify-content: space-between;
  padding: 0 16px;
  background: rgba(5,9,16,.96);
  border-bottom: 1px solid rgba(255,255,255,.07);
  z-index: 20;
}
.bar-left, .bar-right { display: flex; align-items: center; gap: 8px; }
.mode-badge { display: flex; align-items: center; gap: 6px; font-size: 13px; color: var(--muted, #6b7789); padding: 4px 10px; border-radius: 20px; border: 1px solid rgba(255,255,255,.08); }
.mode-badge i { width: 7px; height: 7px; border-radius: 50%; background: var(--muted, #6b7789); }
.mode-badge.active { color: #3fa9ff; border-color: rgba(63,169,255,.3); }
.mode-badge.active i { background: #3fa9ff; box-shadow: 0 0 6px #3fa9ff; animation: blink 1.5s ease infinite; }
.control-state-btn {
  height: 27px; padding: 0 9px;
  border: 1px solid rgba(255,255,255,.1); border-radius: 14px;
  color: #8795a7; background: rgba(255,255,255,.035);
  font-size: 10px; white-space: nowrap; cursor: pointer;
}
.control-state-btn:hover:not(:disabled) { color: #dfe8f1; border-color: rgba(63,169,255,.45); }
.control-state-btn:disabled { opacity: .65; cursor: default; }
.control-state-btn.authority.active,
.control-state-btn.drc.state-online { color: #35d6a4; border-color: rgba(53,214,164,.42); background: rgba(53,214,164,.08); }
.control-state-btn.drc.state-connecting { color: #3fa9ff; border-color: rgba(63,169,255,.4); }
.control-state-btn.drc.state-degraded,
.control-state-btn.drc.state-offline { color: #ffb04f; border-color: rgba(255,176,79,.4); }
.latency-badge { font-size: 12px; color: #35d6a4; padding: 3px 8px; border-radius: 20px; border: 1px solid rgba(53,214,164,.3); font-family: monospace; }
.latency-badge.warn { color: #f5a623; border-color: rgba(245,166,35,.3); }
.control-state-reason {
  max-width: 260px; overflow: hidden; color: #d49a50;
  font: 9px/1.2 ui-monospace, SFMono-Regular, Menlo, monospace;
  text-overflow: ellipsis; white-space: nowrap;
}
.control-state-reason.ready { color: #35d6a4; }
.layout-switch {
  display: inline-flex; align-items: center; gap: 2px;
  padding: 2px; border: 1px solid rgba(255,255,255,.08); border-radius: 9px;
  background: rgba(255,255,255,.035);
}
.layout-switch > span {
  padding: 0 6px; color: #8290a2; font-size: 10px; white-space: nowrap;
}
.layout-switch button {
  padding: 4px 8px; border: 1px solid transparent; border-radius: 6px;
  background: transparent; color: #7d8b9d; font-size: 10px; line-height: 1.2;
  white-space: nowrap; cursor: pointer;
  transition: color .15s, border-color .15s, background .15s, box-shadow .15s;
}
.layout-switch button:hover { color: #dfe6f1; border-color: rgba(63,169,255,.32); }
.layout-switch button.active {
  color: #3fa9ff; border-color: rgba(63,169,255,.55);
  background: rgba(63,169,255,.12);
  box-shadow: 0 0 10px rgba(63,169,255,.12);
}
.bar-btn { background: rgba(255,255,255,.06); border: 1px solid rgba(255,255,255,.1); color: var(--text, #dfe6f1); border-radius: 8px; padding: 5px 12px; font-size: 12px; cursor: pointer; text-decoration: none; }
.bar-btn:hover { border-color: #3fa9ff; }
.shortcut-help-trigger { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.log-trigger span { margin-left: 3px; min-width: 18px; padding: 1px 5px; border-radius: 10px; color: #35d6a4; background: rgba(53,214,164,.12); font-family: monospace; }
.hms-trigger span { margin-left: 3px; min-width: 18px; padding: 1px 5px; border-radius: 10px; font-family: monospace; }
.hms-trigger.active.level-0 { color: #65b9ff; border-color: rgba(63,169,255,.48); }
.hms-trigger.active.level-1 { color: #ffbd62; border-color: rgba(255,176,79,.58); background: rgba(255,176,79,.09); }
.hms-trigger.active.level-2 { color: #ff7b87; border-color: rgba(255,93,108,.65); background: rgba(255,93,108,.11); }
.hms-trigger.active.level-0 span { background: rgba(63,169,255,.15); }
.hms-trigger.active.level-1 span { background: rgba(255,176,79,.16); }
.hms-trigger.active.level-2 span { background: rgba(255,93,108,.18); }

/* ─── HMS 健康告警 ───────────────────────────────── */
.hms-backdrop {
  position: absolute; z-index: 95; inset: 0; display: grid; place-items: center;
  padding: 24px; background: rgba(2,5,9,.76); backdrop-filter: blur(8px);
}
.hms-dialog {
  display: flex; flex-direction: column; width: min(820px, 96vw); max-height: min(760px, 90vh);
  overflow: hidden; border: 1px solid rgba(255,176,79,.26); border-radius: 14px;
  color: #edf3fa; background: #080d15;
  box-shadow: 0 24px 80px rgba(0,0,0,.62), 0 0 28px rgba(255,176,79,.06);
}
.hms-head {
  display: flex; align-items: flex-start; justify-content: space-between; gap: 18px;
  padding: 18px 20px 14px; border-bottom: 1px solid rgba(255,255,255,.08);
  background: linear-gradient(120deg, rgba(255,176,79,.1), transparent 48%);
}
.hms-head small { color: #ffb04f; font: 9px/1 monospace; letter-spacing: .16em; }
.hms-head h2 { margin: 5px 0 0; font-size: 19px; font-weight: 550; }
.hms-head p { margin: 5px 0 0; color: #7e8da0; font-size: 10px; }
.hms-head-actions { display: flex; align-items: center; gap: 7px; }
.hms-head-actions button {
  height: 30px; padding: 0 10px; border: 1px solid rgba(255,255,255,.12); border-radius: 7px;
  color: #cbd5e1; background: rgba(255,255,255,.045); font-size: 10px; cursor: pointer;
}
.hms-head-actions button:hover:not(:disabled) { color: #fff; border-color: rgba(255,176,79,.5); }
.hms-head-actions button:disabled { opacity: .45; cursor: default; }
.hms-head-actions .hms-close { width: 30px; padding: 0; border: 0; background: transparent; font-size: 24px; }
.hms-error { margin: 10px 14px 0; padding: 8px 10px; border: 1px solid rgba(255,93,108,.35); border-radius: 7px; color: #ff9aa5; background: rgba(255,93,108,.08); font-size: 11px; }
.hms-empty { display: grid; place-items: center; align-content: center; gap: 8px; min-height: 260px; padding: 30px; color: #68778a; font-size: 12px; text-align: center; }
.hms-empty strong { color: #aebac8; font-size: 14px; }
.hms-list { min-height: 0; overflow: auto; padding: 10px 14px 16px; }
.hms-row {
  display: grid; grid-template-columns: 48px minmax(0, 1fr); gap: 12px; margin-bottom: 8px;
  padding: 12px; border: 1px solid rgba(255,255,255,.08); border-left-width: 3px; border-radius: 8px;
  background: rgba(255,255,255,.025);
}
.hms-row.level-0 { border-left-color: #3fa9ff; }
.hms-row.level-1 { border-left-color: #ffb04f; background: rgba(255,176,79,.035); }
.hms-row.level-2 { border-left-color: #ff5d6c; background: rgba(255,93,108,.045); }
.hms-level { width: max-content; padding: 3px 7px; border-radius: 5px; color: #8dcaff; background: rgba(63,169,255,.12); font-size: 9px; }
.hms-row.level-1 .hms-level { color: #ffc779; background: rgba(255,176,79,.13); }
.hms-row.level-2 .hms-level { color: #ff909a; background: rgba(255,93,108,.14); }
.hms-detail { min-width: 0; }
.hms-detail > strong { display: block; color: #e2e9f2; font-size: 13px; line-height: 1.45; overflow-wrap: anywhere; }
.hms-detail p { display: flex; flex-wrap: wrap; gap: 6px 12px; margin: 7px 0 5px; color: #7e8da0; font-size: 10px; }
.hms-detail p span:first-child { color: #a7b6c7; font-family: monospace; }
.hms-detail code { color: #627286; font-size: 9px; overflow-wrap: anywhere; }
.hms-live-notice {
  position: fixed; z-index: 1200; top: 56px; right: 16px;
  display: grid; grid-template-columns: 24px minmax(0, 1fr); align-items: start; gap: 9px;
  width: min(420px, calc(100vw - 32px)); padding: 11px 12px;
  border: 1px solid rgba(255,176,79,.55); border-radius: 9px; color: #f5e8d5;
  background: rgba(31,20,8,.95); box-shadow: 0 10px 30px rgba(0,0,0,.42);
  text-align: left; backdrop-filter: blur(10px); cursor: pointer;
}
.hms-live-notice.level-2 { border-color: rgba(255,93,108,.6); color: #f7dce0; background: rgba(31,8,14,.95); }
.hms-live-notice > span { color: #ffb04f; font-size: 18px; }
.hms-live-notice.level-2 > span { color: #ff6f7c; }
.hms-live-notice small { display: block; margin-bottom: 3px; color: #b7976d; font-size: 9px; }
.hms-live-notice.level-2 small { color: #c68e95; }
.hms-live-notice strong { display: block; font-size: 11px; line-height: 1.45; overflow-wrap: anywhere; }

/* ─── 快捷键说明 ──────────────────────────────────── */
.shortcut-help-backdrop {
  position: absolute; z-index: 90; inset: 0; display: grid; place-items: center;
  padding: 24px; background: rgba(2,5,9,.76); backdrop-filter: blur(8px);
}
.shortcut-help-dialog {
  width: min(1080px, 96vw); max-height: 92vh; box-sizing: border-box; overflow: auto;
  padding: 18px; border: 1px solid rgba(255,255,255,.18); border-radius: 14px;
  color: #eef4fb; background: linear-gradient(145deg, rgba(27,32,39,.98), rgba(12,17,24,.98));
  box-shadow: 0 24px 70px rgba(0,0,0,.62);
}
.shortcut-help-dialog > header { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 16px; }
.shortcut-help-dialog > header small { color: #6f8298; font: 9px/1 monospace; letter-spacing: .18em; }
.shortcut-help-dialog > header h2 { margin: 5px 0 0; font-size: 22px; font-weight: 500; }
.shortcut-help-dialog > header button {
  width: 30px; height: 30px; border: 0; color: #9ba9b8; background: transparent;
  font-size: 24px; cursor: pointer;
}
.shortcut-keyboard-image {
  overflow: hidden; margin: 0; border: 1px solid rgba(255,255,255,.14); border-radius: 8px;
  background: #333;
}
.shortcut-keyboard-image img {
  display: block; width: 100%; height: auto; max-height: calc(92vh - 150px); object-fit: contain;
}
.shortcut-help-dialog > footer {
  display: flex; justify-content: space-between; gap: 14px; margin-top: 14px;
  color: #8291a2; font-size: 10px;
}

/* ─── 座舱航线任务 ────────────────────────────────── */
.wayline-task-backdrop {
  position: absolute; z-index: 100; inset: 0; display: grid; place-items: center;
  padding: 24px; background: rgba(2,5,9,.8); backdrop-filter: blur(9px);
}
.wayline-task-dialog {
  display: flex; flex-direction: column; width: min(700px, 96vw); max-height: min(820px, 92vh);
  overflow: hidden; border: 1px solid rgba(63,169,255,.3); border-radius: 14px;
  color: #edf3fa; background: #080d15;
  box-shadow: 0 24px 80px rgba(0,0,0,.64), 0 0 34px rgba(63,169,255,.08);
}
.wayline-task-head {
  display: flex; align-items: flex-start; justify-content: space-between; gap: 18px;
  padding: 18px 20px 14px; border-bottom: 1px solid rgba(255,255,255,.08);
  background: linear-gradient(120deg, rgba(63,169,255,.12), transparent 52%);
}
.wayline-task-head small { color: #3fa9ff; font: 9px/1 monospace; letter-spacing: .18em; }
.wayline-task-head h2 { margin: 5px 0 0; font-size: 20px; font-weight: 550; }
.wayline-task-head p { margin: 6px 0 0; color: #8191a4; font: 10px/1.4 monospace; }
.wayline-task-head > button {
  width: 30px; height: 30px; padding: 0; border: 0; color: #9ba9b8; background: transparent;
  font-size: 24px; line-height: 1; cursor: pointer;
}
.wayline-task-head > button:disabled { opacity: .4; cursor: default; }
.wayline-task-form { min-height: 0; overflow: auto; padding: 16px 20px 18px; }
.wayline-task-route-field > label,
.wayline-task-grid label {
  display: flex; flex-direction: column; gap: 6px; color: #8fa0b3; font-size: 10px;
}
.wayline-task-route-field > div { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 8px; }
.wayline-task-route-field select,
.wayline-task-grid input,
.wayline-task-grid select {
  width: 100%; height: 36px; box-sizing: border-box; padding: 0 10px;
  border: 1px solid rgba(255,255,255,.14); border-radius: 7px;
  color: #e9f1fa; background: #101722; outline: none;
}
.wayline-task-route-field select:focus,
.wayline-task-grid input:focus,
.wayline-task-grid select:focus { border-color: #3fa9ff; box-shadow: 0 0 0 2px rgba(63,169,255,.12); }
.wayline-task-route-field button {
  min-width: 62px; border: 1px solid rgba(63,169,255,.3); border-radius: 7px;
  color: #8dcaff; background: rgba(63,169,255,.08); cursor: pointer;
}
.wayline-task-route-field button:disabled { opacity: .45; cursor: default; }
.wayline-task-route-card {
  display: grid; grid-template-columns: 38px minmax(0, 1fr); gap: 11px; align-items: center;
  margin-top: 12px; padding: 12px; border: 1px solid rgba(63,169,255,.2); border-radius: 8px;
  background: rgba(63,169,255,.055);
}
.wayline-route-icon {
  display: grid; place-items: center; width: 36px; height: 36px; border-radius: 8px;
  color: #64b9ff; background: rgba(63,169,255,.14); font-size: 22px;
}
.wayline-task-route-card strong { display: block; font-size: 13px; }
.wayline-task-route-card p { display: flex; flex-wrap: wrap; gap: 5px 12px; margin: 5px 0; color: #8292a6; font: 9px/1.4 monospace; }
.wayline-task-route-card small { color: #66778b; font-size: 9px; }
.wayline-task-empty {
  display: grid; gap: 5px; margin-top: 12px; padding: 22px;
  border: 1px dashed rgba(255,255,255,.13); border-radius: 8px;
  color: #708095; font-size: 10px; text-align: center;
}
.wayline-task-empty strong { color: #aab7c6; font-size: 12px; }
.wayline-task-grid {
  display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px;
  margin-top: 16px;
}
.wayline-task-defaults { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 14px; }
.wayline-task-defaults span {
  padding: 4px 7px; border: 1px solid rgba(53,214,164,.2); border-radius: 12px;
  color: #67d7b5; background: rgba(53,214,164,.07); font-size: 9px;
}
.wayline-task-confirm {
  display: grid; grid-template-columns: 16px minmax(0, 1fr); align-items: start; gap: 8px;
  margin-top: 14px; padding: 11px; border: 1px solid rgba(255,176,79,.24); border-radius: 8px;
  color: #c4b18f; background: rgba(255,176,79,.055); font-size: 10px; line-height: 1.5; cursor: pointer;
}
.wayline-task-confirm input { margin: 2px 0 0; accent-color: #3fa9ff; }
.wayline-task-drc-note,
.wayline-task-blocked,
.wayline-task-error { margin: 10px 0 0; padding: 8px 10px; border-radius: 7px; font-size: 10px; line-height: 1.45; }
.wayline-task-drc-note { color: #8dcaff; background: rgba(63,169,255,.08); }
.wayline-task-blocked { color: #ffc779; background: rgba(255,176,79,.09); }
.wayline-task-error { color: #ff9aa5; background: rgba(255,93,108,.09); }
.wayline-task-form footer { display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px; }
.wayline-task-form footer button {
  height: 36px; padding: 0 16px; border-radius: 7px; font-size: 11px; font-weight: 600; cursor: pointer;
}
.wayline-task-form footer button:disabled { opacity: .42; cursor: not-allowed; }
.wayline-task-cancel { border: 1px solid rgba(255,255,255,.13); color: #a9b5c3; background: rgba(255,255,255,.04); }
.wayline-task-submit { border: 1px solid #258fea; color: #fff; background: #087ff5; box-shadow: 0 0 16px rgba(8,127,245,.18); }
.wayline-task-submit:hover:not(:disabled) { background: #168bf8; }
.wayline-task-success {
  position: fixed; z-index: 1200; top: 56px; left: 50%; transform: translateX(-50%);
  display: grid; grid-template-columns: 24px minmax(0, 1fr); align-items: start; gap: 9px;
  width: min(520px, calc(100vw - 32px)); box-sizing: border-box; padding: 10px 12px;
  border: 1px solid rgba(53,214,164,.5); border-radius: 9px; color: #d7f6ed;
  background: rgba(6,30,24,.95); box-shadow: 0 10px 30px rgba(0,0,0,.38);
  text-align: left; backdrop-filter: blur(10px); cursor: pointer;
}
.wayline-task-success > span { display: grid; place-items: center; width: 22px; height: 22px; border-radius: 50%; color: #062119; background: #35d6a4; font-weight: 800; }
.wayline-task-success strong { display: block; margin-bottom: 2px; color: #6ee7c1; font-size: 11px; }
.wayline-task-success p { margin: 0; color: #b9dcd1; font-size: 10px; line-height: 1.45; }

.cockpit-error-toast {
  position: fixed; z-index: 1200; top: 56px; left: 50%;
  display: grid; grid-template-columns: 24px minmax(0, 1fr) 24px; align-items: start; gap: 9px;
  width: min(520px, calc(100vw - 32px)); box-sizing: border-box;
  padding: 10px 11px; border: 1px solid rgba(255,93,108,.5); border-radius: 9px;
  color: #f5d9dd; background: rgba(31,8,14,.94);
  box-shadow: 0 10px 30px rgba(0,0,0,.38), 0 0 0 1px rgba(255,93,108,.08);
  backdrop-filter: blur(10px); transform: translateX(-50%);
}
.cockpit-error-icon {
  display: grid; place-items: center; width: 22px; height: 22px;
  border-radius: 50%; color: #fff; background: #e94f61; font-weight: 800;
}
.cockpit-error-toast strong { display: block; margin-bottom: 2px; color: #ff9aa5; font-size: 11px; }
.cockpit-error-toast p { overflow-wrap: anywhere; margin: 0; color: #d9c1c6; font-size: 10px; line-height: 1.45; }
.cockpit-error-toast button {
  display: grid; place-items: center; width: 24px; height: 24px; padding: 0;
  border: 0; border-radius: 5px; color: #cdaeb4; background: transparent;
  font-size: 18px; line-height: 1; cursor: pointer;
}
.cockpit-error-toast button:hover { color: #fff; background: rgba(255,255,255,.08); }
.cockpit-toast-enter-active, .cockpit-toast-leave-active { transition: opacity .18s ease, transform .18s ease; }
.cockpit-toast-enter-from, .cockpit-toast-leave-to { opacity: 0; transform: translate(-50%, -8px); }

/* ─── 云端交互日志 ─────────────────────────────────── */
.interaction-log-backdrop {
  position: fixed; inset: 0; z-index: 1000;
  display: grid; place-items: center;
  padding: 28px;
  background: rgba(1,4,9,.76);
  backdrop-filter: blur(7px);
}
.interaction-log-dialog {
  display: flex; flex-direction: column;
  width: min(1180px, 96vw); height: min(760px, 90vh);
  overflow: hidden;
  border: 1px solid rgba(63,169,255,.24);
  border-radius: 16px;
  background: #080d15;
  box-shadow: 0 24px 90px rgba(0,0,0,.58), 0 0 30px rgba(63,169,255,.08);
}
.interaction-log-head {
  display: flex; align-items: flex-start; justify-content: space-between;
  padding: 18px 20px 14px;
  border-bottom: 1px solid rgba(255,255,255,.08);
  background: linear-gradient(120deg, rgba(63,169,255,.09), transparent 45%);
}
.interaction-log-head p { margin: 0 0 3px; color: #3fa9ff; font-size: 9px; letter-spacing: .2em; }
.interaction-log-head h2 { margin: 0; font-size: 18px; }
.interaction-log-head small { display: block; margin-top: 5px; color: var(--muted,#6b7789); font-size: 11px; }
.log-close { border: 0; color: #8e9bad; background: transparent; cursor: pointer; font-size: 28px; line-height: 1; }
.log-close:hover { color: white; }
.interaction-log-tools {
  display: grid; grid-template-columns: 130px minmax(220px, 1fr) auto auto;
  gap: 8px; padding: 10px 14px;
  border-bottom: 1px solid rgba(255,255,255,.06);
}
.interaction-log-tools select, .interaction-log-tools input, .interaction-log-tools button {
  height: 34px; border: 1px solid rgba(255,255,255,.1); border-radius: 7px;
  color: #dfe6f1; background: rgba(255,255,255,.045);
  padding: 0 10px; font-size: 12px;
}
.interaction-log-tools button { cursor: pointer; }
.interaction-log-tools button:hover, .interaction-log-tools button.active { color: #3fa9ff; border-color: rgba(63,169,255,.5); }
.interaction-log-tools button.danger:hover { color: #ff7b87; border-color: rgba(255,93,108,.5); }
.interaction-log-stats { padding: 6px 15px; color: #6b7789; background: rgba(0,0,0,.2); font-size: 10px; font-family: monospace; }
.interaction-log-stats span { margin-right: 8px; color: #35d6a4; font-weight: 700; }
.interaction-log-stats span.paused { color: #f5a623; }
.interaction-log-list { flex: 1; min-height: 0; overflow: auto; padding: 7px 10px 16px; }
.interaction-log-row { border-bottom: 1px solid rgba(255,255,255,.055); }
.interaction-log-row summary {
  display: grid; grid-template-columns: 92px 78px 22px minmax(240px,1.5fr) minmax(160px,1fr) auto auto;
  align-items: center; gap: 8px;
  min-height: 38px; padding: 3px 8px;
  cursor: pointer; list-style: none;
  font-size: 11px;
}
.interaction-log-row summary::-webkit-details-marker { display: none; }
.interaction-log-row summary:hover { background: rgba(63,169,255,.055); }
.interaction-log-row time { color: #718096; font-family: monospace; }
.interaction-log-row b { width: max-content; padding: 2px 6px; border-radius: 4px; font-size: 9px; letter-spacing: .06em; }
.transport-http { color: #b898ff; background: rgba(184,152,255,.12); }
.transport-mqtt { color: #35d6a4; background: rgba(53,214,164,.12); }
.transport-websocket { color: #3fa9ff; background: rgba(63,169,255,.12); }
.transport-system { color: #f5a623; background: rgba(245,166,35,.12); }
.interaction-log-row i { font-style: normal; font-size: 16px; text-align: center; }
.direction-in { color: #35d6a4; }
.direction-out { color: #3fa9ff; }
.direction-error { color: #ff5d6c; }
.direction-info { color: #8e9bad; }
.interaction-log-row code { overflow: hidden; color: #c8d3e1; text-overflow: ellipsis; white-space: nowrap; }
.interaction-log-row summary > span { overflow: hidden; color: #728095; text-overflow: ellipsis; white-space: nowrap; }
.interaction-log-row em { padding: 2px 5px; border-radius: 4px; color: #9aa8b9; background: rgba(255,255,255,.05); font-size: 9px; font-style: normal; font-family: monospace; }
.interaction-log-row pre {
  max-height: 330px; margin: 0 8px 10px; overflow: auto;
  padding: 12px 14px; border: 1px solid rgba(255,255,255,.06); border-radius: 7px;
  color: #9fb4c9; background: #050910;
  font: 10px/1.55 ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  white-space: pre-wrap; overflow-wrap: anywhere;
}
.interaction-log-row > p { margin: 0 8px 10px; color: #6b7789; font-size: 11px; }
.interaction-log-empty { display: grid; place-items: center; height: 180px; color: #596678; font-size: 13px; }

@media (max-width: 900px) {
  .interaction-log-backdrop { padding: 10px; }
  .interaction-log-dialog { width: 100%; height: 94vh; }
  .interaction-log-tools { grid-template-columns: 1fr 1fr; }
  .interaction-log-row summary { grid-template-columns: 76px 65px 18px minmax(150px,1fr); }
  .interaction-log-row summary > span, .interaction-log-row summary > em { display: none; }
}

/* ─── 左侧边栏 ────────────────────────────────────── */
.session-rail {
  grid-area: rail;
  display: flex; flex-direction: column; gap: 12px;
  padding: 14px 16px;
  border-right: 1px solid rgba(255,255,255,.06);
  background: #080b10;
  overflow-y: auto; overflow-x: hidden;
  /* 内边距随折叠收拢，避免 0 宽时残留空白条 */
  transition: padding .32s cubic-bezier(.4, 0, .2, 1);
}
/* 折叠态：裁切溢出内容并淡出，防止 0 宽时文字挤压闪烁 */
.cockpit-pro.rail-collapsed .session-rail { padding-left: 0; padding-right: 0; overflow: hidden; }
.rail-head, .rail-devices { transition: opacity .22s ease; }
.cockpit-pro.rail-collapsed .rail-head,
.cockpit-pro.rail-collapsed .rail-devices { opacity: 0; pointer-events: none; }
.rail-head { display: flex; align-items: center; justify-content: space-between; }
.rail-head strong { font-size: 15px; }
.rail-cnt { min-width: 26px; text-align: center; font-size: 11px; background: rgba(255,255,255,.08); border-radius: 10px; padding: 2px 8px; }
.rail-devices { display: flex; flex-direction: column; gap: 14px; flex: 1; }
.dev-card {
  overflow: hidden;
  border: 1px solid rgba(255,255,255,.11);
  border-radius: 7px;
  color: #f0f2f5;
  background: #1b1d20;
  cursor: pointer;
  box-shadow: 0 8px 22px rgba(0,0,0,.18);
  transition: border-color .15s, box-shadow .15s;
}
.dev-card:hover { border-color: rgba(45,139,255,.65); }
.dev-card.selected { border: 3px solid #087ff5; box-shadow: 0 0 0 1px rgba(8,127,245,.18); }
.dev-card-select {
  display: block; width: 100%; padding: 0; border: 0;
  color: inherit; background: transparent; text-align: left; cursor: pointer;
}
.dev-card-select:disabled { cursor: default; }
.dev-card-head {
  display: flex; align-items: center; justify-content: space-between;
  min-height: 48px; padding: 0 12px;
  border-bottom: 1px solid rgba(255,255,255,.08);
}
.dev-card-title { display: flex; align-items: center; gap: 9px; min-width: 0; }
.dev-role-status { display: inline-flex; align-items: center; gap: 4px; min-width: 0; }
.dev-role-status svg {
  width: 16px; height: 16px; flex: 0 0 auto;
  fill: none; stroke: currentColor; stroke-width: 1.7;
  stroke-linecap: round; stroke-linejoin: round;
}
.dev-role-status small { color: #7f8995; font-size: 9px; white-space: nowrap; }
.dev-role-status strong {
  overflow: hidden; font-size: 12px; font-weight: 600;
  text-overflow: ellipsis; white-space: nowrap;
}
.dev-status-sep { width: 1px; height: 18px; flex: 0 0 auto; background: rgba(255,255,255,.16); }
.rc-online { color: #35d678; }
.drone-status.flying { color: #35d678; }
.drone-status.standby { color: #ffd21a; }
.drone-status.offline { color: #ef3340; }
.dev-card-body { display: flex; flex-direction: column; gap: 12px; padding: 13px 12px 10px; }
.dev-identity { display: flex; align-items: center; min-width: 0; font-size: 13px; }
.dev-identity strong { flex-shrink: 0; font-weight: 600; }
.dev-identity span { width: 1px; height: 15px; margin: 0 9px; background: rgba(255,255,255,.25); }
.dev-identity em { overflow: hidden; color: #8f939b; font-style: normal; text-overflow: ellipsis; white-space: nowrap; }
.dev-vitals { display: flex; align-items: center; gap: 6px; font-size: 12px; }
.dev-vitals > b { width: 1px; height: 18px; background: rgba(255,255,255,.3); }
.dev-power-group { display: flex; align-items: center; gap: 7px; }
.dev-power-item { display: flex; align-items: center; gap: 3px; white-space: nowrap; }
.dev-power-item small { color: #737b86; font: 7px/1 monospace; }
.dev-power-item .dev-battery-icon { width: 14px; height: 9px; border-width: 1px; }
.dev-power-item .dev-battery-icon::after {
  top: 2px; right: -3px; width: 2px; height: 4px;
}
.dev-power-item strong { font-size: 11px; }
.dev-battery-icon {
  position: relative; width: 22px; height: 13px;
  border: 2px solid #d9dde2; border-radius: 2px;
}
.dev-battery-icon::after {
  content: ''; position: absolute; top: 3px; right: -5px;
  width: 3px; height: 6px; border-radius: 0 2px 2px 0; background: #d9dde2;
}
.dev-battery-icon i { display: block; height: 100%; max-width: 100%; background: #f3f5f7; }
.dev-signal-group { display: flex; align-items: flex-end; gap: 5px; }
.dev-signal-unit { display: flex; align-items: flex-end; gap: 2px; }
.dev-signal-unit small { color: #737b86; font: 7px/1 monospace; }
.dev-signal { display: flex; align-items: flex-end; gap: 3px; height: 19px; }
.dev-signal i { width: 3px; background: #646970; }
.dev-signal i:nth-child(1) { height: 5px; }
.dev-signal i:nth-child(2) { height: 9px; }
.dev-signal i:nth-child(3) { height: 14px; }
.dev-signal i:nth-child(4) { height: 19px; }
.dev-signal i.on { background: #f0f2f5; }
.dev-actions { display: flex; justify-content: flex-end; gap: 6px; padding: 1px 12px 12px; }
.dev-actions button {
  display: inline-flex; align-items: center; justify-content: center; gap: 5px;
  flex: 0 0 auto; min-width: 62px; height: 27px; padding: 0 10px;
  border-radius: 6px; cursor: pointer; font-size: 11px; line-height: 1;
  font-weight: 600; letter-spacing: .04em;
  backdrop-filter: blur(5px); -webkit-backdrop-filter: blur(5px);
  transition: border-color .15s, background .15s, color .15s, transform .12s, box-shadow .15s;
}
.dev-actions button:active:not(:disabled) { transform: translateY(1px); }
.dev-actions button:disabled { opacity: .38; cursor: not-allowed; }
.dev-action-icon { font: 14px/1 monospace; transform: translateY(-.5px); }
.dev-action-icon.stop { font-size: 8px; }
/* 航线任务：青绿色，区别于定位与紧急制动 */
.dev-action-wayline {
  border: 1px solid rgba(53,214,164,.34); color: #69dfbc; background: rgba(53,214,164,.09);
}
.dev-action-wayline:hover:not(:disabled) { border-color: #35d6a4; background: rgba(53,214,164,.18); box-shadow: 0 0 12px rgba(53,214,164,.2); }
/* 定位：冷蓝毛玻璃 */
.dev-action-locate {
  border: 1px solid rgba(63,169,255,.3); color: #7fc4ff; background: rgba(63,169,255,.1);
}
.dev-action-locate:hover:not(:disabled) { border-color: #3fa9ff; background: rgba(63,169,255,.2); box-shadow: 0 0 12px rgba(63,169,255,.25); }
/* 急停：危险红 */
.dev-action-stop {
  border: 1px solid rgba(255,93,108,.4); color: #ff7b87; background: rgba(255,93,108,.1); font-weight: 700;
}
.dev-action-stop:hover:not(:disabled) { border-color: #ff5d6c; background: rgba(255,93,108,.22); box-shadow: 0 0 12px rgba(255,93,108,.3); }
.rail-empty { text-align: center; padding: 20px 0; color: var(--muted, #6b7789); font-size: 13px; }
.rail-empty small { display: block; margin-top: 4px; font-size: 11px; }
/* ─── 地图区 ──────────────────────────────────────── */
.cockpit-map-section {
  grid-area: map;
  position: relative;
  overflow: hidden;
  background: #060b12;
}
.cx-canvas { position: absolute; inset: 0; }

/* 地图右上角工具组：样式切换 + 定位，竖排浮动 */
.map-tools {
  position: absolute; top: 10px; right: 10px; z-index: 6;
  display: flex; flex-direction: column; gap: 6px;
}
.map-tool {
  display: grid; place-items: center; width: 34px; height: 34px;
  border: 1px solid rgba(255,255,255,.16); border-radius: 8px;
  color: #e8eff7; background: rgba(3,8,14,.7);
  cursor: pointer; backdrop-filter: blur(5px); -webkit-backdrop-filter: blur(5px);
  font-size: 16px; line-height: 1; transition: border-color .15s, background .15s, transform .12s;
}
.map-tool:hover { border-color: #3fa9ff; background: rgba(20,28,40,.85); transform: translateY(-1px); }
.map-tool.active { border-color: rgba(63,169,255,.5); color: #3fa9ff; box-shadow: 0 0 0 1px rgba(63,169,255,.2); }
.map-target-tool { position: relative; font: 700 12px/1 ui-sans-serif, system-ui, sans-serif; }
.map-target-tool.executing { color: #35d6a4; border-color: rgba(53,214,164,.65); box-shadow: 0 0 12px rgba(53,214,164,.22); }
.map-target-tool.executing::after {
  content: ''; position: absolute; top: -3px; right: -3px; width: 6px; height: 6px;
  border-radius: 50%; background: #35d6a4; box-shadow: 0 0 7px #35d6a4;
}
.point-flight-map-status {
  position: absolute; z-index: 11; top: 10px; left: 50%; transform: translateX(-50%);
  display: grid; grid-template-columns: 10px minmax(0, 1fr) auto auto; align-items: center; gap: 9px;
  width: max-content; max-width: calc(100% - 150px); box-sizing: border-box; padding: 7px 9px;
  border: 1px solid rgba(53,214,164,.42); border-radius: 8px; color: #dffbf2;
  background: rgba(4,22,18,.9); box-shadow: 0 6px 20px rgba(0,0,0,.34), 0 0 18px rgba(53,214,164,.08);
  backdrop-filter: blur(8px);
}
.point-flight-map-status > i {
  width: 8px; height: 8px; border-radius: 50%; background: #35d6a4;
  box-shadow: 0 0 8px #35d6a4; animation: blink 1.2s ease infinite;
}
.point-flight-map-status > div { min-width: 0; }
.point-flight-map-status strong { display: block; color: #73e9c5; font-size: 11px; }
.point-flight-map-status span {
  display: block; overflow: hidden; margin-top: 2px; color: #91b9ad;
  font: 9px/1.3 ui-monospace, SFMono-Regular, Menlo, monospace; text-overflow: ellipsis; white-space: nowrap;
}
.point-flight-map-status button {
  height: 25px; padding: 0 8px; border: 1px solid rgba(53,214,164,.3); border-radius: 5px;
  color: #8debcf; background: rgba(53,214,164,.08); font-size: 9px; white-space: nowrap; cursor: pointer;
}
.point-flight-map-status button.stop { color: #ff9aa5; border-color: rgba(255,93,108,.34); background: rgba(255,93,108,.08); }
.point-flight-map-status button:disabled { opacity: .4; cursor: not-allowed; }
.point-flight-map-status.disconnected { border-color: rgba(255,176,79,.42); background: rgba(28,18,5,.92); }
.point-flight-map-status.disconnected > i { background: #ffb04f; box-shadow: 0 0 8px #ffb04f; }
.point-flight-map-status.disconnected strong { color: #ffc779; }

/* 无 Key 时的备用指南针 */
.cx-fallback { position: absolute; inset: 0; display: grid; place-items: center; }
.fb-compass { position: relative; width: 110px; height: 110px; border: 1px solid rgba(255,255,255,.12); border-radius: 50%; }
.fb-n,.fb-s,.fb-w,.fb-e { position: absolute; font-size: 11px; font-weight: 700; }
.fb-n { top:6px;left:50%;transform:translateX(-50%);color:#ff5d6c; }
.fb-s { bottom:6px;left:50%;transform:translateX(-50%);color:var(--muted,#6b7789); }
.fb-w { left:6px;top:50%;transform:translateY(-50%);color:var(--muted,#6b7789); }
.fb-e { right:6px;top:50%;transform:translateY(-50%);color:var(--muted,#6b7789); }
.fb-arrow { position: absolute; font-size: 28px; color: #3fa9ff; pointer-events: none; }
.fb-tip { position: absolute; bottom: 20px; left: 50%; transform: translateX(-50%); font-size: 11px; color: var(--muted,#6b7789); white-space: nowrap; }

/* 飞行数据 HUD */
.cx-hud { position: absolute; bottom: 12px; left: 12px; display: flex; gap: 8px; flex-wrap: wrap; z-index: 5; }
.hud-chip { background: rgba(5,9,16,.82); border: 1px solid rgba(255,255,255,.1); border-radius: 8px; padding: 6px 10px; display: flex; flex-direction: column; align-items: center; min-width: 52px; }
.hud-chip span { font-size: 9px; color: var(--muted,#6b7789); letter-spacing: .1em; }
.hud-chip strong { font-size: 15px; color: var(--text,#dfe6f1); }
.hud-chip.bat-low strong { color: #ff5d6c; }

/* 无人机与测距目标坐标 */
.cx-location-stack {
  position: absolute; z-index: 5; top: 10px; left: 12px;
  display: flex; flex-direction: column; align-items: flex-start; gap: 5px;
  max-width: calc(100% - 70px);
  font: 10px/1.25 ui-monospace, SFMono-Regular, Menlo, monospace;
}
.cx-coords,
.cx-measure-target {
  border: 1px solid rgba(255,255,255,.1); border-radius: 6px;
  color: rgba(255,255,255,.65); background: rgba(5,9,16,.74);
  box-shadow: 0 3px 10px rgba(0,0,0,.16); backdrop-filter: blur(5px);
}
.cx-coords { display: inline-flex; align-items: center; gap: 7px; padding: 4px 9px; font-size: 11px; }
.cx-coords strong { color: #63e6bc; font: 700 9px/1 ui-sans-serif, system-ui, sans-serif; }
.cx-coords.is-remote-controller { border-color: rgba(154,140,255,.28); }
.cx-coords.is-remote-controller strong { color: #aa9fff; }
.cx-measure-target {
  display: flex; align-items: center; flex-wrap: wrap; gap: 5px 9px; padding: 5px 7px 5px 9px;
}
.cx-measure-target > span { display: inline-flex; align-items: center; gap: 5px; white-space: nowrap; }
.cx-measure-target strong { color: #68baff; font: 700 9px/1 ui-sans-serif, system-ui, sans-serif; }
.cx-measure-target button {
  padding: 2px 6px; border: 1px solid rgba(63,169,255,.4); border-radius: 4px;
  color: #75bdff; background: rgba(63,169,255,.1); font-size: 8px; cursor: pointer;
}
.cx-measure-target button:hover { border-color: #3fa9ff; color: #eaf5ff; }
.map-target-panel {
  position: absolute; z-index: 12; left: 50%; bottom: 14px; transform: translateX(-50%);
  width: min(360px, calc(100% - 28px)); box-sizing: border-box;
  padding: 11px; border: 1px solid rgba(63,169,255,.38); border-radius: 9px;
  color: #dce8f4; background: rgba(5,11,19,.93);
  box-shadow: 0 10px 28px rgba(0,0,0,.5); backdrop-filter: blur(10px);
}
.map-target-panel header { display: flex; align-items: center; justify-content: space-between; }
.map-target-panel header > div { display: flex; flex-direction: column; gap: 1px; }
.map-target-panel header small { color: #3fa9ff; font: 8px/1 monospace; letter-spacing: .1em; }
.map-target-panel header strong { font-size: 13px; }
.map-target-panel header button { border: 0; color: #8090a2; background: transparent; font-size: 18px; cursor: pointer; }
.map-target-panel > p { margin: 7px 0; color: #6f8194; font-size: 9px; }
.map-target-coordinates,
.map-target-params { display: grid; grid-template-columns: 1fr 1fr; gap: 6px; margin-bottom: 6px; }
.map-target-panel label { display: flex; flex-direction: column; gap: 3px; color: #7f91a5; font-size: 8px; }
.map-target-panel label > span { display: flex; align-items: center; gap: 4px; }
.map-target-panel input {
  width: 100%; min-width: 0; height: 29px; box-sizing: border-box;
  padding: 0 7px; border: 1px solid rgba(255,255,255,.12); border-radius: 4px;
  outline: none; color: #edf4fb; background: #101823; font: 10px/1 monospace;
}
.map-target-panel input:focus { border-color: #3fa9ff; }
.map-target-actions { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.map-target-actions > span { color: #ffb04f; font-size: 9px; }
.map-target-actions > span.valid { color: #35d6a4; }
.map-target-actions button {
  min-width: 112px; height: 30px; border: 1px solid rgba(63,169,255,.48); border-radius: 5px;
  color: #eaf5ff; background: rgba(63,169,255,.16); font-size: 10px; cursor: pointer;
}
.map-target-actions button:disabled { opacity: .38; cursor: not-allowed; }

/* ─── 右侧面板 ────────────────────────────────────── */
.flight-view {
  grid-area: flight;
  display: flex; flex-direction: column; gap: 0;
  border-left: 1px solid rgba(255,255,255,.06);
  background: rgba(6,10,18,.95);
  overflow: hidden;
}
.lens-bar { display: flex; align-items: center; gap: 7px; min-height: 42px; padding: 5px 8px; border-bottom: 1px solid rgba(255,255,255,.06); flex-shrink: 0; background: #080d15; }
.payload-authority-switch {
  display: inline-flex; align-items: center; gap: 6px;
  padding-inline: 8px !important; color: #8fa4b8 !important;
  white-space: nowrap;
}
.payload-authority-switch i {
  position: relative; width: 24px; height: 13px; flex: 0 0 auto;
  border-radius: 8px; background: #313b48;
  box-shadow: inset 0 0 0 1px rgba(255,255,255,.1);
}
.payload-authority-switch i::after {
  content: ''; position: absolute; left: 2px; top: 2px;
  width: 9px; height: 9px; border-radius: 50%;
  background: #8b98a8; transition: transform .18s, background .18s;
}
.lens-bar button.payload-authority-switch.active {
  border-color: rgba(53,214,164,.55) !important;
  color: #35d6a4 !important; background: rgba(53,214,164,.1) !important;
}
.payload-authority-switch.active i { background: rgba(53,214,164,.35); }
.payload-authority-switch.active i::after { transform: translateX(11px); background: #35d6a4; }
.payload-authority-switch:disabled:not(.active) { opacity: .5; cursor: wait; }
.target-detect-switch i {
  width: 7px; height: 7px; border-radius: 50%; background: #596878;
  box-shadow: 0 0 0 3px rgba(89,104,120,.12);
}
.lens-bar button.target-detect-switch.active {
  color: #35d6a4; border-color: rgba(53,214,164,.55);
  background: rgba(53,214,164,.09); box-shadow: 0 0 10px rgba(53,214,164,.13);
}
.target-detect-switch.active i { background: #35d6a4; box-shadow: 0 0 7px #35d6a4; }
.lens-group { display: inline-flex; gap: 2px; padding: 2px; border-radius: 8px; background: rgba(255,255,255,.035); }
.lens-group + .lens-group { border-left: 1px solid rgba(255,255,255,.07); border-radius: 0 8px 8px 0; padding-left: 7px; }
.lens-bar button { position: relative; display: inline-flex; align-items: center; gap: 5px; font-size: 12px; padding: 4px 10px; border-radius: 7px; border: 1px solid rgba(255,255,255,.08); background: transparent; color: var(--muted,#6b7789); cursor: pointer; transition: color .15s, border-color .15s, background .15s, box-shadow .15s, transform .12s; }
.lens-bar button:hover { color: #dfe6f1; border-color: rgba(63,169,255,.35); transform: translateY(-1px); }
.lens-bar button:active { transform: translateY(0); }
.lens-bar button.active { border-color: #3fa9ff; color: #3fa9ff; background: rgba(63,169,255,.1); box-shadow: 0 0 0 1px rgba(63,169,255,.22), 0 0 12px rgba(63,169,255,.18); }
.lens-bar button.thermal.active { border-color: #ff9d37; color: #ffb04f; background: linear-gradient(135deg, rgba(115,42,157,.22), rgba(255,114,33,.14)); box-shadow: 0 0 0 1px rgba(255,157,55,.22), 0 0 12px rgba(255,114,33,.2); }
/* 在播镜头脉冲指示点：跟随 live_status 实时点亮 */
.lens-live-dot { width: 6px; height: 6px; border-radius: 50%; flex: 0 0 auto; background: #35d6a4; box-shadow: 0 0 6px #35d6a4; animation: lens-pulse 1.4s ease-in-out infinite; }
.lens-bar button.thermal .lens-live-dot { background: #ffb04f; box-shadow: 0 0 6px #ffb04f; }
@keyframes lens-pulse { 0%, 100% { opacity: 1; transform: scale(1); } 50% { opacity: .35; transform: scale(.65); } }
.quality-group button { font-family: monospace; }

/* 视频区 */
.video-box {
  position: relative;
  container-type: inline-size;
  flex: 0 0 auto;
  width: 100%;
  min-height: 0;
  overflow: hidden;
  background: #040810;
}
.target-detection-layer { position: absolute; inset: 0; pointer-events: none; z-index: 5; }
.target-detection-box {
  position: absolute; min-width: 18px; min-height: 18px;
  border: 1.5px solid #35d6a4; box-shadow: 0 0 8px rgba(53,214,164,.35), inset 0 0 8px rgba(53,214,164,.08);
}
.target-detection-box::before,
.target-detection-box::after {
  content: ''; position: absolute; width: 9px; height: 9px; border-color: #fff;
}
.target-detection-box::before { left: -2px; top: -2px; border-left: 2px solid; border-top: 2px solid; }
.target-detection-box::after { right: -2px; bottom: -2px; border-right: 2px solid; border-bottom: 2px solid; }
.target-detection-box > span {
  position: absolute; left: -1px; bottom: 100%; padding: 2px 5px;
  background: #35d6a4; color: #04110d; font-size: 9px; font-weight: 700; white-space: nowrap;
}
.target-detection-box > span small { font-size: 8px; opacity: .72; }
.target-detection-box.danger { border-color: #ff5d6c; box-shadow: 0 0 9px rgba(255,93,108,.45); }
.target-detection-box.danger > span { background: #ff5d6c; color: #fff; }
.target-detection-summary {
  position: absolute; z-index: 7; top: 8px; left: 82px;
  width: min(220px, calc(100% - 136px)); box-sizing: border-box;
  padding: 5px 7px 6px; border: 1px solid rgba(118,75,255,.5); border-radius: 7px;
  color: #dce8f4;
  background:
    radial-gradient(circle at 80% 0, rgba(203,35,186,.18), transparent 55%),
    linear-gradient(135deg, rgba(19,25,47,.94), rgba(20,10,42,.88));
  box-shadow: inset 0 0 18px rgba(90,46,211,.12), 0 6px 18px rgba(0,0,0,.26);
  backdrop-filter: blur(7px); pointer-events: auto;
  transition: width .18s ease, padding .18s ease, border-color .18s ease;
}
.target-detection-summary-toggle {
  display: flex; align-items: center; gap: 5px; width: 100%; margin: 0 0 5px; padding: 0;
  border: 0; color: inherit; background: transparent; cursor: pointer;
  color: #c8d5e4; font-size: 9px; line-height: 1;
}
.target-detection-summary-toggle:hover { color: #fff; }
.target-detection-summary-toggle > span { color: #b8c7d9; font-size: 14px; transform: translateY(-1px); }
.target-detection-summary-toggle strong { font-weight: 600; letter-spacing: .03em; white-space: nowrap; }
.target-detection-summary-toggle strong em {
  display: inline-grid; place-items: center; min-width: 17px; height: 17px; margin-left: 4px;
  padding: 0 4px; box-sizing: border-box; border-radius: 9px;
  color: #04110d; background: #43edbb; font: 700 9px/1 ui-monospace, SFMono-Regular, Menlo, monospace;
}
.target-detection-summary-toggle i {
  width: 5px; height: 5px; margin-left: auto; border-radius: 50%;
  background: #35d6a4; box-shadow: 0 0 7px #35d6a4;
  animation: lens-pulse 1.4s ease-in-out infinite;
}
.target-detection-summary-counts { display: flex; align-items: center; justify-content: space-between; gap: 5px; }
.target-detection-summary-counts > span {
  display: inline-flex; align-items: center; gap: 3px; min-width: 0;
  color: #778da2; font: 700 9px/1 ui-monospace, SFMono-Regular, Menlo, monospace;
  transition: color .18s, text-shadow .18s;
}
.target-detection-summary b {
  display: grid; place-items: center; min-width: 17px; height: 17px; padding: 0 3px;
  box-sizing: border-box; white-space: nowrap;
  border-radius: 4px; color: #7f94a9; background: rgba(255,255,255,.045);
  font: 700 8px/1 ui-sans-serif, system-ui, sans-serif;
}
.target-detection-summary em { font-style: normal; }
.target-detection-summary-counts > span.active { color: #43edbb; text-shadow: 0 0 6px rgba(53,214,164,.5); }
.target-detection-summary-counts > span.active b { color: #04110d; background: #43edbb; }
.target-detection-summary .type-hazard.active { color: #ff7b87; text-shadow: 0 0 6px rgba(255,93,108,.55); }
.target-detection-summary .type-hazard.active b { color: #fff; background: #e94f61; }
.target-detection-summary.collapsed { width: auto; padding: 0; border-color: rgba(118,75,255,.38); }
.target-detection-summary.collapsed .target-detection-summary-toggle { margin: 0; padding: 5px 7px; }
@container (max-width: 480px) {
  .target-detection-summary { left: 76px; width: min(190px, calc(100% - 120px)); padding-inline: 5px; }
  .target-detection-summary.collapsed { width: auto; padding-inline: 0; }
  .target-detection-summary-toggle { margin-bottom: 4px; font-size: 8px; }
  .target-detection-summary-counts { gap: 3px; }
  .target-detection-summary-counts > span { gap: 2px; font-size: 8px; }
  .target-detection-summary b { min-width: 15px; height: 15px; padding-inline: 2px; font-size: 7px; }
}
.video-box:fullscreen { width: 100vw; height: 100vh; aspect-ratio: auto !important; background: #000; }
.video-box video { width: 100%; height: 100%; object-fit: cover; opacity: 0; transition: opacity .18s ease; }
.video-box:fullscreen video {
  object-fit: contain;
  object-position: center center;
  background: #000;
}
.video-box video.ready { opacity: 1; }
.camera-actions {
  position: absolute; z-index: 6; top: 50%; right: 9px; transform: translateY(-50%);
  display: flex; flex-direction: column; gap: 6px;
}
.camera-actions button {
  display: grid; place-items: center; width: 34px; height: 34px;
  border: 1px solid rgba(255,255,255,.19); border-radius: 8px;
  color: #e8eff7; background: rgba(3,8,14,.68); cursor: pointer; backdrop-filter: blur(5px);
}
.camera-actions button:hover, .camera-actions button.active, .camera-actions button.recording {
  border-color: #ff5d6c; color: #ff7b87;
}
.camera-actions button:disabled { opacity: .48; cursor: wait; }
.video-fullscreen-btn {
  position: absolute; z-index: 7; top: 9px; right: 9px;
  display: grid; place-items: center; width: 34px; height: 34px;
  padding: 0; border: 1px solid rgba(255,255,255,.19); border-radius: 8px;
  color: #e8eff7; background: rgba(3,8,14,.68);
  font-size: 16px; line-height: 1; cursor: pointer; backdrop-filter: blur(5px);
  transition: color .15s, border-color .15s, background .15s, transform .12s;
}
.video-fullscreen-btn:hover {
  color: #3fa9ff; border-color: rgba(63,169,255,.65);
  background: rgba(11,25,40,.82); transform: translateY(-1px);
}
.video-fullscreen-btn:active { transform: translateY(0); }
.video-status-badges {
  position: absolute; z-index: 6; top: 8px; left: 10px;
  display: flex; align-items: center; gap: 5px;
}
.video-obstacle-hud {
  --obstacle-color: #43d9ff;
  position: absolute; z-index: 5; inset: 0; color: var(--obstacle-color);
  font: 700 10px/1 ui-monospace, SFMono-Regular, Menlo, monospace;
  pointer-events: none;
}
.obstacle-hud-state {
  position: absolute; top: 8px; left: 50%; transform: translateX(-50%);
  display: inline-flex; align-items: center; gap: 5px; padding: 4px 7px;
  border: 1px solid rgba(255,176,79,.32); border-radius: 5px;
  color: #ffbd63; background: rgba(5,10,15,.68); backdrop-filter: blur(5px);
  white-space: nowrap;
}
.obstacle-hud-state i { width: 6px; height: 6px; border-radius: 50%; background: #ffb04f; box-shadow: 0 0 6px rgba(255,176,79,.75); }
.video-obstacle-hud.ready .obstacle-hud-state { border-color: rgba(53,214,164,.3); color: #62e9bd; }
.video-obstacle-hud.ready .obstacle-hud-state i { background: #35d6a4; box-shadow: 0 0 6px rgba(53,214,164,.75); }
.video-obstacle-hud.disabled .obstacle-hud-state { border-color: rgba(255,93,108,.38); color: #ff7b87; }
.video-obstacle-hud.disabled .obstacle-hud-state i { background: #ff5d6c; box-shadow: 0 0 6px rgba(255,93,108,.75); }
.obstacle-rail { position: absolute; display: grid; gap: clamp(5px, 1.6vw, 18px); filter: drop-shadow(0 1px 2px #000); }
.obstacle-rail-front, .obstacle-rail-rear {
  left: 21%; right: 21%; grid-template-columns: repeat(4, minmax(0, 1fr));
}
.obstacle-rail-front { top: 54px; }
.obstacle-rail-rear { bottom: 58px; }
.obstacle-rail-left, .obstacle-rail-right {
  top: 31%; bottom: 31%; grid-template-rows: repeat(3, minmax(0, 1fr)); gap: clamp(7px, 2vh, 20px);
}
.obstacle-rail-left { left: 12%; }
.obstacle-rail-right { right: calc(12% + 38px); }
.obstacle-rail-summary {
  position: absolute; z-index: 1; padding: 3px 5px; border-radius: 4px;
  color: var(--obstacle-color); background: rgba(2,8,14,.72); white-space: nowrap;
  box-shadow: inset 0 0 0 1px color-mix(in srgb, currentColor 22%, transparent);
}
.obstacle-rail-front .obstacle-rail-summary {
  bottom: calc(100% + 5px); left: 50%; transform: translateX(-50%);
}
.obstacle-rail-rear .obstacle-rail-summary {
  top: calc(100% + 5px); left: 50%; transform: translateX(-50%);
}
.obstacle-rail-left .obstacle-rail-summary {
  top: 50%; left: calc(100% + 5px); transform: translateY(-50%);
}
.obstacle-rail-right .obstacle-rail-summary {
  top: 50%; right: calc(100% + 5px); transform: translateY(-50%);
}
.obstacle-segment { display: flex; align-items: center; justify-content: center; min-width: 0; color: var(--obstacle-color); }
.obstacle-segment span {
  padding: 2px 4px; border-radius: 4px; color: currentColor; background: rgba(2,8,14,.69);
  box-shadow: inset 0 0 0 1px color-mix(in srgb, currentColor 22%, transparent); white-space: nowrap;
}
.obstacle-segment small { margin-left: 1px; font-size: .75em; opacity: .72; }
.obstacle-segment i { display: block; flex: 1 1 auto; border-radius: 4px; background: currentColor; box-shadow: 0 0 8px currentColor; }
.obstacle-segment-horizontal { flex-direction: column; gap: 3px; }
.obstacle-segment-horizontal i { width: 100%; min-width: 24px; height: 3px; }
.obstacle-segment-vertical { gap: 3px; }
.obstacle-segment-vertical i { width: 3px; height: 100%; min-height: 28px; }
.obstacle-rail-left .obstacle-segment { flex-direction: row-reverse; }
.obstacle-rail-left .obstacle-segment span, .obstacle-rail-right .obstacle-segment span { writing-mode: vertical-rl; padding: 4px 2px; }
.obstacle-rail-left .obstacle-segment span { transform: rotate(180deg); }
.obstacle-vertical-pair {
  position: absolute; bottom: 74px; left: 50%; transform: translateX(-50%); display: flex; gap: 7px;
}
.obstacle-vertical-pair span {
  padding: 4px 6px; box-sizing: border-box; border-radius: 4px;
  color: var(--obstacle-color); background: rgba(2,8,14,.67); text-align: center; white-space: nowrap;
  filter: drop-shadow(0 1px 2px #000);
}
.video-obstacle-hud .clear { color: #43d9ff; }
.video-obstacle-hud .caution { color: #f1dd55; }
.video-obstacle-hud .warning { color: #ffad45; }
.video-obstacle-hud .danger { color: #ff5d6c; animation: obstacle-danger-pulse .8s ease-in-out infinite alternate; }
.video-obstacle-hud .unknown { color: rgba(127,154,174,.48); }
.video-obstacle-hud .unknown i { box-shadow: none; opacity: .32; }
.video-obstacle-hud .unknown span { opacity: .68; }
@keyframes obstacle-danger-pulse { from { opacity: .72; } to { opacity: 1; filter: drop-shadow(0 0 7px rgba(255,93,108,.8)); } }
.gimbal-pitch-hud {
  position: absolute; z-index: 6; top: 32px; left: 10px;
  display: inline-flex; align-items: center; gap: 5px;
  padding: 4px 7px; box-sizing: border-box; border: 1px solid rgba(73,190,255,.3); border-radius: 6px;
  color: #e8f6ff; background: rgba(2,9,16,.72); box-shadow: inset 0 0 12px rgba(45,161,231,.07);
  font: 700 10px/1 ui-monospace, SFMono-Regular, Menlo, monospace;
  white-space: nowrap; backdrop-filter: blur(5px); pointer-events: none;
}
.gimbal-pitch-hud span { color: #7fc9f5; font: 600 9px/1 ui-sans-serif, system-ui, sans-serif; }
.gimbal-pitch-hud strong { min-width: 37px; color: #f2f8fd; font: inherit; text-align: right; }
.gimbal-pitch-hud.unavailable { border-color: rgba(255,255,255,.14); }
.gimbal-pitch-hud.unavailable span { color: #667586; }
.gimbal-pitch-hud.unavailable strong { color: #7f91a4; }
.measure-overlay {
  position: relative; z-index: 6; flex: 0 0 auto; margin-left: auto;
  min-width: 132px; box-sizing: border-box;
  padding: 2px 6px; border: 1px solid rgba(255,255,255,.12); border-radius: 5px;
  color: #aab8c7; background: rgba(3,8,14,.5);
  font-size: 8px;
}
.measure-overlay-head { display: flex; align-items: center; justify-content: space-between; gap: 7px; }
.measure-overlay-head span { display: inline-flex; align-items: center; gap: 5px; white-space: nowrap; }
.measure-overlay-head i { width: 6px; height: 6px; border-radius: 50%; background: #667586; }
.measure-overlay-head strong { color: #edf4fa; font: 700 11px/1 monospace; white-space: nowrap; }
.measure-overlay.is-active { border-color: rgba(53,214,164,.35); }
.measure-overlay.is-active .measure-overlay-head i { background: #35d6a4; box-shadow: 0 0 6px #35d6a4; }
.measure-overlay.is-warn { border-color: rgba(255,176,79,.42); }
.measure-overlay.is-warn .measure-overlay-head i { background: #ffb04f; box-shadow: 0 0 6px #ffb04f; }
.recording-time {
  padding: 3px 7px; border-radius: 4px; color: white; background: #e23947;
  font: 10px monospace;
}
.camera-action-tip {
  position: absolute; z-index: 7; top: 12px; left: 50%; transform: translateX(-50%);
  padding: 5px 10px; border: 1px solid rgba(53,214,164,.42); border-radius: 6px;
  color: #63e6bc; background: rgba(3,18,20,.84); font-size: 11px; backdrop-filter: blur(5px);
}
.video-ph { position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 6px; }
.video-ph span { font-size: 28px; font-weight: 900; color: rgba(63,169,255,.3); }
.video-ph p { font-size: 12px; color: var(--muted,#6b7789); }
.video-badge { background: #ff5d6c; color: white; font-size: 10px; font-weight: 700; padding: 2px 8px; border-radius: 4px; letter-spacing: .1em; }
.video-metrics { position: absolute; left: 8px; right: 8px; bottom: 8px; display: flex; align-items: center; gap: 6px; padding: 5px 7px; border-radius: 7px; color: rgba(255,255,255,.8); background: rgba(2,6,12,.72); font-size: 10px; backdrop-filter: blur(4px); }
.video-metrics-actions, .video-stream-meta { display: flex; align-items: center; gap: 6px; min-width: 0; }
.video-metrics-actions { flex: 0 0 auto; }
.video-stream-meta { overflow: hidden; white-space: nowrap; }
.video-stream-meta span { padding-right: 6px; border-right: 1px solid rgba(255,255,255,.14); }
.video-stream-meta span.confirmed { color: #35d6a4; }
.video-metrics-actions button { padding: 2px 7px; border: 1px solid rgba(255,93,108,.45); border-radius: 5px; color: #ff7b87; background: rgba(255,93,108,.12); cursor: pointer; font-size: 10px; white-space: nowrap; }
.video-metrics-actions button.retry { border-color: rgba(63,169,255,.5); color: #65b7ff; background: rgba(63,169,255,.12); }
.video-metrics-actions button:disabled { opacity: .5; cursor: wait; }
@container (max-width: 480px) {
  .obstacle-hud-state { top: 5px; padding: 3px 5px; font-size: 8px; }
  .obstacle-rail-front { top: 43px; }
  .obstacle-rail-rear { bottom: 48px; }
  .obstacle-rail-front, .obstacle-rail-rear { left: 18%; right: 18%; gap: 4px; }
  .obstacle-rail-left, .obstacle-rail-right { top: 30%; bottom: 30%; gap: 5px; }
  .obstacle-rail-left { left: 9%; }
  .obstacle-rail-right { right: calc(9% + 34px); }
  .obstacle-rail-summary { padding: 2px 4px; font-size: 8px; }
  .obstacle-segment span { padding: 2px 3px; font-size: 7px; }
  .obstacle-segment-horizontal i { min-width: 14px; height: 2px; }
  .obstacle-segment-vertical i { width: 2px; min-height: 18px; }
  .obstacle-rail-left .obstacle-segment span, .obstacle-rail-right .obstacle-segment span { padding: 3px 1px; }
  .obstacle-vertical-pair { bottom: 59px; gap: 5px; }
  .obstacle-vertical-pair span { padding: 3px 4px; font-size: 8px; }
  .gimbal-pitch-hud { top: 29px; left: 8px; gap: 4px; padding: 3px 5px; font-size: 9px; }
  .gimbal-pitch-hud strong { min-width: 33px; }
  .video-metrics {
    gap: 4px; padding: 3px 4px; font-size: 8px;
  }
  .video-metrics-actions, .video-stream-meta { gap: 3px; }
  .video-metrics-actions button { padding: 1px 4px; font-size: 8px; }
  .video-stream-meta { flex: 1 1 auto; }
  .video-stream-meta span { flex: 0 0 auto; padding-right: 3px; }
  .measure-overlay { min-width: 0; margin-left: 0; padding: 1px 3px; font-size: 7px; }
  .measure-overlay-head { gap: 3px; }
  .measure-overlay-head span { gap: 3px; }
  .measure-overlay-head i { width: 5px; height: 5px; }
  .measure-overlay-head strong { font-size: 9px; }
}
.zoom-scale {
  position: absolute; left: 8px; top: 50%; bottom: auto;
  height: 160px;
  width: 68px; z-index: 5; color: rgba(255,255,255,.92);
  transform: translateY(-50%);
  filter: drop-shadow(0 1px 2px rgba(0,0,0,.92));
  pointer-events: none;
}
.zoom-scale.collapsed { height: 24px; width: 52px; }
.zoom-rail {
  position: absolute; top: 0; bottom: 0; left: 24px; width: 1px;
  background: rgba(255,255,255,.42);
  box-shadow: 0 0 1px rgba(0,0,0,.8);
}
.zoom-mark {
  position: absolute; left: 0; width: 29px; height: 12px;
  display: flex; align-items: center; justify-content: flex-end; gap: 2px;
  transform: translateY(50%);
  color: rgba(255,255,255,.94); white-space: nowrap;
}
.zoom-mark strong {
  min-width: 23px; text-align: right;
  font: 700 8px/12px ui-monospace, SFMono-Regular, Menlo, monospace;
  text-shadow: 0 1px 2px #000, 0 0 3px rgba(0,0,0,.8);
}
.zoom-mark i {
  flex: 0 0 auto; width: 4px; height: 1px;
  background: rgba(255,255,255,.9);
  box-shadow: 0 1px 1px rgba(0,0,0,.65);
}
.zoom-current {
  position: absolute; left: 29px; height: 16px;
  display: flex; align-items: center; gap: 3px;
  padding: 0; border: 0; color: inherit; background: transparent;
  transform: translateY(50%);
  transition: bottom .16s ease-out;
  cursor: pointer; pointer-events: auto;
}
.zoom-current::before {
  content: ''; width: 0; height: 0;
  border-top: 4px solid transparent; border-bottom: 4px solid transparent;
  border-right: 6px solid #fff;
  filter: drop-shadow(0 1px 2px rgba(0,0,0,.9));
}
.zoom-current b {
  padding: 1px 4px; border-radius: 3px;
  color: #fff; background: rgba(3,8,14,.72);
  font: 700 9px/14px ui-monospace, SFMono-Regular, Menlo, monospace;
  text-shadow: 0 1px 2px #000; white-space: nowrap;
}
.zoom-scale.collapsed .zoom-current {
  left: 0; top: 50%; bottom: auto;
  transform: translateY(-50%);
}
.zoom-scale.collapsed .zoom-current::before { display: none; }

/* 遥测仪表 — 专业 HUD 布局 */
.control-deck {
  display: grid;
  grid-template-columns: 130px 1fr 280px;
  grid-template-rows: 1fr;
  flex: 0 0 190px; min-height: 0;
  border-top: 1px solid rgba(255,255,255,.07);
  background: linear-gradient(180deg, #0b111a, #070b12);
  position: relative;
}

/* ── 左列：电池/时间/RTK ── */
.deck-info-col {
  grid-row: 1; grid-column: 1;
  display: flex; flex-direction: column; gap: 6px;
  padding: 10px 10px 6px;
  border-right: 1px solid rgba(255,255,255,.06);
  overflow: hidden;
}
.info-row { display: flex; flex-direction: column; gap: 1px; }
.info-label { font-size: 9px; color: #6b7789; letter-spacing: .04em; }
.info-val { font-size: 13px; font-weight: 600; color: #dce8f4; font-family: 'JetBrains Mono', monospace; }
.info-val.bat-warn { color: #ff5d6c; animation: blink 1.2s infinite; }
/* 失联动作：语义色点 + 文字，随状态变色，无数据置灰 */
.rclost-info { display: inline-flex; align-items: center; gap: 6px; transition: color .18s; }
.rclost-dot {
  width: 6px; height: 6px; border-radius: 50%; flex: 0 0 auto;
  background: currentColor; box-shadow: 0 0 6px currentColor;
  transition: background .18s, box-shadow .18s;
}
.rclost-0 { color: #5ec8ff; }   /* 悬停 */
.rclost-1 { color: #ffb04f; }   /* 降落 */
.rclost-2 { color: #35d6a4; }   /* 返航 */
.rclost-info.unknown { color: #6b7a8a; }
.rclost-info.unknown .rclost-dot { box-shadow: none; }

/* ── 中区：罗盘 + 障碍 + 速度/高度带 ── */
.deck-compass-zone {
  grid-row: 1; grid-column: 2;
  position: relative;
  display: flex; align-items: center; justify-content: center;
  padding: 6px 0;
  overflow: hidden;
}
.hdg-top {
  position: absolute; top: 2px; left: 50%; transform: translateX(-50%);
  font-size: 14px; font-weight: 700; color: #35d6a4;
  font-family: 'JetBrains Mono', monospace; letter-spacing: .08em;
}
.hsi-state {
  position: absolute; top: 3px; left: 6px; z-index: 3;
  display: inline-flex; align-items: center; gap: 4px;
  color: #8fa4b8; font: 9px/1.2 monospace; white-space: nowrap;
}
.hsi-state.enabled { color: #35d6a4; }
.hsi-state small { color: #7890a7; font: inherit; }
.compass-core {
  position: relative; width: 128px; height: 128px;
  display: flex; align-items: center; justify-content: center;
  filter: drop-shadow(0 8px 16px rgba(0,0,0,.28));
}
.attitude-window {
  position: absolute; inset: 10px;
  overflow: hidden; border-radius: 50%;
  background: #162433;
  border: 1px solid rgba(91,179,239,.16);
  box-shadow: inset 0 0 24px rgba(0,0,0,.8), 0 0 14px rgba(63,169,255,.08);
}
.attitude-horizon {
  position: absolute; inset: -45%;
  transition: transform .22s cubic-bezier(.2,.7,.2,1);
  will-change: transform;
}
.attitude-sky,
.attitude-ground { position: absolute; left: 0; right: 0; height: 50%; }
.attitude-sky {
  top: 0;
  background: linear-gradient(180deg, #17334c 10%, #265f82 100%);
}
.attitude-ground {
  bottom: 0;
  background: linear-gradient(180deg, #604d36, #241f1a 85%);
}
.horizon-line {
  position: absolute; top: calc(50% - 1px); left: 0; right: 0; height: 2px;
  background: rgba(255,255,255,.88);
  box-shadow: 0 0 5px rgba(255,255,255,.55);
}
.pitch-mark {
  position: absolute; left: 50%; width: 28px; height: 1px;
  transform: translateX(-50%);
  background: rgba(255,255,255,.55);
}
.compass-ring {
  position: absolute; inset: 0;
  border: 1.5px solid rgba(126,181,221,.3); border-radius: 50%;
  background: radial-gradient(circle, transparent 66%, rgba(20,39,54,.52) 67%, rgba(7,15,23,.74) 100%);
  z-index: 2;
  transition: transform .22s cubic-bezier(.2,.7,.2,1);
  will-change: transform;
}
.compass-ring .tick {
  position: absolute; top: 0; left: 50%; width: 1px; height: 6px;
  background: rgba(255,255,255,.28); transform-origin: 50% 64px;
}
.cn,.cs,.cw,.ce {
  position: absolute; z-index: 1;
  color: #bed2e0; font: 800 10px/1 ui-monospace, SFMono-Regular, Menlo, monospace;
  text-shadow: 0 0 5px rgba(110,193,255,.38);
}
.cn { top: 8px; left: 50%; transform: translateX(-50%); color: #ff7886; }
.cs { bottom: 8px; left: 50%; transform: translateX(-50%) rotate(180deg); }
.cw { left: 8px; top: 50%; transform: translateY(-50%) rotate(-90deg); }
.ce { right: 8px; top: 50%; transform: translateY(-50%) rotate(90deg); }
.aircraft-symbol {
  position: relative; z-index: 4; width: 34px; height: 30px;
  display: flex; align-items: center; justify-content: center;
  color: #4db5ff;
  filter: drop-shadow(0 0 6px rgba(63,169,255,.75));
}
.aircraft-symbol::before {
  content: ''; position: absolute; left: 2px; right: 2px; top: 15px; height: 2px;
  border-radius: 2px; background: currentColor;
}
.aircraft-symbol::after {
  content: ''; position: absolute; left: 50%; top: 8px; width: 2px; height: 18px;
  border-radius: 2px; background: currentColor; transform: translateX(-50%);
}
.aircraft-symbol b { position: relative; z-index: 1; font-size: 19px; line-height: 1; transform: translateY(-7px); }

/* 四向障碍标注 */
.obs {
  position: absolute; font-size: 10px; font-family: monospace;
  color: #8fa4b8; white-space: nowrap; z-index: 2;
}
.obs.warn { color: #ff9d37; font-weight: 700; }
.obs-front { top: 22px; left: 50%; transform: translateX(-50%); }
.obs-back  { bottom: 4px; left: 50%; transform: translateX(-50%); }
.obs-left  { left: 8px; top: 50%; transform: translateY(-50%); }
.obs-right { right: 8px; top: 50%; transform: translateY(-50%); }

/* 速度带（左） */
.tape {
  position: absolute; display: flex; flex-direction: column; align-items: center; gap: 2px;
  box-sizing: border-box; padding: 5px 7px;
  border: 1px solid rgba(126,181,221,.14); border-radius: 7px;
  background: linear-gradient(180deg, rgba(14,27,39,.78), rgba(7,14,22,.68));
  box-shadow: inset 0 1px rgba(255,255,255,.025), 0 5px 12px rgba(0,0,0,.2);
}
.tape-left { left: calc(50% - 105px); top: 50%; transform: translate(-50%, -50%); min-width: 48px; }
.tape-val { font-size: 18px; font-weight: 700; color: #35d6a4; font-family: monospace; }
.tape-unit { font-size: 8px; color: #6b7789; line-height: 1.2; text-align: center; }
.wind-readout { margin-top: 2px; color: #7890a7; font: 7px/1 monospace; }

/* 高度带（右） */
.tape-right { right: calc(50% - 122px); top: 50%; transform: translate(50%, -50%); gap: 4px; }
.tape-vs { display: flex; align-items: center; gap: 3px; }
.vs-bar { width: 6px; height: 50px; border-radius: 3px; background: rgba(255,255,255,.08); position: relative; overflow: hidden; }
.vs-fill { position: absolute; bottom: 0; width: 100%; border-radius: 3px; transition: height .2s; }
.vs-bar.up .vs-fill { background: #35d6a4; }
.vs-bar.down .vs-fill { background: #ff9d37; top: 0; bottom: auto; }
.vs-val { font-size: 12px; font-weight: 600; color: #dce8f4; font-family: monospace; }
.vs-label { font-size: 8px; color: #6b7789; }
.tape-alt { display: flex; align-items: baseline; gap: 3px; }
.alt-val { font-size: 18px; font-weight: 700; color: #3fa9ff; font-family: monospace; }
.alt-label { font-size: 8px; color: #6b7789; line-height: 1.2; }
.tape-asl { display: flex; align-items: baseline; gap: 3px; }
.asl-val { font-size: 11px; color: #8fa4b8; font-family: monospace; }
.asl-label { font-size: 8px; color: #6b7789; }
.home-readout { display: flex; align-items: center; gap: 3px; color: #d3aa27; font: 8px/1 monospace; white-space: nowrap; }
.home-icon {
  display: inline-grid; place-items: center; width: 11px; height: 11px;
  border: 1.5px solid currentColor; border-radius: 50%;
  font: 700 7px/1 monospace;
}

/* ── 右区：八方向控制 + 操作按钮 ── */
.deck-control-zone {
  grid-row: 1; grid-column: 3;
  display: flex; flex-wrap: wrap; align-content: center; align-items: center; gap: 6px 10px;
  padding: 8px 10px;
  border-left: 1px solid rgba(255,255,255,.06);
}
.drc-readiness {
  flex: 0 0 100%; min-width: 0; height: 24px; box-sizing: border-box;
  display: flex; align-items: center; justify-content: space-between; gap: 7px;
  padding: 2px 3px 2px 7px; border: 1px solid rgba(255,176,79,.24); border-radius: 5px;
  color: #ffbd69; background: rgba(255,176,79,.055);
}
.drc-readiness.ready {
  color: #62dfb6; border-color: rgba(53,214,164,.3); background: rgba(53,214,164,.065);
}
.drc-readiness-copy {
  min-width: 0; display: flex; align-items: center; gap: 5px;
  overflow: hidden; font: 9px/1.2 ui-monospace, SFMono-Regular, Menlo, monospace;
  text-overflow: ellipsis; white-space: nowrap;
}
.drc-readiness-copy i {
  width: 6px; height: 6px; flex: 0 0 auto; border-radius: 50%;
  background: currentColor; box-shadow: 0 0 6px currentColor;
}
.drc-readiness-actions { flex: 0 0 auto; display: flex; align-items: center; gap: 4px; }
.drc-readiness-actions button {
  height: 18px; padding: 0 6px; border: 1px solid rgba(255,255,255,.13); border-radius: 4px;
  color: #b8c5d1; background: rgba(255,255,255,.045); font-size: 8px; white-space: nowrap; cursor: pointer;
}
.drc-readiness-actions button:hover:not(:disabled) { color: #fff; border-color: rgba(63,169,255,.5); }
.drc-readiness-actions button.done { color: #62dfb6; border-color: rgba(53,214,164,.32); }
.drc-readiness-actions button.warn { color: #ffbd69; border-color: rgba(255,176,79,.38); }
.drc-readiness-actions button:disabled { opacity: .55; cursor: default; }
.takeoff-side {
  position: relative;
  display: grid; grid-template-columns: minmax(0, 1fr) 96px; gap: 10px;
  width: 100%; height: 100%; padding-bottom: 12px;
}
.takeoff-side-fields {
  display: flex; align-items: center;
}
.takeoff-side-fields label { display: flex; flex: 1; flex-direction: column; gap: 5px; color: #8fa4b8; font-size: 10px; }
.takeoff-side-fields label > span,
.takeoff-side-fields select {
  display: flex; align-items: center; height: 38px; padding: 0 9px;
  border: 1px solid rgba(255,255,255,.14); border-radius: 4px;
  color: #9aa8b9; background: #111720; font-size: 10px;
}
.takeoff-side-fields input {
  min-width: 0; width: 100%; padding: 0; border: 0; outline: 0;
  color: #f0f3f7; background: transparent; font: 17px/1 ui-monospace, monospace;
}
.takeoff-side-fields small { color: #5f7185; font: 8px/1 ui-monospace, monospace; }
.takeoff-side-fields select { width: 100%; }
.takeoff-side-action {
  display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 7px;
  border: 1px solid rgba(63,169,255,.32); border-radius: 5px;
  color: #f2f5f8; background: rgba(63,169,255,.1); cursor: pointer; font-size: 11px;
}
.takeoff-side-action span { font-size: 25px; }
.takeoff-side-action:hover:not(:disabled) { border-color: #3fa9ff; background: rgba(63,169,255,.18); }
.takeoff-side-action:disabled { opacity: .4; cursor: not-allowed; }
.takeoff-progress-line {
  position: absolute; left: 0; right: 0; bottom: -2px;
  overflow: hidden; margin: 0; color: #74bfff; font: 8px/1.2 ui-monospace, monospace;
  text-overflow: ellipsis; white-space: nowrap;
}
.task-side {
  display: grid; grid-template-columns: minmax(0, 1fr) 118px;
  grid-template-rows: auto minmax(0, 1fr); gap: 5px 6px; width: 100%; min-width: 0;
}
.task-side-summary {
  grid-column: 1 / -1;
  display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 3px 8px;
}
.task-side-summary div { display: flex; align-items: center; justify-content: space-between; gap: 6px; }
.task-side-summary span { color: #768395; font-size: 9px; }
.task-side-summary strong { overflow: hidden; color: #eef2f6; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.task-side > .direction-grid { grid-column: 1; grid-row: 2; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 0 3px; }
.task-side > .direction-grid kbd { width: 28px; }
.task-side > .direction-grid button { min-height: 40px; }
/* 任务面板按钮改为纵向单列，保证不再被挤在窄列里重叠 */
.task-side > .deck-btns {
  grid-column: 2; grid-row: 2;
  display: flex; flex-direction: column; gap: 4px; min-width: 0;
}
.task-side > .deck-btns .deck-btn { padding: 4px 6px; font-size: 10px; line-height: 1.2; }
.task-side > .deck-btns .btn-stop { font-size: 11px; }
.task-side > .deck-btns .btn-stop small { font-size: 8px; }

/* ── 航线执行任务面板：全宽摘要 + 两列操作按钮，杜绝拥挤 ── */
.wayline-side {
  display: flex; flex-direction: column; gap: 6px; width: 100%; min-width: 0;
}
.wayline-summary {
  display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 3px 8px;
}
.wayline-summary div { display: flex; align-items: center; justify-content: space-between; gap: 6px; }
.wayline-summary span { color: #768395; font-size: 9px; }
.wayline-summary strong { overflow: hidden; color: #eef2f6; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.wayline-summary strong.danger-text { color: #ff6d6d; }
.wayline-summary-id { grid-column: 1 / -1; }
.wayline-actions {
  display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 5px; width: 100%; min-width: 0;
}
.wayline-actions .deck-btn { padding: 5px 6px; font-size: 10px; line-height: 1.25; }
.wayline-actions .btn-stop { font-size: 11px; }
.wayline-actions .btn-stop small { font-size: 8px; }
.btn-wayline { border-color: rgba(63,169,255,.4); color: #6bb8ff; background: rgba(63,169,255,.08); }
.btn-wayline:hover:not(:disabled) { background: rgba(63,169,255,.18); }
.deck-btn.btn-cancel { border-color: rgba(255,176,79,.45); color: #ffb04f; background: rgba(255,176,79,.08); }
.direction-grid {
  display: grid; grid-template-columns: repeat(4, minmax(38px, 1fr)); gap: 0 6px;
  flex: 1;
}
.direction-grid button {
  min-width: 0; min-height: 46px; padding: 2px;
  display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 3px;
  border: 0; border-radius: 4px;
  color: #727b87; background: transparent;
  transition: all .08s; cursor: pointer; touch-action: none; user-select: none;
}
.direction-grid button.icon-bottom { flex-direction: column-reverse; }
.direction-grid .direction-icon {
  height: 12px; color: #737c87; font: 700 15px/12px ui-sans-serif, system-ui, sans-serif;
  transition: color .08s, transform .08s;
}
.direction-grid kbd {
  display: grid; place-items: center;
  width: 34px; height: 28px; box-sizing: border-box;
  border: 1px solid rgba(255,255,255,.1); border-radius: 3px;
  color: #e4e8ed; background: #30343a;
  box-shadow: inset 0 1px 0 rgba(255,255,255,.06), 0 1px 2px rgba(0,0,0,.4);
  font: 600 12px/1 ui-monospace, SFMono-Regular, Menlo, monospace;
}
.direction-grid button.down {
  background: rgba(63,169,255,.08); color: #3fa9ff;
  box-shadow: 0 0 8px rgba(63,169,255,.16);
}
.direction-grid button.down .direction-icon { color: #3fa9ff; transform: scale(1.12); }
.direction-grid button.down kbd {
  border-color: #3fa9ff; color: #fff; background: rgba(34,112,170,.72);
  box-shadow: inset 0 0 0 1px rgba(255,255,255,.08), 0 0 8px rgba(63,169,255,.35);
  transform: translateY(1px);
}
.direction-grid button:disabled { opacity: .4; cursor: not-allowed; }
.sr-only {
  position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px;
  overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0;
}

.deck-btns { display: flex; flex-direction: column; gap: 5px; flex: 1; min-width: 90px; }
.deck-btns.landing-actions {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-content: center;
  min-width: 176px;
}
.deck-btn {
  padding: 6px 10px; border-radius: 6px; border: 1px solid rgba(255,255,255,.12);
  background: rgba(255,255,255,.04); color: #dce8f4;
  font-size: 12px; font-weight: 600; cursor: pointer;
  text-align: center; transition: all .12s;
}
.deck-btn:hover:not(:disabled) { background: rgba(255,255,255,.08); border-color: rgba(255,255,255,.2); }
.deck-btn:disabled { opacity: .35; cursor: not-allowed; }
.btn-drc { border-color: rgba(53,214,164,.34); color: #66e0b7; }
.btn-drc.active { color: #ffb04f; border-color: rgba(255,176,79,.4); background: rgba(255,176,79,.08); }
.btn-rth { border-color: rgba(63,169,255,.3); color: #3fa9ff; }
.btn-rth:hover:not(:disabled) { background: rgba(63,169,255,.15); }
.btn-stop {
  border-color: rgba(255,93,108,.5); color: #ff5d6c;
  background: rgba(255,93,108,.08);
  font-size: 13px; font-weight: 700;
}
.btn-stop small { font-size: 9px; font-weight: 400; opacity: .7; }
.btn-stop:hover:not(:disabled) { background: rgba(255,93,108,.2); }
.btn-continuous-land {
  border-color: rgba(53,214,164,.56);
  color: #66e0b7;
  background: rgba(53,214,164,.08);
}
.btn-continuous-land:hover:not(:disabled) { background: rgba(53,214,164,.18); }
.btn-continuous-land.armed {
  border-color: #ffb04f;
  color: #fff2d9;
  background: rgba(255,176,79,.16);
}
.btn-continuous-land.active {
  border-color: #ffb04f;
  color: #fff2d9;
  background: rgba(255,176,79,.22);
  box-shadow: inset 0 0 0 1px rgba(255,176,79,.18), 0 0 10px rgba(255,176,79,.18);
  animation: continuous-land-pulse 1.1s ease-in-out infinite alternate;
}
@keyframes continuous-land-pulse {
  from { box-shadow: inset 0 0 0 1px rgba(255,176,79,.12), 0 0 4px rgba(255,176,79,.12); }
  to { box-shadow: inset 0 0 0 1px rgba(255,176,79,.3), 0 0 12px rgba(255,176,79,.34); }
}
.btn-emergency-land {
  border-color: rgba(255,176,79,.55);
  color: #ffb04f;
  background: rgba(255,176,79,.08);
}
.btn-emergency-land:hover:not(:disabled) { background: rgba(255,176,79,.18); }
.btn-force-land {
  border-color: rgba(255,61,82,.72);
  color: #ff5266;
  background: rgba(142,20,38,.22);
}
.btn-force-land:hover:not(:disabled) { background: rgba(190,24,47,.32); }

/* ── 负载快捷控制：键盘状态与触控按钮共用同一套按下反馈 ── */
.payload-shortcut-panel {
  flex: 0 0 126px;
  min-height: 126px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 174px;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  box-sizing: border-box;
  border-top: 1px solid rgba(53,214,164,.18);
  background:
    radial-gradient(circle at 78% 50%, rgba(53,214,164,.07), transparent 42%),
    linear-gradient(180deg, #09121a, #070c13);
}
.payload-shortcut-copy {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
  color: #8fa4b8;
  font-size: 10px;
  line-height: 1.25;
}
.payload-shortcut-heading {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 2px;
  color: #dce8f4;
  font-size: 11px;
  white-space: nowrap;
}
.payload-shortcut-status {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #35d6a4;
  box-shadow: 0 0 7px rgba(53,214,164,.8);
}
.payload-shortcut-copy small {
  color: #35d6a4;
  font: 9px/1.2 ui-monospace, SFMono-Regular, Menlo, monospace;
  white-space: nowrap;
}
.payload-gimbal-reset {
  align-self: flex-start;
  display: inline-flex; align-items: center; gap: 5px;
  margin-top: 2px; padding: 3px 4px 3px 7px;
  border: 1px solid rgba(63,169,255,.42); border-radius: 5px;
  color: #65b7ff; background: rgba(63,169,255,.08);
  font-size: 9px;
  transition: border-color .12s, color .12s, background .12s;
}
.payload-gimbal-reset span { font-size: 12px; line-height: 1; }
.payload-gimbal-reset:focus-within, .payload-gimbal-reset:hover {
  border-color: #3fa9ff; color: #d9efff; background: rgba(63,169,255,.16);
}
.payload-gimbal-reset select, .payload-gimbal-reset button {
  height: 20px; border: 0; color: inherit; background: transparent;
  font: inherit; cursor: pointer;
}
.payload-gimbal-reset select { min-width: 66px; outline: 0; }
.payload-gimbal-reset select option { color: #dce8f4; background: #111b27; }
.payload-gimbal-reset button {
  padding: 0 7px; border-left: 1px solid rgba(63,169,255,.28);
  color: #8ed0ff; font-weight: 700;
}
.payload-gimbal-reset button:hover:not(:disabled) { color: #fff; }
.payload-gimbal-reset select:disabled, .payload-gimbal-reset button:disabled {
  opacity: .45; cursor: wait;
}
.payload-shortcut-pad {
  display: grid;
  grid-template-areas:
    ". up ."
    "left center right"
    ". down .";
  grid-template-columns: repeat(3, 54px);
  grid-template-rows: repeat(3, 34px);
  gap: 2px;
  justify-content: center;
}
.payload-shortcut-btn {
  min-width: 0;
  padding: 1px 2px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 1px;
  border: 1px solid rgba(255,255,255,.12);
  border-radius: 5px;
  color: #dce8f4;
  background: rgba(255,255,255,.035);
  cursor: pointer;
  touch-action: none;
  user-select: none;
  transition: border-color .08s, color .08s, background .08s, box-shadow .08s, transform .08s;
}
.payload-shortcut-btn:hover {
  color: #fff;
  border-color: rgba(53,214,164,.45);
  background: rgba(53,214,164,.08);
}
.payload-shortcut-btn.down {
  color: #35d6a4;
  border-color: #35d6a4;
  background: rgba(53,214,164,.2);
  box-shadow: 0 0 10px rgba(53,214,164,.28), inset 0 0 8px rgba(53,214,164,.1);
  transform: scale(.96);
}
.payload-shortcut-btn span { font-size: 11px; line-height: 1; }
.payload-shortcut-btn small { font-size: 8px; line-height: 1; white-space: nowrap; }
.payload-shortcut-btn.is-up { grid-area: up; }
.payload-shortcut-btn.is-down { grid-area: down; }
.payload-shortcut-btn.is-left { grid-area: left; }
.payload-shortcut-btn.is-right { grid-area: right; }
.payload-shortcut-center {
  grid-area: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 1px;
  color: #69788a;
  font-size: 7px;
  letter-spacing: .06em;
}
.payload-shortcut-center kbd {
  padding: 1px 4px;
  border: 1px solid rgba(255,255,255,.1);
  border-bottom-width: 2px;
  border-radius: 3px;
  color: #8fa4b8;
  background: rgba(255,255,255,.04);
  font: 7px/1.2 ui-monospace, monospace;
}

/* ═══ 地图优先基准面板：地图优先与视频优先共用一致的内部排版 ═══ */
.cockpit-pro:is(.layout-map, .layout-video) .lens-bar {
  flex-wrap: wrap; gap: 4px; min-height: 36px; padding: 4px 6px;
}
.cockpit-pro:is(.layout-map, .layout-video) .lens-bar button { font-size: 10px; padding: 3px 7px; }
.cockpit-pro:is(.layout-map, .layout-video) .lens-group { gap: 1px; padding: 1px; }
.cockpit-pro:is(.layout-map, .layout-video) .payload-authority-switch { font-size: 10px; padding-inline: 6px !important; }
.cockpit-pro:is(.layout-map, .layout-video) .video-box {
  flex: 0 0 auto;
  width: 100%; min-height: 0; max-height: none;
}
.cockpit-pro:is(.layout-map, .layout-video) .zoom-scale { left: 6px; }
/* 仪表区：单列三行堆叠。DRC 操作已在顶栏提供，窄列内不再重复占一行。 */
.cockpit-pro:is(.layout-map, .layout-video) .control-deck {
  grid-template-columns: 1fr;
  grid-template-rows: auto 180px auto;
  flex: 1 1 auto;
  align-content: start;
  min-height: 0; overflow: hidden;
}
.cockpit-pro:is(.layout-map, .layout-video) .deck-info-col {
  grid-row: 1; grid-column: 1;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 4px;
  padding: 6px;
  border-right: none; border-bottom: 1px solid rgba(255,255,255,.06);
}
.cockpit-pro:is(.layout-map, .layout-video) .info-row {
  min-width: 0; min-height: 38px;
  padding: 5px 6px; box-sizing: border-box;
  flex-direction: column; align-items: flex-start; justify-content: center; gap: 3px;
  border: 1px solid rgba(255,255,255,.07); border-radius: 5px;
  background: rgba(255,255,255,.025);
}
.cockpit-pro:is(.layout-map, .layout-video) .info-label {
  font-size: 8px; line-height: 1; letter-spacing: .03em; white-space: nowrap;
}
.cockpit-pro:is(.layout-map, .layout-video) .info-val {
  max-width: 100%; overflow: hidden;
  font-size: 11px; line-height: 1.2; text-overflow: ellipsis; white-space: nowrap;
}
.cockpit-pro:is(.layout-map, .layout-video) .deck-compass-zone {
  grid-row: 2; grid-column: 1;
  height: 180px; min-height: 180px; max-height: 180px;
  padding: 4px 0;
  align-self: start;
}
.cockpit-pro:is(.layout-map, .layout-video) .compass-core { width: 120px; height: 120px; }
.cockpit-pro:is(.layout-map, .layout-video) .compass-ring .tick { transform-origin: 50% 60px; }
.cockpit-pro:is(.layout-map, .layout-video) .hdg-top { font-size: 12px; }
.cockpit-pro:is(.layout-map, .layout-video) .tape-left { left: calc(50% - 88px); }
.cockpit-pro:is(.layout-map, .layout-video) .tape-right { right: calc(50% - 104px); }
.cockpit-pro:is(.layout-map, .layout-video) .tape-val { font-size: 14px; }
.cockpit-pro:is(.layout-map, .layout-video) .alt-val { font-size: 14px; }
.cockpit-pro:is(.layout-map, .layout-video) .vs-bar { height: 36px; }
.cockpit-pro:is(.layout-map, .layout-video) .deck-control-zone {
  grid-row: 3; grid-column: 1;
  min-height: 86px; box-sizing: border-box;
  border-left: none; border-top: 1px solid rgba(255,255,255,.06);
  padding: 6px 8px;
}
.cockpit-pro:is(.layout-map, .layout-video) .deck-control-zone > .drc-readiness { display: none; }
.cockpit-pro:is(.layout-map, .layout-video) .takeoff-side {
  grid-template-columns: 1fr 72px; gap: 6px;
  padding-bottom: 4px;
}
.cockpit-pro:is(.layout-map, .layout-video) .takeoff-side-fields label { font-size: 9px; }
.cockpit-pro:is(.layout-map, .layout-video) .takeoff-side-fields input { font-size: 14px; }
.cockpit-pro:is(.layout-map, .layout-video) .takeoff-side-action { font-size: 10px; }
.cockpit-pro:is(.layout-map, .layout-video) .takeoff-side-action span { font-size: 20px; }
.cockpit-pro:is(.layout-map, .layout-video) .task-side { grid-template-columns: 1fr 108px; gap: 4px 6px; }
.cockpit-pro:is(.layout-map, .layout-video) .task-side-summary { grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 2px 5px; }
.cockpit-pro:is(.layout-map, .layout-video) .task-side-summary span { font-size: 7px; }
.cockpit-pro:is(.layout-map, .layout-video) .task-side-summary strong { font-size: 8px; }
.cockpit-pro:is(.layout-map, .layout-video) .direction-grid { grid-template-columns: repeat(4, 1fr); gap: 0 3px; }
.cockpit-pro:is(.layout-map, .layout-video) .direction-grid button { min-height: 43px; padding: 1px; }
.cockpit-pro:is(.layout-map, .layout-video) .direction-grid .direction-icon { font-size: 13px; }
.cockpit-pro:is(.layout-map, .layout-video) .direction-grid kbd { width: 30px; height: 25px; font-size: 11px; }
.cockpit-pro:is(.layout-map, .layout-video) .deck-btns { flex-direction: row; flex-wrap: wrap; gap: 4px; min-width: 0; }
/* 任务面板按钮列在紧凑布局下仍保持纵向单列，避免重叠 */
.cockpit-pro:is(.layout-map, .layout-video) .task-side > .deck-btns { flex-direction: column; flex-wrap: nowrap; }
.cockpit-pro:is(.layout-map, .layout-video) .task-side > .deck-btns .deck-btn { flex: 0 0 auto; min-width: 0; }
.cockpit-pro:is(.layout-map, .layout-video) .wayline-summary span { font-size: 7px; }
.cockpit-pro:is(.layout-map, .layout-video) .wayline-summary strong { font-size: 8px; }
.cockpit-pro:is(.layout-map, .layout-video) .wayline-actions .deck-btn { font-size: 9px; padding: 4px 5px; }
.cockpit-pro:is(.layout-map, .layout-video) .deck-btn { padding: 5px 8px; font-size: 10px; flex: 1; min-width: 60px; }

/* 视频优先且负载控制已开启时，两个控制栏各占自己的布局行。
 * flex-basis: 0 让飞行控制区只使用扣除视频和负载栏后的剩余高度，
 * 避免飞行状态切换后内容高度把负载云台栏顶上来。 */
.cockpit-pro.layout-video .flight-view.has-payload-shortcuts .control-deck {
  flex: 1 1 0;
  grid-template-rows: auto minmax(132px, 160px) minmax(104px, 1fr);
}
.cockpit-pro.layout-video .flight-view.has-payload-shortcuts .deck-compass-zone {
  height: auto; min-height: 132px; max-height: 160px;
  align-self: stretch;
}
.cockpit-pro.layout-video .flight-view.has-payload-shortcuts .deck-control-zone {
  min-height: 104px;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-width: thin;
}
.cockpit-pro.layout-video .flight-view.has-payload-shortcuts .payload-shortcut-panel {
  position: relative;
  z-index: 2;
  flex: 0 0 126px;
}

@media (max-width: 1220px) {
  /* 设备列表宽度始终锁定 320px：此处不再覆盖 --rail-width，沿用默认值 */
  .cockpit-pro { grid-template-columns: var(--rail-width) minmax(0, 1fr) minmax(0, 1fr); }
  .cockpit-pro.rail-collapsed { grid-template-columns: 0px minmax(0, 1fr) minmax(0, 1fr); }
  .cockpit-pro.layout-balanced { grid-template-columns: var(--rail-width) 1.3fr 1fr; }
  .cockpit-pro.layout-map { grid-template-columns: var(--rail-width) minmax(0, 1fr) 360px; }
  .cockpit-pro.rail-collapsed.layout-map { grid-template-columns: 0px minmax(0, 1fr) 360px; }
  .control-deck { grid-template-columns: 110px 1fr 240px; flex-basis: 178px; }
  .compass-core { width: 100px; height: 100px; }
  .compass-ring .tick { transform-origin: 50% 50px; }
  .takeoff-side { grid-template-columns: minmax(0, 1fr) 72px; }
  .takeoff-side-fields label { font-size: 7px; }
  .takeoff-side-action { font-size: 9px; }
  .task-side { grid-template-columns: minmax(0, 1fr) 76px; }
  .direction-grid { grid-template-columns: repeat(4, minmax(34px, 1fr)); }
  .direction-grid button { min-height: 43px; }
  .deck-btns { min-width: 76px; }
  .deck-btn { padding: 5px 8px; font-size: 11px; }
  .lens-bar { gap: 4px; }
  .lens-bar button { padding-inline: 7px; }
}

@keyframes blink { 0%,100% { opacity: 1; } 50% { opacity: .3; } }
</style>
