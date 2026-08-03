package com.yoox.service.control.service.impl;

import com.yoox.great.mqtt.enums.control.CameraTypeEnum;
import com.yoox.great.mqtt.model.device.OsdCamera;
import com.yoox.service.control.model.param.DronePayloadParam;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraFocalLengthSetImplTest {

    @Test
    void visibleZoomAcceptsOneThroughOneHundredSixty() {
        assertTrue(command(CameraTypeEnum.ZOOM, 1).valid());
        assertTrue(command(CameraTypeEnum.ZOOM, 160).valid());
        assertFalse(command(CameraTypeEnum.ZOOM, 160.1f).valid());
    }

    @Test
    void infraredZoomAcceptsOneThroughSixteen() {
        assertTrue(command(CameraTypeEnum.IR, 1).valid());
        assertTrue(command(CameraTypeEnum.IR, 16).valid());
        assertFalse(command(CameraTypeEnum.IR, 16.1f).valid());
    }

    @Test
    void matchingVisibleZoomIsAnIdempotentNoOp() {
        CameraFocalLengthSetImpl command = command(CameraTypeEnum.ZOOM, 50);
        OsdCamera camera = new OsdCamera();
        camera.setZoomFactor(50f);
        command.osdCamera = camera;

        assertTrue(command.isNoOp());
        camera.setZoomFactor(30f);
        assertFalse(command.isNoOp());
    }

    @Test
    void matchingInfraredZoomIsAnIdempotentNoOp() {
        CameraFocalLengthSetImpl command = command(CameraTypeEnum.IR, 16);
        OsdCamera camera = new OsdCamera();
        camera.setIrZoomFactor(16f);
        command.osdCamera = camera;

        assertTrue(command.isNoOp());
        camera.setIrZoomFactor(15f);
        assertFalse(command.isNoOp());
    }

    private CameraFocalLengthSetImpl command(CameraTypeEnum cameraType, float zoomFactor) {
        DronePayloadParam param = new DronePayloadParam();
        param.setCameraType(cameraType);
        param.setZoomFactor(zoomFactor);
        return new CameraFocalLengthSetImpl(param);
    }
}
