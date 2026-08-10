package com.yoox.service.control.model.dto;

import com.yoox.great.context.utils.SpringBeanUtilsTest;
import com.yoox.great.mqtt.enums.device.DroneModeCodeEnum;
import com.yoox.great.mqtt.model.device.OsdDockDrone;
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
class ReturnHomeCancelStateTest {

    @Mock
    private ApplicationContext applicationContext;

    @Mock
    private IDeviceRedisService deviceRedisService;

    private ReturnHomeCancelState state;

    @BeforeEach
    void setUp() {
        new SpringBeanUtilsTest().setApplicationContext(applicationContext);
        when(applicationContext.getBean(IDeviceRedisService.class)).thenReturn(deviceRedisService);
        when(deviceRedisService.getDeviceOnline("gateway"))
                .thenReturn(Optional.of(DeviceDTO.builder().childDeviceSn("aircraft").build()));
        state = new ReturnHomeCancelState();
    }

    @Test
    void rcAircraftReturningHomeCanCancelReturnHome() {
        when(deviceRedisService.getDeviceOsd("aircraft")).thenReturn(Optional.of(
                new OsdRcDrone().setModeCode(DroneModeCodeEnum.RETURN_AUTO)));

        assertTrue(state.canPublish("gateway"));
    }

    @Test
    void dockAircraftReturningHomeCanCancelReturnHome() {
        when(deviceRedisService.getDeviceOsd("aircraft")).thenReturn(Optional.of(
                new OsdDockDrone().setModeCode(DroneModeCodeEnum.RETURN_AUTO)));

        assertTrue(state.canPublish("gateway"));
    }

    @Test
    void aircraftOutsideReturnHomeCannotCancelReturnHome() {
        when(deviceRedisService.getDeviceOsd("aircraft")).thenReturn(Optional.of(
                new OsdRcDrone().setModeCode(DroneModeCodeEnum.MANUAL)));

        assertFalse(state.canPublish("gateway"));
    }
}
