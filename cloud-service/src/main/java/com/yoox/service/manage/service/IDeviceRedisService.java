package com.yoox.service.manage.service;

import com.yoox.great.mqtt.core.EventsReceiver;
import com.yoox.great.mqtt.model.firmware.OtaProgress;
import com.yoox.service.manage.model.dto.DeviceDTO;

import java.util.Optional;
import java.util.Set;

public interface IDeviceRedisService {

    Boolean checkDeviceOnline(String sn);

    Optional<DeviceDTO> getDeviceOnline(String sn);

    void setDeviceOnline(DeviceDTO device);

    Boolean delDeviceOnline(String sn);

    void setDeviceOsd(String sn, Object data);

    <T> Optional<T> getDeviceOsd(String sn, Class<T> clazz);

    Boolean delDeviceOsd(String sn);

    void setFirmwareUpgrading(String sn, EventsReceiver<OtaProgress> events);

    Optional<EventsReceiver<OtaProgress>> getFirmwareUpgradingProgress(String sn);

    Boolean delFirmwareUpgrading(String sn);

    void addEndHmsKeys(String sn, String... keys);

    Set<String> getAllHmsKeys(String sn);

    Boolean delHmsKeysBySn(String sn);

    void gatewayOffline(String gatewaySn);

    void subDeviceOffline(String deviceSn);
}
