package com.yoox.great.mqtt.handle.state;

import com.yoox.great.context.enums.version.GatewayManager;
import com.yoox.great.mqtt.core.subscribe.IMqttTopicService;
import com.yoox.great.mqtt.constant.TopicConst;
import com.yoox.great.mqtt.core.SDKManager;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
public class StateSubscribe {

    @Resource
    private IMqttTopicService topicService;

    public static final String TOPIC = TopicConst.THING_MODEL_PRE + TopicConst.PRODUCT + "%s" + TopicConst.STATE_SUF;

    public void subscribe(GatewayManager gateway, boolean unsubscribeSubDevice) {
        SDKManager.registerDevice(gateway);
        topicService.subscribe(String.format(TOPIC, gateway.getGatewaySn()));
        if (unsubscribeSubDevice) {
            topicService.unsubscribe(String.format(TOPIC, gateway.getDroneSn()));
            return;
        }
        if (null != gateway.getDroneSn()) {
            topicService.subscribe(String.format(TOPIC, gateway.getDroneSn()));
        }
    }

    public void unsubscribe(GatewayManager gateway) {
        SDKManager.logoutDevice(gateway.getGatewaySn());
        topicService.unsubscribe(String.format(TOPIC, gateway.getGatewaySn()));
        if (null != gateway.getDroneSn()) {
            topicService.unsubscribe(String.format(TOPIC, gateway.getDroneSn()));
        }
    }
}
