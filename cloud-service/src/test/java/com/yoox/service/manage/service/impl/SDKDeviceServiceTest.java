package com.yoox.service.manage.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yoox.great.mqtt.handle.osd.TopicOsdRequest;
import com.yoox.great.mqtt.model.device.OsdRemoteControl;
import com.yoox.great.mqtt.model.livestream.RcLiveCapacityDevice;
import com.yoox.service.manage.model.dto.DeviceDTO;
import com.yoox.service.manage.service.ICapacityCameraService;
import com.yoox.service.manage.service.IDeviceRedisService;
import com.yoox.service.manage.service.IDeviceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SDKDeviceServiceTest {

    private static final String GATEWAY_SN = "test-gateway";
    private static final String AIRCRAFT_SN = "test-aircraft";
    private static final String REPORTED_SN = "reported-aircraft";

    @Mock
    private IDeviceRedisService deviceRedisService;

    @Mock
    private IDeviceService deviceService;

    @Mock
    private ICapacityCameraService capacityCameraService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private SDKDeviceService sdkDeviceService;

    @Test
    void reportedCapacityDeviceSnTakesPrecedenceOverBoundChild() {
        prepareOnlineGateway(AIRCRAFT_SN);
        RcLiveCapacityDevice capacityDevice = new RcLiveCapacityDevice()
                .setSn("  " + REPORTED_SN + "  ")
                .setCameraList(Collections.emptyList());

        sdkDeviceService.osdRemoteControl(requestWith(capacityDevice), null);

        verify(capacityCameraService)
                .saveCapacityCameraReceiverList(anyList(), org.mockito.ArgumentMatchers.eq(REPORTED_SN));
    }

    @Test
    void missingCapacityDeviceSnFallsBackToBoundChild() {
        prepareOnlineGateway(AIRCRAFT_SN);
        RcLiveCapacityDevice capacityDevice = new RcLiveCapacityDevice()
                .setSn(" ")
                .setCameraList(Collections.emptyList());

        sdkDeviceService.osdRemoteControl(requestWith(capacityDevice), null);

        verify(capacityCameraService)
                .saveCapacityCameraReceiverList(anyList(), org.mockito.ArgumentMatchers.eq(AIRCRAFT_SN));
    }

    @Test
    void missingCapacityDeviceSnWithoutBoundChildIsSkippedWithoutThrowing() {
        prepareOnlineGateway(null);
        OsdRemoteControl data = new OsdRemoteControl().setDeviceList(Arrays.asList(
                new RcLiveCapacityDevice().setCameraList(Collections.emptyList()), null));
        TopicOsdRequest<OsdRemoteControl> request = baseRequest().setData(data);

        assertDoesNotThrow(() -> sdkDeviceService.osdRemoteControl(request, null));

        verify(capacityCameraService, never())
                .saveCapacityCameraReceiverList(anyList(), org.mockito.ArgumentMatchers.anyString());
    }

    private void prepareOnlineGateway(String childDeviceSn) {
        DeviceDTO gateway = DeviceDTO.builder()
                .deviceSn(GATEWAY_SN)
                .workspaceId("test-workspace")
                .childDeviceSn(childDeviceSn)
                .build();
        when(deviceRedisService.checkDeviceOnline(GATEWAY_SN)).thenReturn(true);
        when(deviceRedisService.getDeviceOnline(GATEWAY_SN)).thenReturn(Optional.of(gateway));
        if (childDeviceSn != null && !childDeviceSn.isBlank()) {
            when(deviceService.getDeviceBySn(childDeviceSn)).thenReturn(Optional.empty());
        }
    }

    private TopicOsdRequest<OsdRemoteControl> requestWith(RcLiveCapacityDevice capacityDevice) {
        return baseRequest().setData(new OsdRemoteControl()
                .setDeviceList(Collections.singletonList(capacityDevice)));
    }

    private TopicOsdRequest<OsdRemoteControl> baseRequest() {
        return new TopicOsdRequest<OsdRemoteControl>()
                .setFrom(GATEWAY_SN)
                .setGateway(GATEWAY_SN);
    }
}
