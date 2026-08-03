package com.yoox.service.manage.service.impl;

import com.yoox.great.context.enums.device.DeviceDomainEnum;
import com.yoox.great.mqtt.enums.control.ControlSourceEnum;
import com.yoox.great.mqtt.model.device.PayloadIndex;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DevicePayloadServiceImplTest {

    private static final String DEVICE_SN = "test-aircraft";
    private static final String PAYLOAD_INDEX = "10806-0-0";

    @Mock
    private IDeviceRedisService deviceRedisService;

    @InjectMocks
    private DevicePayloadServiceImpl payloadService;

    @Test
    void offlineDeviceDoesNotImplyPayloadAuthority() {
        when(deviceRedisService.getDeviceOnline(DEVICE_SN)).thenReturn(Optional.empty());

        assertFalse(payloadService.checkAuthorityPayload(DEVICE_SN, PAYLOAD_INDEX));
    }

    @Test
    void missingPayloadIndexDoesNotImplyPayloadAuthority() {
        assertFalse(payloadService.checkAuthorityPayload(DEVICE_SN, null));
    }

    @Test
    void missingPayloadStateDoesNotImplyPayloadAuthority() {
        DeviceDTO aircraft = DeviceDTO.builder()
                .domain(DeviceDomainEnum.DRONE)
                .payloadsList(List.of())
                .build();
        when(deviceRedisService.getDeviceOnline(DEVICE_SN)).thenReturn(Optional.of(aircraft));

        assertFalse(payloadService.checkAuthorityPayload(DEVICE_SN, PAYLOAD_INDEX));
    }

    @Test
    void missingRequestedPayloadDoesNotImplyPayloadAuthority() {
        DeviceDTO aircraft = aircraftWithPayload("10806-0-1", ControlSourceEnum.A);
        when(deviceRedisService.getDeviceOnline(DEVICE_SN)).thenReturn(Optional.of(aircraft));

        assertFalse(payloadService.checkAuthorityPayload(DEVICE_SN, PAYLOAD_INDEX));
    }

    @Test
    void matchingPayloadRequiresCloudControlSource() {
        DeviceDTO aircraft = aircraftWithPayload(PAYLOAD_INDEX, ControlSourceEnum.A);
        when(deviceRedisService.getDeviceOnline(DEVICE_SN)).thenReturn(Optional.of(aircraft));

        assertTrue(payloadService.checkAuthorityPayload(DEVICE_SN, PAYLOAD_INDEX));

        aircraft.getPayloadsList().get(0).setControlSource(ControlSourceEnum.B);
        assertFalse(payloadService.checkAuthorityPayload(DEVICE_SN, PAYLOAD_INDEX));
    }

    private DeviceDTO aircraftWithPayload(String payloadIndex, ControlSourceEnum controlSource) {
        return DeviceDTO.builder()
                .domain(DeviceDomainEnum.DRONE)
                .payloadsList(List.of(DevicePayloadDTO.builder()
                        .payloadIndex(new PayloadIndex(payloadIndex))
                        .controlSource(controlSource)
                        .build()))
                .build();
    }
}
