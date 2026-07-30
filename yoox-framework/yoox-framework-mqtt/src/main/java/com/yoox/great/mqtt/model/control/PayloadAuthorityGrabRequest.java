package com.yoox.great.mqtt.model.control;

import com.yoox.great.context.base.BaseModel;
import com.yoox.great.mqtt.model.device.PayloadIndex;

import javax.validation.constraints.NotNull;

public class PayloadAuthorityGrabRequest extends BaseModel {

    @NotNull
    private PayloadIndex payloadIndex;

    public PayloadAuthorityGrabRequest() {
    }

    @Override
    public String toString() {
        return "PayloadAuthorityGrabRequest{" +
                "payloadIndex=" + payloadIndex +
                '}';
    }

    public PayloadIndex getPayloadIndex() {
        return payloadIndex;
    }

    public PayloadAuthorityGrabRequest setPayloadIndex(PayloadIndex payloadIndex) {
        this.payloadIndex = payloadIndex;
        return this;
    }
}
