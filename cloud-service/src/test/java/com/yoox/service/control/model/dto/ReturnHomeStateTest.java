package com.yoox.service.control.model.dto;

import com.yoox.great.context.utils.SpringBeanUtilsTest;
import com.yoox.great.mqtt.enums.device.DroneModeCodeEnum;
import com.yoox.great.mqtt.model.device.OsdRcDrone;
import com.yoox.service.manage.model.dto.DeviceDTO;
import com.yoox.service.manage.service.IDeviceRedisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReturnHomeStateTest {

    @Mock
    private ApplicationContext applicationContext;

    @Mock
    private IDeviceRedisService deviceRedisService;

    private ReturnHomeState state;

    @BeforeEach
    void setUp() {
        new SpringBeanUtilsTest().setApplicationContext(applicationContext);
        when(applicationContext.getBean(IDeviceRedisService.class)).thenReturn(deviceRedisService);
        when(deviceRedisService.getDeviceOnline("gateway"))
                .thenReturn(Optional.of(DeviceDTO.builder().childDeviceSn("aircraft").build()));
        state = new ReturnHomeState();
    }

    @Test
    void rcAircraftInFlightCanCallReturnHome() {
        when(deviceRedisService.getDeviceOsd("aircraft")).thenReturn(Optional.of(
                new OsdRcDrone().setElevation(10F).setModeCode(DroneModeCodeEnum.MANUAL)));

        assertTrue(state.canPublish("gateway"));
    }

    @Test
    void rcAircraftOnGroundCannotCallReturnHome() {
        when(deviceRedisService.getDeviceOsd("aircraft")).thenReturn(Optional.of(
                new OsdRcDrone().setElevation(0F).setModeCode(DroneModeCodeEnum.MANUAL)));

        assertFalse(state.canPublish("gateway"));
    }
}
