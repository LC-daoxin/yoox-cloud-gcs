package com.yoox.service.control.model.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PayloadCommandsEnumTest {

    @Test
    void resolvesNewOfficialPayloadCommands() {
        assertEquals(PayloadCommandsEnum.CAMERA_FOCAL_LENGTH_DRAG,
                PayloadCommandsEnum.find("camera_focal_length_drag"));
        assertEquals(PayloadCommandsEnum.CAMERA_LOOK_AT,
                PayloadCommandsEnum.find("camera_look_at"));
        assertEquals(PayloadCommandsEnum.PHOTO_STORAGE_SET,
                PayloadCommandsEnum.find("photo_storage_set"));
        assertEquals(PayloadCommandsEnum.VIDEO_STORAGE_SET,
                PayloadCommandsEnum.find("video_storage_set"));
    }
}
