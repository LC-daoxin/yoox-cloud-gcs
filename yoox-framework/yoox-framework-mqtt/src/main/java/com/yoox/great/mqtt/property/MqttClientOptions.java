package com.yoox.great.mqtt.property;

import com.yoox.great.mqtt.enums.base.MqttProtocolEnum;
import lombok.Data;


@Data
public class MqttClientOptions {

    private MqttProtocolEnum protocol;

    private String host;

    private Integer port;

    private String username;

    private String password;

    private String clientId;

    private String path;

    private String inboundTopic;
}
