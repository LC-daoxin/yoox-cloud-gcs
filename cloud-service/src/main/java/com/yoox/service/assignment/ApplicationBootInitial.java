package com.yoox.service.assignment;

import com.yoox.great.context.enums.device.DeviceDomainEnum;
import com.yoox.great.mqtt.core.SDKManager;
import com.yoox.great.redis.RedisConst;
import com.yoox.great.redis.RedisOpsUtils;
import com.yoox.service.manage.model.dto.DeviceDTO;
import com.yoox.service.manage.model.param.DeviceQueryParam;
import com.yoox.service.manage.service.IDeviceRedisService;
import com.yoox.service.manage.service.IDeviceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Slf4j
@Component
public class ApplicationBootInitial implements CommandLineRunner {

    @Autowired
    private IDeviceService deviceService;

    @Autowired
    private IDeviceRedisService deviceRedisService;

    @Override
    public void run(String... args) throws Exception {
        // SDKManager is an in-memory registry. After an API restart, devices
        // can keep publishing OSD without sending another online topology
        // message, so restore every persisted gateway before OSD routing starts.
        deviceService.getDevicesByParams(DeviceQueryParam.builder()
                        .domains(List.of(
                                DeviceDomainEnum.DOCK.getDomain(),
                                DeviceDomainEnum.REMOTER_CONTROL.getDomain()))
                        .build())
                .forEach(this::registerPersistedGateway);

        int start = RedisConst.DEVICE_ONLINE_PREFIX.length();
        RedisOpsUtils.getAllKeys(RedisConst.DEVICE_ONLINE_PREFIX + "*")
                .stream()
                .map(key -> key.substring(start))
                .map(deviceRedisService::getDeviceOnline)
                .flatMap(Optional::stream)
                .filter(device -> DeviceDomainEnum.DRONE != device.getDomain())
                .forEach(device -> deviceService.subDeviceOnlineSubscribeTopic(
                        SDKManager.registerDevice(device.getDeviceSn(), device.getChildDeviceSn(), device.getDomain(),
                                device.getType(), device.getSubType(), device.getThingVersion(),
                                deviceRedisService.getDeviceOnline(device.getChildDeviceSn()).map(DeviceDTO::getThingVersion).orElse(null))));

    }

    private void registerPersistedGateway(DeviceDTO gateway) {
        try {
            String childThingVersion = Optional.ofNullable(gateway.getChildDeviceSn())
                    .flatMap(deviceService::getDeviceBySn)
                    .map(DeviceDTO::getThingVersion)
                    .orElse(null);
            SDKManager.registerDevice(
                    gateway.getDeviceSn(),
                    gateway.getChildDeviceSn(),
                    gateway.getDomain(),
                    gateway.getType(),
                    gateway.getSubType(),
                    gateway.getThingVersion(),
                    childThingVersion);
            log.info("Restored persisted gateway in SDK registry: {}", gateway.getDeviceSn());
        } catch (RuntimeException exception) {
            log.warn("Unable to restore gateway {} in SDK registry: {}",
                    gateway.getDeviceSn(), exception.getMessage());
        }
    }
}
