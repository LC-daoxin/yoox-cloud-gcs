package com.yoox.service.manage.model.receiver;

import com.yoox.great.mqtt.model.log.FileUploadProgressExt;
import lombok.Data;

@Data
public class OutputLogsProgressReceiver {

    private FileUploadProgressExt ext;

    private String status;
}
