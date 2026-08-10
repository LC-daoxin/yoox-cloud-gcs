package com.yoox.service.wayline.service.impl;

import com.yoox.great.context.error.CommonErrorEnum;
import com.yoox.great.mqtt.enums.wayline.FlighttaskStatusEnum;
import com.yoox.great.mqtt.model.wayline.*;
import com.yoox.great.websocket.enums.BizCodeEnum;
import com.yoox.great.websocket.enums.UserTypeEnum;
import com.yoox.great.websocket.service.IWebSocketMessageService;
import com.yoox.great.mqtt.core.consume.MqttReply;
import com.yoox.great.mqtt.handle.events.EventsDataRequest;
import com.yoox.great.mqtt.handle.events.TopicEventsRequest;
import com.yoox.great.mqtt.handle.events.TopicEventsResponse;
import com.yoox.great.mqtt.core.EventsReceiver;
import com.yoox.great.mqtt.handle.requests.TopicRequestsRequest;
import com.yoox.great.mqtt.handle.requests.TopicRequestsResponse;
import com.yoox.service.manage.model.dto.DeviceDTO;
import com.yoox.service.manage.service.IDeviceRedisService;
import com.yoox.service.media.model.MediaFileCountDTO;
import com.yoox.service.media.service.IMediaRedisService;
import com.yoox.service.wayline.model.dto.WaylineJobDTO;
import com.yoox.service.wayline.model.enums.WaylineJobStatusEnum;
import com.yoox.service.wayline.service.IWaylineFileService;
import com.yoox.service.wayline.service.IWaylineJobService;
import com.yoox.service.wayline.service.IWaylineRedisService;
import com.yoox.api.wayline.AbstractWaylineService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.MessageHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.URL;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

@Service
@Slf4j
public class SDKWaylineService extends AbstractWaylineService {

    @Autowired
    private IDeviceRedisService deviceRedisService;

    @Autowired
    private IWaylineRedisService waylineRedisService;

    @Autowired
    private IMediaRedisService mediaRedisService;

    @Autowired
    private IWebSocketMessageService webSocketMessageService;

    @Autowired
    private IWaylineJobService waylineJobService;

    @Autowired
    private IWaylineFileService waylineFileService;

    @Override
    public TopicEventsResponse<MqttReply> deviceExitHomingNotify(TopicEventsRequest<DeviceExitHomingNotify> request, MessageHeaders headers) {
        return super.deviceExitHomingNotify(request, headers);
    }

