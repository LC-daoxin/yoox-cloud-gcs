package com.yoox.service.control.model.dto;

import com.yoox.great.context.utils.SpringBeanUtilsTest;
import com.yoox.great.mqtt.enums.device.DroneModeCodeEnum;
import com.yoox.great.mqtt.model.device.OsdDockDrone;
import com.yoox.great.mqtt.model.device.OsdRcDrone;
import com.yoox.service.control.service.impl.RemoteDebugHandler;
import com.yoox.service.manage.model.dto.DeviceDTO;
import com.yoox.service.manage.service.IDeviceRedisService;


public class ReturnHomeState extends RemoteDebugHandler {

    @Override
    public boolean canPublish(String sn) {
        IDeviceRedisService deviceRedisService = SpringBeanUtilsTest.getBean(IDeviceRedisService.class);
        return deviceRedisService.getDeviceOnline(sn)
                .map(DeviceDTO::getChildDeviceSn)
                .flatMap(deviceRedisService::getDeviceOsd)
                .map(this::osdCanReturnHome)
                .orElse(false);
    }

    private boolean osdCanReturnHome(Object osd) {
        if (osd instanceof OsdRcDrone) {
            OsdRcDrone rc = (OsdRcDrone) osd;
            return airborne(rc.getElevation()) && modeCodeCanReturnHome(rc.getModeCode());
        }
        if (osd instanceof OsdDockDrone) {
            OsdDockDrone dock = (OsdDockDrone) osd;
            return airborne(dock.getElevation()) && modeCodeCanReturnHome(dock.getModeCode());
        }
        return false;
    }

    private boolean airborne(Float elevation) {
        return elevation != null && elevation > 0;
    }

    private boolean modeCodeCanReturnHome(DroneModeCodeEnum modeCode) {
        return DroneModeCodeEnum.TAKEOFF_FINISHED == modeCode || DroneModeCodeEnum.TAKEOFF_AUTO == modeCode
                || DroneModeCodeEnum.WAYLINE == modeCode || DroneModeCodeEnum.PANORAMIC_SHOT == modeCode
                || DroneModeCodeEnum.ACTIVE_TRACK == modeCode || DroneModeCodeEnum.APAS == modeCode
                || DroneModeCodeEnum.VIRTUAL_JOYSTICK == modeCode || DroneModeCodeEnum.MANUAL == modeCode
                || DroneModeCodeEnum.FLY_TO_POINT_MODE == modeCode;
    }
}
