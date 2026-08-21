package com.yoox.service.wayline.service.impl;

import com.yoox.api.media.AbstractMediaService;
import com.yoox.great.context.error.CommonErrorEnum;
import com.yoox.great.context.model.CustomClaim;
import com.yoox.great.context.response.HttpResultResponse;
import com.yoox.great.mqtt.core.consume.MqttReply;
import com.yoox.great.mqtt.constant.ChannelName;
import com.yoox.great.context.enums.device.DeviceDomainEnum;
import com.yoox.great.mqtt.enums.device.DroneModeCodeEnum;
import com.yoox.great.mqtt.enums.device.ExitWaylineWhenRcLostEnum;
import com.yoox.great.mqtt.enums.wayline.BarrierSwitchStateEnum;
import com.yoox.great.mqtt.enums.wayline.MediaUploadMethodEnum;
import com.yoox.great.mqtt.enums.wayline.TaskTypeEnum;
import com.yoox.great.mqtt.enums.wayline.WaylinePrecisionTypeEnum;
import com.yoox.great.mqtt.handle.events.TopicEventsRequest;
import com.yoox.great.mqtt.handle.events.TopicEventsResponse;
import com.yoox.great.mqtt.core.EventsReceiver;
import com.yoox.great.mqtt.model.media.UploadFlighttaskMediaPrioritize;
import com.yoox.great.mqtt.model.wayline.*;
import com.yoox.great.mqtt.core.SDKManager;
import com.yoox.great.mqtt.handle.services.ServicesReplyData;
import com.yoox.great.mqtt.handle.services.TopicServicesResponse;
import com.yoox.great.redis.RedisConst;
import com.yoox.great.redis.RedisOpsUtils;
import com.yoox.great.websocket.service.IWebSocketMessageService;
import com.yoox.service.control.service.IControlService;
import com.yoox.service.manage.model.dto.DeviceDTO;
import com.yoox.service.manage.service.IDeviceRedisService;
import com.yoox.service.manage.service.IDeviceService;
import com.yoox.service.media.model.MediaFileCountDTO;
import com.yoox.service.media.service.IMediaRedisService;
import com.yoox.service.wayline.model.dto.ConditionalWaylineJobKey;
import com.yoox.service.wayline.model.dto.WaylineJobDTO;
import com.yoox.service.wayline.model.dto.WaylineTaskConditionDTO;
import com.yoox.service.wayline.model.enums.WaylineErrorCodeEnum;
import com.yoox.service.wayline.model.enums.WaylineJobStatusEnum;
import com.yoox.service.wayline.model.param.CreateJobParam;
import com.yoox.service.wayline.model.param.UpdateJobParam;
import com.yoox.service.wayline.service.IFlightTaskService;
import com.yoox.service.wayline.service.IWaylineFileService;
import com.yoox.service.wayline.service.IWaylineJobService;
import com.yoox.service.wayline.service.IWaylineRedisService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.messaging.MessageHeaders;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URL;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


@Service
@Slf4j
public class FlightTaskServiceImpl implements IFlightTaskService {

    /**
     * The RC can acknowledge {@code flighttask_prepare} before its internal task
     * state has become idle. In that short window {@code flighttask_execute}
     * returns the common transient code 104. Only this explicit rejection is
     * safe to retry: no flight command was accepted by the device.
     */
    private static final int COMMAND_IN_PROGRESS_CODE = 104;

    private static final int EXECUTE_BUSY_MAX_ATTEMPTS = 6;

    private static final long EXECUTE_BUSY_RETRY_DELAY_MILLIS = 1_000L;

    private static final long EXECUTE_BUSY_MAX_RETRY_DELAY_MILLIS = 10_000L;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private IWebSocketMessageService websocketMessageService;

    @Autowired
    private IWaylineJobService waylineJobService;

    @Autowired
    private IDeviceRedisService deviceRedisService;

    @Autowired
    private IDeviceService deviceService;

    @Autowired
    private IWaylineRedisService waylineRedisService;

    @Autowired
    private IMediaRedisService mediaRedisService;

    @Autowired
    private IWaylineFileService waylineFileService;

    @Autowired
    private SDKWaylineService abstractWaylineService;

    // @Lazy 打破 FlightTaskServiceImpl → ControlServiceImpl → SDKControlService → 本类的循环依赖
    @Autowired
    @Lazy
    private IControlService controlService;

    @Autowired
    @Qualifier("mediaServiceImpl")
    private AbstractMediaService abstractMediaService;

    @Scheduled(initialDelay = 10, fixedRate = 5, timeUnit = TimeUnit.SECONDS)
    public void checkScheduledJob() {
        Object jobIdValue = RedisOpsUtils.zGetMin(RedisConst.WAYLINE_JOB_TIMED_EXECUTE);
        if (Objects.isNull(jobIdValue)) {
            return;
        }
        log.info("Check the timed tasks of the wayline. {}", jobIdValue);
        // format: {workspace_id}:{dock_sn}:{job_id}
        ConditionalWaylineJobKey jobKey;
        try {
            jobKey = new ConditionalWaylineJobKey(String.valueOf(jobIdValue));
        } catch (IllegalArgumentException e) {
            log.warn("Removing malformed timed wayline queue member: {}", jobIdValue);
            RedisOpsUtils.zRemove(RedisConst.WAYLINE_JOB_TIMED_EXECUTE, jobIdValue);
            return;
        }
        Double time = RedisOpsUtils.zScore(RedisConst.WAYLINE_JOB_TIMED_EXECUTE, jobIdValue);
        if (time == null) {
            // Another scheduler instance already removed or claimed this member.
            return;
        }
        long now = System.currentTimeMillis();
        int offset = 30_000;

        // Expired tasks are deleted directly.
        if (time < now - offset) {
            RedisOpsUtils.zRemove(RedisConst.WAYLINE_JOB_TIMED_EXECUTE, jobIdValue);
            waylineJobService.updateJobIfNotEnded(WaylineJobDTO.builder()
                    .jobId(jobKey.getJobId())
                    .status(WaylineJobStatusEnum.FAILED.getVal())
                    .executeTime(LocalDateTime.now())
                    .completedTime(LocalDateTime.now())
                    .code(HttpStatus.SC_REQUEST_TIMEOUT).build());
            return;
        }

        if (now <= time && time <= now + offset) {
            boolean removeScheduledMember = true;
            try {
                this.executeFlightTask(jobKey.getWorkspaceId(), jobKey.getJobId());
            } catch (WaylineOperationBusyException e) {
                // A pause/cancel/execute may temporarily own this gateway. Keep
                // the member so a later scheduler tick can retry it safely.
                removeScheduledMember = false;
                log.info("The scheduled task is waiting for another gateway operation to finish.");
            } catch (Exception e) {
                log.info("The scheduled task delivery failed.");
                waylineJobService.updateJobIfNotEnded(WaylineJobDTO.builder()
                        .jobId(jobKey.getJobId())
                        .status(WaylineJobStatusEnum.FAILED.getVal())
                        .executeTime(LocalDateTime.now())
                        .completedTime(LocalDateTime.now())
                        .code(HttpStatus.SC_INTERNAL_SERVER_ERROR).build());
            } finally {
                if (removeScheduledMember) {
                    RedisOpsUtils.zRemove(RedisConst.WAYLINE_JOB_TIMED_EXECUTE, jobIdValue);
                }
            }
        }
    }

