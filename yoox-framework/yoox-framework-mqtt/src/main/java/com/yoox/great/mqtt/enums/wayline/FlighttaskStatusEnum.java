package com.yoox.great.mqtt.enums.wayline;

import com.yoox.great.context.exception.CloudSDKException;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum FlighttaskStatusEnum {

    SENT("sent", false),

    IN_PROGRESS("in_progress", false),

    OK("ok", true),

    PAUSED("paused", false),

    REJECTED("rejected", true),

    FAILED("failed", true),

    CANCELED("canceled", true),

    TIMEOUT("timeout", true),

    PARTIALLY_DONE("partially_done", true),

    // The aircraft is still returning/landing and continues to own the flight
    // command. Releasing the runtime job here lets a second wayline reach the
    // device too early, where it is rejected with result code 104.
    RETURN("return", false),

    PENDING("pending", false);

    private final String status;

    private final boolean end;

    FlighttaskStatusEnum(String status, boolean end) {
        this.status = status;
        this.end = end;
    }

    @JsonValue
    public String getStatus() {
        return status;
    }

    public boolean isEnd() {
        return end;
    }

    @JsonCreator
    public static FlighttaskStatusEnum find(String status) {
        return Arrays.stream(values()).filter(statusEnum -> statusEnum.status.equals(status)).findAny()
                .orElseThrow(() -> new CloudSDKException(FlighttaskStatusEnum.class, status));
    }
}

