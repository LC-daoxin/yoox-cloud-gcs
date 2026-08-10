package com.yoox.service.control.model.dto;

import com.yoox.great.context.utils.SpringBeanUtilsTest;
import com.yoox.great.mqtt.enums.device.DroneModeCodeEnum;
import com.yoox.great.mqtt.model.device.OsdDockDrone;
import com.yoox.great.mqtt.model.device.OsdRcDrone;
import com.yoox.service.control.service.impl.RemoteDebugHandler;
import com.yoox.service.manage.model.dto.DeviceDTO;
import com.yoox.service.manage.service.IDeviceRedisService;


public class ReturnHomeCancelState extends RemoteDebugHandler {

    @Override
    public boolean canPublish(String sn) {
        IDeviceRedisService deviceRedisService = SpringBeanUtilsTest.getBean(IDeviceRedisService.class);
        return deviceRedisService.getDeviceOnline(sn)
                .map(DeviceDTO::getChildDeviceSn)
                .flatMap(deviceRedisService::getDeviceOsd)
                .map(this::isReturningHome)
                .orElse(false);
    }

    private boolean isReturningHome(Object osd) {
        if (osd instanceof OsdRcDrone) {
            return DroneModeCodeEnum.RETURN_AUTO == ((OsdRcDrone) osd).getModeCode();
        }
        if (osd instanceof OsdDockDrone) {
            return DroneModeCodeEnum.RETURN_AUTO == ((OsdDockDrone) osd).getModeCode();
        }
        return false;
    }

}
