package com.yoox.service.control.service.impl;

import com.yoox.api.control.AbstractControlService;
import com.yoox.great.mqtt.core.consume.MqttReply;
import com.yoox.great.mqtt.enums.control.ControlSourceEnum;
import com.yoox.great.mqtt.enums.control.DrcStatusErrorEnum;
import com.yoox.great.mqtt.enums.control.JoystickInvalidReasonEnum;
import com.yoox.great.mqtt.enums.device.DrcStateEnum;
import com.yoox.great.mqtt.handle.events.TopicEventsRequest;
import com.yoox.great.mqtt.handle.events.TopicEventsResponse;
import com.yoox.great.mqtt.model.control.DrcStatusNotify;
import com.yoox.great.mqtt.model.control.FlyToPointProgress;
import com.yoox.great.mqtt.model.control.JoystickInvalidNotify;
import com.yoox.great.mqtt.model.control.TakeoffToPointProgress;
import com.yoox.great.websocket.enums.BizCodeEnum;
import com.yoox.great.websocket.enums.UserTypeEnum;
import com.yoox.great.websocket.service.IWebSocketMessageService;
import com.yoox.service.control.model.dto.DrcSession;
import com.yoox.service.manage.model.dto.DeviceDTO;
import com.yoox.service.manage.service.IDeviceRedisService;
import com.yoox.service.manage.service.IDeviceService;
import com.yoox.service.wayline.model.enums.WaylineTaskStatusEnum;
import com.yoox.service.wayline.model.param.UpdateJobParam;
import com.yoox.service.wayline.service.IFlightTaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.MessageHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.Map;
import com.fasterxml.jackson.core.type.TypeReference;

@Service
@Slf4j
public class SDKControlService extends AbstractControlService {

    @Autowired
    private IWebSocketMessageService webSocketMessageService;

    @Autowired
    private IDeviceRedisService deviceRedisService;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private IDeviceService deviceService;

    @Autowired
    private DrcSessionStore drcSessionStore;

    @Autowired
    private IFlightTaskService flightTaskService;

    @Autowired
    private PointFlightTaskStore pointFlightTaskStore;

    @Override
    public TopicEventsResponse<MqttReply> flyToPointProgress(TopicEventsRequest<FlyToPointProgress> request, MessageHeaders headers) {
        String dockSn = eventGatewaySn(request);

        FlyToPointProgress eventsReceiver = request.getData();
        Map<String, Object> payload = progressPayload(dockSn, request, eventsReceiver);
        if (eventsReceiver != null) {
            try {
                pointFlightTaskStore.recordProgress(dockSn, "flyto", payload);
            } catch (RuntimeException exception) {
                log.error("Failed to persist FlyTo progress for gateway {}", dockSn, exception);
            }
        }
        try {
            workspaceId(dockSn).ifPresentOrElse(
                    workspaceId -> webSocketMessageService.sendBatch(
                            workspaceId, UserTypeEnum.WEB.getVal(),
                            BizCodeEnum.FLY_TO_POINT_PROGRESS.getCode(), payload),
                    () -> log.warn("Unable to broadcast FlyTo progress: gateway {} is not bound to a workspace", dockSn));
        } catch (RuntimeException exception) {
            log.error("Failed to broadcast FlyTo progress for gateway {}", dockSn, exception);
        }
        return acknowledge(request);
    }

    @Override
    public TopicEventsResponse<MqttReply> takeoffToPointProgress(TopicEventsRequest<TakeoffToPointProgress> request, MessageHeaders headers) {
        String dockSn = eventGatewaySn(request);

        TakeoffToPointProgress eventsReceiver = request.getData();
        Map<String, Object> payload = progressPayload(dockSn, request, eventsReceiver);
        if (eventsReceiver != null) {
            try {
                pointFlightTaskStore.recordProgress(dockSn, "takeoff", payload);
            } catch (RuntimeException exception) {
                log.error("Failed to persist takeoff progress for gateway {}", dockSn, exception);
            }
        }
        try {
            workspaceId(dockSn).ifPresentOrElse(
                    workspaceId -> webSocketMessageService.sendBatch(
                            workspaceId, UserTypeEnum.WEB.getVal(),
                            BizCodeEnum.TAKE_OFF_TO_POINT_PROGRESS.getCode(), payload),
                    () -> log.warn("Unable to broadcast takeoff progress: gateway {} is not bound to a workspace", dockSn));
        } catch (RuntimeException exception) {
            log.error("Failed to broadcast takeoff progress for gateway {}", dockSn, exception);
        }
        return acknowledge(request);
    }

