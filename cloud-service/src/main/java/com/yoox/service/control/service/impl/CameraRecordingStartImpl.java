package com.yoox.service.control.service.impl;


import com.yoox.great.mqtt.enums.device.CameraModeEnum;
import com.yoox.great.mqtt.enums.device.CameraStateEnum;
import com.yoox.service.control.model.param.DronePayloadParam;

public class CameraRecordingStartImpl extends PayloadCommandsHandler {

    public CameraRecordingStartImpl(DronePayloadParam param) {
        super(param);
    }

    @Override
    public boolean canPublish(String deviceSn) {
        super.canPublish(deviceSn);
        return CameraModeEnum.VIDEO == osdCamera.getCameraMode()
                && CameraStateEnum.IDLE == osdCamera.getRecordingState()
                && osdCamera.getRemainRecordDuration() > 0;
    }
}
