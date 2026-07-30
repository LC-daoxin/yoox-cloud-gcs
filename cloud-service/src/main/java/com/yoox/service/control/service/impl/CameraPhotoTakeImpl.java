package com.yoox.service.control.service.impl;

import com.yoox.great.mqtt.enums.device.CameraStateEnum;
import com.yoox.service.control.model.param.DronePayloadParam;

public class CameraPhotoTakeImpl extends PayloadCommandsHandler {

    public CameraPhotoTakeImpl(DronePayloadParam param) {
        super(param);
    }

    @Override
    public boolean canPublish(String deviceSn) {
        super.canPublish(deviceSn);
        return CameraStateEnum.WORKING != osdCamera.getPhotoState() && osdCamera.getRemainPhotoNum() > 0;
    }
}
