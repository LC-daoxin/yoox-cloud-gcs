package com.yoox.service.manage.service;

import com.yoox.great.context.response.HttpResultResponse;
import com.yoox.great.context.page.PaginationData;
import com.yoox.great.mqtt.enums.log.LogModuleEnum;
import com.yoox.great.mqtt.model.log.FileUploadUpdateRequest;
import com.yoox.service.manage.model.dto.DeviceLogsDTO;
import com.yoox.service.manage.model.param.DeviceLogsCreateParam;
import com.yoox.service.manage.model.param.DeviceLogsQueryParam;

import java.net.URL;
import java.util.List;

public interface IDeviceLogsService {

    PaginationData<DeviceLogsDTO> getUploadedLogs(String deviceSn, DeviceLogsQueryParam param);

    HttpResultResponse getRealTimeLogs(String deviceSn, List<LogModuleEnum> domainList);

    String insertDeviceLogs(String bid, String username, String deviceSn, DeviceLogsCreateParam param);

    HttpResultResponse pushFileUpload(String username, String deviceSn, DeviceLogsCreateParam param);

    HttpResultResponse pushUpdateFile(String deviceSn, FileUploadUpdateRequest param);

    void deleteLogs(String deviceSn, String logsId);

    void updateLogsStatus(String logsId, Integer value);

    URL getLogsFileUrl(String logsId, String fileId);
}
