package com.yoox.service.control.service.impl;

import com.yoox.service.control.model.param.DronePayloadParam;

import java.util.Objects;

public class CameraFocalLengthDragImpl extends PayloadCommandsHandler {

    public CameraFocalLengthDragImpl(DronePayloadParam param) {
        super(param);
    }

    @Override
    public boolean valid() {
        return Objects.nonNull(param.getCameraType())
                && Objects.nonNull(param.getZoomType())
                && param.getZoomType() >= 0
                && param.getZoomType() <= 2;
    }
}
