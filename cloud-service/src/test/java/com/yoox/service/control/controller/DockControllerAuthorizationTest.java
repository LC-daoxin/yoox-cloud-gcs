package com.yoox.service.control.controller;

import com.yoox.great.mqtt.model.control.TargetDetectOpenRequest;
import com.yoox.service.control.model.enums.DroneAuthorityEnum;
import com.yoox.service.control.model.param.DronePayloadParam;
import com.yoox.service.control.model.param.FlyToPointParam;
import com.yoox.service.control.model.param.PayloadCommandsParam;
import com.yoox.service.control.model.param.TakeoffToPointParam;
import com.yoox.service.control.service.IControlService;
import com.yoox.service.control.service.impl.ControlAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.servlet.http.HttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class DockControllerAuthorizationTest {

    private static final String DEVICE_SN = "foreign-gateway";

    @Mock
    private IControlService controlService;

    @Mock
    private ControlAccessService controlAccessService;

    @Mock
    private HttpServletRequest request;

    @InjectMocks
    private DockController controller;

    @Test
    void everyDeviceControlRouteStopsBeforeServiceWhenDeviceIsUnauthorized() {
        doThrow(new SecurityException("denied"))
                .when(controlAccessService).requireDevice(request, DEVICE_SN);

        assertThrows(SecurityException.class,
                () -> controller.createControlJob(
                        DEVICE_SN, "unknown", null, request));
        assertThrows(SecurityException.class,
                () -> controller.flyToPoint(
                        DEVICE_SN, new FlyToPointParam(), request));
        assertThrows(SecurityException.class,
                () -> controller.flyToPointStop(DEVICE_SN, request));
        assertThrows(SecurityException.class,
                () -> controller.getPointFlightState(DEVICE_SN, request));
        assertThrows(SecurityException.class,
                () -> controller.takeoffToPoint(
                        DEVICE_SN, new TakeoffToPointParam(), request));
        assertThrows(SecurityException.class,
                () -> controller.seizeFlightAuthority(DEVICE_SN, request));
        assertThrows(SecurityException.class,
                () -> controller.seizePayloadAuthority(
                        DEVICE_SN, new DronePayloadParam(), request));
        assertThrows(SecurityException.class,
                () -> controller.payloadCommands(
                        DEVICE_SN, new PayloadCommandsParam(), request));
        assertThrows(SecurityException.class,
                () -> controller.openTargetDetection(
                        DEVICE_SN, new TargetDetectOpenRequest(), request));
        assertThrows(SecurityException.class,
                () -> controller.closeTargetDetection(DEVICE_SN, request));

        verifyNoInteractions(controlService);
    }

    @Test
    void explicitFlightAuthorityRouteAlwaysDispatchesFreshGrab() {
        controller.seizeFlightAuthority(DEVICE_SN, request);

        verify(controlAccessService).requireDevice(request, DEVICE_SN);
        verify(controlService).seizeAuthority(
                DEVICE_SN, DroneAuthorityEnum.FLIGHT, null, true);
    }
}
