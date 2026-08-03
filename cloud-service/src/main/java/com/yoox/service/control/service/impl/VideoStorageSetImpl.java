package com.yoox.service.control.service.impl;

import com.yoox.service.control.model.param.DronePayloadParam;

public class VideoStorageSetImpl extends PayloadCommandsHandler {

    public VideoStorageSetImpl(DronePayloadParam param) {
        super(param);
    }

    @Override
    public boolean valid() {
        return param.getVideoStorageSettings() != null && !param.getVideoStorageSettings().isEmpty();
    }
}
