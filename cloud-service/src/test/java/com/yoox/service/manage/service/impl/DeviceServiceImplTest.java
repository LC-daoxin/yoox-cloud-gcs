package com.yoox.service.manage.service.impl;

import com.yoox.great.context.enums.device.DeviceDomainEnum;
import com.yoox.great.mqtt.enums.control.ControlSourceEnum;
import com.yoox.great.mqtt.enums.device.DroneModeCodeEnum;
import com.yoox.great.mqtt.model.device.PayloadIndex;
import com.yoox.great.mqtt.model.device.OsdDockDrone;
import com.yoox.great.mqtt.model.device.OsdRcDrone;
import com.yoox.service.manage.model.dto.DeviceDTO;
import com.yoox.service.manage.model.dto.DevicePayloadDTO;
import com.yoox.service.manage.service.IDeviceRedisService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceServiceImplTest {

    private static final String DEVICE_SN = "test-aircraft";

    @Mock
    private IDeviceRedisService deviceRedisService;

    @InjectMocks
    private DeviceServiceImpl deviceService;

    @Test
    void getDeviceModeReadsRcDroneOsdWithoutUsingParentTopology() {
        OsdRcDrone osd = new OsdRcDrone().setModeCode(DroneModeCodeEnum.IDLE);
        when(deviceRedisService.getDeviceOnline(DEVICE_SN))
                .thenReturn(Optional.of(new DeviceDTO()));
        when(deviceRedisService.getDeviceOsd(DEVICE_SN))
                .thenReturn(Optional.of(osd));

        assertEquals(DroneModeCodeEnum.IDLE, deviceService.getDeviceMode(DEVICE_SN));
    }

    @Test
    void getDeviceModeStillSupportsDockDroneOsd() {
        OsdDockDrone osd = new OsdDockDrone().setModeCode(DroneModeCodeEnum.MANUAL);
        when(deviceRedisService.getDeviceOnline(DEVICE_SN))
                .thenReturn(Optional.of(new DeviceDTO()));
        when(deviceRedisService.getDeviceOsd(DEVICE_SN))
                .thenReturn(Optional.of(osd));

        assertEquals(DroneModeCodeEnum.MANUAL, deviceService.getDeviceMode(DEVICE_SN));
    }

    @Test
    void missingGatewayDoesNotImplyFlightAuthority() {
        when(deviceRedisService.getDeviceOnline(DEVICE_SN)).thenReturn(Optional.empty());

        assertFalse(deviceService.checkAuthorityFlight(DEVICE_SN));
    }

    @Test
    void flightAuthorityRequiresSupportedOnlineGatewayWithCloudControlSource() {
        DeviceDTO gateway = DeviceDTO.builder()
                .domain(DeviceDomainEnum.REMOTER_CONTROL)
                .controlSource(ControlSourceEnum.A)
                .build();
        when(deviceRedisService.getDeviceOnline(DEVICE_SN)).thenReturn(Optional.of(gateway));

        assertTrue(deviceService.checkAuthorityFlight(DEVICE_SN));

        gateway.setControlSource(ControlSourceEnum.B);
        assertFalse(deviceService.checkAuthorityFlight(DEVICE_SN));

        gateway.setControlSource(ControlSourceEnum.A);
        gateway.setDomain(DeviceDomainEnum.DRONE);
        assertFalse(deviceService.checkAuthorityFlight(DEVICE_SN));
    }

    @Test
    void topologyUsesLivePayloadAuthorityAfterBrowserRefresh() {
        String payloadIndex = "10806-0-0";
        DevicePayloadDTO persistedPayload = DevicePayloadDTO.builder()
                .payloadIndex(new PayloadIndex(payloadIndex))
                .controlSource(ControlSourceEnum.B)
                .build();
        DevicePayloadDTO livePayload = DevicePayloadDTO.builder()
                .payloadIndex(new PayloadIndex(payloadIndex))
                .controlSource(ControlSourceEnum.A)
                .build();
        List<DevicePayloadDTO> payloads = new java.util.ArrayList<>(List.of(persistedPayload));

        deviceService.mergeLivePayloadAuthority(payloads, List.of(livePayload));

        assertEquals(ControlSourceEnum.A, payloads.get(0).getControlSource());
    }
}
