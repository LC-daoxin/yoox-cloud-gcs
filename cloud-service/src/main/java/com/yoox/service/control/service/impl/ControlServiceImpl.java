package com.yoox.service.control.service.impl;


import com.yoox.api.control.AbstractControlService;

import com.yoox.api.debug.AbstractDebugService;

import com.yoox.great.context.enums.device.DeviceDomainEnum;
import com.yoox.great.context.exception.CloudSDKErrorEnum;
import com.yoox.great.context.response.HttpResultResponse;
import com.yoox.great.mqtt.enums.control.ControlSourceEnum;
import com.yoox.great.mqtt.enums.debug.DebugMethodEnum;
import com.yoox.great.mqtt.enums.device.DockModeCodeEnum;
import com.yoox.great.mqtt.enums.device.DroneModeCodeEnum;
import com.yoox.great.mqtt.enums.device.RcLostActionEnum;
import com.yoox.great.mqtt.model.control.FlyToPointRequest;
import com.yoox.great.mqtt.model.control.PayloadAuthorityGrabRequest;
import com.yoox.great.mqtt.model.control.TakeoffToPointRequest;
import com.yoox.great.mqtt.model.control.TargetDetectOpenRequest;
import com.yoox.great.mqtt.model.device.PayloadIndex;
import com.yoox.great.mqtt.model.wayline.FlighttaskUndoRequest;
import com.yoox.great.websocket.enums.BizCodeEnum;
import com.yoox.great.websocket.enums.UserTypeEnum;
import com.yoox.great.websocket.service.IWebSocketMessageService;
import com.yoox.great.mqtt.handle.services.ServicesReplyData;
import com.yoox.great.mqtt.handle.services.TopicServicesResponse;
import com.yoox.great.mqtt.core.SDKManager;
import com.yoox.service.control.model.enums.DroneAuthorityEnum;
import com.yoox.service.control.model.enums.DroneControlMethodEnum;
import com.yoox.service.control.model.enums.RemoteDebugMethodEnum;
import com.yoox.service.control.model.param.*;
import com.yoox.service.control.service.IControlService;
import com.yoox.service.manage.model.dto.DeviceDTO;
import com.yoox.service.manage.service.IDevicePayloadService;
import com.yoox.service.manage.service.IDeviceRedisService;
import com.yoox.service.manage.service.IDeviceService;
import com.yoox.api.wayline.AbstractWaylineService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class ControlServiceImpl implements IControlService {

    @Autowired
    private IWebSocketMessageService webSocketMessageService;

    @Autowired
    private IDeviceService deviceService;

    @Autowired
    private IDeviceRedisService deviceRedisService;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private IDevicePayloadService devicePayloadService;

    @Autowired
    private PayloadAuthorityCacheService payloadAuthorityCacheService;

    @Autowired
    private PointFlightTaskStore pointFlightTaskStore;

    @Autowired
    private AbstractControlService abstractControlService;

    @Autowired
    private AbstractDebugService abstractDebugService;

    @Autowired
    @Qualifier("SDKWaylineService")
    private AbstractWaylineService abstractWaylineService;

    private RemoteDebugHandler checkDebugCondition(String sn, RemoteDebugParam param, RemoteDebugMethodEnum controlMethodEnum) {
        RemoteDebugHandler handler = Objects.nonNull(controlMethodEnum.getClazz()) ?
                mapper.convertValue(Objects.nonNull(param) ? param : new Object(), controlMethodEnum.getClazz())
                : new RemoteDebugHandler();
        if (!handler.canPublish(sn)) {
            throw new RuntimeException("The current state of the dock does not support this function.");
        }
        return handler;
    }

    @Override
    public HttpResultResponse controlDockDebug(String sn, RemoteDebugMethodEnum controlMethodEnum, RemoteDebugParam param) {
        DebugMethodEnum methodEnum = controlMethodEnum.getDebugMethodEnum();
        RemoteDebugHandler data = checkDebugCondition(sn, param, controlMethodEnum);

        log.info("【控制指令】收到请求 sn={} method={}", sn, controlMethodEnum.getMethod());
        boolean isExist = deviceRedisService.checkDeviceOnline(sn);
        if (!isExist) {
            log.warn("【控制指令】网关不在线，拒绝执行 sn={} method={}", sn, controlMethodEnum.getMethod());
            return HttpResultResponse.error("The dock is offline.");
        }
        TopicServicesResponse response;
        switch (controlMethodEnum) {
            case RETURN_HOME:
            case RETURN_HOME_CANCEL:
                // 飞行类指令（如 takeoff、flyTo）都会先确保云端持有飞行控制权，
                // 否则设备处于遥控器手动控制状态时会直接忽略该指令且不回复
                // services_reply，从而导致 211001（无消息回复）超时。
                // return_home / return_home_cancel 之前遗漏了这一步，此处补齐。
                HttpResultResponse authority = seizeAuthority(sn, DroneAuthorityEnum.FLIGHT, null);
                if (HttpResultResponse.CODE_SUCCESS != authority.getCode()) {
                    log.warn("【返航链路】抢占飞控权失败，终止 {} sn={} code={} message={}",
                            controlMethodEnum.getMethod(), sn, authority.getCode(), authority.getMessage());
                    return authority;
                }
                boolean isReturnHome = RemoteDebugMethodEnum.RETURN_HOME == controlMethodEnum;
                // return_home 是网关级整机指令：官方文档不带 device_list（详见
                // AbstractWaylineService.returnHomeRc）。RC 仅用独立方法绕开
                // returnHomeCancel 上的 @CloudSDKVersion(exclude=RC) 限制。
                DeviceDomainEnum gatewayDomain = deviceRedisService.getDeviceOnline(sn)
                        .map(DeviceDTO::getDomain)
                        .orElse(null);
                log.info("【返航链路】飞控权就绪，准备下发 {} sn={} gatewayDomain={} 分支={}",
                        controlMethodEnum.getMethod(), sn, gatewayDomain,
                        DeviceDomainEnum.REMOTER_CONTROL == gatewayDomain ? "RC(网关级,无device_list)" : "普通网关");
                response = DeviceDomainEnum.REMOTER_CONTROL == gatewayDomain
                        ? (isReturnHome
                                ? abstractWaylineService.returnHomeRc(SDKManager.getDeviceSDK(sn))
                                : abstractWaylineService.returnHomeCancelRc(SDKManager.getDeviceSDK(sn)))
                        : (isReturnHome
                                ? abstractWaylineService.returnHome(SDKManager.getDeviceSDK(sn))
                                : abstractWaylineService.returnHomeCancel(SDKManager.getDeviceSDK(sn)));
                break;
            default:
                response = abstractDebugService.remoteDebug(SDKManager.getDeviceSDK(sn), methodEnum,
                        Objects.nonNull(methodEnum.getClazz()) ? mapper.convertValue(data, methodEnum.getClazz()) : null);
        }
        ServicesReplyData serviceReply = (ServicesReplyData) response.getData();
        log.info("【控制指令】设备回复 sn={} method={} result={} success={}",
                sn, controlMethodEnum.getMethod(), serviceReply.getResult(), serviceReply.getResult().isSuccess());
        if (!serviceReply.getResult().isSuccess()) {
            return HttpResultResponse.error(serviceReply.getResult());
        }
        return HttpResultResponse.success();
    }

    private void checkFlyToCondition(String dockSn) {
        // TODO 设备固件版本不兼容情况
        DeviceDTO gateway = requireOnlineGatewayAndAircraft(dockSn);

        DroneModeCodeEnum deviceMode = deviceService.getDeviceMode(gateway.getChildDeviceSn());
        if (DroneModeCodeEnum.MANUAL != deviceMode) {
            throw new RuntimeException("The current state of the drone does not support this function, please try again later.");
        }

        HttpResultResponse result = seizeAuthority(dockSn, DroneAuthorityEnum.FLIGHT, null);
        if (HttpResultResponse.CODE_SUCCESS != result.getCode()) {
            throw new IllegalArgumentException(result.getMessage());
        }
    }

    @Override
    public HttpResultResponse flyToPoint(String sn, FlyToPointParam param) {
        if (pointFlightTaskStore.hasPotentiallyActiveTask(sn)) {
            return HttpResultResponse.error(
                    "A point-flight command is already active or awaiting confirmation. Stop it before starting another one.");
        }
        param.setFlyToId(UUID.randomUUID().toString());
        if (!pointFlightTaskStore.tryRecordPending(sn, "flyto", param.getFlyToId())) {
            return HttpResultResponse.error(
                    "A point-flight command is already active or awaiting confirmation. Stop it before starting another one.");
        }
        try {
            checkFlyToCondition(sn);
        } catch (RuntimeException exception) {
            pointFlightTaskStore.recordFailure(
                    sn, "flyto", param.getFlyToId(), exception.getMessage());
            throw exception;
        }

        TopicServicesResponse<ServicesReplyData> response;
        try {
            // RC 网关需 device_list 寻址无人机，否则指令被静默丢弃（211001）。
            DeviceDomainEnum flyToGatewayDomain = deviceRedisService.getDeviceOnline(sn)
                    .map(DeviceDTO::getDomain)
                    .orElse(null);
            FlyToPointRequest request = mapper.convertValue(param, FlyToPointRequest.class);
            response = DeviceDomainEnum.REMOTER_CONTROL == flyToGatewayDomain
                    ? abstractControlService.flyToPointRc(SDKManager.getDeviceSDK(sn), request)
                    : abstractControlService.flyToPoint(SDKManager.getDeviceSDK(sn), request);
        } catch (RuntimeException exception) {
            pointFlightTaskStore.recordUnknown(
                    sn, "flyto", param.getFlyToId(), exception.getMessage());
            throw exception;
        }
        ServicesReplyData reply = response == null ? null : response.getData();
        if (reply == null || reply.getResult() == null) {
            pointFlightTaskStore.recordUnknown(
                    sn, "flyto", param.getFlyToId(), "The device reply was empty.");
            return HttpResultResponse.error(
                    "FlyTo command status is unknown. Check task status before retrying.");
        }
        if (reply.getResult().isSuccess()) {
            pointFlightTaskStore.recordAccepted(sn, "flyto", param.getFlyToId());
            return HttpResultResponse.success();
        }
        pointFlightTaskStore.recordFailure(
                sn, "flyto", param.getFlyToId(), reply.getResult().toString());
        return HttpResultResponse.error("Flying to the target point failed. " + reply.getResult());
    }

    @Override
    public HttpResultResponse flyToPointStop(String sn) {
        requireOnlineGatewayAndAircraft(sn);
        HttpResultResponse authority = seizeAuthority(sn, DroneAuthorityEnum.FLIGHT, null);
        if (HttpResultResponse.CODE_SUCCESS != authority.getCode()) {
            return authority;
        }
        Map<String, Object> taskState = pointFlightTaskStore.get(sn).orElse(Map.of());
        String taskKind = String.valueOf(taskState.getOrDefault("kind", ""));
        String takeoffFlightId = String.valueOf(taskState.getOrDefault("flight_id", ""));
        pointFlightTaskStore.recordCancelRequested(sn, false, "Cancel command is being sent.");
        TopicServicesResponse<ServicesReplyData> response;
        try {
            // FlyTo and takeoff-to-point use different cancellation protocols.
            // The latter is a flight task and must be canceled with
            // flighttask_undo + flight_ids. RC commands in both branches need
            // device_list addressing or they can time out with 211001.
            DeviceDomainEnum stopGatewayDomain = deviceRedisService.getDeviceOnline(sn)
                    .map(DeviceDTO::getDomain)
                    .orElse(null);
            if ("takeoff".equals(taskKind) && StringUtils.hasText(takeoffFlightId)) {
                FlighttaskUndoRequest undoRequest = new FlighttaskUndoRequest()
                        .setFlightIds(List.of(takeoffFlightId));
                response = DeviceDomainEnum.REMOTER_CONTROL == stopGatewayDomain
                        ? abstractWaylineService.flighttaskUndoRc(
                                SDKManager.getDeviceSDK(sn), undoRequest)
                        : abstractWaylineService.flighttaskUndo(
                                SDKManager.getDeviceSDK(sn), undoRequest);
            } else {
                response = DeviceDomainEnum.REMOTER_CONTROL == stopGatewayDomain
                        ? abstractControlService.flyToPointStopRc(SDKManager.getDeviceSDK(sn))
                        : abstractControlService.flyToPointStop(SDKManager.getDeviceSDK(sn));
            }
        } catch (RuntimeException exception) {
            pointFlightTaskStore.recordCancelRequested(sn, true, exception.getMessage());
            throw exception;
        }
        ServicesReplyData reply = response == null ? null : response.getData();
        if (reply == null || reply.getResult() == null) {
            pointFlightTaskStore.recordCancelRequested(
                    sn, true, "The device reply was empty.");
            return HttpResultResponse.error(
                "Point-flight cancellation status is unknown. It is safe to retry the cancel command.");
        }
        if (reply.getResult().isSuccess()) {
            // 设备已确认停止：进入终态并释放额度。若仅停留在 cancel_requested，
            // 从未真正启动过的任务将阻塞后续指令直至 TTL 过期。
            pointFlightTaskStore.recordCancelConfirmed(sn, "Cancel command accepted.");
            if ("takeoff".equals(taskKind) && StringUtils.hasText(takeoffFlightId)) {
                pointFlightTaskStore.clearLastAcceptedTakeoffId(sn, takeoffFlightId);
            }
            return HttpResultResponse.success();
        }
        pointFlightTaskStore.recordCancelFailure(sn, reply.getResult().toString());
        return HttpResultResponse.error(
                "The point-flight task failed to cancel. " + reply.getResult());
    }

    @Override
    public HttpResultResponse releaseStaleFlightSessions(String sn) {
        if (pointFlightTaskStore.hasPotentiallyActiveTask(sn)) {
            return HttpResultResponse.error(
                    "A point-flight task is still active. It will not be canceled automatically.");
        }
        requireOnlineGatewayAndAircraft(sn);
        HttpResultResponse authority = seizeAuthority(sn, DroneAuthorityEnum.FLIGHT, null);
        if (HttpResultResponse.CODE_SUCCESS != authority.getCode()) {
            return authority;
        }
        boolean rcGateway = isRcGateway(sn);
        // 先清 flyto 残留会话；失败不阻断后续 takeoff 清理。
        try {
            TopicServicesResponse<ServicesReplyData> stopReply = rcGateway
                    ? abstractControlService.flyToPointStopRc(SDKManager.getDeviceSDK(sn))
                    : abstractControlService.flyToPointStop(SDKManager.getDeviceSDK(sn));
            log.info("【残留清理】fly_to_point_stop sn={} result={}", sn,
                    Optional.ofNullable(stopReply)
                            .map(TopicServicesResponse::getData)
                            .map(ServicesReplyData::getResult)
                            .orElse(null));
        } catch (RuntimeException exception) {
            log.warn("【残留清理】fly_to_point_stop 发送失败 sn={}", sn, exception);
        }
        // RC 不上报 takeoff 终结事件，内部任务会一直挂着并以 104 拒绝航线执行，
        // 必须用 flighttask_undo + 当时的 flight_id 定向清除。
        Optional<String> takeoffId = pointFlightTaskStore.getLastAcceptedTakeoffId(sn);
        if (takeoffId.isPresent()) {
            FlighttaskUndoRequest undoRequest = new FlighttaskUndoRequest()
                    .setFlightIds(List.of(takeoffId.get()));
            TopicServicesResponse<ServicesReplyData> undoReply = rcGateway
                    ? abstractWaylineService.flighttaskUndoRc(SDKManager.getDeviceSDK(sn), undoRequest)
                    : abstractWaylineService.flighttaskUndo(SDKManager.getDeviceSDK(sn), undoRequest);
            ServicesReplyData undoData = undoReply == null ? null : undoReply.getData();
            boolean undoAccepted = undoData != null && undoData.getResult() != null
                    && undoData.getResult().isSuccess();
            log.info("【残留清理】flighttask_undo sn={} flightId={} accepted={}",
                    sn, takeoffId.get(), undoAccepted);
            if (undoAccepted) {
                pointFlightTaskStore.clearLastAcceptedTakeoffId(sn, takeoffId.get());
            }
        }
        return HttpResultResponse.success();
    }

    @Override
    public HttpResultResponse getPointFlightState(String sn) {
        return HttpResultResponse.success(pointFlightTaskStore.get(sn).orElse(null));
    }

    private void checkTakeoffCondition(String gatewaySn) {
        DeviceDTO gateway = deviceRedisService.getDeviceOnline(gatewaySn)
                .orElseThrow(() -> new RuntimeException("The gateway is offline, please reconnect the device."));

        if (DeviceDomainEnum.DOCK == gateway.getDomain()) {
            if (DockModeCodeEnum.IDLE != deviceService.getDockMode(gatewaySn)) {
                throw new RuntimeException("The current dock state does not support takeoff.");
            }
        } else if (DeviceDomainEnum.REMOTER_CONTROL == gateway.getDomain()) {
            String aircraftSn = gateway.getChildDeviceSn();
            if (aircraftSn == null || deviceRedisService.getDeviceOnline(aircraftSn).isEmpty()) {
                throw new RuntimeException("The aircraft is offline, please reconnect the aircraft.");
            }
            if (DroneModeCodeEnum.IDLE != deviceService.getDeviceMode(aircraftSn)) {
                throw new RuntimeException("The aircraft must be on the ground and idle before takeoff.");
            }
        } else {
            throw new RuntimeException("The current gateway type does not support takeoff.");
        }

        HttpResultResponse result = seizeAuthority(gatewaySn, DroneAuthorityEnum.FLIGHT, null);
        if (HttpResultResponse.CODE_SUCCESS != result.getCode()) {
            throw new IllegalArgumentException(result.getMessage());
        }

    }

    @Override
    public HttpResultResponse takeoffToPoint(String sn, TakeoffToPointParam param) {
        if (pointFlightTaskStore.hasPotentiallyActiveTask(sn)) {
            return HttpResultResponse.error(
                    "A point-flight command is already active or awaiting confirmation. Resolve it before takeoff.");
        }
        // Autel smart-controller gateways validate these safety fields even
        // though the direct-cloud command uses the compact takeoff payload.
        // Supply conservative defaults when the cockpit only specifies the
        // destination and speed.
        if (param.getSecurityTakeoffHeight() == null) {
            param.setSecurityTakeoffHeight(20D);
        }
        if (param.getRthAltitude() == null) {
            param.setRthAltitude(20D);
        }
        if (param.getRcLostAction() == null) {
            param.setRcLostAction(RcLostActionEnum.RETURN_HOME);
        }
        param.setFlightId(UUID.randomUUID().toString());
        if (!pointFlightTaskStore.tryRecordPending(sn, "takeoff", param.getFlightId())) {
            return HttpResultResponse.error(
                    "A point-flight command is already active or awaiting confirmation. Resolve it before takeoff.");
        }
        TakeoffToPointRequest request;
        DeviceDomainEnum gatewayDomain;
        try {
            checkTakeoffCondition(sn);
            request = mapper.convertValue(param, TakeoffToPointRequest.class);
            gatewayDomain = deviceRedisService.getDeviceOnline(sn)
                    .map(DeviceDTO::getDomain)
                    .orElse(null);
        } catch (RuntimeException exception) {
            pointFlightTaskStore.recordFailure(
                    sn, "takeoff", param.getFlightId(), exception.getMessage());
            throw exception;
        }
        TopicServicesResponse<ServicesReplyData> response;
        try {
            response = DeviceDomainEnum.REMOTER_CONTROL == gatewayDomain
                    ? abstractControlService.takeoffToPointRc(SDKManager.getDeviceSDK(sn), request)
                    : abstractControlService.takeoffToPoint(SDKManager.getDeviceSDK(sn), request);
        } catch (RuntimeException exception) {
            pointFlightTaskStore.recordUnknown(
                    sn, "takeoff", param.getFlightId(), exception.getMessage());
            throw exception;
        }
        ServicesReplyData reply = response == null ? null : response.getData();
        if (reply == null || reply.getResult() == null) {
            pointFlightTaskStore.recordUnknown(
                    sn, "takeoff", param.getFlightId(), "The device reply was empty.");
            return HttpResultResponse.error(
                    "Takeoff command status is unknown. Check task status before retrying.");
        }
        if (reply.getResult().isSuccess()) {
            pointFlightTaskStore.recordAccepted(sn, "takeoff", param.getFlightId());
            return HttpResultResponse.success();
        }
        pointFlightTaskStore.recordFailure(
                sn, "takeoff", param.getFlightId(), reply.getResult().toString());
        return HttpResultResponse.error("The drone failed to take off. " + reply.getResult());
    }

    @Override
    public HttpResultResponse seizeAuthority(String sn, DroneAuthorityEnum authority, DronePayloadParam param) {
        return seizeAuthority(sn, authority, param, false);
    }

    @Override
    public HttpResultResponse seizeAuthority(
            String sn,
            DroneAuthorityEnum authority,
            DronePayloadParam param,
            boolean force) {
        DeviceDTO gatewayDevice = requireOnlineGatewayAndAircraft(sn);
        TopicServicesResponse<ServicesReplyData> response;
        switch (authority) {
            case FLIGHT:
                if (!force && deviceService.checkAuthorityFlight(sn)) {
                    log.info("【抢权】飞控权缓存命中，跳过发送 flight_authority_grab sn={}", sn);
                    return HttpResultResponse.success();
                }
                log.info("【抢权】发送 flight_authority_grab sn={} domain={} force={}",
                        sn, gatewayDevice.getDomain(), force);
                // RC 网关需 device_list 寻址无人机，否则指令被静默丢弃（211001）。
                response = DeviceDomainEnum.REMOTER_CONTROL == gatewayDevice.getDomain()
                        ? abstractControlService.flightAuthorityGrabRc(SDKManager.getDeviceSDK(sn))
                        : abstractControlService.flightAuthorityGrab(SDKManager.getDeviceSDK(sn));
                break;
            case PAYLOAD:
                if (param == null || !StringUtils.hasText(param.getPayloadIndex())) {
                    return HttpResultResponse.error(CloudSDKErrorEnum.INVALID_PARAMETER);
                }
                if (!force && checkPayloadAuthority(sn, param.getPayloadIndex())) {
                    log.info("【抢权】负载控制权缓存命中，跳过发送 payload_authority_grab sn={} payload={}",
                            sn, param.getPayloadIndex());
                    publishPayloadAuthorityState(sn, param.getPayloadIndex(), true,
                            0, "已取得负载控制权", null);
                    return HttpResultResponse.success();
                }
                log.info("【抢权】发送 payload_authority_grab sn={} payload={} domain={} force={}",
                        sn, param.getPayloadIndex(), gatewayDevice.getDomain(), force);
                PayloadAuthorityGrabRequest grabRequest = new PayloadAuthorityGrabRequest()
                        .setPayloadIndex(new PayloadIndex(param.getPayloadIndex()));
                // RC 网关需 device_list 寻址无人机，否则指令被静默丢弃（211001）。
                response = DeviceDomainEnum.REMOTER_CONTROL == gatewayDevice.getDomain()
                        ? abstractControlService.payloadAuthorityGrabRc(SDKManager.getDeviceSDK(sn), grabRequest)
                        : abstractControlService.payloadAuthorityGrab(SDKManager.getDeviceSDK(sn), grabRequest);
                break;
            default:
                return HttpResultResponse.error(CloudSDKErrorEnum.INVALID_PARAMETER);
        }

        ServicesReplyData serviceReply = response.getData();
        log.info("【抢权】设备回复 sn={} authority={} result={} success={}",
                sn, authority, serviceReply.getResult(), serviceReply.getResult().isSuccess());
        if (!serviceReply.getResult().isSuccess()) {
            return HttpResultResponse.error(serviceReply.getResult());
        }
        if (DroneAuthorityEnum.FLIGHT == authority) {
            deviceRedisService.getDeviceOnline(sn)
                    .ifPresentOrElse(
                            gateway -> deviceService.updateFlightControl(gateway, ControlSourceEnum.A),
                            () -> log.warn("Unable to cache confirmed flight authority: gateway {} is offline", sn));
        } else if (DroneAuthorityEnum.PAYLOAD == authority && param != null) {
            payloadAuthorityCacheService.confirm(sn, param.getPayloadIndex());
        }
        return HttpResultResponse.success();
    }

    /**
     * The MQTT services_reply belongs to the whole workspace, not only to the
     * HTTP caller that initiated the command. Include the payload index and
     * broadcast the confirmed result so every open cockpit can update the same
     * authority state.
     */
    private void publishPayloadAuthorityState(
            String gatewaySn,
            String payloadIndex,
            boolean success,
            Integer result,
            String message,
            TopicServicesResponse<?> response) {
        Optional<String> workspaceId = deviceService.getDeviceBySn(gatewaySn)
                .map(DeviceDTO::getWorkspaceId)
                .filter(value -> value != null && !value.isBlank());
        if (workspaceId.isEmpty()) {
            log.warn("无法广播负载控制权状态，网关未绑定工作空间: gateway={}, payload={}",
                    gatewaySn, payloadIndex);
            return;
        }

        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("gateway_sn", gatewaySn);
        notification.put("payload_index", payloadIndex);
        notification.put("method", DroneControlMethodEnum.PAYLOAD_AUTHORITY_GRAB.getMethod());
        notification.put("result", result);
        notification.put("success", success);
        notification.put("message", message);
        notification.put("tid", response == null ? null : response.getTid());
        notification.put("bid", response == null ? null : response.getBid());
        notification.put("timestamp", response == null ? System.currentTimeMillis() : response.getTimestamp());

        webSocketMessageService.sendBatch(
                workspaceId.get(),
                UserTypeEnum.WEB.getVal(),
                BizCodeEnum.PAYLOAD_AUTHORITY_GRAB.getCode(),
                notification);
        log.info("已广播负载控制权状态: gateway={}, payload={}, success={}",
                gatewaySn, payloadIndex, success);
    }

    private Boolean checkPayloadAuthority(String sn, String payloadIndex) {
        DeviceDTO gateway = requireOnlineGatewayAndAircraft(sn);
        return devicePayloadService.checkAuthorityPayload(gateway.getChildDeviceSn(), payloadIndex);
    }

    @Override
    public HttpResultResponse payloadCommands(PayloadCommandsParam param) throws Exception {
        log.info("【负载指令】收到请求 sn={} cmd={} data={}",
                param.getSn(), param.getCmd().getCmd(), toJsonSafely(param.getData()));
        PayloadCommandsHandler handler = param.getCmd().getClazz()
                .getDeclaredConstructor(DronePayloadParam.class)
                .newInstance(param.getData());
        if (!handler.checkCondition(param.getSn())) {
            log.info("【负载指令】命中幂等/无操作分支，未下发 sn={} cmd={}", param.getSn(), param.getCmd().getCmd());
            return HttpResultResponse.success();
        }

        // RC 网关下所有负载指令需 device_list 寻址无人机，否则指令被静默丢弃（211001）。
        DeviceDomainEnum gatewayDomain = deviceRedisService.getDeviceOnline(param.getSn())
                .map(DeviceDTO::getDomain)
                .orElse(null);
        Object requestModel = mapper.convertValue(param.getData(), param.getCmd().getCmd().getClazz());
        // 打印 Jackson 实际序列化结果：可核对 payload_index/locked 等字段是否被 @JsonIgnore 丢弃。
        log.info("【负载指令】准备下发 sn={} cmd={} gatewayDomain={} 分支={} 实际data={}",
                param.getSn(), param.getCmd().getCmd(), gatewayDomain,
                DeviceDomainEnum.REMOTER_CONTROL == gatewayDomain ? "RC(带device_list)" : "普通网关",
                toJsonSafely(requestModel));
        TopicServicesResponse<ServicesReplyData> response;
        if (DeviceDomainEnum.REMOTER_CONTROL == gatewayDomain) {
            response = abstractControlService.payloadControlRc(
                    SDKManager.getDeviceSDK(param.getSn()), param.getCmd().getCmd(),
                    mapper.convertValue(param.getData(), param.getCmd().getCmd().getClazz()));
        } else {
            response = abstractControlService.payloadControl(
                    SDKManager.getDeviceSDK(param.getSn()), param.getCmd().getCmd(),
                    mapper.convertValue(param.getData(), param.getCmd().getCmd().getClazz()));
        }

        ServicesReplyData serviceReply = response.getData();
        log.info("【负载指令】设备回复 sn={} cmd={} result={} success={}",
                param.getSn(), param.getCmd().getCmd(), serviceReply.getResult(),
                serviceReply.getResult().isSuccess());
        return serviceReply.getResult().isSuccess() ?
                HttpResultResponse.success()
                : HttpResultResponse.error(serviceReply.getResult());
    }

    private String toJsonSafely(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    @Override
    public HttpResultResponse openTargetDetection(String sn, TargetDetectOpenRequest param) {
        requirePayloadAuthority(sn);
        // RC 网关需 device_list 寻址无人机，否则指令被静默丢弃（211001）。
        TopicServicesResponse<ServicesReplyData> response = isRcGateway(sn)
                ? abstractControlService.targetDetectOpenRc(SDKManager.getDeviceSDK(sn), param)
                : abstractControlService.targetDetectOpen(SDKManager.getDeviceSDK(sn), param);
        ServicesReplyData reply = response.getData();
        return reply.getResult().isSuccess()
                ? HttpResultResponse.success()
                : HttpResultResponse.error(reply.getResult());
    }

    @Override
    public HttpResultResponse closeTargetDetection(String sn) {
        requirePayloadAuthority(sn);
        TopicServicesResponse<ServicesReplyData> response = isRcGateway(sn)
                ? abstractControlService.targetDetectCloseRc(SDKManager.getDeviceSDK(sn))
                : abstractControlService.targetDetectClose(SDKManager.getDeviceSDK(sn));
        ServicesReplyData reply = response.getData();
        return reply.getResult().isSuccess()
                ? HttpResultResponse.success()
                : HttpResultResponse.error(reply.getResult());
    }

    private boolean isRcGateway(String gatewaySn) {
        return DeviceDomainEnum.REMOTER_CONTROL == deviceRedisService.getDeviceOnline(gatewaySn)
                .map(DeviceDTO::getDomain)
                .orElse(null);
    }

    private DeviceDTO requireOnlineGatewayAndAircraft(String gatewaySn) {
        DeviceDTO gateway = deviceRedisService.getDeviceOnline(gatewaySn)
                .orElseThrow(() -> new RuntimeException("The gateway is offline, please reconnect the device."));
        if (!StringUtils.hasText(gateway.getChildDeviceSn())) {
            throw new RuntimeException("The gateway is not connected to an aircraft.");
        }
        if (deviceRedisService.getDeviceOnline(gateway.getChildDeviceSn()).isEmpty()) {
            throw new RuntimeException("The aircraft is offline, please reconnect the aircraft.");
        }
        return gateway;
    }

    private void requirePayloadAuthority(String gatewaySn) {
        DeviceDTO gateway = requireOnlineGatewayAndAircraft(gatewaySn);
        DeviceDTO aircraft = deviceRedisService.getDeviceOnline(gateway.getChildDeviceSn())
                .orElseThrow(() -> new RuntimeException("The aircraft is offline, please reconnect the aircraft."));
        boolean hasAuthority = !CollectionUtils.isEmpty(aircraft.getPayloadsList())
                && aircraft.getPayloadsList().stream()
                .filter(Objects::nonNull)
                .anyMatch(payload -> ControlSourceEnum.A == payload.getControlSource());
        if (!hasAuthority) {
            throw new RuntimeException("The device does not have payload control authority.");
        }
    }
}
