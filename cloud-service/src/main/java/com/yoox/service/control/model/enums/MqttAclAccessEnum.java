package com.yoox.service.control.model.enums;

import lombok.Getter;


@Getter
public enum MqttAclAccessEnum {

    SUB("subscribe"),

    PUB("publish"),

    ALL("all");

    private final String value;

    MqttAclAccessEnum(String value) {
        this.value = value;
    }
}
