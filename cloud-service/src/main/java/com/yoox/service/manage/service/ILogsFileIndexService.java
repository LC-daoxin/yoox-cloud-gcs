package com.yoox.service.manage.service;

import com.yoox.great.mqtt.model.log.LogFileIndex;
import com.yoox.service.manage.model.dto.LogsFileDTO;
import com.yoox.service.manage.model.dto.LogsFileUploadDTO;

import java.util.List;
import java.util.Optional;

public interface ILogsFileIndexService {

    Boolean insertFileIndex(LogFileIndex file, String deviceSn, Integer domain, String fileId);

    Optional<LogsFileUploadDTO> getFileIndexByFileId(String fileId);

    List<LogsFileUploadDTO> getFileIndexByFileIds(List<LogsFileDTO> fileIds);

    void deleteFileIndexByFileIds(List<String> fileIds);
}
