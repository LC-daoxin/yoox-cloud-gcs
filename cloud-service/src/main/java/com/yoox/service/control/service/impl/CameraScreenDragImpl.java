package com.yoox.service.control.service.impl;

import com.yoox.service.control.model.param.DronePayloadParam;

import java.util.Objects;

public class CameraScreenDragImpl extends PayloadCommandsHandler {

    public CameraScreenDragImpl(DronePayloadParam param) {
        super(param);
    }

    @Override
    public boolean valid() {
        return Objects.nonNull(param.getLocked())
                && Objects.nonNull(param.getPitchSpeed())
                && Objects.nonNull(param.getYawSpeed());
    }
}
