package com.yoox.api.log;

import com.yoox.great.context.enums.version.GatewayManager;
import com.yoox.great.mqtt.core.consume.MqttReply;
import com.yoox.great.mqtt.constant.ChannelName;
import com.yoox.great.mqtt.enums.log.LogMethodEnum;
import com.yoox.great.mqtt.handle.events.EventsDataRequest;
import com.yoox.great.mqtt.handle.events.TopicEventsRequest;
import com.yoox.great.mqtt.handle.events.TopicEventsResponse;
import com.yoox.great.mqtt.model.log.*;
import com.yoox.great.mqtt.handle.services.ServicesPublish;
import com.yoox.great.mqtt.handle.services.ServicesReplyData;
import com.yoox.great.mqtt.handle.services.TopicServicesResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.messaging.MessageHeaders;

import javax.annotation.Resource;

public abstract class AbstractLogService {

    @Resource
    private ServicesPublish servicesPublish;

    @ServiceActivator(inputChannel = ChannelName.INBOUND_EVENTS_FILEUPLOAD_PROGRESS, outputChannel = ChannelName.OUTBOUND_EVENTS)
    public TopicEventsResponse<MqttReply> fileuploadProgress(TopicEventsRequest<EventsDataRequest<FileUploadProgress>> request, MessageHeaders headers) {
        throw new UnsupportedOperationException("fileuploadProgress not implemented");
    }

    public TopicServicesResponse<ServicesReplyData<FileUploadListResponse>> fileuploadList(GatewayManager gateway, FileUploadListRequest request) {
        return servicesPublish.publish(
                new TypeReference<FileUploadListResponse>() {
                },
                gateway.getGatewaySn(),
                LogMethodEnum.FILE_UPLOAD_LIST.getMethod(),
                request);
    }

    public TopicServicesResponse<ServicesReplyData> fileuploadStart(GatewayManager gateway, FileUploadStartRequest request) {
        return servicesPublish.publish(
                gateway.getGatewaySn(),
                LogMethodEnum.FILE_UPLOAD_START.getMethod(),
                request);
    }

    public TopicServicesResponse<ServicesReplyData> fileuploadUpdate(GatewayManager gateway, FileUploadUpdateRequest request) {
        return servicesPublish.publish(
                gateway.getGatewaySn(),
                LogMethodEnum.FILE_UPLOAD_UPDATE.getMethod(),
                request);
    }

}
