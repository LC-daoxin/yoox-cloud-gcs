package com.yoox.service.control.service.impl;

import com.yoox.service.control.model.param.DronePayloadParam;

public class PhotoStorageSetImpl extends PayloadCommandsHandler {

    public PhotoStorageSetImpl(DronePayloadParam param) {
        super(param);
    }

    @Override
    public boolean valid() {
        return param.getPhotoStorageSettings() != null && !param.getPhotoStorageSettings().isEmpty();
    }
}
