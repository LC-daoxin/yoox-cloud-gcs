package com.yoox.service.wayline.service.impl;

import com.yoox.great.context.enums.device.DeviceDomainEnum;
import com.yoox.great.context.enums.version.GatewayManager;
import com.yoox.great.context.enums.version.GatewayTypeEnum;
import com.yoox.great.mqtt.core.EventsReceiver;
import com.yoox.great.mqtt.core.SDKManager;
import com.yoox.great.mqtt.handle.services.ServicesErrorCode;
import com.yoox.great.mqtt.handle.services.ServicesReplyData;
import com.yoox.great.mqtt.handle.services.TopicServicesResponse;
import com.yoox.great.mqtt.model.wayline.FlighttaskProgress;
import com.yoox.great.mqtt.model.wayline.FlighttaskUndoRequest;
import com.yoox.great.redis.RedisConst;
import com.yoox.great.redis.RedisOpsUtils;
import com.yoox.service.manage.model.dto.DeviceDTO;
import com.yoox.service.manage.service.IDeviceRedisService;
import com.yoox.service.wayline.model.dto.ConditionalWaylineJobKey;
import com.yoox.service.wayline.model.dto.WaylineJobDTO;
import com.yoox.service.wayline.model.enums.WaylineJobStatusEnum;
import com.yoox.service.wayline.model.enums.WaylineTaskStatusEnum;
import com.yoox.service.wayline.model.param.UpdateJobParam;
import com.yoox.service.wayline.service.IWaylineJobService;
import com.yoox.service.wayline.service.IWaylineRedisService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FlightTaskServiceImplTest {

    private static final String WORKSPACE_ID = "workspace";
    private static final String GATEWAY_SN = "rc-gateway";
    private static final String AIRCRAFT_SN = "aircraft";
    private static final String JOB_ID = "job-1";

    @Mock
    private IWaylineJobService waylineJobService;

    @Mock
    private IDeviceRedisService deviceRedisService;

    @Mock
    private IWaylineRedisService waylineRedisService;

    @Mock
    private SDKWaylineService abstractWaylineService;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ZSetOperations<String, Object> zSetOperations;

    @InjectMocks
    private FlightTaskServiceImpl flightTaskService;

    @BeforeEach
    void setUp() {
        SDKManager.registerDevice(GATEWAY_SN, AIRCRAFT_SN, GatewayTypeEnum.RC, "1.0.0", null);
        new RedisOpsUtils().setRedisTemplate(redisTemplate);
        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        lenient().when(zSetOperations.remove(any(), any())).thenReturn(1L);
        lenient().when(waylineRedisService.tryAcquireWaylineJobOperation(anyString()))
                .thenReturn(Optional.of("operation-token"));
        lenient().when(waylineRedisService.releaseWaylineJobOperation(anyString(), anyString()))
                .thenReturn(true);
        lenient().when(waylineRedisService.pauseRunningWaylineJob(anyString(), anyString(), anyLong()))
                .thenReturn(true);
        lenient().when(waylineRedisService.resumePausedWaylineJob(
                anyString(), anyString(), any(), anyLong())).thenReturn(true);
        lenient().when(waylineRedisService.refreshRunningWaylineJob(anyString(), anyString(), any()))
                .thenReturn(true);
        lenient().when(waylineRedisService.clearWaylineJobState(anyString(), anyString()))
                .thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        SDKManager.logoutDevice(GATEWAY_SN);
    }

    @Test
    void pendingCancelWaitsForUndoAckThenClearsPreparedSchedulingState() {
        stubSuccessfulCancel(WaylineJobStatusEnum.PENDING);
        List<String> requestedJobIds = List.of(JOB_ID);

        flightTaskService.cancelFlightTask(WORKSPACE_ID, requestedJobIds);

        assertEquals(List.of(JOB_ID), requestedJobIds);
        assertUndoPublishedFor(JOB_ID);
        assertCanceledJobPersisted();
        verify(zSetOperations).remove(
                RedisConst.WAYLINE_JOB_TIMED_EXECUTE,
                WORKSPACE_ID + RedisConst.DELIMITER + GATEWAY_SN + RedisConst.DELIMITER + JOB_ID);
        verify(waylineRedisService).removePrepareConditionalWaylineJob(
                new ConditionalWaylineJobKey(WORKSPACE_ID, GATEWAY_SN, JOB_ID));
        verify(waylineRedisService).delConditionalWaylineJob(JOB_ID);
        verify(waylineRedisService).clearWaylineJobState(GATEWAY_SN, JOB_ID);
        verify(abstractWaylineService, never()).flighttaskPauseRc(any());
    }

    @Test
    void inProgressCancelWaitsForUndoAckThenClearsRunningState() {
        stubSuccessfulCancel(WaylineJobStatusEnum.IN_PROGRESS);
        flightTaskService.cancelFlightTask(WORKSPACE_ID, List.of(JOB_ID));

        assertUndoPublishedFor(JOB_ID);
        assertCanceledJobPersisted();
        InOrder commandOrder = inOrder(abstractWaylineService);
        commandOrder.verify(abstractWaylineService).flighttaskPauseRc(any(GatewayManager.class));
        commandOrder.verify(abstractWaylineService).flighttaskUndoRc(
                any(GatewayManager.class), any(FlighttaskUndoRequest.class));
        verify(waylineRedisService).pauseRunningWaylineJob(eq(GATEWAY_SN), eq(JOB_ID), anyLong());
        verify(waylineRedisService).clearWaylineJobState(GATEWAY_SN, JOB_ID);
    }

    @Test
    void pausedCancelWaitsForUndoAckThenClearsPausedState() {
        stubSuccessfulCancel(WaylineJobStatusEnum.PAUSED);

        flightTaskService.cancelFlightTask(WORKSPACE_ID, List.of(JOB_ID));

        assertUndoPublishedFor(JOB_ID);
        assertCanceledJobPersisted();
        verify(waylineRedisService).clearWaylineJobState(GATEWAY_SN, JOB_ID);
        verify(abstractWaylineService, never()).flighttaskPauseRc(any());
    }

    @Test
    void undoFailureDoesNotFalselyMarkJobCanceledOrClearState() {
        stubCancelableJob(WaylineJobStatusEnum.IN_PROGRESS);
        when(abstractWaylineService.flighttaskPauseRc(any(GatewayManager.class))).thenReturn(reply(0));
        when(abstractWaylineService.flighttaskUndoRc(any(GatewayManager.class), any(FlighttaskUndoRequest.class)))
                .thenReturn(reply(319_000));

        assertThrows(RuntimeException.class, () ->
                flightTaskService.cancelFlightTask(WORKSPACE_ID, List.of(JOB_ID)));

        verify(waylineJobService, never()).cancelJobsIfNotEnded(anyString(), any());
        verify(waylineRedisService, never()).removePrepareConditionalWaylineJob(any());
        verify(waylineRedisService, never()).delConditionalWaylineJob(any());
        verify(waylineRedisService).pauseRunningWaylineJob(eq(GATEWAY_SN), eq(JOB_ID), anyLong());
        verify(waylineRedisService, never()).clearWaylineJobState(anyString(), anyString());
    }

    @Test
    void inProgressCancelRetriesUndoFromItsOwnedPausedMarker() {
        when(waylineJobService.getJobsByConditions(eq(WORKSPACE_ID), eq(Set.of(JOB_ID)), isNull()))
                .thenReturn(List.of(job(WaylineJobStatusEnum.IN_PROGRESS)));
        when(waylineRedisService.getPausedWaylineJobId(GATEWAY_SN)).thenReturn(JOB_ID);
        when(waylineRedisService.getRunningWaylineJob(GATEWAY_SN)).thenReturn(Optional.empty());
        when(deviceRedisService.checkDeviceOnline(GATEWAY_SN)).thenReturn(true);
        when(deviceRedisService.getDeviceOnline(GATEWAY_SN)).thenReturn(Optional.of(rcGateway()));
        when(abstractWaylineService.flighttaskUndoRc(any(), any())).thenReturn(reply(0));
        when(waylineJobService.cancelJobsIfNotEnded(WORKSPACE_ID, List.of(JOB_ID))).thenReturn(1);
        when(waylineJobService.getJobsByConditions(
                eq(WORKSPACE_ID), eq(List.of(JOB_ID)), isNull()))
                .thenReturn(List.of(job(WaylineJobStatusEnum.CANCEL)));

        flightTaskService.cancelFlightTask(WORKSPACE_ID, List.of(JOB_ID));

        verify(abstractWaylineService, never()).flighttaskPauseRc(any());
        assertUndoPublishedFor(JOB_ID);
        verify(waylineRedisService).clearWaylineJobState(GATEWAY_SN, JOB_ID);
    }

    @Test
    void pauseFailureDoesNotPublishUndoOrChangeLocalTaskState() {
        stubCancelableJob(WaylineJobStatusEnum.IN_PROGRESS);
        when(abstractWaylineService.flighttaskPauseRc(any(GatewayManager.class)))
                .thenReturn(reply(319_020));

        assertThrows(RuntimeException.class, () ->
                flightTaskService.cancelFlightTask(WORKSPACE_ID, List.of(JOB_ID)));

        verify(abstractWaylineService, never()).flighttaskUndoRc(any(), any());
        verify(waylineJobService, never()).cancelJobsIfNotEnded(anyString(), any());
        verify(waylineRedisService, never()).pauseRunningWaylineJob(anyString(), anyString(), anyLong());
        verify(waylineRedisService, never()).clearWaylineJobState(anyString(), anyString());
    }

    @Test
    void offlineGatewayDoesNotFalselyMarkJobCanceled() {
        when(waylineJobService.getJobsByConditions(eq(WORKSPACE_ID), eq(Set.of(JOB_ID)), isNull()))
                .thenReturn(List.of(job(WaylineJobStatusEnum.PENDING)));
        when(deviceRedisService.checkDeviceOnline(GATEWAY_SN)).thenReturn(false);

        assertThrows(RuntimeException.class, () ->
                flightTaskService.cancelFlightTask(WORKSPACE_ID, List.of(JOB_ID)));

        verify(abstractWaylineService, never()).flighttaskUndoRc(any(), any());
        verify(waylineJobService, never()).cancelJobsIfNotEnded(anyString(), any());
        verify(waylineRedisService, never()).removePrepareConditionalWaylineJob(any());
    }

    @Test
    void terminalOrMissingJobRejectsWholeRequestBeforePublishingUndo() {
        when(waylineJobService.getJobsByConditions(eq(WORKSPACE_ID), eq(Set.of(JOB_ID)), isNull()))
                .thenReturn(List.of(job(WaylineJobStatusEnum.SUCCESS)));

        assertThrows(IllegalArgumentException.class, () ->
                flightTaskService.cancelFlightTask(WORKSPACE_ID, List.of(JOB_ID)));

        verify(abstractWaylineService, never()).flighttaskUndoRc(any(), any());
        verify(abstractWaylineService, never()).flighttaskUndo(any(), any());
        verify(waylineJobService, never()).cancelJobsIfNotEnded(anyString(), any());
    }

    @Test
    void resumeAlreadyRunningJobIsIdempotentAndDoesNotPublishRecovery() {
        EventsReceiver<FlighttaskProgress> running = EventsReceiver.<FlighttaskProgress>builder()
                .bid(JOB_ID)
                .sn(GATEWAY_SN)
                .build();
        stubJobForStatusUpdate(WaylineJobStatusEnum.IN_PROGRESS);
        when(waylineRedisService.getRunningWaylineJob(GATEWAY_SN)).thenReturn(Optional.of(running));

        flightTaskService.updateJobStatus(WORKSPACE_ID, JOB_ID, resumeParam());

        verify(waylineRedisService).refreshRunningWaylineJob(GATEWAY_SN, JOB_ID, running);
        verify(abstractWaylineService, never()).flighttaskRecoveryRc(any());
        verify(abstractWaylineService, never()).flighttaskRecovery(any());
    }

    @Test
    void resumeWithMissingRunningMetadataFailsSafelyInsteadOfDereferencingOptional() {
        stubJobForStatusUpdate(WaylineJobStatusEnum.IN_PROGRESS);
        when(waylineRedisService.getRunningWaylineJob(GATEWAY_SN)).thenReturn(Optional.empty());

        RuntimeException exception = assertThrows(RuntimeException.class, () ->
                flightTaskService.updateJobStatus(WORKSPACE_ID, JOB_ID, resumeParam()));

        assertTrue(exception.getMessage().contains("belongs to a different job"));
        verify(abstractWaylineService, never()).flighttaskRecoveryRc(any());
        verify(abstractWaylineService, never()).flighttaskRecovery(any());
    }

    @Test
    void pausedResumePublishesRecoveryAndRebuildsMissingRunningMetadata() {
        stubJobForStatusUpdate(WaylineJobStatusEnum.PAUSED);
        when(waylineRedisService.getRunningWaylineJob(GATEWAY_SN)).thenReturn(Optional.empty());
        when(waylineRedisService.getPausedWaylineJobId(GATEWAY_SN)).thenReturn(JOB_ID);
        when(deviceRedisService.getDeviceOnline(GATEWAY_SN)).thenReturn(Optional.of(rcGateway()));
        when(abstractWaylineService.flighttaskRecoveryRc(any(GatewayManager.class))).thenReturn(reply(0));

        flightTaskService.updateJobStatus(WORKSPACE_ID, JOB_ID, resumeParam());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<EventsReceiver<FlighttaskProgress>> runningCaptor =
                ArgumentCaptor.forClass(EventsReceiver.class);
        verify(waylineRedisService).resumePausedWaylineJob(
                eq(GATEWAY_SN), eq(JOB_ID), runningCaptor.capture(), anyLong());
        assertEquals(JOB_ID, runningCaptor.getValue().getBid());
        assertEquals(GATEWAY_SN, runningCaptor.getValue().getSn());
    }

    @Test
    void pausedResumeRefusesDifferentJobWithoutPublishingRecovery() {
        stubJobForStatusUpdate(WaylineJobStatusEnum.PAUSED);
        when(waylineRedisService.getRunningWaylineJob(GATEWAY_SN)).thenReturn(Optional.empty());
        when(waylineRedisService.getPausedWaylineJobId(GATEWAY_SN)).thenReturn("another-job");

        RuntimeException exception = assertThrows(RuntimeException.class, () ->
                flightTaskService.updateJobStatus(WORKSPACE_ID, JOB_ID, resumeParam()));

        assertTrue(exception.getMessage().contains("belongs to a different job"));
        verify(abstractWaylineService, never()).flighttaskRecoveryRc(any());
        verify(abstractWaylineService, never()).flighttaskRecovery(any());
    }

    @Test
    void cancelRefusesToPauseDifferentRunningJob() {
        stubCancelableJob(WaylineJobStatusEnum.IN_PROGRESS);
        when(waylineRedisService.getRunningWaylineJob(GATEWAY_SN))
                .thenReturn(Optional.of(running("another-job")));

        RuntimeException exception = assertThrows(RuntimeException.class, () ->
                flightTaskService.cancelFlightTask(WORKSPACE_ID, List.of(JOB_ID)));

        assertTrue(exception.getMessage().contains("belongs to a different job"));
        verify(abstractWaylineService, never()).flighttaskPauseRc(any());
        verify(abstractWaylineService, never()).flighttaskUndoRc(any(), any());
    }

    @Test
    void pauseRefusesToPauseDifferentRunningJob() {
        stubJobForStatusUpdate(WaylineJobStatusEnum.IN_PROGRESS);
        when(waylineRedisService.getRunningWaylineJob(GATEWAY_SN))
                .thenReturn(Optional.of(running("another-job")));
        when(waylineRedisService.getPausedWaylineJobId(GATEWAY_SN)).thenReturn(null);

        RuntimeException exception = assertThrows(RuntimeException.class, () ->
                flightTaskService.updateJobStatus(WORKSPACE_ID, JOB_ID, pauseParam()));

        assertTrue(exception.getMessage().contains("belongs to a different job"));
        verify(abstractWaylineService, never()).flighttaskPauseRc(any());
    }

    @Test
    void mixedAlreadyCanceledAndPendingBatchIsRetryable() {
        String pendingId = "job-2";
        WaylineJobDTO alreadyCanceled = job(JOB_ID, WaylineJobStatusEnum.CANCEL);
        WaylineJobDTO pending = job(pendingId, WaylineJobStatusEnum.PENDING);
        when(waylineJobService.getJobsByConditions(
                eq(WORKSPACE_ID), eq(Set.of(JOB_ID, pendingId)), isNull()))
                .thenReturn(List.of(alreadyCanceled, pending));
        when(waylineJobService.getJobsByConditions(
                eq(WORKSPACE_ID), eq(List.of(pendingId)), isNull()))
                .thenReturn(List.of(job(pendingId, WaylineJobStatusEnum.CANCEL)));
        when(deviceRedisService.checkDeviceOnline(GATEWAY_SN)).thenReturn(true);
        when(deviceRedisService.getDeviceOnline(GATEWAY_SN)).thenReturn(Optional.of(rcGateway()));
        when(abstractWaylineService.flighttaskUndoRc(any(), any())).thenReturn(reply(0));
        when(waylineJobService.cancelJobsIfNotEnded(WORKSPACE_ID, List.of(pendingId))).thenReturn(1);

        flightTaskService.cancelFlightTask(WORKSPACE_ID, Set.of(JOB_ID, pendingId));

        ArgumentCaptor<FlighttaskUndoRequest> request = ArgumentCaptor.forClass(FlighttaskUndoRequest.class);
        verify(abstractWaylineService).flighttaskUndoRc(any(), request.capture());
        assertEquals(List.of(pendingId), request.getValue().getFlightIds());
        verify(waylineRedisService).clearWaylineJobState(GATEWAY_SN, JOB_ID);
        verify(waylineRedisService).clearWaylineJobState(GATEWAY_SN, pendingId);
    }

    @Test
    void acknowledgedUndoDoesNotPretendCancelWhenTerminalProgressWon() {
        stubCancelableJob(WaylineJobStatusEnum.PENDING);
        when(abstractWaylineService.flighttaskUndoRc(any(), any())).thenReturn(reply(0));
        when(waylineJobService.cancelJobsIfNotEnded(WORKSPACE_ID, List.of(JOB_ID))).thenReturn(0);
        when(waylineJobService.getJobsByConditions(
                eq(WORKSPACE_ID), eq(List.of(JOB_ID)), isNull()))
                .thenReturn(List.of(job(WaylineJobStatusEnum.SUCCESS)));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                flightTaskService.cancelFlightTask(WORKSPACE_ID, List.of(JOB_ID)));

        assertTrue(exception.getMessage().contains("terminal progress won"));
        verify(waylineRedisService).clearWaylineJobState(GATEWAY_SN, JOB_ID);
    }

    @Test
    void executeClaimsRuntimeStateBeforePublishingDeviceCommand() {
        stubPendingJobForExecute();
        when(abstractWaylineService.flighttaskExecuteRc(any(), any())).thenReturn(reply(0));
        when(waylineJobService.updateJobIfNotEnded(any())).thenReturn(true);

        assertTrue(flightTaskService.executeFlightTask(WORKSPACE_ID, JOB_ID));

        InOrder order = inOrder(waylineRedisService, abstractWaylineService, waylineJobService);
        order.verify(waylineRedisService).setRunningWaylineJob(eq(GATEWAY_SN), any());
        order.verify(abstractWaylineService).flighttaskExecuteRc(any(), any());
        order.verify(waylineJobService).updateJobIfNotEnded(any());
        verify(waylineRedisService, never()).clearWaylineJobState(GATEWAY_SN, JOB_ID);
    }

    @Test
    void rejectedExecuteClearsOnlyItsPreclaimedRuntimeState() {
        stubPendingJobForExecute();
        when(abstractWaylineService.flighttaskExecuteRc(any(), any())).thenReturn(reply(319_000));
        when(waylineJobService.updateJobIfNotEnded(any())).thenReturn(true);

        assertFalse(flightTaskService.executeFlightTask(WORKSPACE_ID, JOB_ID));

        InOrder order = inOrder(waylineRedisService, abstractWaylineService);
        order.verify(waylineRedisService).setRunningWaylineJob(eq(GATEWAY_SN), any());
        order.verify(abstractWaylineService).flighttaskExecuteRc(any(), any());
        order.verify(waylineRedisService).clearWaylineJobState(GATEWAY_SN, JOB_ID);
    }

    @Test
    void missingExecuteReplyIsHandledAsFailureAndReleasesPreclaim() {
        stubPendingJobForExecute();
        when(abstractWaylineService.flighttaskExecuteRc(any(), any())).thenReturn(null);
        when(waylineJobService.updateJobIfNotEnded(any())).thenReturn(true);

        assertFalse(flightTaskService.executeFlightTask(WORKSPACE_ID, JOB_ID));

        verify(waylineJobService).updateJobIfNotEnded(any());
        verify(waylineRedisService).clearWaylineJobState(GATEWAY_SN, JOB_ID);
        verify(waylineRedisService).releaseWaylineJobOperation(GATEWAY_SN, "operation-token");
    }

    @Test
    void successfulExecuteRemainsSuccessfulWhenLocalPersistenceFailsAfterAck() {
        stubPendingJobForExecute();
        when(abstractWaylineService.flighttaskExecuteRc(any(), any())).thenReturn(reply(0));
        when(waylineJobService.updateJobIfNotEnded(any()))
                .thenThrow(new RuntimeException("database unavailable"));

        assertTrue(flightTaskService.executeFlightTask(WORKSPACE_ID, JOB_ID));

        verify(waylineRedisService, never()).clearWaylineJobState(GATEWAY_SN, JOB_ID);
        verify(waylineRedisService).releaseWaylineJobOperation(GATEWAY_SN, "operation-token");
    }

    @Test
    void executeDoesNotReachDeviceWhenAnotherJobWinsPreclaimRace() {
        when(waylineJobService.getJobByJobId(WORKSPACE_ID, JOB_ID))
                .thenReturn(Optional.of(job(WaylineJobStatusEnum.PENDING)));
        when(deviceRedisService.checkDeviceOnline(GATEWAY_SN)).thenReturn(true);
        when(waylineRedisService.getRunningWaylineJob(GATEWAY_SN))
                .thenReturn(Optional.empty(), Optional.of(running("another-job")));
        when(waylineRedisService.getPausedWaylineJobId(GATEWAY_SN)).thenReturn(null);
        doThrow(new IllegalStateException("owned"))
                .when(waylineRedisService).setRunningWaylineJob(eq(GATEWAY_SN), any());

        assertThrows(IllegalStateException.class, () ->
                flightTaskService.executeFlightTask(WORKSPACE_ID, JOB_ID));

        verify(abstractWaylineService, never()).flighttaskExecuteRc(any(), any());
        verify(waylineRedisService, never()).clearWaylineJobState(GATEWAY_SN, JOB_ID);
    }

    @Test
    void terminalJobCannotBeExecutedAgain() {
        when(waylineJobService.getJobByJobId(WORKSPACE_ID, JOB_ID))
                .thenReturn(Optional.of(job(WaylineJobStatusEnum.SUCCESS)));

        assertThrows(IllegalArgumentException.class, () ->
                flightTaskService.executeFlightTask(WORKSPACE_ID, JOB_ID));

        verify(deviceRedisService, never()).checkDeviceOnline(GATEWAY_SN);
        verify(waylineRedisService, never()).setRunningWaylineJob(anyString(), any());
        verify(abstractWaylineService, never()).flighttaskExecuteRc(any(), any());
    }

    @Test
    void malformedTimedQueueMemberIsRemovedWithoutBlockingScheduler() {
        when(zSetOperations.range(RedisConst.WAYLINE_JOB_TIMED_EXECUTE, 0, 0))
                .thenReturn(Set.of("malformed"));

        flightTaskService.checkScheduledJob();

        verify(zSetOperations).remove(RedisConst.WAYLINE_JOB_TIMED_EXECUTE, "malformed");
        verify(waylineJobService, never()).updateJobIfNotEnded(any());
        verify(abstractWaylineService, never()).flighttaskExecuteRc(any(), any());
    }

    @Test
    void scheduledJobRemainsQueuedWhileAnotherGatewayOperationHoldsLock() {
        String member = WORKSPACE_ID + RedisConst.DELIMITER + GATEWAY_SN
                + RedisConst.DELIMITER + JOB_ID;
        when(zSetOperations.range(RedisConst.WAYLINE_JOB_TIMED_EXECUTE, 0, 0))
                .thenReturn(Set.of(member));
        when(zSetOperations.score(RedisConst.WAYLINE_JOB_TIMED_EXECUTE, member))
                .thenReturn((double) (System.currentTimeMillis() + 10_000));
        when(waylineJobService.getJobByJobId(WORKSPACE_ID, JOB_ID))
                .thenReturn(Optional.of(job(WaylineJobStatusEnum.PENDING)));
        when(deviceRedisService.checkDeviceOnline(GATEWAY_SN)).thenReturn(true);
        when(waylineRedisService.tryAcquireWaylineJobOperation(GATEWAY_SN))
                .thenReturn(Optional.empty());

        flightTaskService.checkScheduledJob();

        verify(zSetOperations, never()).remove(RedisConst.WAYLINE_JOB_TIMED_EXECUTE, member);
        verify(waylineJobService, never()).updateJobIfNotEnded(any());
        verify(abstractWaylineService, never()).flighttaskExecuteRc(any(), any());
    }

    private void stubSuccessfulCancel(WaylineJobStatusEnum status) {
        stubCancelableJob(status);
        when(waylineJobService.cancelJobsIfNotEnded(WORKSPACE_ID, List.of(JOB_ID))).thenReturn(1);
        when(waylineJobService.getJobsByConditions(
                eq(WORKSPACE_ID), eq(List.of(JOB_ID)), isNull()))
                .thenReturn(List.of(job(WaylineJobStatusEnum.CANCEL)));
        if (WaylineJobStatusEnum.IN_PROGRESS == status) {
            when(abstractWaylineService.flighttaskPauseRc(any(GatewayManager.class)))
                    .thenReturn(reply(0));
        }
        when(abstractWaylineService.flighttaskUndoRc(any(GatewayManager.class), any(FlighttaskUndoRequest.class)))
                .thenReturn(reply(0));
    }

    private void stubPendingJobForExecute() {
        when(waylineJobService.getJobByJobId(WORKSPACE_ID, JOB_ID))
                .thenReturn(Optional.of(job(WaylineJobStatusEnum.PENDING)));
        when(deviceRedisService.checkDeviceOnline(GATEWAY_SN)).thenReturn(true);
        when(deviceRedisService.getDeviceOnline(GATEWAY_SN)).thenReturn(Optional.of(rcGateway()));
        when(waylineRedisService.getRunningWaylineJob(GATEWAY_SN)).thenReturn(Optional.empty());
        when(waylineRedisService.getPausedWaylineJobId(GATEWAY_SN)).thenReturn(null);
    }

    private void stubCancelableJob(WaylineJobStatusEnum status) {
        when(waylineJobService.getJobsByConditions(eq(WORKSPACE_ID), eq(Set.of(JOB_ID)), isNull()))
                .thenReturn(List.of(job(status)));
        lenient().when(deviceRedisService.checkDeviceOnline(GATEWAY_SN)).thenReturn(true);
        lenient().when(deviceRedisService.getDeviceOnline(GATEWAY_SN)).thenReturn(Optional.of(rcGateway()));
        if (WaylineJobStatusEnum.IN_PROGRESS == status) {
            lenient().when(waylineRedisService.getRunningWaylineJob(GATEWAY_SN))
                    .thenReturn(Optional.of(running(JOB_ID)));
            lenient().when(waylineRedisService.getPausedWaylineJobId(GATEWAY_SN)).thenReturn(null);
        }
        if (WaylineJobStatusEnum.PAUSED == status) {
            lenient().when(waylineRedisService.getRunningWaylineJob(GATEWAY_SN))
                    .thenReturn(Optional.empty());
            lenient().when(waylineRedisService.getPausedWaylineJobId(GATEWAY_SN)).thenReturn(JOB_ID);
        }
    }

    private void stubJobForStatusUpdate(WaylineJobStatusEnum state) {
        when(waylineJobService.getJobByJobId(WORKSPACE_ID, JOB_ID))
                .thenReturn(Optional.of(job(WaylineJobStatusEnum.IN_PROGRESS)));
        when(waylineJobService.getWaylineState(GATEWAY_SN)).thenReturn(state);
    }

    private void assertUndoPublishedFor(String jobId) {
        ArgumentCaptor<FlighttaskUndoRequest> requestCaptor =
                ArgumentCaptor.forClass(FlighttaskUndoRequest.class);
        verify(abstractWaylineService).flighttaskUndoRc(
                any(GatewayManager.class), requestCaptor.capture());
        assertEquals(List.of(jobId), requestCaptor.getValue().getFlightIds());
    }

    private void assertCanceledJobPersisted() {
        verify(waylineJobService).cancelJobsIfNotEnded(WORKSPACE_ID, List.of(JOB_ID));
    }

    private WaylineJobDTO job(WaylineJobStatusEnum status) {
        return job(JOB_ID, status);
    }

    private WaylineJobDTO job(String jobId, WaylineJobStatusEnum status) {
        return WaylineJobDTO.builder()
                .workspaceId(WORKSPACE_ID)
                .dockSn(GATEWAY_SN)
                .jobId(jobId)
                .status(status.getVal())
                .build();
    }

    private EventsReceiver<FlighttaskProgress> running(String jobId) {
        return EventsReceiver.<FlighttaskProgress>builder()
                .bid(jobId)
                .sn(GATEWAY_SN)
                .build();
    }

    private DeviceDTO rcGateway() {
        return DeviceDTO.builder()
                .deviceSn(GATEWAY_SN)
                .childDeviceSn(AIRCRAFT_SN)
                .domain(DeviceDomainEnum.REMOTER_CONTROL)
                .build();
    }

    private UpdateJobParam resumeParam() {
        return UpdateJobParam.builder().status(WaylineTaskStatusEnum.RESUME).build();
    }

    private UpdateJobParam pauseParam() {
        return UpdateJobParam.builder().status(WaylineTaskStatusEnum.PAUSE).build();
    }

    private TopicServicesResponse<ServicesReplyData> reply(int code) {
        return new TopicServicesResponse<ServicesReplyData>()
                .setData(new ServicesReplyData<>().setResult(new ServicesErrorCode(code)));
    }
}
