package com.yoox.service.manage.service.impl;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapacityCameraServiceImplTest {

    private final CapacityCameraServiceImpl capacityCameraService = new CapacityCameraServiceImpl();

    @Test
    void blankDeviceSnNeverReachesRedis() {
        assertTrue(capacityCameraService.getCapacityCameraByDeviceSn(" ").isEmpty());
        assertFalse(capacityCameraService.deleteCapacityCameraByDeviceSn(null));
        assertDoesNotThrow(() -> capacityCameraService.saveCapacityCameraReceiverList(
                Collections.emptyList(), " "));
    }

    @Test
    void nullCameraListIsIgnoredWithoutThrowing() {
        assertDoesNotThrow(() -> capacityCameraService.saveCapacityCameraReceiverList(
                null, "test-aircraft"));
    }
}
