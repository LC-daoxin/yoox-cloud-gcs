package com.yoox.service.control.service.impl;

import com.yoox.great.mqtt.enums.control.ControlSourceEnum;
import com.yoox.service.manage.model.dto.DeviceDTO;
import com.yoox.service.manage.model.dto.DevicePayloadDTO;
import com.yoox.service.manage.service.IDeviceRedisService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PayloadAuthorityCacheServiceTest {

    private static final String GATEWAY_SN = "test-rc";
    private static final String AIRCRAFT_SN = "test-aircraft";
    private static final String PAYLOAD_INDEX = "10806-0-0";

    @Mock
    private IDeviceRedisService deviceRedisService;

    @InjectMocks
    private PayloadAuthorityCacheService cacheService;

    @Test
    void confirmedReplyCreatesPayloadEntryAndPersistsAuthority() {
        DeviceDTO gateway = DeviceDTO.builder()
                .deviceSn(GATEWAY_SN)
                .childDeviceSn(AIRCRAFT_SN)
                .build();
        DeviceDTO aircraft = DeviceDTO.builder().deviceSn(AIRCRAFT_SN).build();
        when(deviceRedisService.getDeviceOnline(GATEWAY_SN)).thenReturn(Optional.of(gateway));
        when(deviceRedisService.getDeviceOnline(AIRCRAFT_SN)).thenReturn(Optional.of(aircraft));

        assertTrue(cacheService.confirm(GATEWAY_SN, PAYLOAD_INDEX));

        ArgumentCaptor<DeviceDTO> captor = ArgumentCaptor.forClass(DeviceDTO.class);
        verify(deviceRedisService).setDeviceOnline(captor.capture());
        DevicePayloadDTO payload = captor.getValue().getPayloadsList().get(0);
        assertEquals(PAYLOAD_INDEX, payload.getPayloadIndex().toString());
        assertEquals(ControlSourceEnum.A, payload.getControlSource());
    }

    @Test
    void confirmedReplyFailsClosedWhenAircraftIsOffline() {
        DeviceDTO gateway = DeviceDTO.builder()
                .deviceSn(GATEWAY_SN)
                .childDeviceSn(AIRCRAFT_SN)
                .build();
        when(deviceRedisService.getDeviceOnline(GATEWAY_SN)).thenReturn(Optional.of(gateway));
        when(deviceRedisService.getDeviceOnline(AIRCRAFT_SN)).thenReturn(Optional.empty());

        assertFalse(cacheService.confirm(GATEWAY_SN, PAYLOAD_INDEX));

        verify(deviceRedisService, never()).setDeviceOnline(org.mockito.ArgumentMatchers.any());
    }
}
