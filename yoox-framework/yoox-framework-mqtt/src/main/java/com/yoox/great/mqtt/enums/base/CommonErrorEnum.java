
package com.yoox.great.mqtt.enums.base;


import com.yoox.great.context.error.IErrorInfo;
import com.yoox.great.mqtt.handle.events.IEventsErrorCode;
import com.yoox.great.mqtt.handle.services.IServicesErrorCode;

import java.util.Arrays;

public enum CommonErrorEnum implements IServicesErrorCode, IEventsErrorCode, IErrorInfo {

    SUCCESS(0, "Success"),

    // Autel Service Reply 短错误码（无来源前缀，见上云API文档“service 返回结果错误码”表）
    REPLY_TIMEOUT(2, "Device reply: command timed out."),
    STANDBY_FAILED(103, "Device reply: standby failed."),
    COMMAND_IN_PROGRESS(104, "Device reply: another command is still executing, please try again later."),

    STATUS_NOT_SUPPORTED(314000, "The device is either uploading logs or executing a flight mission. Please try again later."),

    WRONG_PARAMETER(325001, "Cloud command parameter error. Dock unable to execute command."),

    UNKNOWN(-1, "Unknown");

    private final int code;

    private final String msg;

    CommonErrorEnum(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public Integer getCode() {
        return this.code;
    }

    public String getMessage() {
        return this.msg;
    }

    public static CommonErrorEnum find(int code) {
        return Arrays.stream(values()).filter(error -> error.code == code).findAny().orElse(UNKNOWN);
    }
}
