package com.yoox.great.mqtt.model.device;

import com.yoox.great.context.error.ErrorCodeSourceEnum;
import com.yoox.great.mqtt.core.consume.MqttReply;
import com.yoox.great.mqtt.enums.livestream.LiveErrorCodeEnum;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public class DockLiveErrorStatus {

    private static final int MOD = 100_000;

    private ErrorCodeSourceEnum source = ErrorCodeSourceEnum.DOCK;

    private LiveErrorCodeEnum errorCode;

    private boolean success;

    @Override
    public String toString() {
        return "{" +
                "errorCode=" + getCode() +
                ", errorMsg=" + getMessage() +
                '}';
    }

    @JsonCreator
    public DockLiveErrorStatus(int code) {
        this.success = MqttReply.CODE_SUCCESS == code;
        if (success) {
            this.errorCode = LiveErrorCodeEnum.SUCCESS;
            return;
        }
        this.source = ErrorCodeSourceEnum.find(code / MOD);
        this.errorCode = LiveErrorCodeEnum.find(code % MOD);
    }

    public String getMessage() {
        return errorCode.getMessage();
    }

    @JsonValue
    public Integer getCode() {
        if (success) {
            return MqttReply.CODE_SUCCESS;
        }
        return source.getSource() * MOD + errorCode.getCode();
    }

    public boolean isSuccess() {
        return success;
    }
}
