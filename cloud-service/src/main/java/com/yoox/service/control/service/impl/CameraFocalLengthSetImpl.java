package com.yoox.service.control.service.impl;

import com.yoox.great.mqtt.enums.control.CameraTypeEnum;
import com.yoox.great.mqtt.enums.device.CameraStateEnum;
import com.yoox.service.control.model.param.DronePayloadParam;

import java.util.Objects;

public class CameraFocalLengthSetImpl extends PayloadCommandsHandler {

    public CameraFocalLengthSetImpl(DronePayloadParam param) {
        super(param);
    }

    @Override
    public boolean valid() {
        if (Objects.isNull(param.getCameraType()) || Objects.isNull(param.getZoomFactor())) {
            return false;
        }
        float zoomFactor = param.getZoomFactor();
        if (CameraTypeEnum.ZOOM == param.getCameraType()) {
            return zoomFactor >= 1 && zoomFactor <= 160;
        }
        if (CameraTypeEnum.IR == param.getCameraType()) {
            return zoomFactor >= 1 && zoomFactor <= 16;
        }
        return false;
    }

    @Override
    public boolean canPublish(String deviceSn) {
        super.canPublish(deviceSn);
        if (CameraStateEnum.WORKING == osdCamera.getPhotoState()) {
            return false;
        }
        switch (param.getCameraType()) {
            case IR:
                return Objects.nonNull(osdCamera.getIrZoomFactor())
                        && Math.abs(param.getZoomFactor() - osdCamera.getIrZoomFactor()) > 0.01;
            case ZOOM:
                return Objects.nonNull(osdCamera.getZoomFactor())
                        && Math.abs(param.getZoomFactor() - osdCamera.getZoomFactor()) > 0.01;
        }
        return false;
    }

    @Override
    public boolean isNoOp() {
        if (Objects.isNull(osdCamera) || Objects.isNull(param.getZoomFactor())) {
            return false;
        }
        Float reportedZoom;
        switch (param.getCameraType()) {
            case IR:
                reportedZoom = osdCamera.getIrZoomFactor();
                break;
            case ZOOM:
                reportedZoom = osdCamera.getZoomFactor();
                break;
            default:
                return false;
        }
        return Objects.nonNull(reportedZoom)
                && Math.abs(param.getZoomFactor() - reportedZoom) <= 0.01;
    }
}
