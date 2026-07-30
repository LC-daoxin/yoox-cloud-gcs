package com.yoox.service.control.service.impl;

import com.yoox.great.mqtt.enums.device.CameraStateEnum;
import com.yoox.service.control.model.param.DronePayloadParam;

public class CameraRecordingStopImpl extends PayloadCommandsHandler {

    public CameraRecordingStopImpl(DronePayloadParam param) {
        super(param);
    }

    @Override
    public boolean canPublish(String deviceSn) {
        super.canPublish(deviceSn);
        return CameraStateEnum.WORKING == osdCamera.getRecordingState();
    }
}
