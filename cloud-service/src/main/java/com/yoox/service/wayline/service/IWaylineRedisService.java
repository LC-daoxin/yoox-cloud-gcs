package com.yoox.service.wayline.service;

import com.yoox.great.mqtt.core.EventsReceiver;
import com.yoox.great.mqtt.model.wayline.FlighttaskProgress;
import com.yoox.service.wayline.model.dto.ConditionalWaylineJobKey;
import com.yoox.service.wayline.model.dto.WaylineJobDTO;

import java.util.Optional;

public interface IWaylineRedisService {

    void setRunningWaylineJob(String dockSn, EventsReceiver<FlighttaskProgress> data);

    Optional<EventsReceiver<FlighttaskProgress>> getRunningWaylineJob(String dockSn);

    Boolean delRunningWaylineJob(String dockSn);

    void setPausedWaylineJob(String dockSn, String jobId);

    String getPausedWaylineJobId(String dockSn);

    Boolean delPausedWaylineJob(String dockSn);

    void setBlockedWaylineJob(String dockSn, String jobId);

    String getBlockedWaylineJobId(String dockSn);

    void setConditionalWaylineJob(WaylineJobDTO waylineJob);

    Optional<WaylineJobDTO> getConditionalWaylineJob(String jobId);

    Boolean delConditionalWaylineJob(String jobId);

    Boolean addPrepareConditionalWaylineJob(WaylineJobDTO waylineJob);

    Optional<ConditionalWaylineJobKey> getNearestConditionalWaylineJob();

    Double getConditionalWaylineJobTime(ConditionalWaylineJobKey jobKey);

    Boolean removePrepareConditionalWaylineJob(ConditionalWaylineJobKey jobKey);
}
