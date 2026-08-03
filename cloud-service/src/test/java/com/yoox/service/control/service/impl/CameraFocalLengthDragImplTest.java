package com.yoox.service.control.service.impl;

import com.yoox.great.mqtt.enums.control.CameraTypeEnum;
import com.yoox.service.control.model.param.DronePayloadParam;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraFocalLengthDragImplTest {

    @Test
    void acceptsZoomOutZoomInAndStop() {
        for (int zoomType = 0; zoomType <= 2; zoomType++) {
            DronePayloadParam param = new DronePayloadParam();
            param.setCameraType(CameraTypeEnum.ZOOM);
            param.setZoomType(zoomType);
            assertTrue(new CameraFocalLengthDragImpl(param).valid());
        }
    }

    @Test
    void rejectsUnsupportedZoomType() {
        DronePayloadParam param = new DronePayloadParam();
        param.setCameraType(CameraTypeEnum.ZOOM);
        param.setZoomType(3);
        assertFalse(new CameraFocalLengthDragImpl(param).valid());
    }
}
