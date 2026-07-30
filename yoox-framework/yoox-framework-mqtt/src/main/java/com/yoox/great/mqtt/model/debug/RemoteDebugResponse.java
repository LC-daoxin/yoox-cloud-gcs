package com.yoox.great.mqtt.model.debug;

import com.yoox.great.mqtt.enums.debug.RemoteDebugStatusEnum;

public class RemoteDebugResponse {

    private RemoteDebugStatusEnum status;

    public RemoteDebugResponse() {
    }

    @Override
    public String toString() {
        return "RemoteDebugResponse{" +
                "status=" + status +
                '}';
    }

    public RemoteDebugStatusEnum getStatus() {
        return status;
    }

    public RemoteDebugResponse setStatus(RemoteDebugStatusEnum status) {
        this.status = status;
        return this;
    }
}
