package com.yoox.api.wayline;

import com.yoox.great.context.annotations.CloudSDKVersion;
import com.yoox.great.context.base.Common;
import com.yoox.great.context.enums.version.CloudSDKVersionEnum;
import com.yoox.great.context.enums.version.GatewayManager;
import com.yoox.great.context.enums.version.GatewayTypeEnum;
import com.yoox.great.context.exception.CloudSDKErrorEnum;
import com.yoox.great.context.exception.CloudSDKException;
import com.yoox.great.mqtt.core.consume.MqttReply;
import com.yoox.great.mqtt.constant.ChannelName;
import com.yoox.great.mqtt.enums.wayline.TaskTypeEnum;
import com.yoox.great.mqtt.enums.wayline.WaylineMethodEnum;
import com.yoox.great.mqtt.handle.events.EventsDataRequest;
import com.yoox.great.mqtt.handle.events.TopicEventsRequest;
import com.yoox.great.mqtt.handle.events.TopicEventsResponse;
import com.yoox.great.mqtt.model.wayline.*;
import com.yoox.great.mqtt.handle.requests.TopicRequestsRequest;
import com.yoox.great.mqtt.handle.requests.TopicRequestsResponse;
import com.yoox.great.mqtt.handle.services.ServicesPublish;
import com.yoox.great.mqtt.handle.services.ServicesReplyData;
import com.yoox.great.mqtt.handle.services.TopicServicesResponse;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.messaging.MessageHeaders;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

public abstract class AbstractWaylineService {

    @Resource
    private ServicesPublish servicesPublish;

    @ServiceActivator(inputChannel = ChannelName.INBOUND_EVENTS_DEVICE_EXIT_HOMING_NOTIFY, outputChannel = ChannelName.OUTBOUND_EVENTS)
    public TopicEventsResponse<MqttReply> deviceExitHomingNotify(TopicEventsRequest<DeviceExitHomingNotify> request, MessageHeaders headers) {
        throw new UnsupportedOperationException("deviceExitHomingNotify not implemented");
    }

    @ServiceActivator(inputChannel = ChannelName.INBOUND_EVENTS_FLIGHTTASK_PROGRESS, outputChannel = ChannelName.OUTBOUND_EVENTS)
    public TopicEventsResponse<MqttReply> flighttaskProgress(TopicEventsRequest<EventsDataRequest<FlighttaskProgress>> request, MessageHeaders headers) {
        throw new UnsupportedOperationException("flighttaskProgress not implemented");
    }

    public TopicEventsResponse<MqttReply> flighttaskReady(TopicEventsRequest<FlighttaskReady> request, MessageHeaders headers) {
        throw new UnsupportedOperationException("flighttaskReady not implemented");
    }

    @CloudSDKVersion(deprecated = CloudSDKVersionEnum.V0_0_1, exclude = GatewayTypeEnum.RC)
    public TopicServicesResponse<ServicesReplyData> flighttaskCreate(GatewayManager gateway, FlighttaskCreateRequest request) {
        return servicesPublish.publish(
                gateway.getGatewaySn(),
                WaylineMethodEnum.FLIGHTTASK_CREATE.getMethod(),
                request);
    }

    @CloudSDKVersion(exclude = GatewayTypeEnum.RC)
    public TopicServicesResponse<ServicesReplyData> flighttaskPrepare(GatewayManager gateway, FlighttaskPrepareRequest request) {
        validPrepareParam(request);
        return servicesPublish.publish(
                gateway.getGatewaySn(),
                WaylineMethodEnum.FLIGHTTASK_PREPARE.getMethod(),
                request,
                request.getFlightId());
    }

    /**
     * RC 网关把无人机作为子设备管理，航线任务同样需要 device_list 显式寻址无人机
     * （理由同 returnHomeRc），否则遥控器静默丢弃指令、永不回复，表现为 211001。
     */
    public TopicServicesResponse<ServicesReplyData> flighttaskPrepareRc(GatewayManager gateway, FlighttaskPrepareRequest request) {
        validPrepareParam(request);
        return servicesPublish.publish(
                gateway.getGatewaySn(),
                WaylineMethodEnum.FLIGHTTASK_PREPARE.getMethod(),
                request,
                request.getFlightId(),
                List.of(Map.of("sn", gateway.getDroneSn())));
    }

    @CloudSDKVersion(exclude = GatewayTypeEnum.RC)
    public TopicServicesResponse<ServicesReplyData> flighttaskExecute(GatewayManager gateway, FlighttaskExecuteRequest request) {
        return servicesPublish.publish(
                gateway.getGatewaySn(),
                WaylineMethodEnum.FLIGHTTASK_EXECUTE.getMethod(),
                request,
                request.getFlightId());
    }

    // RC 网关需 device_list 寻址无人机，理由同 flighttaskPrepareRc。
    public TopicServicesResponse<ServicesReplyData> flighttaskExecuteRc(GatewayManager gateway, FlighttaskExecuteRequest request) {
        return servicesPublish.publish(
                gateway.getGatewaySn(),
                WaylineMethodEnum.FLIGHTTASK_EXECUTE.getMethod(),
                request,
                request.getFlightId(),
                List.of(Map.of("sn", gateway.getDroneSn())));
    }

    @CloudSDKVersion(exclude = GatewayTypeEnum.RC)
    public TopicServicesResponse<ServicesReplyData> flighttaskUndo(GatewayManager gateway, FlighttaskUndoRequest request) {
        return servicesPublish.publish(
                gateway.getGatewaySn(),
                WaylineMethodEnum.FLIGHTTASK_UNDO.getMethod(),
                request);
    }

