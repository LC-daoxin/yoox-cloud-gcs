package com.yoox.great.mqtt.handle.services;

import com.yoox.great.context.base.Common;
import com.yoox.great.mqtt.core.produce.MqttGatewayPublish;
import com.yoox.great.mqtt.constant.TopicConst;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class ServicesPublish {

    @Resource
    private MqttGatewayPublish gatewayPublish;

    public <T> TopicServicesResponse<ServicesReplyData<T>>  publish(TypeReference<T> clazz, String sn, String method) {
        return this.publish(clazz, sn, method, null);
    }

    public <T> TopicServicesResponse<ServicesReplyData<T>> publish(TypeReference<T> clazz, String sn, String method, Object data) {
        return this.publish(clazz, sn, method, data, MqttGatewayPublish.DEFAULT_RETRY_COUNT);
    }

    public <T> TopicServicesResponse<ServicesReplyData<T>>  publish(TypeReference<T> clazz, String sn, String method, Object data, int retryCount) {
        return this.publish(clazz, sn, method, data, retryCount, MqttGatewayPublish.DEFAULT_RETRY_TIMEOUT);
    }

    public <T> TopicServicesResponse<ServicesReplyData<T>>  publish(TypeReference<T> clazz, String sn, String method, Object data, long timeout) {
        return this.publish(clazz, sn, method, data, MqttGatewayPublish.DEFAULT_RETRY_COUNT, timeout);
    }

    public <T> TopicServicesResponse<ServicesReplyData<T>>  publish(TypeReference<T> clazz, String sn, String method, Object data, int retryCount, long timeout) {
        return this.publish(clazz, sn, method, data, null, retryCount, timeout);
    }

    public TopicServicesResponse<ServicesReplyData> publish(String sn, String method) {
        return this.publish(sn, method, null, (String) null);
    }

    public TopicServicesResponse<ServicesReplyData> publish(String sn, String method, Object data) {
        return this.publish(sn, method, data, (String) null);
    }

    public TopicServicesResponse<ServicesReplyData> publish(
            String sn, String method, Object data, List<Map<String, String>> deviceList) {
        return (TopicServicesResponse) this.publish(
                null, sn, method, data, null,
                MqttGatewayPublish.DEFAULT_RETRY_COUNT,
                MqttGatewayPublish.DEFAULT_RETRY_TIMEOUT,
                deviceList);
    }

    public TopicServicesResponse<ServicesReplyData> publish(
            String sn, String method, Object data, String bid, List<Map<String, String>> deviceList) {
        return (TopicServicesResponse) this.publish(
                null, sn, method, data, bid,
                MqttGatewayPublish.DEFAULT_RETRY_COUNT,
                MqttGatewayPublish.DEFAULT_RETRY_TIMEOUT,
                deviceList);
    }

    public TopicServicesResponse<ServicesReplyData> publish(String sn, String method, Object data, int retryCount) {
        return this.publish(sn, method, data, null, retryCount);
    }

    public TopicServicesResponse<ServicesReplyData> publish(String sn, String method, Object data, long timeout) {
        return this.publish(sn, method, data, null, timeout);
    }

    public TopicServicesResponse<ServicesReplyData> publish(String sn, String method, Object data, int retryCount, long timeout) {
        return this.publish(sn, method, data, null, retryCount, timeout);
    }

    public TopicServicesResponse<ServicesReplyData> publish(String sn, String method, Object data, String bid) {
        return this.publish(sn, method, data, bid, MqttGatewayPublish.DEFAULT_RETRY_COUNT);
    }

    public TopicServicesResponse<ServicesReplyData> publish(String sn, String method, Object data, String bid, int retryCount) {
        return this.publish(sn, method, data, bid, retryCount, MqttGatewayPublish.DEFAULT_RETRY_TIMEOUT);
    }

    public TopicServicesResponse<ServicesReplyData> publish(String sn, String method, Object data, String bid, long timeout) {
        return this.publish(sn, method, data, bid, MqttGatewayPublish.DEFAULT_RETRY_COUNT, timeout);
    }

    public TopicServicesResponse<ServicesReplyData> publish(String sn, String method, Object data, String bid, int retryCount, long timeout) {
        return (TopicServicesResponse) this.publish(null, sn, method, data, bid, retryCount, timeout);
    }

    public <T> TopicServicesResponse<ServicesReplyData<T>> publish(
            TypeReference<T> clazz, String sn, String method, Object data, String bid, int retryCount, long timeout) {
        return publish(clazz, sn, method, data, bid, retryCount, timeout, null);
    }

    private <T> TopicServicesResponse<ServicesReplyData<T>> publish(
            TypeReference<T> clazz, String sn, String method, Object data, String bid,
            int retryCount, long timeout, List<Map<String, String>> deviceList) {
        String topic = TopicConst.THING_MODEL_PRE + TopicConst.PRODUCT + Objects.requireNonNull(sn) + TopicConst.SERVICES_SUF;
        TopicServicesResponse response = (TopicServicesResponse) gatewayPublish.publishWithReply(
                ServicesReplyReceiver.class, topic, new TopicServicesRequest<>()
                        .setTid(UUID.randomUUID().toString())
                        .setBid(bid)
                        .setTimestamp(System.currentTimeMillis())
                        .setMethod(method)
                        .setDeviceList(deviceList)
                        .setData(data), retryCount, timeout);
        ServicesReplyReceiver replyReceiver = (ServicesReplyReceiver) response.getData();
        ServicesReplyData<T> reply = new ServicesReplyData<T>().setResult(replyReceiver.getResult());
        if (Objects.isNull(clazz)) {
            reply.setOutput((T) Objects.requireNonNullElse(
                    replyReceiver.getOutput(), Objects.requireNonNullElse(replyReceiver.getInfo(), "")));
        } else {
            ObjectMapper mapper = Common.getObjectMapper();
            if (Objects.nonNull(replyReceiver.getInfo())) {
                reply.setOutput(mapper.convertValue(replyReceiver.getInfo(), clazz));
            }
            if (Objects.nonNull(replyReceiver.getOutput())) {
                reply.setOutput(mapper.convertValue(replyReceiver.getOutput(), clazz));
            }
        }
        // 不能直接 response.setData(reply) 复用同一个对象：该 response 与
        // ServicesReplyHandler 发布的 ServicesReplyReceivedEvent 共享同一实例，
        // 若在此处把 data 字段从 ServicesReplyReceiver 替换成 ServicesReplyData，
        // 会与异步事件监听线程产生数据竞争，导致监听端 ClassCastException。
        // 因此这里构造一个新的响应对象返回，不修改共享的 response 实例。
        return new TopicServicesResponse<ServicesReplyData<T>>()
                .setTid(response.getTid())
                .setBid(response.getBid())
                .setMethod(response.getMethod())
                .setTimestamp(response.getTimestamp())
                .setData(reply);
    }

}
