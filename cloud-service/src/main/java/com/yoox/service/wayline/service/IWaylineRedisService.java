package com.yoox.service.wayline.service;

import com.yoox.great.mqtt.core.EventsReceiver;
import com.yoox.great.mqtt.model.wayline.FlighttaskProgress;
import com.yoox.service.wayline.model.dto.ConditionalWaylineJobKey;
import com.yoox.service.wayline.model.dto.WaylineJobDTO;

import java.util.Optional;

public interface IWaylineRedisService {

    /**
     * Atomically claims an otherwise unowned gateway for a job. This is used
     * before publishing {@code flighttask_execute}; it never replaces an
     * existing running or paused owner.
     */
    void setRunningWaylineJob(String dockSn, EventsReceiver<FlighttaskProgress> data);

    /**
     * Atomically applies a non-terminal device progress event only when the
     * gateway runtime state is unowned or still owned by {@code jobId}.
     * Older events for the same job are rejected by their device timestamp.
     */
    boolean applyWaylineJobProgress(String dockSn, String jobId,
                                    EventsReceiver<FlighttaskProgress> data,
                                    long eventTimestamp, boolean paused);

    /** Atomically transitions the matching running job to PAUSED. */
    boolean pauseRunningWaylineJob(String dockSn, String jobId, long transitionTimestamp);

    /** Atomically transitions the matching PAUSED job back to running. */
    boolean resumePausedWaylineJob(String dockSn, String jobId,
                                   EventsReceiver<FlighttaskProgress> data,
                                   long transitionTimestamp);

    /** Atomically refreshes a running job without being able to replace another job. */
    boolean refreshRunningWaylineJob(String dockSn, String jobId,
                                     EventsReceiver<FlighttaskProgress> data);

    /** Atomically removes only runtime keys that are still owned by {@code jobId}. */
    boolean clearWaylineJobState(String dockSn, String jobId);

    Optional<EventsReceiver<FlighttaskProgress>> getRunningWaylineJob(String dockSn);

    /** @deprecated Use {@link #clearWaylineJobState(String, String)}. */
    @Deprecated
    Boolean delRunningWaylineJob(String dockSn);

    void setPausedWaylineJob(String dockSn, String jobId);

    String getPausedWaylineJobId(String dockSn);

    /** @deprecated Use {@link #clearWaylineJobState(String, String)}. */
    @Deprecated
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

    /** Acquire a short gateway-scoped lock around service commands with external side effects. */
    Optional<String> tryAcquireWaylineJobOperation(String dockSn);

    /** Release a gateway-scoped operation lock only when the token still owns it. */
    boolean releaseWaylineJobOperation(String dockSn, String token);
}
