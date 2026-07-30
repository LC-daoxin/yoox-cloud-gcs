package com.yoox.service.control.model.dto;

import com.yoox.great.context.utils.SpringBeanUtilsTest;
import com.yoox.great.mqtt.enums.device.DockModeCodeEnum;
import com.yoox.service.control.service.impl.RemoteDebugHandler;
import com.yoox.service.manage.service.IDeviceService;
import lombok.Data;
import lombok.EqualsAndHashCode;
@EqualsAndHashCode(callSuper = true)
@Data
public class RemoteDebugOpenState extends RemoteDebugHandler {

    @Override
    public boolean canPublish(String sn) {
        IDeviceService deviceService = SpringBeanUtilsTest.getBean(IDeviceService.class);
        DockModeCodeEnum dockMode = deviceService.getDockMode(sn);
        return DockModeCodeEnum.IDLE == dockMode;
    }
}
