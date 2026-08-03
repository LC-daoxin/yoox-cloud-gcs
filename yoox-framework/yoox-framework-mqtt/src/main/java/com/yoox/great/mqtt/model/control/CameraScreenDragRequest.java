package com.yoox.great.mqtt.model.control;

import com.yoox.great.context.base.BaseModel;
import com.yoox.great.mqtt.model.device.PayloadIndex;

import javax.validation.constraints.NotNull;

public class CameraScreenDragRequest extends BaseModel {

    @NotNull
    private PayloadIndex payloadIndex;

    @NotNull
    private Boolean locked;

    @NotNull
    private Double pitchSpeed;

    @NotNull
    private Double yawSpeed;

    public PayloadIndex getPayloadIndex() {
        return payloadIndex;
    }

    public CameraScreenDragRequest setPayloadIndex(PayloadIndex payloadIndex) {
        this.payloadIndex = payloadIndex;
        return this;
    }

    public Boolean getLocked() {
        return locked;
    }

    public CameraScreenDragRequest setLocked(Boolean locked) {
        this.locked = locked;
        return this;
    }

    public Double getPitchSpeed() {
        return pitchSpeed;
    }

    public CameraScreenDragRequest setPitchSpeed(Double pitchSpeed) {
        this.pitchSpeed = pitchSpeed;
        return this;
    }

    public Double getYawSpeed() {
        return yawSpeed;
    }

    public CameraScreenDragRequest setYawSpeed(Double yawSpeed) {
        this.yawSpeed = yawSpeed;
        return this;
    }

    @Override
    public String toString() {
        return "CameraScreenDragRequest{" +
                "payloadIndex=" + payloadIndex +
                ", locked=" + locked +
                ", pitchSpeed=" + pitchSpeed +
                ", yawSpeed=" + yawSpeed +
                '}';
    }
}
