package com.yoox.great.mqtt.enums.wayline;

import com.yoox.great.context.exception.CloudSDKException;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/**
 * 媒体文件上传方式（Autel 航线管理 flighttask_prepare.media_upload_method）
 */
public enum MediaUploadMethodEnum {

    /**
     * 落地上传
     */
    AFTER_LANDING(0),

    /**
     * 边飞边传
     */
    WHILE_FLYING(1),
    ;

    private final int method;

    MediaUploadMethodEnum(int method) {
        this.method = method;
    }

    @JsonValue
    public int getMethod() {
        return method;
    }

    @JsonCreator
    public static MediaUploadMethodEnum find(int method) {
        return Arrays.stream(values()).filter(methodEnum -> methodEnum.method == method).findAny()
            .orElseThrow(() -> new CloudSDKException(MediaUploadMethodEnum.class, method));
    }

}
