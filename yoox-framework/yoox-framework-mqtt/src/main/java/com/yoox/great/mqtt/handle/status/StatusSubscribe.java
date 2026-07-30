package com.yoox.great.mqtt.handle.status;

import com.yoox.great.context.enums.version.GatewayManager;
import com.yoox.great.mqtt.core.subscribe.IMqttTopicService;
import com.yoox.great.mqtt.constant.TopicConst;
import com.yoox.great.mqtt.core.SDKManager;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
public class StatusSubscribe {

    public static final String TOPIC = TopicConst.BASIC_PRE + TopicConst.PRODUCT + "%s" + TopicConst.STATUS_SUF;

    @Resource
    private IMqttTopicService topicService;

    public void subscribe(GatewayManager gateway) {
        SDKManager.registerDevice(gateway);
        topicService.subscribe(String.format(TOPIC, gateway.getGatewaySn()));
    }

    public void subscribeWildcardsStatus() {
        topicService.subscribe(String.format(TOPIC, "+"));
    }

    public void unsubscribe(GatewayManager gateway) {
        SDKManager.logoutDevice(gateway.getGatewaySn());
        topicService.unsubscribe(String.format(TOPIC, gateway.getGatewaySn()));
    }

}