    // RC 网关需 device_list 寻址无人机，理由同 flighttaskPrepareRc。
    public TopicServicesResponse<ServicesReplyData> flighttaskUndoRc(GatewayManager gateway, FlighttaskUndoRequest request) {
        return servicesPublish.publish(
                gateway.getGatewaySn(),
                WaylineMethodEnum.FLIGHTTASK_UNDO.getMethod(),
                request,
                List.of(Map.of("sn", gateway.getDroneSn())));
    }

    @CloudSDKVersion(exclude = GatewayTypeEnum.RC)
    public TopicServicesResponse<ServicesReplyData> flighttaskPause(GatewayManager gateway) {
        return servicesPublish.publish(
                gateway.getGatewaySn(),
                WaylineMethodEnum.FLIGHTTASK_PAUSE.getMethod());
    }

    // RC 网关需 device_list 寻址无人机，理由同 flighttaskPrepareRc。
    public TopicServicesResponse<ServicesReplyData> flighttaskPauseRc(GatewayManager gateway) {
        return servicesPublish.publish(
                gateway.getGatewaySn(),
                WaylineMethodEnum.FLIGHTTASK_PAUSE.getMethod(),
                null,
                List.of(Map.of("sn", gateway.getDroneSn())));
    }

    @CloudSDKVersion(exclude = GatewayTypeEnum.RC)
    public TopicServicesResponse<ServicesReplyData> flighttaskRecovery(GatewayManager gateway) {
        return servicesPublish.publish(
                gateway.getGatewaySn(),
                WaylineMethodEnum.FLIGHTTASK_RECOVERY.getMethod());
    }

    // RC 网关需 device_list 寻址无人机，理由同 flighttaskPrepareRc。
    public TopicServicesResponse<ServicesReplyData> flighttaskRecoveryRc(GatewayManager gateway) {
        return servicesPublish.publish(
                gateway.getGatewaySn(),
                WaylineMethodEnum.FLIGHTTASK_RECOVERY.getMethod(),
                null,
                List.of(Map.of("sn", gateway.getDroneSn())));
    }

    // RC/App gateways also implement return_home on the gateway services topic.
    // A result=0 services_reply only confirms invocation; flight completion is
    // reported separately by aircraft state/events.
    public TopicServicesResponse<ServicesReplyData> returnHome(GatewayManager gateway) {
        return servicesPublish.publish(
                gateway.getGatewaySn(),
                WaylineMethodEnum.RETURN_HOME.getMethod());
    }

    @CloudSDKVersion(exclude = GatewayTypeEnum.RC)
    public TopicServicesResponse<ServicesReplyData> returnHomeCancel(GatewayManager gateway) {
        return servicesPublish.publish(
                gateway.getGatewaySn(),
                WaylineMethodEnum.RETURN_HOME_CANCEL.getMethod());
    }

    /**
     * EVO RC 实测（固件 1.9.1.203，2026-08-12 MQTT 直连 A/B 验证）：return_home 必须
     * 发送 data={}（空对象）并携带 device_list，固件才回复 services_reply；
     * data:null（无论带不带 device_list）都会被静默丢弃 → 211001。
     * 离线文档"data 为 null、不带 device_list"的说法与实机行为不符，以实测为准。
     */
    public TopicServicesResponse<ServicesReplyData> returnHomeRc(GatewayManager gateway) {
        return servicesPublish.publish(
                gateway.getGatewaySn(),
                WaylineMethodEnum.RETURN_HOME.getMethod(),
                Map.of(),
                List.of(Map.of("sn", gateway.getDroneSn())));
    }

    // 同 returnHomeRc：EVO RC 要求 data={} + device_list。
    public TopicServicesResponse<ServicesReplyData> returnHomeCancelRc(GatewayManager gateway) {
        return servicesPublish.publish(
                gateway.getGatewaySn(),
                WaylineMethodEnum.RETURN_HOME_CANCEL.getMethod(),
                Map.of(),
                List.of(Map.of("sn", gateway.getDroneSn())));
    }

    @ServiceActivator(inputChannel = ChannelName.INBOUND_REQUESTS_FLIGHTTASK_RESOURCE_GET, outputChannel = ChannelName.OUTBOUND_REQUESTS)
    public TopicRequestsResponse<MqttReply<FlighttaskResourceGetResponse>> flighttaskResourceGet(TopicRequestsRequest<FlighttaskResourceGetRequest> request, MessageHeaders headers) {
        throw new UnsupportedOperationException("flighttaskResourceGet not implemented");
    }

    @ServiceActivator(inputChannel = ChannelName.INBOUND_EVENTS_RETURN_HOME_INFO, outputChannel = ChannelName.OUTBOUND_EVENTS)
    @CloudSDKVersion(since = CloudSDKVersionEnum.V1_0_0)
    public TopicRequestsResponse<MqttReply> returnHomeInfo(TopicRequestsRequest<ReturnHomeInfo> request, MessageHeaders headers) {
        throw new UnsupportedOperationException("returnHomeInfo not implemented");
    }

    private void validPrepareParam(FlighttaskPrepareRequest request) {
        if (null == request.getExecuteTime()
                && (TaskTypeEnum.IMMEDIATE == request.getTaskType() || TaskTypeEnum.TIMED == request.getTaskType())) {
            throw new CloudSDKException(CloudSDKErrorEnum.INVALID_PARAMETER, "Execute time must not be null.");
        }
        if (TaskTypeEnum.CONDITIONAL == request.getTaskType()) {
            Common.validateModel(request.getReadyConditions());
        }
    }

}
