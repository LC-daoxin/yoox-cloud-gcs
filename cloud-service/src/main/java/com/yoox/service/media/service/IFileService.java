package com.yoox.service.media.service;

import com.yoox.great.context.page.PaginationData;
import com.yoox.great.mqtt.model.media.MediaUploadCallbackRequest;
import com.yoox.service.media.model.MediaFileDTO;

import java.net.URL;
import java.util.List;

public interface IFileService {

    Boolean checkExist(String workspaceId, String fingerprint);

    Integer saveFile(String workspaceId, MediaUploadCallbackRequest file);

    List<MediaFileDTO> getAllFilesByWorkspaceId(String workspaceId);

    PaginationData<MediaFileDTO> getMediaFilesPaginationByWorkspaceId(String workspaceId, long page, long pageSize);

    URL getObjectUrl(String workspaceId, String fileId);

    List<MediaFileDTO> getFilesByWorkspaceAndJobId(String workspaceId, String jobId);
}
