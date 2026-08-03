package com.yoox.great.mqtt.handle.services;

import com.yoox.great.context.base.Common;
import com.yoox.great.mqtt.core.sync.Chan;
import com.yoox.great.mqtt.constant.ChannelName;
import com.yoox.great.mqtt.core.CommonTopicRequest;
import com.yoox.great.mqtt.enums.log.LogMethodEnum;
import com.yoox.great.mqtt.model.log.FileUploadListResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.Objects;

@Slf4j
@Component
public class ServicesReplyHandler {

    @Resource
    private ApplicationEventPublisher eventPublisher;

    @ServiceActivator(inputChannel = ChannelName.INBOUND_SERVICES_REPLY)
    public void servicesReply(Message<?> message) throws IOException {
        byte[] payload = (byte[]) message.getPayload();

        TopicServicesResponse<ServicesReplyReceiver> receiver = Common.getObjectMapper()
                .readValue(payload, new TypeReference<TopicServicesResponse<ServicesReplyReceiver>>() {
                });
        String topic = String.valueOf(message.getHeaders().get(MqttHeaders.RECEIVED_TOPIC));
        log.info("【services_reply】设备回复:{}", receiver.getData());

        Chan chan = Chan.getInstance(receiver.getTid(), false);
        CommonTopicRequest<?> request = Objects.isNull(chan) ? null : chan.getRequest();
        if (Objects.isNull(chan)) {
            eventPublisher.publishEvent(new ServicesReplyReceivedEvent(topic, receiver, null));
            return;
        }
        if (LogMethodEnum.FILE_UPLOAD_LIST.getMethod().equals(receiver.getMethod())) {
            receiver.getData().setOutput(Common.getObjectMapper().convertValue(receiver.getData(),
                    new TypeReference<FileUploadListResponse>() {
                    }));
        }
        chan.put(receiver);
        eventPublisher.publishEvent(new ServicesReplyReceivedEvent(topic, receiver, request));
    }
}
