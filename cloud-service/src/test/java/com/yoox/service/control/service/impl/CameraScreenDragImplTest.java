package com.yoox.service.control.service.impl;

import com.yoox.great.mqtt.enums.control.PayloadControlMethodEnum;
import com.yoox.service.control.model.enums.PayloadCommandsEnum;
import com.yoox.service.control.model.enums.GimbalResetModeEnum;
import com.yoox.service.control.model.param.DronePayloadParam;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraScreenDragImplTest {

    @Test
    void cameraScreenDragIsRegisteredAsPayloadCommand() {
        PayloadCommandsEnum command = PayloadCommandsEnum.find("camera_screen_drag");

        assertEquals(PayloadControlMethodEnum.CAMERA_SCREEN_DRAG, command.getCmd());
        assertEquals(CameraScreenDragImpl.class, command.getClazz());
    }

    @Test
    void validRequiresLockAndBothAxisSpeeds() {
        DronePayloadParam complete = new DronePayloadParam();
        complete.setLocked(false);
        complete.setPitchSpeed(5.0);
        complete.setYawSpeed(0.0);

        assertTrue(new CameraScreenDragImpl(complete).valid());

        complete.setPitchSpeed(null);
        assertFalse(new CameraScreenDragImpl(complete).valid());
    }

    @Test
    void gimbalResetIsRegisteredAndAcceptsEveryDocumentedMode() {
        PayloadCommandsEnum command = PayloadCommandsEnum.find("gimbal_reset");
        DronePayloadParam param = new DronePayloadParam();

        assertEquals(PayloadControlMethodEnum.GIMBAL_RESET, command.getCmd());
        assertEquals(GimbalResetImpl.class, command.getClazz());
        for (GimbalResetModeEnum mode : GimbalResetModeEnum.values()) {
            param.setResetMode(mode);
            assertTrue(new GimbalResetImpl(param).valid());
        }
    }
}
