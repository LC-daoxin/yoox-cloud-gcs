package com.yoox.great.mqtt.enums.livestream;

import com.yoox.great.context.exception.CloudSDKException;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum UrlTypeEnum {

    RTMP(1),

    RTSP(2),

    GB28181(3),
    ;

    private final int type;

    UrlTypeEnum(int type) {
        this.type = type;
    }

    @JsonValue
    public int getType() {
        return type;
    }

    @JsonCreator
    public static UrlTypeEnum find(int type) {
        return Arrays.stream(values()).filter(typeEnum -> typeEnum.type == type).findAny()
                .orElseThrow(() -> new CloudSDKException(UrlTypeEnum.class, type));
    }
}