    @Scheduled(initialDelay = 10, fixedRate = 5, timeUnit = TimeUnit.SECONDS)
    public void prepareConditionJob() {
        Optional<ConditionalWaylineJobKey> jobKeyOpt = waylineRedisService.getNearestConditionalWaylineJob();
        if (jobKeyOpt.isEmpty()) {
            return;
        }
        ConditionalWaylineJobKey jobKey = jobKeyOpt.get();
        log.info("Check the conditional tasks of the wayline. {}", jobKey.toString());
        // format: {workspace_id}:{dock_sn}:{job_id}
        Double time = waylineRedisService.getConditionalWaylineJobTime(jobKey);
        if (time == null) {
            // Another scheduler instance already removed this member.
            return;
        }
        long now = System.currentTimeMillis();
        // prepare the task one day in advance.
        int offset = 86_400_000;

        if (now + offset < time) {
            return;
        }

        WaylineJobDTO job = WaylineJobDTO.builder()
                .jobId(jobKey.getJobId())
                .status(WaylineJobStatusEnum.FAILED.getVal())
                .executeTime(LocalDateTime.now())
                .completedTime(LocalDateTime.now())
                .code(HttpStatus.SC_INTERNAL_SERVER_ERROR).build();
        try {
            Optional<WaylineJobDTO> waylineJobOpt = waylineRedisService.getConditionalWaylineJob(jobKey.getJobId());
            if (waylineJobOpt.isEmpty()) {
                job.setCode(CommonErrorEnum.REDIS_DATA_NOT_FOUND.getCode());
                waylineJobService.updateJobIfNotEnded(job);
                waylineRedisService.removePrepareConditionalWaylineJob(jobKey);
                return;
            }
            WaylineJobDTO waylineJob = waylineJobOpt.get();

            HttpResultResponse result = this.publishOneFlightTask(waylineJob);
            waylineRedisService.removePrepareConditionalWaylineJob(jobKey);

            if (HttpResultResponse.CODE_SUCCESS == result.getCode()) {
                return;
            }

            // If the end time is exceeded, no more retries will be made.
            waylineRedisService.delConditionalWaylineJob(jobKey.getJobId());
            if (waylineJob.getEndTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() - RedisConst.WAYLINE_JOB_BLOCK_TIME * 1000 < now) {
                return;
            }

            // Retry if the end time has not been exceeded.
            this.retryPrepareJob(jobKey, waylineJob);

        } catch (Exception e) {
            log.info("Failed to prepare the conditional task.");
            waylineJobService.updateJobIfNotEnded(job);
        }

    }

    /**
     * For immediate tasks, the server time shall prevail.
     *
     * @param param
     */
    private void fillImmediateTime(CreateJobParam param) {
        if (TaskTypeEnum.IMMEDIATE != param.getTaskType()) {
            return;
        }
        long now = System.currentTimeMillis() / 1000;
        param.setTaskDays(List.of(now));
        param.setTaskPeriods(List.of(List.of(now)));
    }


    private void addConditions(WaylineJobDTO waylineJob, CreateJobParam param, Long beginTime, Long endTime) {
        if (TaskTypeEnum.CONDITIONAL != param.getTaskType()) {
            return;
        }

        waylineJob.setConditions(
                WaylineTaskConditionDTO.builder()
                        .executableConditions(Objects.nonNull(param.getMinStorageCapacity()) ?
                                new ExecutableConditions().setStorageCapacity(param.getMinStorageCapacity()) : null)
                        .readyConditions(new ReadyConditions()
                                .setBatteryCapacity(param.getMinBatteryCapacity())
                                .setBeginTime(beginTime)
                                .setEndTime(endTime))
                        .build());

        waylineRedisService.setConditionalWaylineJob(waylineJob);
        // key: wayline_job_condition, value: {workspace_id}:{dock_sn}:{job_id}
        boolean isAdd = waylineRedisService.addPrepareConditionalWaylineJob(waylineJob);
        if (!isAdd) {
            throw new RuntimeException("Failed to create conditional job.");
        }
    }

    @Override
    public HttpResultResponse publishFlightTask(CreateJobParam param, CustomClaim customClaim) throws SQLException {
        fillImmediateTime(param);

        for (Long taskDay : param.getTaskDays()) {
            LocalDate date = LocalDate.ofInstant(Instant.ofEpochSecond(taskDay), ZoneId.systemDefault());
            for (List<Long> taskPeriod : param.getTaskPeriods()) {
                long beginTime = LocalDateTime.of(date, LocalTime.ofInstant(Instant.ofEpochSecond(taskPeriod.get(0)), ZoneId.systemDefault()))
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                long endTime = taskPeriod.size() > 1 ?
                        LocalDateTime.of(date, LocalTime.ofInstant(Instant.ofEpochSecond(taskPeriod.get(1)), ZoneId.systemDefault()))
                                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() : beginTime;
                if (TaskTypeEnum.IMMEDIATE != param.getTaskType() && endTime < System.currentTimeMillis()) {
                    continue;
                }

                Optional<WaylineJobDTO> waylineJobOpt = waylineJobService.createWaylineJob(param, customClaim.getWorkspaceId(), customClaim.getUsername(), beginTime, endTime);
                if (waylineJobOpt.isEmpty()) {
                    throw new SQLException("Failed to create wayline job.");
                }
                WaylineJobDTO waylineJob = waylineJobOpt.get();
                // Autel 扩展参数不落库，创建后补到 DTO 上随 prepare 下发（条件任务经 Redis 序列化保留）
                waylineJob.setWaylinePrecisionType(param.getWaylinePrecisionType());
                waylineJob.setBarrierSwitchState(param.getBarrierSwitchState());
                waylineJob.setTakeoffAltitude(param.getTakeoffAltitude());
                waylineJob.setFirstWaypointSpeed(param.getFirstWaypointSpeed());
                waylineJob.setReturnSpeed(param.getReturnSpeed());
                waylineJob.setMediaUploadMethod(param.getMediaUploadMethod());
                waylineJob.setAlternateLandPoint(param.getAlternateLandPoint());
                // If it is a conditional task type, add conditions to the job parameters.
                addConditions(waylineJob, param, beginTime, endTime);

                HttpResultResponse response = this.publishOneFlightTask(waylineJob);
                if (HttpResultResponse.CODE_SUCCESS != response.getCode()) {
                    return response;
                }
            }
        }
        return HttpResultResponse.success();
    }

