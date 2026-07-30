package com.yoox.service.manage.service;

import com.yoox.great.context.page.PaginationData;
import com.yoox.service.manage.model.dto.DeviceHmsDTO;
import com.yoox.service.manage.model.param.DeviceHmsQueryParam;

public interface IDeviceHmsService {

    PaginationData<DeviceHmsDTO> getDeviceHmsByParam(DeviceHmsQueryParam param);
    void updateUnreadHms(String deviceSn);
}