    @Override
    public TopicEventsResponse<MqttReply> drcStatusNotify(TopicEventsRequest<DrcStatusNotify> request, MessageHeaders headers) {
        String dockSn = eventGatewaySn(request);

        Optional<DeviceDTO> deviceOpt = deviceRedisService.getDeviceOnline(dockSn)
                .or(() -> deviceService.getDeviceBySn(dockSn));
        DrcStatusNotify eventsReceiver = request.getData();
        DrcStatusErrorEnum result = eventsReceiver == null ? null : eventsReceiver.getResult();
        DrcStateEnum drcState = eventsReceiver == null ? null : eventsReceiver.getDrcState();
        boolean disconnected = eventsReceiver != null
                && ((result != null && DrcStatusErrorEnum.SUCCESS != result)
                || DrcStateEnum.DISCONNECTED == drcState);
        if (disconnected) {
            try {
                if (!restoreAndClearDrcSession(dockSn, request.getTimestamp())) {
                    log.debug("Ignored stale or already-cleared DRC status for gateway {}", dockSn);
                }
            } catch (RuntimeException exception) {
                log.error("Failed to clear DRC session after status event for gateway {}",
                        dockSn, exception);
                // Do not acknowledge a disconnect whose lease/ACL cleanup was
                // not completed. The device must redeliver it, and an existing
                // EVENT_CLEANING generation remains recoverable for that retry.
                throw exception;
            }
        }

        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("sn", dockSn);
        notification.put("result", result == null ? null : result.getCode());
        notification.put("message", result == null ? "Unknown DRC status result." : result.getMessage());
        notification.put("drc_state", drcState == null ? null : drcState.getState());

        Optional<String> workspaceId = deviceOpt
                .map(DeviceDTO::getWorkspaceId)
                .filter(StringUtils::hasText);
        if (workspaceId.isPresent()) {
            try {
                webSocketMessageService.sendBatch(
                        workspaceId.get(), UserTypeEnum.WEB.getVal(),
                        BizCodeEnum.DRC_STATUS_NOTIFY.getCode(), notification);
            } catch (RuntimeException exception) {
                log.error("Failed to broadcast DRC status for gateway {}", dockSn, exception);
            }
        } else {
            log.warn("Unable to broadcast DRC status: gateway {} is not bound to a workspace", dockSn);
        }
        return acknowledge(request);
    }

    private boolean restoreAndClearDrcSession(String gatewaySn, Long eventTimestamp) {
        Optional<DrcSession> sessionOpt = drcSessionStore.getSessionForEvent(gatewaySn);
        if (sessionOpt.isEmpty()) {
            return false;
        }
        DrcSession session = sessionOpt.get();
        Long watermark = session.getDeviceTimestampWatermark();
        if (eventTimestamp == null
                || watermark == null
                || eventTimestamp < watermark) {
            return false;
        }
        // Atomically hold the active generation before any external resume
        // side effect. This prevents a new DRC generation from being created
        // between the old isCurrent check and wayline restoration.
        if (!drcSessionStore.claimEventCleanup(session)) {
            return false;
        }

        if (StringUtils.hasText(session.getPausedJobId())) {
            try {
                flightTaskService.updateJobStatus(
                        session.getWorkspaceId(),
                        session.getPausedJobId(),
                        UpdateJobParam.builder().status(WaylineTaskStatusEnum.RESUME).build());
            } catch (RuntimeException exception) {
                log.error("Failed to resume wayline {} after DRC disconnect for gateway {}",
                        session.getPausedJobId(), gatewaySn, exception);
                try {
                    // Stop control immediately but retain the EVENT_CLEANING
                    // lease and paused-job metadata for event redelivery.
                    drcSessionStore.revokeSessionAcls(session);
                } catch (RuntimeException revokeException) {
                    exception.addSuppressed(revokeException);
                }
                throw exception;
            }
        }
        if (!drcSessionStore.releaseSession(session)) {
            throw new IllegalStateException(
                    "The DRC generation changed before disconnect cleanup completed.");
        }
        return true;
    }

