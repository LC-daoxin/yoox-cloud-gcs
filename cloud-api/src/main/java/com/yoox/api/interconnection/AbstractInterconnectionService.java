package com.yoox.api.interconnection;

import com.yoox.great.context.annotations.CloudSDKVersion;
import com.yoox.great.context.enums.version.CloudSDKVersionEnum;
import com.yoox.great.context.enums.version.GatewayManager;
import com.yoox.great.context.enums.version.GatewayTypeEnum;
import com.yoox.great.mqtt.core.consume.MqttReply;
import com.yoox.great.mqtt.constant.ChannelName;
import com.yoox.great.mqtt.enums.interconnection.InterconnectionMethodEnum;
import com.yoox.great.mqtt.handle.events.TopicEventsRequest;
import com.yoox.great.mqtt.handle.events.TopicEventsResponse;
import com.yoox.great.mqtt.model.interconnection.CustomDataTransmissionFromEsdk;
import com.yoox.great.mqtt.model.interconnection.CustomDataTransmissionToEsdkRequest;
import com.yoox.great.mqtt.model.interconnection.CustomDataTransmissionToPsdkRequest;
import com.yoox.great.mqtt.handle.services.ServicesPublish;
import com.yoox.great.mqtt.handle.services.ServicesReplyData;
import com.yoox.great.mqtt.handle.services.TopicServicesResponse;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.messaging.MessageHeaders;

import javax.annotation.Resource;

public abstract class AbstractInterconnectionService {

    @Resource
    private ServicesPublish servicesPublish;

    @ServiceActivator(inputChannel = ChannelName.INBOUND_EVENTS_CUSTOM_DATA_TRANSMISSION_FROM_ESDK, outputChannel = ChannelName.OUTBOUND_EVENTS)
    @CloudSDKVersion(since = CloudSDKVersionEnum.V1_0_0)
    public TopicEventsResponse<MqttReply> customDataTransmissionFromEsdk(TopicEventsRequest<CustomDataTransmissionFromEsdk> request, MessageHeaders headers) {
        throw new UnsupportedOperationException("customDataTransmissionFromEsdk not implemented");
    }

    @CloudSDKVersion(since = CloudSDKVersionEnum.V1_0_0, exclude = GatewayTypeEnum.RC)
    public TopicServicesResponse<ServicesReplyData> customDataTransmissionToEsdk(GatewayManager gateway, CustomDataTransmissionToEsdkRequest request) {
        return servicesPublish.publish(
                gateway.getGatewaySn(),
                InterconnectionMethodEnum.CUSTOM_DATA_TRANSMISSION_TO_ESDK.getMethod(),
                request);
    }

    @ServiceActivator(inputChannel = ChannelName.INBOUND_EVENTS_CUSTOM_DATA_TRANSMISSION_FROM_PSDK, outputChannel = ChannelName.OUTBOUND_EVENTS)
    @CloudSDKVersion(since = CloudSDKVersionEnum.V1_0_0)
    public TopicEventsResponse<MqttReply> customDataTransmissionFromPsdk(TopicEventsRequest<CustomDataTransmissionFromEsdk> request, MessageHeaders headers) {
        throw new UnsupportedOperationException("customDataTransmissionFromPsdk not implemented");
    }

    @CloudSDKVersion(since = CloudSDKVersionEnum.V1_0_0, exclude = GatewayTypeEnum.RC)
    public TopicServicesResponse<ServicesReplyData> customDataTransmissionToPsdk(GatewayManager gateway, CustomDataTransmissionToPsdkRequest request) {
        return servicesPublish.publish(
                gateway.getGatewaySn(),
                InterconnectionMethodEnum.CUSTOM_DATA_TRANSMISSION_TO_PSDK.getMethod(),
                request);
    }
}
