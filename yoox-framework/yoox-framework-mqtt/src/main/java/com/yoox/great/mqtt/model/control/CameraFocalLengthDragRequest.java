package com.yoox.great.mqtt.model.control;

import com.yoox.great.context.base.BaseModel;
import com.yoox.great.mqtt.enums.control.CameraTypeEnum;
import com.yoox.great.mqtt.model.device.PayloadIndex;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

public class CameraFocalLengthDragRequest extends BaseModel {

    @NotNull
    private PayloadIndex payloadIndex;

    @NotNull
    private CameraTypeEnum cameraType;

    /** 0: zoom out, 1: zoom in, 2: stop. */
    @NotNull
    @Min(0)
    @Max(2)
    private Integer zoomType;

    public PayloadIndex getPayloadIndex() {
        return payloadIndex;
    }

    public CameraFocalLengthDragRequest setPayloadIndex(PayloadIndex payloadIndex) {
        this.payloadIndex = payloadIndex;
        return this;
    }

    public CameraTypeEnum getCameraType() {
        return cameraType;
    }

    public CameraFocalLengthDragRequest setCameraType(CameraTypeEnum cameraType) {
        this.cameraType = cameraType;
        return this;
    }

    public Integer getZoomType() {
        return zoomType;
    }

    public CameraFocalLengthDragRequest setZoomType(Integer zoomType) {
        this.zoomType = zoomType;
        return this;
    }
}
