
package com.yoox.great.context.error;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum ErrorCodeSourceEnum {
    // Autel 设备回复短错误码（如 104）无来源前缀，除法后为 0；不能因此炸掉整条回复
    UNKNOWN(0),

    DEVICE(3),

    DOCK(5),

    PILOT(6);

    private final int source;

    ErrorCodeSourceEnum(int source) {
        this.source = source;
    }

    @JsonValue
    public int getSource() {
        return source;
    }

    @JsonCreator
    public static ErrorCodeSourceEnum find(int source) {
        return Arrays.stream(values()).filter(error -> error.source == source).findAny()
                .orElse(UNKNOWN);
    }
}
