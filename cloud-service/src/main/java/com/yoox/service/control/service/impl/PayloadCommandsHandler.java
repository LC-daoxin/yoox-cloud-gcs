package com.yoox.service.control.service.impl;

import com.yoox.great.context.utils.SpringBeanUtilsTest;
import com.yoox.great.mqtt.model.device.OsdCamera;
import com.yoox.great.mqtt.model.device.OsdDockDrone;
import com.yoox.great.mqtt.model.device.OsdRcDrone;
import com.yoox.service.control.model.param.DronePayloadParam;
import com.yoox.service.manage.model.dto.DeviceDTO;
import com.yoox.service.manage.service.IDevicePayloadService;
import com.yoox.service.manage.service.IDeviceRedisService;

import java.util.List;
import java.util.Optional;

public abstract class PayloadCommandsHandler {

    DronePayloadParam param;

    OsdCamera osdCamera;

    PayloadCommandsHandler(DronePayloadParam param) {
        this.param = param;
    }

    public boolean valid() {
        return true;
    }

    public boolean canPublish(String deviceSn) {
        Object deviceOsd = SpringBeanUtilsTest.getBean(IDeviceRedisService.class)
                .getDeviceOsd(deviceSn)
                .orElseThrow(() -> new RuntimeException("The device is offline."));
        List<OsdCamera> cameras;
        if (deviceOsd instanceof OsdDockDrone) {
            cameras = ((OsdDockDrone) deviceOsd).getCameras();
        } else if (deviceOsd instanceof OsdRcDrone) {
            cameras = ((OsdRcDrone) deviceOsd).getCameras();
        } else {
            throw new RuntimeException("Unsupported aircraft OSD type: " +
                    deviceOsd.getClass().getSimpleName());
        }
        if (cameras == null) {
            throw new RuntimeException("Did not receive osd information about the camera, please check the cache data.");
        }
        osdCamera = cameras.stream()
                .filter(osdCamera -> param.getPayloadIndex().equals(osdCamera.getPayloadIndex().toString()))
                .findAny()
                .orElseThrow(() -> new RuntimeException("Did not receive osd information about the camera, please check the cache data."));
        return true;
    }

    /**
     * Whether the requested state is already reflected by the latest OSD.
     * Idempotent commands should succeed without publishing another MQTT message.
     */
    public boolean isNoOp() {
        return false;
    }

    private String checkDockOnline(String dockSn) {
        Optional<DeviceDTO> deviceOpt = SpringBeanUtilsTest.getBean(IDeviceRedisService.class).getDeviceOnline(dockSn);
        if (deviceOpt.isEmpty()) {
            throw new RuntimeException("The dock is offline.");
        }
        return deviceOpt.get().getChildDeviceSn();
    }

    private void checkDeviceOnline(String deviceSn) {
        boolean isOnline = SpringBeanUtilsTest.getBean(IDeviceRedisService.class).checkDeviceOnline(deviceSn);
        if (!isOnline) {
            throw new RuntimeException("The device is offline.");
        }
    }

    private void checkAuthority(String deviceSn) {
        boolean hasAuthority = SpringBeanUtilsTest.getBean(IDevicePayloadService.class)
                .checkAuthorityPayload(deviceSn, param.getPayloadIndex());
        if (!hasAuthority) {
            throw new RuntimeException("The device does not have payload control authority.");
        }
    }

    public final boolean checkCondition(String dockSn) {
        if (!valid()) {
            throw new RuntimeException("illegal argument");
        }

        String deviceSn = checkDockOnline(dockSn);
        checkDeviceOnline(deviceSn);
        checkAuthority(deviceSn);

        if (!canPublish(deviceSn)) {
            if (isNoOp()) {
                return false;
            }
            throw new RuntimeException("The current state of the drone does not support this function, please try again later.");
        }
        return true;
    }

}
