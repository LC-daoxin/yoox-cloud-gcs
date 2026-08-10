package com.yoox.service.wayline.service.impl;

import com.yoox.great.mqtt.enums.wayline.FlighttaskStatusEnum;
import com.yoox.great.mqtt.core.EventsReceiver;
import com.yoox.great.mqtt.handle.events.EventsDataRequest;
import com.yoox.great.mqtt.handle.events.EventsErrorCode;
import com.yoox.great.mqtt.handle.events.TopicEventsRequest;
import com.yoox.great.mqtt.model.wayline.FlighttaskProgress;
import com.yoox.great.mqtt.model.wayline.FlighttaskProgressData;
import com.yoox.great.mqtt.model.wayline.FlighttaskProgressExt;
import com.yoox.great.websocket.service.IWebSocketMessageService;
import com.yoox.service.manage.model.dto.DeviceDTO;
import com.yoox.service.manage.service.IDeviceRedisService;
import com.yoox.service.media.service.IMediaRedisService;
import com.yoox.service.wayline.model.dto.WaylineJobDTO;
import com.yoox.service.wayline.model.enums.WaylineJobStatusEnum;
import com.yoox.service.wayline.service.IWaylineFileService;
import com.yoox.service.wayline.service.IWaylineJobService;
import com.yoox.service.wayline.service.IWaylineRedisService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SDKWaylineServiceTest {

    private static final String GATEWAY_SN = "gateway";
    private static final String AIRCRAFT_SN = "aircraft";
    private static final String WORKSPACE_ID = "workspace";
    private static final String JOB_ID = "job-1";

    @Mock
    private IDeviceRedisService deviceRedisService;

    @Mock
    private IWaylineRedisService waylineRedisService;

    @Mock
    private IMediaRedisService mediaRedisService;

    @Mock
    private IWebSocketMessageService webSocketMessageService;

    @Mock
    private IWaylineJobService waylineJobService;

    @Mock
    private IWaylineFileService waylineFileService;

    @InjectMocks
    private SDKWaylineService sdkWaylineService;

    @Test
    void terminalProgressClearsPausedCacheByGatewaySnNotJobId() {
        stubOnlineGateway();
        when(waylineJobService.getJobByJobId(WORKSPACE_ID, JOB_ID))
                .thenReturn(Optional.of(job(JOB_ID, WaylineJobStatusEnum.IN_PROGRESS)));
        when(waylineJobService.updateJobIfNotEnded(any())).thenReturn(true);

        sdkWaylineService.flighttaskProgress(request(JOB_ID, FlighttaskStatusEnum.OK), null);

        verify(waylineRedisService).clearWaylineJobState(GATEWAY_SN, JOB_ID);
        verify(waylineJobService).updateJobIfNotEnded(any());
        verify(webSocketMessageService).sendBatch(eq(WORKSPACE_ID), any(), any(), any());
    }

    @Test
    void lateTerminalEventDoesNotDeleteNewJobsRunningOrPausedCache() {
        stubOnlineGateway();
        when(waylineJobService.getJobByJobId(WORKSPACE_ID, JOB_ID))
                .thenReturn(Optional.of(job(JOB_ID, WaylineJobStatusEnum.IN_PROGRESS)));
        when(waylineJobService.updateJobIfNotEnded(any())).thenReturn(true);

        sdkWaylineService.flighttaskProgress(request(JOB_ID, FlighttaskStatusEnum.OK), null);

        verify(waylineRedisService).clearWaylineJobState(GATEWAY_SN, JOB_ID);
        verify(waylineJobService).updateJobIfNotEnded(any());
    }

    @Test
    void lateNonterminalEventDoesNotOverwriteNewJobsRunningCache() {
        stubOnlineGateway();
        when(waylineJobService.getJobByJobId(WORKSPACE_ID, JOB_ID))
                .thenReturn(Optional.of(job(JOB_ID, WaylineJobStatusEnum.IN_PROGRESS)));
        when(waylineRedisService.applyWaylineJobProgress(
                eq(GATEWAY_SN), eq(JOB_ID), any(), eq(1_000L), eq(false)))
                .thenReturn(false);

        sdkWaylineService.flighttaskProgress(
                request(JOB_ID, FlighttaskStatusEnum.IN_PROGRESS), null);

        verify(waylineRedisService).applyWaylineJobProgress(
                eq(GATEWAY_SN), eq(JOB_ID), any(), eq(1_000L), eq(false));
        verify(waylineJobService, never()).updateJobIfNotEnded(any());
        verifyNoInteractions(webSocketMessageService);
    }

    @ParameterizedTest
    @EnumSource(value = WaylineJobStatusEnum.class, names = {"CANCEL", "SUCCESS", "FAILED"})
    void terminalDatabaseStateIsNeverReversedByLateProgress(WaylineJobStatusEnum terminalStatus) {
        stubOnlineGateway();
        when(waylineJobService.getJobByJobId(WORKSPACE_ID, JOB_ID))
                .thenReturn(Optional.of(job(JOB_ID, terminalStatus)));

        sdkWaylineService.flighttaskProgress(request(JOB_ID, FlighttaskStatusEnum.OK), null);

        verify(waylineJobService, never()).updateJobIfNotEnded(any());
        verify(waylineRedisService).clearWaylineJobState(GATEWAY_SN, JOB_ID);
        verifyNoInteractions(webSocketMessageService);
    }

    @Test
    void terminalEventThatLosesAtomicDatabaseRaceIsNotBroadcast() {
        stubOnlineGateway();
        when(waylineJobService.getJobByJobId(WORKSPACE_ID, JOB_ID))
                .thenReturn(Optional.of(job(JOB_ID, WaylineJobStatusEnum.IN_PROGRESS)));
        when(waylineJobService.updateJobIfNotEnded(any())).thenReturn(false);

        sdkWaylineService.flighttaskProgress(request(JOB_ID, FlighttaskStatusEnum.FAILED), null);

        verify(waylineRedisService).clearWaylineJobState(GATEWAY_SN, JOB_ID);
        verifyNoInteractions(webSocketMessageService);
    }

    @Test
    void pausedProgressUsesAtomicPausedTransition() {
        stubOnlineGateway();
        when(waylineJobService.getJobByJobId(WORKSPACE_ID, JOB_ID))
                .thenReturn(Optional.of(job(JOB_ID, WaylineJobStatusEnum.IN_PROGRESS)));
        when(waylineRedisService.applyWaylineJobProgress(
                eq(GATEWAY_SN), eq(JOB_ID), any(), eq(1_000L), eq(true)))
                .thenReturn(true);

        sdkWaylineService.flighttaskProgress(request(JOB_ID, FlighttaskStatusEnum.PAUSED), null);

        verify(waylineRedisService).applyWaylineJobProgress(
                eq(GATEWAY_SN), eq(JOB_ID), any(), eq(1_000L), eq(true));
        verify(webSocketMessageService).sendBatch(eq(WORKSPACE_ID), any(), any(), any());
    }

    @Test
    void malformedProgressEventsAreIgnoredWithoutMutatingState() {
        sdkWaylineService.flighttaskProgress(null, null);
        sdkWaylineService.flighttaskProgress(
                new TopicEventsRequest<EventsDataRequest<FlighttaskProgress>>()
                        .setGateway(GATEWAY_SN)
                        .setBid(JOB_ID),
                null);
        sdkWaylineService.flighttaskProgress(
                new TopicEventsRequest<EventsDataRequest<FlighttaskProgress>>()
                        .setGateway(GATEWAY_SN)
                        .setBid(JOB_ID)
                        .setData(new EventsDataRequest<>()),
                null);
        EventsDataRequest<FlighttaskProgress> missingOutput = new EventsDataRequest<>();
        missingOutput.setResult(new EventsErrorCode(0));
        sdkWaylineService.flighttaskProgress(
                new TopicEventsRequest<EventsDataRequest<FlighttaskProgress>>()
                        .setGateway(GATEWAY_SN)
                        .setBid(JOB_ID)
                        .setData(missingOutput),
                null);
        EventsDataRequest<FlighttaskProgress> missingResult = new EventsDataRequest<>();
        missingResult.setOutput(new FlighttaskProgress().setStatus(FlighttaskStatusEnum.OK));
        sdkWaylineService.flighttaskProgress(
                new TopicEventsRequest<EventsDataRequest<FlighttaskProgress>>()
                        .setGateway(GATEWAY_SN)
                        .setBid(JOB_ID)
                        .setData(missingResult),
                null);
        EventsDataRequest<FlighttaskProgress> missingStatus = new EventsDataRequest<>();
        missingStatus.setResult(new EventsErrorCode(0)).setOutput(new FlighttaskProgress());
        sdkWaylineService.flighttaskProgress(
                new TopicEventsRequest<EventsDataRequest<FlighttaskProgress>>()
                        .setGateway(GATEWAY_SN)
                        .setBid(JOB_ID)
                        .setData(missingStatus),
                null);

        verifyNoInteractions(deviceRedisService, waylineRedisService, waylineJobService,
                mediaRedisService, webSocketMessageService);
    }

    private void stubOnlineGateway() {
        when(deviceRedisService.getDeviceOnline(GATEWAY_SN)).thenReturn(Optional.of(
                DeviceDTO.builder()
                        .deviceSn(GATEWAY_SN)
                        .childDeviceSn(AIRCRAFT_SN)
                        .workspaceId(WORKSPACE_ID)
                        .build()));
    }

    private EventsReceiver<FlighttaskProgress> running(String jobId) {
        return EventsReceiver.<FlighttaskProgress>builder()
                .bid(jobId)
                .sn(GATEWAY_SN)
                .build();
    }

    private WaylineJobDTO job(String jobId, WaylineJobStatusEnum status) {
        return WaylineJobDTO.builder()
                .jobId(jobId)
                .workspaceId(WORKSPACE_ID)
                .dockSn(GATEWAY_SN)
                .status(status.getVal())
                .build();
    }

    private TopicEventsRequest<EventsDataRequest<FlighttaskProgress>> request(
            String jobId, FlighttaskStatusEnum status) {
        FlighttaskProgress output = new FlighttaskProgress()
                .setStatus(status)
                .setProgress(new FlighttaskProgressData().setPercent(100))
                .setExt(new FlighttaskProgressExt().setMediaCount(0));
        EventsDataRequest<FlighttaskProgress> data = new EventsDataRequest<>();
        data.setResult(new EventsErrorCode(0)).setOutput(output);
        return new TopicEventsRequest<EventsDataRequest<FlighttaskProgress>>()
                .setGateway(GATEWAY_SN)
                .setBid(jobId)
                .setTimestamp(1_000L)
                .setData(data);
    }
}
