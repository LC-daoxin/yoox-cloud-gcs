package com.yoox.service.control.service.impl;

import com.yoox.great.mqtt.enums.control.ControlSourceEnum;
import com.yoox.great.mqtt.model.device.PayloadIndex;
import com.yoox.service.manage.model.dto.DeviceDTO;
import com.yoox.service.manage.model.dto.DevicePayloadDTO;
import com.yoox.service.manage.service.IDeviceRedisService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Applies a confirmed payload-authority reply to the short-lived online cache.
 * The same path is used by the synchronous HTTP request and by late MQTT replies,
 * so an HTTP timeout cannot leave subsequent payload commands falsely denied.
 */
@Service
public class PayloadAuthorityCacheService {

    @Resource
    private IDeviceRedisService deviceRedisService;

    public boolean confirm(String gatewaySn, String payloadIndex) {
        if (!StringUtils.hasText(gatewaySn) || !StringUtils.hasText(payloadIndex)) {
            return false;
        }
        Optional<DeviceDTO> aircraftOpt = deviceRedisService.getDeviceOnline(gatewaySn)
                .map(DeviceDTO::getChildDeviceSn)
                .filter(StringUtils::hasText)
                .flatMap(deviceRedisService::getDeviceOnline);
        if (aircraftOpt.isEmpty()) {
            return false;
        }

        DeviceDTO aircraft = aircraftOpt.get();
        List<DevicePayloadDTO> payloads = aircraft.getPayloadsList() == null
                ? new ArrayList<>()
                : new ArrayList<>(aircraft.getPayloadsList());
        Optional<DevicePayloadDTO> cachedPayload = payloads.stream()
                .filter(payload -> payload != null && payload.getPayloadIndex() != null)
                .filter(payload -> payloadIndex.equals(payload.getPayloadIndex().toString()))
                .findFirst();
        if (cachedPayload.isPresent()) {
            cachedPayload.get().setControlSource(ControlSourceEnum.A);
        } else {
            payloads.add(DevicePayloadDTO.builder()
                    .payloadIndex(new PayloadIndex(payloadIndex))
                    .controlSource(ControlSourceEnum.A)
                    .build());
        }
        aircraft.setPayloadsList(payloads);
        deviceRedisService.setDeviceOnline(aircraft);
        return true;
    }
}
