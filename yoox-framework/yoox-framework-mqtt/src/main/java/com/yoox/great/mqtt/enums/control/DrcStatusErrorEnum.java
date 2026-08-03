package com.yoox.great.mqtt.enums.control;

import com.yoox.great.context.error.IErrorInfo;
import com.yoox.great.context.exception.CloudSDKException;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum DrcStatusErrorEnum implements IErrorInfo {

    SUCCESS(0, "success"),

    MQTT_ERR(514300, "The mqtt connection error."),

    HEARTBEAT_TIMEOUT(514301, "The heartbeat times out and the dock disconnects."),

    MQTT_CERTIFICATE_ERR(514302, "The mqtt certificate is abnormal and the connection fails."),

    MQTT_LOST(514303, "The dock network is abnormal and the mqtt connection is lost."),

    MQTT_REFUSE(514304, "The dock connection to mqtt server was refused.");

    private final String msg;

    private final int code;

    DrcStatusErrorEnum(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    @Override
    public String getMessage() {
        return msg;
    }

    @JsonValue
    @Override
    public Integer getCode() {
        return code;
    }

    public static DrcStatusErrorEnum find(int code) {
        return Arrays.stream(values()).filter(error -> error.code == code).findAny()
                .orElseThrow(() -> new CloudSDKException(DrcStatusErrorEnum.class, code));
    }

    /**
     * Device firmware variants report this field as a JSON number, a numeric
     * string, or the symbolic enum name. Accept all three wire forms while
     * keeping the canonical serialized representation numeric via JsonValue.
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static DrcStatusErrorEnum fromJson(Object value) {
        if (value instanceof Number) {
            return find(((Number) value).intValue());
        }
        if (value instanceof String) {
            String text = ((String) value).trim();
            try {
                return find(Integer.parseInt(text));
            } catch (NumberFormatException ignored) {
                try {
                    return valueOf(text);
                } catch (IllegalArgumentException exception) {
                    throw new CloudSDKException(DrcStatusErrorEnum.class, value);
                }
            }
        }
        throw new CloudSDKException(DrcStatusErrorEnum.class, value);
    }
}