    public HttpResultResponse publishOneFlightTask(WaylineJobDTO waylineJob) throws SQLException {

        boolean isOnline = deviceRedisService.checkDeviceOnline(waylineJob.getDockSn());
        if (!isOnline) {
            throw new RuntimeException("Dock is offline.");
        }

        // An immediate prepare must not be sent while the preceding mission is
        // returning, landing, or paused. Some RC firmware accepts prepare but
        // then rejects execute with code 104, leaving a misleading failed job.
        if (TaskTypeEnum.IMMEDIATE == waylineJob.getTaskType()) {
            Optional<EventsReceiver<FlighttaskProgress>> running =
                    waylineRedisService.getRunningWaylineJob(waylineJob.getDockSn());
            if (running.isPresent()
                    && !waylineJob.getJobId().equals(running.get().getBid())) {
                throw new IllegalStateException(
                        "The previous wayline job is still returning or completing. "
                                + "Wait for its final status before starting another job.");
            }
            String pausedJobId = waylineRedisService.getPausedWaylineJobId(waylineJob.getDockSn());
            if (StringUtils.hasText(pausedJobId)
                    && !waylineJob.getJobId().equals(pausedJobId)) {
                throw new IllegalStateException(
                        "A paused wayline job still owns this gateway. Resume or cancel it first.");
            }
            try {
                requireAircraftGroundedForWayline(waylineJob.getDockSn());
            } catch (IllegalStateException e) {
                // 立即任务被拦截即失败终态；前端重试总是新建任务，保留 PENDING 只会堆积。
                failJob(waylineJob.getJobId(), HttpStatus.SC_CONFLICT);
                throw e;
            }
        }

        boolean isSuccess = this.prepareFlightTask(waylineJob);
        if (!isSuccess) {
            return HttpResultResponse.error("Failed to prepare job.");
        }

        // Issue an immediate task execution command.
        if (TaskTypeEnum.IMMEDIATE == waylineJob.getTaskType()) {
            if (!executeFlightTask(waylineJob.getWorkspaceId(), waylineJob.getJobId())) {
                return HttpResultResponse.error("Failed to execute job.");
            }
        }

        if (TaskTypeEnum.TIMED == waylineJob.getTaskType()) {
            // key: wayline_job_timed, value: {workspace_id}:{dock_sn}:{job_id}
            boolean isAdd = RedisOpsUtils.zAdd(RedisConst.WAYLINE_JOB_TIMED_EXECUTE,
                    waylineJob.getWorkspaceId() + RedisConst.DELIMITER + waylineJob.getDockSn() + RedisConst.DELIMITER + waylineJob.getJobId(),
                    waylineJob.getBeginTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
            if (!isAdd) {
                return HttpResultResponse.error("Failed to create scheduled job.");
            }
        }

        return HttpResultResponse.success();
    }

    // RC 网关下航线任务需 device_list 寻址无人机，此处按网关域选择 *Rc 变体。
    private boolean isRcGateway(String gatewaySn) {
        return DeviceDomainEnum.REMOTER_CONTROL == deviceRedisService.getDeviceOnline(gatewaySn)
                .map(DeviceDTO::getDomain)
                .orElse(null);
    }

    private Boolean prepareFlightTask(WaylineJobDTO waylineJob) throws SQLException {        Optional<GetWaylineListResponse> waylineFile = waylineFileService.getWaylineByWaylineId(waylineJob.getWorkspaceId(), waylineJob.getFileId());
        if (waylineFile.isEmpty()) {
            throw new SQLException("Wayline file doesn't exist.");
        }
        URL url = waylineFileService.getObjectUrl(waylineJob.getWorkspaceId(), waylineFile.get().getId());
        boolean rcGateway = isRcGateway(waylineJob.getDockSn());
        FlighttaskPrepareRequest flightTask = new FlighttaskPrepareRequest()
                .setFlightId(waylineJob.getJobId())
                .setExecuteTime(waylineJob.getBeginTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                .setTaskType(waylineJob.getTaskType())
                .setWaylineType(waylineJob.getWaylineType())
                .setRthAltitude(waylineJob.getRthAltitude())
                .setOutOfControlAction(waylineJob.getOutOfControlAction())
                .setExitWaylineWhenRcLost(ExitWaylineWhenRcLostEnum.EXECUTE_RC_LOST_ACTION)
                .setFile(new FlighttaskFile()
                        .setUrl(url.toString())
                        .setFingerprint(waylineFile.get().getSign()));

        if (rcGateway) {
            // 2026-08-21 MQTT 直连 A/B 实测(EVO RC 1.9.1.203):takeoff_altitude 为
            // 必需字段(缺失时任务 316009 失败);其余 Autel 扩展字段(rth_mode/精度/
            // 避障/速度/媒体/备降点)与全量载荷一起发送会导致 314010"执行任务指令
            // 发送失败",RC 甚至不去下载 KMZ。因此 RC 只发实测成功的字段集。
            flightTask.setRthMode(null);
            flightTask.setTakeoffAltitude(Objects.requireNonNullElse(
                    waylineJob.getTakeoffAltitude(), waylineJob.getRthAltitude()));
        } else {
            // 机场(dock)网关保留全量扩展字段;未指定时精度默认 GPS、避障默认打开、
            // 媒体默认落地上传、备降点标记未配置。
            flightTask.setWaylinePrecisionType(Objects.requireNonNullElse(
                            waylineJob.getWaylinePrecisionType(), WaylinePrecisionTypeEnum.GPS))
                    .setBarrierSwitchState(Objects.requireNonNullElse(
                            waylineJob.getBarrierSwitchState(), BarrierSwitchStateEnum.ENABLE))
                    .setTakeoffAltitude(waylineJob.getTakeoffAltitude())
                    .setFirstWaypointSpeed(waylineJob.getFirstWaypointSpeed())
                    .setReturnSpeed(waylineJob.getReturnSpeed())
                    .setMediaUploadMethod(Objects.requireNonNullElse(
                            waylineJob.getMediaUploadMethod(), MediaUploadMethodEnum.AFTER_LANDING))
                    .setAlternateLandPoint(Objects.requireNonNullElse(
                            waylineJob.getAlternateLandPoint(), new AlternateLandPoint().setIsConfigured(0)));
        }

        if (TaskTypeEnum.CONDITIONAL == waylineJob.getTaskType()) {
            if (Objects.isNull(waylineJob.getConditions())) {
                throw new IllegalArgumentException();
            }
            flightTask.setReadyConditions(waylineJob.getConditions().getReadyConditions());
            flightTask.setExecutableConditions(waylineJob.getConditions().getExecutableConditions());
        }

        TopicServicesResponse<ServicesReplyData> serviceReply = rcGateway
                ? abstractWaylineService.flighttaskPrepareRc(
                        SDKManager.getDeviceSDK(waylineJob.getDockSn()), flightTask)
                : abstractWaylineService.flighttaskPrepare(
                        SDKManager.getDeviceSDK(waylineJob.getDockSn()), flightTask);
        if (!hasSuccessfulResult(serviceReply)) {
            log.info("Prepare task ====> Error code: {}", replyResult(serviceReply));
            waylineJobService.updateJobIfNotEnded(WaylineJobDTO.builder()
                    .workspaceId(waylineJob.getWorkspaceId())
                    .jobId(waylineJob.getJobId())
                    .executeTime(LocalDateTime.now())
                    .status(WaylineJobStatusEnum.FAILED.getVal())
                    .completedTime(LocalDateTime.now())
                    .code(Optional.ofNullable(replyResult(serviceReply))
                            .map(result -> result.getCode()).orElse(211001)).build());
            return false;
        }
        return true;
    }


    @Override
    public Boolean executeFlightTask(String workspaceId, String jobId) {
        // get job
        Optional<WaylineJobDTO> waylineJob = waylineJobService.getJobByJobId(workspaceId, jobId);
        if (waylineJob.isEmpty()) {
            throw new IllegalArgumentException("Job doesn't exist.");
        }

        WaylineJobDTO job = waylineJob.get();
        WaylineJobStatusEnum persistedStatus = Optional.ofNullable(job.getStatus())
                .map(WaylineJobStatusEnum::find)
                .orElse(WaylineJobStatusEnum.UNKNOWN);
        if (persistedStatus.getEnd()) {
            throw new IllegalArgumentException("A terminal wayline job cannot be executed again.");
        }

        boolean isOnline = deviceRedisService.checkDeviceOnline(job.getDockSn());
        if (!isOnline) {
            throw new RuntimeException("Dock is offline.");
        }
        try {
            requireAircraftGroundedForWayline(job.getDockSn());
        } catch (IllegalStateException e) {
            failJob(jobId, HttpStatus.SC_CONFLICT);
            throw e;
        }

        String operationToken = acquireWaylineOperation(job.getDockSn());
        boolean executionClaimed = false;
        boolean executeAccepted = false;
        try {
            Optional<EventsReceiver<FlighttaskProgress>> currentRunning =
                    waylineRedisService.getRunningWaylineJob(job.getDockSn());
            String pausedJobId = waylineRedisService.getPausedWaylineJobId(job.getDockSn());
            if (currentRunning.isPresent()) {
                if (jobId.equals(currentRunning.get().getBid()) && !StringUtils.hasText(pausedJobId)) {
                    return true;
                }
                throw new RuntimeException("Another wayline job owns this gateway.");
            }
            if (StringUtils.hasText(pausedJobId)) {
                throw new RuntimeException("A paused wayline job still owns this gateway.");
            }
            if (WaylineJobStatusEnum.PENDING != persistedStatus) {
                throw new RuntimeException(
                        "Only a pending wayline job can publish a new execute command.");
            }

            EventsReceiver<FlighttaskProgress> running = EventsReceiver.<FlighttaskProgress>builder()
                    .bid(jobId).sn(job.getDockSn()).build();
            try {
                // Claim before publishing the external command. Device progress is
                // asynchronous and must not be able to install another job in the
                // gap between flighttask_execute ACK and local bookkeeping.
                waylineRedisService.setRunningWaylineJob(job.getDockSn(), running);
                executionClaimed = true;
            } catch (IllegalStateException e) {
                boolean sameJobAlreadyRunning = waylineRedisService.getRunningWaylineJob(job.getDockSn())
                        .map(EventsReceiver::getBid)
                        .filter(jobId::equals)
                        .isPresent();
                if (sameJobAlreadyRunning
                        && !StringUtils.hasText(waylineRedisService.getPausedWaylineJobId(job.getDockSn()))) {
                    return true;
                }
                throw e;
            }

            TopicServicesResponse<ServicesReplyData> serviceReply = executeWithBusyRetry(job);
            if (!hasSuccessfulResult(serviceReply)) {
                log.info("Execute job ====> Error: {}", replyResult(serviceReply));
                if (Optional.ofNullable(replyResult(serviceReply))
                        .map(result -> result.getCode() == COMMAND_IN_PROGRESS_CODE)
                        .orElse(false)) {
                    // 104 拒绝无外部副作用，但任务也进终态：前端重试总是新建任务，
                    // 保留 PENDING 只会在任务列表堆积废弃记录。
                    failJob(jobId, COMMAND_IN_PROGRESS_CODE);
                    throw new IllegalStateException(
                            "The aircraft rejected the wayline start (code 104: another command in "
                                    + "progress). This firmware only starts wayline missions on the "
                                    + "ground with motors stopped—land or return home first. If the "
                                    + "aircraft is already on the ground, wait a moment for it to "
                                    + "finish parsing the wayline and retry.");
                }
                waylineJobService.updateJobIfNotEnded(WaylineJobDTO.builder()
                        .jobId(jobId)
                        .executeTime(LocalDateTime.now())
                        .status(WaylineJobStatusEnum.FAILED.getVal())
                        .completedTime(LocalDateTime.now())
                        .code(Optional.ofNullable(replyResult(serviceReply))
                                .map(result -> result.getCode()).orElse(211001))
                        .build());
                // The conditional task fails and enters the blocking status.
                if (TaskTypeEnum.CONDITIONAL == job.getTaskType()
                        && Optional.ofNullable(replyResult(serviceReply))
                        .map(result -> WaylineErrorCodeEnum.find(result.getCode()).isBlock())
                        .orElse(false)) {
                    waylineRedisService.setBlockedWaylineJob(job.getDockSn(), jobId);
                }
                return false;
            }

            executeAccepted = true;
            // A terminal progress event can arrive before the service ACK. Do not
            // reverse it back to IN_PROGRESS when that event won the database race.
            try {
                waylineJobService.updateJobIfNotEnded(WaylineJobDTO.builder()
                        .jobId(jobId)
                        .executeTime(LocalDateTime.now())
                        .status(WaylineJobStatusEnum.IN_PROGRESS.getVal())
                        .build());
            } catch (RuntimeException e) {
                // The external side effect has already succeeded. Preserve that
                // truth for the caller and let progress events reconcile the DB.
                log.error("The gateway accepted execution for job {}, but local status persistence failed.",
                        jobId, e);
            }
            return true;
        } finally {
            try {
                if (executionClaimed && !executeAccepted) {
                    waylineRedisService.clearWaylineJobState(job.getDockSn(), jobId);
                }
            } finally {
                releaseWaylineOperation(job.getDockSn(), operationToken);
            }
        }
    }

    @Override
    public void cancelFlightTask(String workspaceId, Collection<String> jobIds) {
        if (jobIds == null || jobIds.isEmpty()) {
            throw new IllegalArgumentException("At least one wayline job must be selected.");
        }

        Set<String> requestedJobIds = jobIds.stream()
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (requestedJobIds.isEmpty() || requestedJobIds.size() != jobIds.size()) {
            throw new IllegalArgumentException("Wayline job IDs must not be blank or duplicated.");
        }
        List<WaylineJobDTO> waylineJobs = waylineJobService.getJobsByConditions(
                workspaceId, requestedJobIds, null);
        Set<String> acceptedJobIds = waylineJobs.stream()
                .filter(Objects::nonNull)
                .filter(job -> job.getStatus() != null)
                .filter(job -> {
                    WaylineJobStatusEnum status = WaylineJobStatusEnum.find(job.getStatus());
                    return WaylineJobStatusEnum.PENDING == status
                            || WaylineJobStatusEnum.IN_PROGRESS == status
                            || WaylineJobStatusEnum.PAUSED == status
                            || WaylineJobStatusEnum.CANCEL == status;
                })
                .map(WaylineJobDTO::getJobId)
                .collect(Collectors.toSet());
        Set<String> rejectedJobIds = new LinkedHashSet<>(requestedJobIds);
        rejectedJobIds.removeAll(acceptedJobIds);
        if (!rejectedJobIds.isEmpty()) {
            Map<String, WaylineJobStatusEnum> currentStates = waylineJobs.stream()
                    .filter(Objects::nonNull)
                    .collect(Collectors.toMap(WaylineJobDTO::getJobId,
                            job -> WaylineJobStatusEnum.find(job.getStatus()), (left, right) -> left));
            String detail = rejectedJobIds.stream()
                    .map(id -> id + "(" + Optional.ofNullable(currentStates.get(id))
                            .map(Enum::toString).orElse("NOT_FOUND") + ")")
                    .collect(Collectors.joining(", "));
            throw new IllegalArgumentException(
                    "These jobs are already finished or do not exist and cannot be canceled: "
                            + detail + ". Refresh the job list to see the latest status.");
        }

        // A previously confirmed cancellation is an idempotent local-reconciliation
        // request. This makes a multi-gateway/Redis partial failure safely retryable.
        waylineJobs.stream()
                .filter(job -> WaylineJobStatusEnum.CANCEL == WaylineJobStatusEnum.find(job.getStatus()))
                .forEach(job -> reconcileCanceledJob(workspaceId, job.getDockSn(), job.getJobId()));

        // Even pending timed/conditional jobs have already been prepared on the gateway.
        // Only change local state after the gateway confirms flighttask_undo.
        Map<String, List<WaylineJobDTO>> dockJobs = waylineJobs.stream()
                .filter(Objects::nonNull)
                .filter(job -> WaylineJobStatusEnum.CANCEL != WaylineJobStatusEnum.find(job.getStatus()))
                .collect(Collectors.groupingBy(WaylineJobDTO::getDockSn));
        dockJobs.forEach((dockSn, jobs) -> {
            long activeJobCount = jobs.stream()
                    .map(WaylineJobDTO::getStatus)
                    .map(WaylineJobStatusEnum::find)
                    .filter(status -> WaylineJobStatusEnum.IN_PROGRESS == status
                            || WaylineJobStatusEnum.PAUSED == status)
                    .count();
            if (activeJobCount > 1) {
                throw new IllegalArgumentException(
                        "Multiple active jobs were found for gateway " + dockSn + ".");
            }
        });
        dockJobs.forEach((dockSn, jobs) -> this.cancelJobsForGateway(workspaceId, dockSn, jobs));

    }

    private void cancelJobsForGateway(String workspaceId, String dockSn, List<WaylineJobDTO> jobs) {
        String operationToken = acquireWaylineOperation(dockSn);
        try {
            Optional<WaylineJobDTO> runningJob = jobs.stream()
                    .filter(job -> WaylineJobStatusEnum.IN_PROGRESS == WaylineJobStatusEnum.find(job.getStatus()))
                    .findFirst();
            Optional<WaylineJobDTO> pausedJob = jobs.stream()
                    .filter(job -> WaylineJobStatusEnum.PAUSED == WaylineJobStatusEnum.find(job.getStatus()))
                    .findFirst();
            boolean runningJobAlreadyPaused = runningJob
                    .map(job -> isRuntimePausedBy(dockSn, job.getJobId()))
                    .orElse(false);
            if (runningJob.isPresent() && !runningJobAlreadyPaused) {
                assertRuntimeOwnership(dockSn, runningJob.get().getJobId(),
                        WaylineJobStatusEnum.IN_PROGRESS);
            }
            pausedJob.ifPresent(job -> assertRuntimeOwnership(
                    dockSn, job.getJobId(), WaylineJobStatusEnum.PAUSED));
            if (runningJob.isPresent() && !runningJobAlreadyPaused) {
                if (!deviceRedisService.checkDeviceOnline(dockSn)) {
                    throw new RuntimeException("Dock is offline.");
                }
                // Devices reject flighttask_undo with 319006 while a task is running.
                // Keep the confirmed paused marker until undo succeeds so callers can retry or resume.
                pauseJob(workspaceId, dockSn, runningJob.get().getJobId(), WaylineJobStatusEnum.IN_PROGRESS);
            }
            publishCancelTask(workspaceId, dockSn,
                    jobs.stream().map(WaylineJobDTO::getJobId).collect(Collectors.toList()));
        } finally {
            releaseWaylineOperation(dockSn, operationToken);
        }
    }

    private boolean isRuntimePausedBy(String dockSn, String jobId) {
        if (!jobId.equals(waylineRedisService.getPausedWaylineJobId(dockSn))) {
            return false;
        }
        return waylineRedisService.getRunningWaylineJob(dockSn)
                .map(EventsReceiver::getBid)
                .filter(StringUtils::hasText)
                .filter(current -> !jobId.equals(current))
                .isEmpty();
    }

    public void publishCancelTask(String workspaceId, String dockSn, List<String> jobIds) {
        boolean isOnline = deviceRedisService.checkDeviceOnline(dockSn);
        if (!isOnline) {
            throw new RuntimeException("Dock is offline.");
        }

        TopicServicesResponse<ServicesReplyData> serviceReply = isRcGateway(dockSn)
                ? abstractWaylineService.flighttaskUndoRc(SDKManager.getDeviceSDK(dockSn),
                        new FlighttaskUndoRequest().setFlightIds(jobIds))
                : abstractWaylineService.flighttaskUndo(SDKManager.getDeviceSDK(dockSn),
                        new FlighttaskUndoRequest().setFlightIds(jobIds));
        if (!hasSuccessfulResult(serviceReply)) {
            log.info("Cancel job ====> Error: {}", replyResult(serviceReply));
            throw new RuntimeException("Failed to cancel the wayline job of " + dockSn);
        }

        // One SQL statement prevents an acknowledged batch undo from becoming a
        // partially persisted local cancellation. Redis cleanup is replayable.
        waylineJobService.cancelJobsIfNotEnded(workspaceId, jobIds);
        List<WaylineJobDTO> finalJobs = waylineJobService.getJobsByConditions(workspaceId, jobIds, null);
        Map<String, WaylineJobStatusEnum> finalStates = finalJobs.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(WaylineJobDTO::getJobId,
                        job -> WaylineJobStatusEnum.find(job.getStatus()), (left, right) -> left));
        jobIds.forEach(jobId -> reconcileCanceledJob(workspaceId, dockSn, jobId));

        List<String> notCanceled = jobIds.stream()
                .filter(jobId -> WaylineJobStatusEnum.CANCEL != finalStates.get(jobId))
                .collect(Collectors.toList());
        if (!notCanceled.isEmpty()) {
            throw new IllegalStateException(
                    "The gateway accepted undo, but terminal progress won the database transition for jobs: "
                            + Arrays.toString(notCanceled.toArray()));
        }

    }

    private void reconcileCanceledJob(String workspaceId, String dockSn, String jobId) {
        RedisOpsUtils.zRemove(RedisConst.WAYLINE_JOB_TIMED_EXECUTE,
                workspaceId + RedisConst.DELIMITER + dockSn + RedisConst.DELIMITER + jobId);
        waylineRedisService.removePrepareConditionalWaylineJob(
                new ConditionalWaylineJobKey(workspaceId, dockSn, jobId));
        waylineRedisService.delConditionalWaylineJob(jobId);
        waylineRedisService.clearWaylineJobState(dockSn, jobId);

    }

    @Override
    public void uploadMediaHighestPriority(String workspaceId, String jobId) {
        Optional<WaylineJobDTO> jobOpt = waylineJobService.getJobByJobId(workspaceId, jobId);
        if (jobOpt.isEmpty()) {
            throw new RuntimeException(CommonErrorEnum.ILLEGAL_ARGUMENT.getMessage());
        }

        String dockSn = jobOpt.get().getDockSn();
        String key = RedisConst.MEDIA_HIGHEST_PRIORITY_PREFIX + dockSn;
        if (RedisOpsUtils.checkExist(key) && jobId.equals(((MediaFileCountDTO) RedisOpsUtils.get(key)).getJobId())) {
            return;
        }

        TopicServicesResponse<ServicesReplyData> reply = abstractMediaService.uploadFlighttaskMediaPrioritize(
                SDKManager.getDeviceSDK(dockSn), new UploadFlighttaskMediaPrioritize().setFlightId(jobId));
        if (!hasSuccessfulResult(reply)) {
            throw new RuntimeException("Failed to set media job upload priority. Error: " + replyResult(reply));
        }
    }

    @Override
    public void updateJobStatus(String workspaceId, String jobId, UpdateJobParam param) {
        Optional<WaylineJobDTO> waylineJobOpt = waylineJobService.getJobByJobId(workspaceId, jobId);
        if (waylineJobOpt.isEmpty()) {
            throw new RuntimeException("The job does not exist.");
        }
        WaylineJobDTO waylineJob = waylineJobOpt.get();
        WaylineJobStatusEnum persistedStatus = WaylineJobStatusEnum.find(waylineJob.getStatus());
        if (persistedStatus.getEnd() || WaylineJobStatusEnum.PENDING == persistedStatus) {
            throw new RuntimeException(
                    "The requested job is not active (current status: " + persistedStatus
                            + "). Only an in-progress or paused job can be paused or resumed. "
                            + "Refresh the job list to see its latest status.");
        }
        String operationToken = acquireWaylineOperation(waylineJob.getDockSn());
        try {
            WaylineJobStatusEnum statusEnum = waylineJobService.getWaylineState(waylineJob.getDockSn());
            if (statusEnum.getEnd() || WaylineJobStatusEnum.PENDING == statusEnum) {
                throw new RuntimeException("The wayline job status does not match, and the operation cannot be performed.");
            }
            assertRuntimeOwnership(waylineJob.getDockSn(), jobId, statusEnum);

            switch (param.getStatus()) {
                case PAUSE:
                    pauseJob(workspaceId, waylineJob.getDockSn(), jobId, statusEnum);
                    break;
                case RESUME:
                    resumeJob(workspaceId, waylineJob.getDockSn(), jobId, statusEnum);
                    break;
            }
        } finally {
            releaseWaylineOperation(waylineJob.getDockSn(), operationToken);
        }

    }

    private void pauseJob(String workspaceId, String dockSn, String jobId, WaylineJobStatusEnum statusEnum) {
        if (WaylineJobStatusEnum.PAUSED == statusEnum && jobId.equals(waylineRedisService.getPausedWaylineJobId(dockSn))) {
            if (!waylineRedisService.pauseRunningWaylineJob(dockSn, jobId, System.currentTimeMillis())) {
                throw new RuntimeException("The paused wayline metadata changed.");
            }
            return;
        }

        TopicServicesResponse<ServicesReplyData> reply = isRcGateway(dockSn)
                ? abstractWaylineService.flighttaskPauseRc(SDKManager.getDeviceSDK(dockSn))
                : abstractWaylineService.flighttaskPause(SDKManager.getDeviceSDK(dockSn));
        if (!hasSuccessfulResult(reply)) {
            throw new RuntimeException("Failed to pause wayline job. Error: " + replyResult(reply));
        }
        if (!waylineRedisService.pauseRunningWaylineJob(dockSn, jobId, System.currentTimeMillis())) {
            throw new RuntimeException("The running wayline metadata changed after pause was acknowledged.");
        }
    }

    private void resumeJob(String workspaceId, String dockSn, String jobId, WaylineJobStatusEnum statusEnum) {
        Optional<EventsReceiver<FlighttaskProgress>> runningDataOpt = waylineRedisService.getRunningWaylineJob(dockSn);
        if (WaylineJobStatusEnum.IN_PROGRESS == statusEnum) {
            Optional<EventsReceiver<FlighttaskProgress>> currentJob = runningDataOpt
                    .filter(running -> jobId.equals(running.getBid()));
            if (currentJob.isPresent()) {
                if (!waylineRedisService.refreshRunningWaylineJob(dockSn, jobId, currentJob.get())) {
                    throw new RuntimeException("The running wayline metadata changed.");
                }
                return;
            }
            throw new RuntimeException(
                    "The running wayline metadata changed; refusing to resume a different job.");
        }
        if (WaylineJobStatusEnum.PAUSED == statusEnum
                && !jobId.equals(waylineRedisService.getPausedWaylineJobId(dockSn))) {
            throw new RuntimeException(
                    "The paused wayline metadata changed; refusing to resume a different job.");
        }
        TopicServicesResponse<ServicesReplyData> reply = isRcGateway(dockSn)
                ? abstractWaylineService.flighttaskRecoveryRc(SDKManager.getDeviceSDK(dockSn))
                : abstractWaylineService.flighttaskRecovery(SDKManager.getDeviceSDK(dockSn));
        if (!hasSuccessfulResult(reply)) {
            throw new RuntimeException("Failed to resume wayline job. Error: " + replyResult(reply));
        }

        EventsReceiver<FlighttaskProgress> runningData = runningDataOpt
                .filter(running -> jobId.equals(running.getBid()))
                .orElseGet(() -> EventsReceiver.<FlighttaskProgress>builder()
                        .bid(jobId)
                        .sn(dockSn)
                        .build());
        if (!waylineRedisService.resumePausedWaylineJob(
                dockSn, jobId, runningData, System.currentTimeMillis())) {
            throw new RuntimeException("The paused wayline metadata changed after recovery was acknowledged.");
        }
    }

    private void assertRuntimeOwnership(String dockSn, String jobId, WaylineJobStatusEnum statusEnum) {
        Optional<String> runningJobId = waylineRedisService.getRunningWaylineJob(dockSn)
                .map(EventsReceiver::getBid)
                .filter(StringUtils::hasText);
        String pausedJobId = waylineRedisService.getPausedWaylineJobId(dockSn);
        if (WaylineJobStatusEnum.IN_PROGRESS == statusEnum) {
            if (runningJobId.filter(jobId::equals).isEmpty() || StringUtils.hasText(pausedJobId)) {
                throw new RuntimeException(
                        "The running wayline metadata belongs to a different job.");
            }
            return;
        }
        if (WaylineJobStatusEnum.PAUSED == statusEnum
                && (!jobId.equals(pausedJobId)
                || runningJobId.filter(current -> !jobId.equals(current)).isPresent())) {
            throw new RuntimeException(
                    "The paused wayline metadata belongs to a different job.");
        }
    }

    private String acquireWaylineOperation(String dockSn) {
        return waylineRedisService.tryAcquireWaylineJobOperation(dockSn)
                .orElseThrow(() -> new WaylineOperationBusyException(
                        "Another wayline operation is already in progress for this gateway."));
    }

    private void releaseWaylineOperation(String dockSn, String token) {
        try {
            if (!waylineRedisService.releaseWaylineJobOperation(dockSn, token)) {
                log.warn("Wayline operation lock for gateway {} was no longer owned by this request.", dockSn);
            }
        } catch (RuntimeException e) {
            // The lock has a bounded TTL. A cleanup outage must not turn an
            // already acknowledged aircraft command into an API failure.
            log.error("Failed to release wayline operation lock for gateway {}.", dockSn, e);
        }
    }

    private boolean hasSuccessfulResult(TopicServicesResponse<ServicesReplyData> response) {
        return replyResult(response) != null && replyResult(response).isSuccess();
    }

    /** 执行失败的任务统一进 FAILED 终态，避免列表堆积废弃的 PENDING 记录。 */
    private void failJob(String jobId, int code) {
        try {
            waylineJobService.updateJobIfNotEnded(WaylineJobDTO.builder()
                    .jobId(jobId)
                    .executeTime(LocalDateTime.now())
                    .status(WaylineJobStatusEnum.FAILED.getVal())
                    .completedTime(LocalDateTime.now())
                    .code(code)
                    .build());
        } catch (RuntimeException e) {
            log.error("Failed to mark wayline job {} as failed.", jobId, e);
        }
    }

    /**
     * 官方文档：“航线任务指令目前需要在无人机关机或停桨时才能执行”（空中悬停时 RC 会永远以 104 拒绝)
     * flighttask_execute 且不去下载 KMZ（MinIO trace 实测零请求），因此在下发前
     * 拦截并给出准确提示。模式未知（OSD 未到/飞机关机）时放行，由设备自行裁决。
     */
    private void requireAircraftGroundedForWayline(String dockSn) {
        DroneModeCodeEnum mode = deviceRedisService.getDeviceOnline(dockSn)
                .map(DeviceDTO::getChildDeviceSn)
                .filter(StringUtils::hasText)
                .map(deviceService::getDeviceMode)
                .orElse(DroneModeCodeEnum.DISCONNECTED);
        if (DroneModeCodeEnum.IDLE == mode || DroneModeCodeEnum.DISCONNECTED == mode) {
            return;
        }
        throw new IllegalStateException(
                "The aircraft is airborne or busy (mode code " + mode.getCode() + "/" + mode
                        + "). This firmware only starts wayline missions while the aircraft is on "
                        + "the ground with motors stopped (mode code 0/IDLE). Land or return home "
                        + "first, then retry.");
    }

    private TopicServicesResponse<ServicesReplyData> executeWithBusyRetry(WaylineJobDTO job) {
        TopicServicesResponse<ServicesReplyData> reply = null;
        boolean stalePointFlightCleanupTried = false;
        for (int attempt = 1; attempt <= EXECUTE_BUSY_MAX_ATTEMPTS; attempt++) {
            reply = isRcGateway(job.getDockSn())
                    ? abstractWaylineService.flighttaskExecuteRc(
                            SDKManager.getDeviceSDK(job.getDockSn()),
                            new FlighttaskExecuteRequest().setFlightId(job.getJobId()))
                    : abstractWaylineService.flighttaskExecute(
                            SDKManager.getDeviceSDK(job.getDockSn()),
                            new FlighttaskExecuteRequest().setFlightId(job.getJobId()));

            com.yoox.great.mqtt.handle.services.ServicesErrorCode result = replyResult(reply);
            if (result == null || result.getCode() != COMMAND_IN_PROGRESS_CODE
                    || attempt == EXECUTE_BUSY_MAX_ATTEMPTS) {
                return reply;
            }

            if (!stalePointFlightCleanupTried) {
                stalePointFlightCleanupTried = true;
                releaseStalePointFlightSession(job.getDockSn());
            }

            long delayMillis = Math.min(
                    EXECUTE_BUSY_MAX_RETRY_DELAY_MILLIS,
                    EXECUTE_BUSY_RETRY_DELAY_MILLIS << (attempt - 1));
            log.warn("Gateway {} is still completing the previous command; retrying execute for job {} "
                            + "in {} ms (attempt {}/{}).",
                    job.getDockSn(), job.getJobId(), delayMillis, attempt + 1,
                    EXECUTE_BUSY_MAX_ATTEMPTS);
            try {
                TimeUnit.MILLISECONDS.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while waiting to retry wayline execution.", e);
            }
        }
        return reply;
    }

    /**
     * RC 固件在指点飞行/一键起飞到点后不会自行终结内部会话，之后
     * flighttask_execute 会一直被 104 拒绝。交给 ControlService 统一清理
     * （fly_to_point_stop + 定向 flighttask_undo 挂着的 takeoff）；点飞任务
     * 仍活跃时它会拒绝清理，绝不自动取消飞行中的任务。
     */
    private void releaseStalePointFlightSession(String dockSn) {
        try {
            log.info("Gateway {} keeps rejecting execute with code {}. Trying to release stale "
                    + "point-flight sessions before the next retry.", dockSn, COMMAND_IN_PROGRESS_CODE);
            controlService.releaseStaleFlightSessions(dockSn);
        } catch (RuntimeException e) {
            // 清理失败不阻断 execute 重试，由既有的重试与最终报错兜底。
            log.warn("Failed to release the stale point-flight session for gateway {}.", dockSn, e);
        }
    }

    private com.yoox.great.mqtt.handle.services.ServicesErrorCode replyResult(
            TopicServicesResponse<ServicesReplyData> response) {
        return Optional.ofNullable(response)
                .map(TopicServicesResponse::getData)
                .map(ServicesReplyData::getResult)
                .orElse(null);
    }

    private static final class WaylineOperationBusyException extends RuntimeException {

        private WaylineOperationBusyException(String message) {
            super(message);
        }
    }

    @Override
    public void retryPrepareJob(ConditionalWaylineJobKey jobKey, WaylineJobDTO waylineJob) {
        Optional<WaylineJobDTO> childJobOpt = waylineJobService.createWaylineJobByParent(jobKey.getWorkspaceId(), jobKey.getJobId());
        if (childJobOpt.isEmpty()) {
            log.error("Failed to create wayline job.");
            return;
        }

        WaylineJobDTO newJob = childJobOpt.get();
        newJob.setBeginTime(LocalDateTime.now().plusSeconds(RedisConst.WAYLINE_JOB_BLOCK_TIME));
        boolean isAdd = waylineRedisService.addPrepareConditionalWaylineJob(newJob);
        if (!isAdd) {
            log.error("Failed to create wayline job. {}", newJob.getJobId());
            return;
        }

        waylineJob.setJobId(newJob.getJobId());
        waylineRedisService.setConditionalWaylineJob(waylineJob);
    }


    @ServiceActivator(inputChannel = ChannelName.INBOUND_EVENTS_FLIGHTTASK_READY,
            outputChannel = ChannelName.OUTBOUND_EVENTS)
    public TopicEventsResponse<MqttReply> flighttaskReady(TopicEventsRequest<FlighttaskReady> response, MessageHeaders headers) {
        List<String> flightIds = response.getData().getFlightIds();

        log.info("ready task list：{}", Arrays.toString(flightIds.toArray()));
        // Check conditional task blocking status.
        String blockedId = waylineRedisService.getBlockedWaylineJobId(response.getGateway());
        if (!StringUtils.hasText(blockedId)) {
            return null;
        }

        Optional<DeviceDTO> deviceOpt = deviceRedisService.getDeviceOnline(response.getGateway());
        if (deviceOpt.isEmpty()) {
            return null;
        }
        DeviceDTO device = deviceOpt.get();

        try {
            for (String jobId : flightIds) {
                boolean isExecute = this.executeFlightTask(device.getWorkspaceId(), jobId);
                if (!isExecute) {
                    return null;
                }
                Optional<WaylineJobDTO> waylineJobOpt = waylineRedisService.getConditionalWaylineJob(jobId);
                if (waylineJobOpt.isEmpty()) {
                    log.info("The conditional job has expired and will no longer be executed.");
                    return new TopicEventsResponse<>();
                }
                WaylineJobDTO waylineJob = waylineJobOpt.get();
                this.retryPrepareJob(new ConditionalWaylineJobKey(device.getWorkspaceId(), response.getGateway(), jobId), waylineJob);
                return new TopicEventsResponse<>();
            }
        } catch (Exception e) {
            log.error("Failed to execute conditional task.");
            e.printStackTrace();
        }
        return new TopicEventsResponse<>();
    }

}
