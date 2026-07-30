package com.yoox.service.manage.model.param;

import com.yoox.great.mqtt.model.log.FileUploadStartFile;
import lombok.Data;

import java.util.List;

@Data
public class DeviceLogsCreateParam {

    private String logsInformation;

    private Long happenTime;

    private List<FileUploadStartFile> files;
}
