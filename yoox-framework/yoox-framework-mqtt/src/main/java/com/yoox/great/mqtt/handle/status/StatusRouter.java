package com.yoox.great.mqtt.handle.status;

import com.yoox.great.context.base.Common;
import com.yoox.great.context.exception.CloudSDKException;
import com.yoox.great.mqtt.constant.ChannelName;
import com.yoox.great.mqtt.constant.TopicConst;
import com.yoox.great.mqtt.core.produce.MqttGatewayPublish;
import com.yoox.great.mqtt.model.device.UpdateTopo;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.IntegrationFlows;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Configuration
public class StatusRouter {

    @Resource
    private MqttGatewayPublish gatewayPublish;

    @Bean
    public IntegrationFlow statusRouterFlow() {
        return IntegrationFlows
                .from(ChannelName.INBOUND_STATUS)
                .transform(Message.class, source -> {
                    try {
                        TopicStatusRequest<UpdateTopo> response = Common.getObjectMapper().readValue((byte[]) source.getPayload(), new TypeReference<TopicStatusRequest<UpdateTopo>>() {
                        });
                        log.info("【status】 router log info print:{}", response);
                        String topic = String.valueOf(source.getHeaders().get(MqttHeaders.RECEIVED_TOPIC));
                        return response.setFrom(topic.substring((TopicConst.BASIC_PRE + TopicConst.PRODUCT).length(), topic.indexOf(TopicConst.STATUS_SUF)));
                    } catch (IOException e) {
                        throw new CloudSDKException(e);
                    }
                }, null)
                .<TopicStatusRequest<UpdateTopo>, Boolean>route(
                        response -> Optional.ofNullable(response.getData()).map(UpdateTopo::getSubDevices).map(CollectionUtils::isEmpty).orElse(true),
                        mapping -> mapping.channelMapping(true, ChannelName.INBOUND_STATUS_OFFLINE)
                                .channelMapping(false, ChannelName.INBOUND_STATUS_ONLINE))
                .get();
    }

    @Bean
    public IntegrationFlow replySuccessStatus() {
        return IntegrationFlows
                .from(ChannelName.OUTBOUND_STATUS)
                .handle(this::publish)
                .nullChannel();

    }

    private TopicStatusResponse publish(TopicStatusResponse request, MessageHeaders headers) {
        if (Objects.isNull(request)) {
            return null;
        }
        gatewayPublish.publishReply(request, headers);
        return request;
    }
}