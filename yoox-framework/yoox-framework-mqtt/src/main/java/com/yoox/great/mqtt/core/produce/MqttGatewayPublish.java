package com.yoox.great.mqtt.core.produce;

import com.yoox.great.context.base.Common;
import com.yoox.great.context.exception.CloudSDKErrorEnum;
import com.yoox.great.context.exception.CloudSDKException;
import com.yoox.great.mqtt.constant.TopicConst;
import com.yoox.great.mqtt.core.CommonTopicRequest;
import com.yoox.great.mqtt.core.CommonTopicResponse;
import com.yoox.great.mqtt.core.sync.Chan;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.MessageHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class MqttGatewayPublish {

    private static final Logger log = LoggerFactory.getLogger(MqttGatewayPublish.class);

    private static final int DEFAULT_QOS = 0;
    public static final int DEFAULT_RETRY_COUNT = 2;
    public static final int DEFAULT_RETRY_TIMEOUT = 3000;

    @Resource
    private IMqttMessageGateway messageGateway;

    public void publish(String topic, int qos, CommonTopicRequest request) {
        try {
            log.debug("send topic: {}, payload: {}", topic, request.toString());
            byte[] payload = Common.getObjectMapper().writeValueAsBytes(request);
            messageGateway.publish(topic, payload, qos);
        } catch (JsonProcessingException e) {
            log.error("Failed to publish the message. {}", request.toString());
            e.printStackTrace();
        }
    }

    public void publish(String topic, int qos, CommonTopicResponse response) {
        try {
            log.debug("send topic: {}, payload: {}", topic, response.toString());
            byte[] payload = Common.getObjectMapper().writeValueAsBytes(response);
            messageGateway.publish(topic, payload, qos);
        } catch (JsonProcessingException e) {
            log.error("Failed to publish the message. {}", response.toString());
            e.printStackTrace();
        }
    }

    public void publish(String topic, CommonTopicRequest request, int publishCount) {
        AtomicInteger time = new AtomicInteger(0);
        while (time.getAndIncrement() < publishCount) {
            this.publish(topic, DEFAULT_QOS, request);
        }
    }

    public void publish(String topic, CommonTopicRequest request) {
        this.publish(topic, DEFAULT_QOS, request);
    }

    public void publishReply(CommonTopicResponse response, MessageHeaders headers) {
        this.publish(headers.get(MqttHeaders.RECEIVED_TOPIC) + TopicConst._REPLY_SUF, 2, response);
    }

    public <T> CommonTopicResponse<T> publishWithReply(Class<T> clazz, String topic, CommonTopicRequest request, int retryCount, long timeout) {
        AtomicInteger time = new AtomicInteger(0);
        boolean hasBid = StringUtils.hasText(request.getBid());
        request.setBid(hasBid ? request.getBid() : UUID.randomUUID().toString());
        while (time.getAndIncrement() <= retryCount) {
            Chan chan = Chan.getInstance(request.getTid(), true).setRequest(request);
            // 诊断日志：完整打印每次下发的报文，便于定位 211001（无回复）问题。
            log.info("【services下发】attempt={}/{} topic={} payload={}",
                    time.get(), retryCount + 1, topic, toJson(request));
            long publishedAt = System.currentTimeMillis();
            this.publish(topic, request);

            CommonTopicResponse<T> receiver = chan.get(request.getTid(), timeout);
            if (Objects.nonNull(receiver)
                    && receiver.getTid().equals(request.getTid())
                    && receiver.getBid().equals(request.getBid())) {
                if (clazz.isAssignableFrom(receiver.getData().getClass())) {
                    log.info("【services下发】收到匹配回复 topic={} tid={} 耗时={}ms",
                            topic, request.getTid(), System.currentTimeMillis() - publishedAt);
                    return receiver;
                }
                throw new TypeMismatchException(receiver.getData(), clazz);
            }
            if (Objects.isNull(receiver)) {
                log.warn("【services下发】attempt={}/{} 等待回复超时({}ms) topic={} tid={} bid={}",
                        time.get(), retryCount + 1, timeout, topic, request.getTid(), request.getBid());
            } else {
                // tid 匹配（Chan 按 tid 唤醒）但 bid 不一致：设备回包未正确回显 bid。
                log.warn("【services下发】attempt={}/{} 回复关联ID不匹配 topic={} 期望tid={}/bid={} 实际tid={}/bid={}",
                        time.get(), retryCount + 1, topic, request.getTid(), request.getBid(),
                        receiver.getTid(), receiver.getBid());
            }
            if (!hasBid) {
                request.setBid(UUID.randomUUID().toString());
            }
            request.setTid(UUID.randomUUID().toString());
        }
        log.error("【services下发】全部{}次尝试均未收到回复，即将抛出211001 topic={} 最后payload={}",
                retryCount + 1, topic, toJson(request));
        throw new CloudSDKException(CloudSDKErrorEnum.MQTT_PUBLISH_ABNORMAL, "No message reply received.");
    }

    private String toJson(Object value) {
        try {
            return Common.getObjectMapper().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }


}
