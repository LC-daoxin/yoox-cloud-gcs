package com.yoox.great.mqtt.handle.events;

import com.yoox.great.context.error.ErrorCodeSourceEnum;
import com.yoox.great.context.error.IErrorInfo;
import com.yoox.great.mqtt.core.consume.MqttReply;
import com.yoox.great.mqtt.enums.base.CommonErrorEnum;
import com.yoox.great.mqtt.enums.control.ControlErrorCodeEnum;
import com.yoox.great.mqtt.enums.debug.DebugErrorCodeEnum;
import com.yoox.great.mqtt.enums.firmware.FirmwareErrorCodeEnum;
import com.yoox.great.mqtt.enums.log.LogErrorCodeEnum;
import com.yoox.great.mqtt.enums.wayline.WaylineErrorCodeEnum;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public class EventsErrorCode implements IErrorInfo {

    private static final int MOD = 100_000;

    private ErrorCodeSourceEnum source;

    private IEventsErrorCode errorCode;

    private boolean success;

    private Integer sourceCode;

    @Override
    public String toString() {
        return "{" +
                "errorCode=" + getCode() +
                ", errorMsg=" + getMessage() +
                '}';
    }

    @JsonCreator
    public EventsErrorCode(int code) {
        this.sourceCode = code;
        if (MqttReply.CODE_SUCCESS == code) {
            this.success = true;
            this.errorCode = CommonErrorEnum.SUCCESS;
            return;
        }
        this.source = ErrorCodeSourceEnum.find(code / MOD);
        this.errorCode = DebugErrorCodeEnum.find(code);
        if (errorCode.getCode() != -1) {
            return;
        }
        this.errorCode = ControlErrorCodeEnum.find(code);
        if (errorCode.getCode() != -1) {
            return;
        }
        this.errorCode = LogErrorCodeEnum.find(code);
        if (errorCode.getCode() != -1) {
            return;
        }
        this.errorCode = FirmwareErrorCodeEnum.find(code);
        if (errorCode.getCode() != -1) {
            return;
        }
        this.errorCode = WaylineErrorCodeEnum.find(code);
        if (errorCode.getCode() != -1) {
            return;
        }
        this.errorCode = CommonErrorEnum.find(code);
    }

    @Override
    public String getMessage() {
        return errorCode.getMessage();
    }

    @JsonValue
    public Integer getCode() {
        return sourceCode;
    }

    public boolean isSuccess() {
        return success;
    }

    public ErrorCodeSourceEnum getSource() {
        return source;
    }
}