    @Override
    public TopicEventsResponse<MqttReply> flighttaskProgress(TopicEventsRequest<EventsDataRequest<FlighttaskProgress>> response, MessageHeaders headers) {
        if (response == null
                || !StringUtils.hasText(response.getGateway())
                || !StringUtils.hasText(response.getBid())
                || response.getData() == null
                || response.getData().getResult() == null
                || response.getData().getOutput() == null
                || response.getData().getOutput().getStatus() == null) {
            log.warn("Ignoring malformed flighttask_progress event.");
            return new TopicEventsResponse<>();
        }
        EventsReceiver<FlighttaskProgress> eventsReceiver = new EventsReceiver<>();
        eventsReceiver.setResult(response.getData().getResult());
        eventsReceiver.setOutput(response.getData().getOutput());
        eventsReceiver.setBid(response.getBid());
        eventsReceiver.setSn(response.getGateway());

        FlighttaskProgress output = eventsReceiver.getOutput();
        log.info("Task progress: {}", output.getProgress());
        if (!eventsReceiver.getResult().isSuccess()) {
            log.error("Task progress ===> Error: " + eventsReceiver.getResult());
        }

        Optional<DeviceDTO> deviceOpt = deviceRedisService.getDeviceOnline(response.getGateway());
        if (deviceOpt.isEmpty()) {
            return new TopicEventsResponse<>();
        }

        FlighttaskStatusEnum statusEnum = output.getStatus();
        Optional<WaylineJobDTO> jobOpt = waylineJobService.getJobByJobId(
                deviceOpt.get().getWorkspaceId(), response.getBid());
        boolean jobAlreadyTerminal = jobOpt
                .map(WaylineJobDTO::getStatus)
                .map(WaylineJobStatusEnum::find)
                .map(WaylineJobStatusEnum::getEnd)
                .orElse(false);
        boolean acceptedForWebsocket = false;
        if (!statusEnum.isEnd() && !jobAlreadyTerminal && jobOpt.isPresent()) {
            long eventTimestamp = Optional.ofNullable(response.getTimestamp())
                    .orElseGet(System::currentTimeMillis);
            acceptedForWebsocket = waylineRedisService.applyWaylineJobProgress(
                    response.getGateway(), response.getBid(), eventsReceiver,
                    eventTimestamp, FlighttaskStatusEnum.PAUSED == statusEnum);
        }

        if (statusEnum.isEnd()) {
            if (!jobAlreadyTerminal && jobOpt.isPresent()) {
                Integer mediaCount = Optional.ofNullable(output.getExt())
                        .map(FlighttaskProgressExt::getMediaCount)
                        .orElse(null);
                WaylineJobDTO job = WaylineJobDTO.builder()
                        .jobId(response.getBid())
                        .status(FlighttaskStatusEnum.CANCELED == statusEnum
                                ? WaylineJobStatusEnum.CANCEL.getVal()
                                : WaylineJobStatusEnum.SUCCESS.getVal())
                        .completedTime(LocalDateTime.now())
                        .mediaCount(mediaCount)
                        .build();

                if (FlighttaskStatusEnum.OK != statusEnum
                        && FlighttaskStatusEnum.CANCELED != statusEnum) {
                    job.setCode(eventsReceiver.getResult().getCode());
                    job.setStatus(WaylineJobStatusEnum.FAILED.getVal());
                }
                boolean updated = Boolean.TRUE.equals(waylineJobService.updateJobIfNotEnded(job));
                acceptedForWebsocket = updated;
                // Record media only when this event won the terminal-state transition.
                if (updated && Objects.nonNull(job.getMediaCount()) && job.getMediaCount() != 0) {
                    mediaRedisService.setMediaCount(response.getGateway(), job.getJobId(),
                            MediaFileCountDTO.builder().deviceSn(deviceOpt.get().getChildDeviceSn())
                                    .jobId(response.getBid()).mediaCount(job.getMediaCount()).uploadedCount(0).build());
                }
            }
            waylineRedisService.clearWaylineJobState(response.getGateway(), response.getBid());
        }

        if (acceptedForWebsocket) {
            webSocketMessageService.sendBatch(deviceOpt.get().getWorkspaceId(), UserTypeEnum.WEB.getVal(),
                    BizCodeEnum.FLIGHT_TASK_PROGRESS.getCode(), eventsReceiver);
        }

        return new TopicEventsResponse<>();
    }

    @Transactional(isolation = Isolation.READ_UNCOMMITTED)
    @Override
    public TopicRequestsResponse<MqttReply<FlighttaskResourceGetResponse>> flighttaskResourceGet(TopicRequestsRequest<FlighttaskResourceGetRequest> response, MessageHeaders headers) {
        String jobId = response.getData().getFlightId();

        Optional<DeviceDTO> deviceOpt = deviceRedisService.getDeviceOnline(response.getGateway());
        if (deviceOpt.isEmpty()) {
            log.error("The device is offline, please try again later.");
            return new TopicRequestsResponse().setData(MqttReply.error(CommonErrorEnum.DEVICE_OFFLINE));
        }
        Optional<WaylineJobDTO> waylineJobOpt = waylineJobService.getJobByJobId(deviceOpt.get().getWorkspaceId(), jobId);
        if (waylineJobOpt.isEmpty()) {
            log.error("The wayline job does not exist.");
            return new TopicRequestsResponse().setData(MqttReply.error(CommonErrorEnum.ILLEGAL_ARGUMENT));
        }

        WaylineJobDTO waylineJob = waylineJobOpt.get();

        Optional<GetWaylineListResponse> waylineFile = waylineFileService.getWaylineByWaylineId(waylineJob.getWorkspaceId(), waylineJob.getFileId());
        if (waylineFile.isEmpty()) {
            log.error("The wayline file does not exist.");
            return new TopicRequestsResponse().setData(MqttReply.error(CommonErrorEnum.ILLEGAL_ARGUMENT));
        }
        try {
            URL url = waylineFileService.getObjectUrl(waylineJob.getWorkspaceId(), waylineFile.get().getId());
            return new TopicRequestsResponse<MqttReply<FlighttaskResourceGetResponse>>().setData(
                    MqttReply.success(new FlighttaskResourceGetResponse()
                            .setFile(new FlighttaskFile()
                                    .setUrl(url.toString())
                                    .setFingerprint(waylineFile.get().getSign()))));
        } catch (SQLException | NullPointerException e) {
            e.printStackTrace();
            return new TopicRequestsResponse().setData(MqttReply.error(CommonErrorEnum.SYSTEM_ERROR));
        }
    }
}