    @Override
    public TopicEventsResponse<MqttReply> joystickInvalidNotify(TopicEventsRequest<JoystickInvalidNotify> request, MessageHeaders headers) {
        String dockSn = eventGatewaySn(request);

        JoystickInvalidNotify eventsReceiver = request.getData();
        JoystickInvalidReasonEnum reason = eventsReceiver == null ? null : eventsReceiver.getReason();
        if (eventsReceiver != null
                && JoystickInvalidReasonEnum.RC_AUTHORITY == reason) {
            deviceRedisService.getDeviceOnline(dockSn).ifPresentOrElse(
                    gateway -> deviceService.updateFlightControl(gateway, ControlSourceEnum.B),
                    () -> log.warn(
                            "Unable to cache remote-controller authority: gateway {} is offline",
                            dockSn));
        }
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("sn", dockSn);
        notification.put("reason", reason == null ? null : reason.getVal());
        // result is retained for compatibility with cockpit versions that predate the documented reason field.
        notification.put("result", reason == null ? null : reason.getVal());
        notification.put("message", joystickInvalidMessage(reason));
        notification.put("event_timestamp", request.getTimestamp());
        try {
            workspaceId(dockSn).ifPresentOrElse(
                    workspaceId -> webSocketMessageService.sendBatch(
                            workspaceId, UserTypeEnum.WEB.getVal(), BizCodeEnum.JOYSTICK_INVALID_NOTIFY.getCode(),
                            notification),
                    () -> log.warn("Unable to broadcast joystick invalid event: gateway {} is not bound to a workspace", dockSn));
        } catch (RuntimeException exception) {
            log.error("Failed to broadcast joystick invalid event for gateway {}", dockSn, exception);
        }
        return acknowledge(request);
    }

    private String joystickInvalidMessage(JoystickInvalidReasonEnum reason) {
        if (reason == null) {
            return "Joystick 控制已失效（设备未提供原因）";
        }
        return switch (reason) {
            case RC_LOST -> "遥控器失联，Joystick 控制已失效";
            case BATTERY_LOW_GO_HOME -> "低电量返航已触发，Joystick 控制已失效";
            case BATTERY_SUPER_LOW_LANDING -> "严重低电量降落已触发，Joystick 控制已失效";
            case NEAR_BOUNDARY -> "飞行器靠近限飞区，Joystick 控制已失效";
            case RC_AUTHORITY -> "遥控器已夺取飞行控制权，Joystick 控制已失效";
        };
    }

    private Optional<String> workspaceId(String gatewaySn) {
        if (!StringUtils.hasText(gatewaySn)) {
            return Optional.empty();
        }
        return deviceRedisService.getDeviceOnline(gatewaySn)
                .map(DeviceDTO::getWorkspaceId)
                .filter(StringUtils::hasText)
                .or(() -> deviceService.getDeviceBySn(gatewaySn)
                        .map(DeviceDTO::getWorkspaceId)
                        .filter(StringUtils::hasText));
    }

    private String eventGatewaySn(TopicEventsRequest<?> request) {
        String topicSource = request == null ? null : request.getFrom();
        if (!StringUtils.hasText(topicSource)) {
            throw new SecurityException("The MQTT event topic does not identify a device.");
        }
        String claimedGateway = request.getGateway();
        if (!StringUtils.hasText(claimedGateway) || claimedGateway.equals(topicSource)) {
            return topicSource;
        }

        DeviceDTO gateway = knownDevice(claimedGateway)
                .orElseThrow(() -> new SecurityException(
                        "The MQTT event gateway is not a registered device."));
        boolean directChild = topicSource.equals(gateway.getChildDeviceSn())
                || topicSource.equals(gateway.getAircraftSn());
        boolean registeredChild = knownDevice(topicSource)
                .filter(source -> claimedGateway.equals(source.getParentSn()))
                .filter(source -> StringUtils.hasText(gateway.getWorkspaceId())
                        && gateway.getWorkspaceId().equals(source.getWorkspaceId()))
                .isPresent();
        if (!directChild && !registeredChild) {
            throw new SecurityException(
                    "The MQTT event topic device does not belong to the claimed gateway.");
        }
        return claimedGateway;
    }

    private Optional<DeviceDTO> knownDevice(String deviceSn) {
        if (!StringUtils.hasText(deviceSn)) {
            return Optional.empty();
        }
        return deviceRedisService.getDeviceOnline(deviceSn)
                .or(() -> deviceService.getDeviceBySn(deviceSn));
    }

    private TopicEventsResponse<MqttReply> acknowledge(TopicEventsRequest<?> request) {
        return new TopicEventsResponse<MqttReply>()
                .setTid(request.getTid())
                .setBid(request.getBid())
                .setMethod(request.getMethod())
                .setTimestamp(request.getTimestamp())
                .setData(MqttReply.success());
    }

    private Map<String, Object> progressPayload(
            String gatewaySn, TopicEventsRequest<?> request, Object progress) {
        Map<String, Object> converted = progress == null
                ? Map.of()
                : mapper.convertValue(progress, new TypeReference<Map<String, Object>>() { });
        Map<String, Object> payload = new LinkedHashMap<>(converted);
        payload.put("sn", gatewaySn);
        payload.put("tid", request.getTid());
        payload.put("bid", request.getBid());
        payload.put("timestamp", request.getTimestamp());
        return payload;
    }
}
