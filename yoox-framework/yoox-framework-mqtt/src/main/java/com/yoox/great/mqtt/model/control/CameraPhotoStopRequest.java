package com.yoox.great.mqtt.model.control;

import com.yoox.great.context.base.BaseModel;
import com.yoox.great.mqtt.model.device.PayloadIndex;

import javax.validation.constraints.NotNull;

public class CameraPhotoStopRequest extends BaseModel {

    @NotNull
    private PayloadIndex payloadIndex;

    public CameraPhotoStopRequest() {
    }

    @Override
    public String toString() {
        return "CameraPhotoStopRequest{" +
                "payloadIndex=" + payloadIndex +
                '}';
    }

    public PayloadIndex getPayloadIndex() {
        return payloadIndex;
    }

    public CameraPhotoStopRequest setPayloadIndex(PayloadIndex payloadIndex) {
        this.payloadIndex = payloadIndex;
        return this;
    }
}
