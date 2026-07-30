package com.yoox.service.manage.service;

import com.yoox.great.context.response.HttpResultResponse;
import com.yoox.great.mqtt.model.device.VideoId;
import com.yoox.service.manage.model.dto.CapacityDeviceDTO;
import com.yoox.service.manage.model.dto.LiveTypeDTO;

import java.util.List;

public interface ILiveStreamService {

    List<CapacityDeviceDTO> getLiveCapacity(String workspaceId);

    HttpResultResponse liveStart(LiveTypeDTO liveParam);

    HttpResultResponse liveStop(VideoId videoId);

    HttpResultResponse liveSetQuality(LiveTypeDTO liveParam);

    HttpResultResponse liveLensChange(LiveTypeDTO liveParam);
}
