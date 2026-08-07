package com.yoox.service.control.service.impl;

import com.yoox.api.control.AbstractControlService;
import com.yoox.great.context.enums.device.DeviceDomainEnum;
import com.yoox.great.context.response.HttpResultResponse;
import com.yoox.great.mqtt.autoconfiguration.MqttPropertyConfiguration;
import com.yoox.great.mqtt.constant.TopicConst;
import com.yoox.great.mqtt.core.EventsReceiver;
import com.yoox.great.mqtt.core.SDKManager;
import com.yoox.great.mqtt.enums.device.DockModeCodeEnum;
import com.yoox.great.mqtt.handle.services.ServicesReplyData;
import com.yoox.great.mqtt.handle.services.TopicServicesResponse;
import com.yoox.great.mqtt.model.control.DrcModeEnterRequest;
import com.yoox.great.mqtt.model.wayline.FlighttaskProgress;
import com.yoox.great.mqtt.property.DrcModeMqttBroker;
import com.yoox.great.redis.RedisConst;
import com.yoox.service.control.model.dto.DrcSession;
import com.yoox.service.control.model.dto.JwtAclDTO;
import com.yoox.service.control.model.enums.DroneAuthorityEnum;
import com.yoox.service.control.model.param.DrcConnectParam;
import com.yoox.service.control.model.param.DrcModeParam;
import com.yoox.service.control.service.IControlService;
import com.yoox.service.control.service.IDrcService;
import com.yoox.service.manage.model.dto.DeviceDTO;
import com.yoox.service.manage.service.IDeviceRedisService;
import com.yoox.service.manage.service.IDeviceService;
import com.yoox.service.wayline.model.enums.WaylineJobStatusEnum;
import com.yoox.service.wayline.model.enums.WaylineTaskStatusEnum;
import com.yoox.service.wayline.model.param.UpdateJobParam;
import com.yoox.service.wayline.service.IFlightTaskService;
import com.yoox.service.wayline.service.IWaylineJobService;
import com.yoox.service.wayline.service.IWaylineRedisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class DrcServiceImpl implements IDrcService {

    private static final String BROWSER_MQTT_USERNAME_PREFIX = "drc-browser-";
    private static final String DEVICE_MQTT_USERNAME_PREFIX = "drc-device-";

    @Autowired
    private IWaylineJobService waylineJobService;

    @Autowired
    private IFlightTaskService flighttaskService;

    @Autowired
    private IDeviceService deviceService;

    @Autowired
    private IControlService controlService;

    @Autowired
    private IDeviceRedisService deviceRedisService;

    @Autowired
    private IWaylineRedisService waylineRedisService;

    @Autowired
    private AbstractControlService abstractControlService;

    @Autowired
    private DrcSessionStore drcSessionStore;

    @Override
    public DrcModeMqttBroker userDrcAuth(
            String workspaceId, String userId, String username, DrcConnectParam param) {
        if (StringUtils.hasText(param.getClientId())) {
            throw new IllegalArgumentException(
                    "Reusing a DRC MQTT client ID is not supported. Request a new client instead.");
        }

        String clientId = drcSessionStore.createOwnedBrowserClient(workspaceId, userId);
        try {
            return MqttPropertyConfiguration.getMqttBrokerWithDrc(
                    clientId, BROWSER_MQTT_USERNAME_PREFIX + clientId,
                    param.getExpireSec(), Collections.emptyMap());
        } catch (RuntimeException exception) {
            drcSessionStore.deleteOwnedBrowserClient(workspaceId, userId, clientId);
            throw exception;
        }
    }

    private DeviceDTO requireDrcGateway(String gatewaySn) {
        DeviceDTO gateway = deviceRedisService.getDeviceOnline(gatewaySn)
                .orElseThrow(() -> new RuntimeException("The gateway is offline."));
        if (DeviceDomainEnum.REMOTER_CONTROL == gateway.getDomain()
                && !StringUtils.hasText(gateway.getChildDeviceSn())) {
            throw new RuntimeException("The remote controller has no connected aircraft.");
        }
        if (DeviceDomainEnum.DOCK != gateway.getDomain()
                && DeviceDomainEnum.REMOTER_CONTROL != gateway.getDomain()) {
            throw new RuntimeException(
                    "The current gateway type does not support command flight mode.");
        }
        return gateway;
    }

    private void checkDrcModeCondition(String gatewaySn, DeviceDTO gateway) {
        if (DeviceDomainEnum.DOCK == gateway.getDomain()) {
            DockModeCodeEnum dockMode = deviceService.getDockMode(gatewaySn);
            if (DockModeCodeEnum.IDLE != dockMode && DockModeCodeEnum.WORKING != dockMode) {
                throw new RuntimeException(
                        "The current dock state does not support entering command flight mode.");
            }
        }

        // DRC is also the command-flight communication channel used before
        // take-off. Requiring a positive elevation or an airborne mode here
        // prevents a standby aircraft from establishing MQTT and heartbeats,
        // and the device subsequently reports HEARTBEAT_TIMEOUT. Availability
        // of current aircraft OSD is sufficient for opening the channel; the
        // cockpit separately keeps all non-zero stick output interlocked while
        // the aircraft remains on the ground.
        deviceRedisService.getDeviceOsd(gateway.getChildDeviceSn())
                .orElseThrow(() -> new RuntimeException("Aircraft OSD is unavailable."));

        // A cached cloud authority value can be stale when the remote
        // controller took authority but its state event was delayed/lost.
        // Every DRC entry must therefore dispatch flight_authority_grab again.
        HttpResultResponse result = controlService.seizeAuthority(
                gatewaySn, DroneAuthorityEnum.FLIGHT, null, true);
        if (HttpResultResponse.CODE_SUCCESS != result.getCode()) {
            throw new IllegalArgumentException(result.getMessage());
        }
    }

    private String pauseRunningWayline(String workspaceId, String gatewaySn) {
        Optional<EventsReceiver<FlighttaskProgress>> runningOpt =
                waylineRedisService.getRunningWaylineJob(gatewaySn);
        if (runningOpt.isEmpty()
                || WaylineJobStatusEnum.IN_PROGRESS != waylineJobService.getWaylineState(gatewaySn)) {
            return null;
        }
        String jobId = runningOpt.get().getBid();
        flighttaskService.updateJobStatus(
                workspaceId, jobId,
                UpdateJobParam.builder().status(WaylineTaskStatusEnum.PAUSE).build());
        return jobId;
    }

    private void resumeWaylineAfterFailedEnter(String workspaceId, String jobId) {
        if (!StringUtils.hasText(jobId)) {
            return;
        }
        try {
            flighttaskService.updateJobStatus(
                    workspaceId, jobId,
                    UpdateJobParam.builder().status(WaylineTaskStatusEnum.RESUME).build());
        } catch (RuntimeException exception) {
            log.error("Failed to resume wayline {} after DRC enter failed", jobId, exception);
        }
    }

    @Override
    public JwtAclDTO deviceDrcEnter(String workspaceId, String userId, DrcModeParam param) {
        assertDeviceWorkspace(workspaceId, param.getDockSn());
        drcSessionStore.assertBrowserClientOwner(workspaceId, userId, param.getClientId());
        Optional<DrcSession> existingOpt = drcSessionStore.getSession(param.getDockSn());
        if (existingOpt.isPresent()) {
            assertSessionPrincipalOwner(existingOpt.get(), workspaceId, userId);
        }
        DeviceDTO gateway = requireDrcGateway(param.getDockSn());
        DrcTopics topics = drcTopics(param.getDockSn(), gateway);

        JwtAclDTO browserAcl = JwtAclDTO.builder()
                .sub(topics.subTopics)
                .pub(topics.pubTopics)
                .build();

        if (existingOpt.isPresent()) {
            DrcSession existing = existingOpt.get();
            if (drcSessionStore.getState(existing)
                    .filter(DrcSessionStore.SessionState.ACTIVE::equals)
                    .isEmpty()) {
                throw new IllegalStateException("The DRC session is currently changing state.");
            }
            if (!topics.controlTopicSn.equals(existing.getControlTopicSn())) {
                throw new IllegalStateException(
                        "The active DRC session uses an outdated control topic. Exit DRC and enter again.");
            }
            checkDrcModeCondition(param.getDockSn(), gateway);
            if (!param.getClientId().equals(existing.getBrowserClientId())
                    && !drcSessionStore.rebindBrowserClient(existing, param.getClientId())) {
                throw new IllegalStateException(
                        "The active DRC session changed before browser recovery.");
            }
            grantUserTopics(param.getClientId(), topics);
            drcSessionStore.refreshSession(existing);
            return browserAcl;
        }

        String generation = UUID.randomUUID().toString();
        DrcSession session = DrcSession.builder()
                .gatewaySn(param.getDockSn())
                .workspaceId(workspaceId)
                .userId(userId)
                .browserClientId(param.getClientId())
                .deviceClientId(drcSessionStore.deviceClientId(param.getDockSn(), generation))
                .controlTopicSn(topics.controlTopicSn)
                .generation(generation)
                .createdAt(System.currentTimeMillis())
                .build();
        if (!drcSessionStore.acquireSession(session)) {
            throw new SecurityException("The gateway already has an active DRC owner.");
        }

        boolean enterCommandDispatched = false;
        try {
            checkDrcModeCondition(param.getDockSn(), gateway);
            session.setPausedJobId(pauseRunningWayline(workspaceId, param.getDockSn()));
            drcSessionStore.saveSession(session);

            // The gateway publishes uplink data and subscribes to downlink commands.
            grantDeviceTopics(session.getDeviceClientId(), topics);
            // Once dispatch starts, a timeout is not proof that the device did
            // not enter DRC. Every later failure must therefore be compensated.
            enterCommandDispatched = true;
            TopicServicesResponse<ServicesReplyData> reply = abstractControlService.drcModeEnter(
                    SDKManager.getDeviceSDK(param.getDockSn()),
                    new DrcModeEnterRequest()
                            .setMqttBroker(MqttPropertyConfiguration.getMqttBrokerWithDrc(
                                    session.getDeviceClientId(),
                                    DEVICE_MQTT_USERNAME_PREFIX + session.getGeneration(),
                                    RedisConst.DRC_MODE_ALIVE_SECOND.longValue(),
                                    Collections.emptyMap()))
                            .setHsiFrequency(1)
                            .setOsdFrequency(10));

            if (reply != null && reply.getTimestamp() != null) {
                session.setDeviceTimestampWatermark(reply.getTimestamp());
                drcSessionStore.saveSession(session);
            }
            if (!isSuccessfulReply(reply)) {
                throw new RuntimeException("SN: " + param.getDockSn() + "; Error:"
                        + replyResult(reply)
                        + "; Failed to enter command flight control mode, please try again later!");
            }
            grantUserTopics(param.getClientId(), topics);
            if (!drcSessionStore.markActive(session)) {
                throw new IllegalStateException("The DRC lease changed before activation.");
            }
            drcSessionStore.refreshSession(session);
            return browserAcl;
        } catch (RuntimeException exception) {
            if (enterCommandDispatched) {
                RuntimeException compensationFailure = exitDeviceAfterFailedEnter(param.getDockSn());
                if (compensationFailure != null) {
                    if (!drcSessionStore.markUncertain(session)) {
                        log.error("Unable to mark DRC session {} uncertain after compensation failure",
                                session.getGeneration());
                    }
                    try {
                        drcSessionStore.revokeSessionAcls(session);
                    } catch (RuntimeException aclException) {
                        compensationFailure.addSuppressed(aclException);
                    }
                    exception.addSuppressed(compensationFailure);
                    // Keep the generation and paused job so the same owner can
                    // retry /drc/exit. Releasing here would make an uncertain
                    // device-side DRC session impossible to recover explicitly.
                    throw exception;
                }
            }
            releaseFailedSession(session);
            resumeWaylineAfterFailedEnter(workspaceId, session.getPausedJobId());
            throw exception;
        }
    }

    private DrcTopics drcTopics(String gatewaySn, DeviceDTO gateway) {
        // The browser sends commands through the gateway topic. Some RC firmware also
        // subscribes to the child-aircraft topic while keeping heartbeat/control replies
        // on the gateway topic, so authorize both exact topic pairs for an RC. Docks use
        // only their gateway topic.
        String controlTopicSn = gatewaySn;
        List<String> topicSns = new ArrayList<>();
        topicSns.add(controlTopicSn);
        if (DeviceDomainEnum.REMOTER_CONTROL == gateway.getDomain()
                && StringUtils.hasText(gateway.getChildDeviceSn())
                && !gatewaySn.equals(gateway.getChildDeviceSn())) {
            topicSns.add(gateway.getChildDeviceSn());
        }
        List<String> pubTopics = new ArrayList<>(topicSns.size());
        List<String> subTopics = new ArrayList<>(topicSns.size());
        for (String topicSn : topicSns) {
            String topic = TopicConst.THING_MODEL_PRE + TopicConst.PRODUCT
                    + topicSn + TopicConst.DRC;
            pubTopics.add(topic + TopicConst.DOWN);
            subTopics.add(topic + TopicConst.UP);
        }
        // drc_emergency_landing and drc_force_landing are published on drc/down,
        // but RC/App firmware confirms them on the gateway services_reply topic.
        subTopics.add(TopicConst.THING_MODEL_PRE + TopicConst.PRODUCT
                + gatewaySn + TopicConst.SERVICES_SUF + TopicConst._REPLY_SUF);
        return new DrcTopics(
                controlTopicSn, List.copyOf(pubTopics), List.copyOf(subTopics));
    }

    private void grantDeviceTopics(String clientId, DrcTopics topics) {
        for (int index = 0; index < topics.pubTopics.size(); index++) {
            drcSessionStore.grantDeviceTopics(
                    clientId, topics.pubTopics.get(index), topics.subTopics.get(index));
        }
    }

    private void grantUserTopics(String clientId, DrcTopics topics) {
        for (int index = 0; index < topics.pubTopics.size(); index++) {
            drcSessionStore.grantUserTopics(
                    clientId, topics.pubTopics.get(index), topics.subTopics.get(index));
        }
        for (int index = topics.pubTopics.size(); index < topics.subTopics.size(); index++) {
            drcSessionStore.grantUserSubscribeTopic(clientId, topics.subTopics.get(index));
        }
    }

    private static final class DrcTopics {

        private final String controlTopicSn;

        private final List<String> pubTopics;

        private final List<String> subTopics;

        private DrcTopics(
                String controlTopicSn, List<String> pubTopics, List<String> subTopics) {
            this.controlTopicSn = controlTopicSn;
            this.pubTopics = pubTopics;
            this.subTopics = subTopics;
        }
    }

    @Override
    public void deviceDrcExit(String workspaceId, String userId, DrcModeParam param) {
        assertDeviceWorkspace(workspaceId, param.getDockSn());
        drcSessionStore.assertBrowserClientOwner(workspaceId, userId, param.getClientId());

        Optional<DrcSession> sessionOpt = drcSessionStore.getSession(param.getDockSn());
        if (sessionOpt.isEmpty()) {
            sessionOpt = drcSessionStore.recoverSessionForOwner(
                    param.getDockSn(), workspaceId, userId, param.getClientId());
            if (sessionOpt.isEmpty()) {
                drcSessionStore.deleteOwnedBrowserClient(
                        workspaceId, userId, param.getClientId());
                return;
            }
        }

        DrcSession session = sessionOpt.get();
        // Exit is bound to the currently active browser identity. A refreshed
        // page first atomically rebinds the active lease in /drc/enter; an old
        // tab can therefore close its local socket without terminating the new
        // tab's recovered DRC session.
        assertSessionOwner(session, workspaceId, userId, param.getClientId());
        DrcSessionStore.ExitPreparation exitPreparation = drcSessionStore.prepareExit(session);
        if (exitPreparation == DrcSessionStore.ExitPreparation.REJECTED) {
            throw new IllegalStateException("The DRC session is currently changing state.");
        }
        DrcSessionStore.SessionState previousState = previousState(exitPreparation);

        TopicServicesResponse<ServicesReplyData> reply;
        try {
            reply = abstractControlService.drcModeExit(SDKManager.getDeviceSDK(param.getDockSn()));
        } catch (RuntimeException exception) {
            restoreAfterFailedExit(session, previousState);
            throw exception;
        }
        if (!isSuccessfulReply(reply)) {
            restoreAfterFailedExit(session, previousState);
            throw new RuntimeException("SN: " + param.getDockSn() + "; Error:"
                    + replyResult(reply)
                    + "; Failed to exit command flight control mode, please try again later!");
        }

        RuntimeException resumeFailure = null;
        try {
            if (StringUtils.hasText(session.getPausedJobId())) {
                flighttaskService.updateJobStatus(
                        workspaceId,
                        session.getPausedJobId(),
                        UpdateJobParam.builder().status(WaylineTaskStatusEnum.RESUME).build());
            }
        } catch (RuntimeException exception) {
            resumeFailure = exception;
        }

        RuntimeException cleanupFailure = null;
        try {
            if (!drcSessionStore.releaseSession(session)) {
                cleanupFailure = new IllegalStateException(
                        "The DRC lease changed before cleanup.");
            }
        } catch (RuntimeException exception) {
            cleanupFailure = exception;
        }

        if (resumeFailure != null) {
            if (cleanupFailure != null) {
                resumeFailure.addSuppressed(cleanupFailure);
            }
            throw resumeFailure;
        }
        if (cleanupFailure != null) {
            throw cleanupFailure;
        }
    }

    private void assertDeviceWorkspace(String workspaceId, String gatewaySn) {
        DeviceDTO gateway = deviceService.getDeviceBySn(gatewaySn)
                .orElseThrow(() -> new SecurityException(
                        "The device is not authorized for this workspace."));
        if (!workspaceId.equals(gateway.getWorkspaceId())) {
            throw new SecurityException("The device is not authorized for this workspace.");
        }
    }

    private void assertSessionOwner(
            DrcSession session, String workspaceId, String userId, String browserClientId) {
        assertSessionPrincipalOwner(session, workspaceId, userId);
        if (!browserClientId.equals(session.getBrowserClientId())) {
            throw new SecurityException("The DRC session belongs to another owner.");
        }
    }

    private void assertSessionPrincipalOwner(
            DrcSession session, String workspaceId, String userId) {
        if (!workspaceId.equals(session.getWorkspaceId())
                || !userId.equals(session.getUserId())) {
            throw new SecurityException("The DRC session belongs to another owner.");
        }
    }

    private RuntimeException exitDeviceAfterFailedEnter(String gatewaySn) {
        try {
            TopicServicesResponse<ServicesReplyData> reply =
                    abstractControlService.drcModeExit(SDKManager.getDeviceSDK(gatewaySn));
            if (!isSuccessfulReply(reply)) {
                return new RuntimeException("Failed to compensate DRC enter. Error: "
                        + replyResult(reply));
            }
            return null;
        } catch (RuntimeException cleanupException) {
            log.error("Failed to compensate device DRC enter for gateway {}", gatewaySn,
                    cleanupException);
            return cleanupException;
        }
    }

    private void releaseFailedSession(DrcSession session) {
        try {
            drcSessionStore.releaseSession(session);
        } catch (RuntimeException cleanupException) {
            log.error("Failed to release DRC session {}", session.getGeneration(), cleanupException);
        }
    }

    private void restoreAfterFailedExit(
            DrcSession session, DrcSessionStore.SessionState previousState) {
        if (previousState == null) {
            // A retry that was already EXITING must remain EXITING on failure;
            // the next owner request will issue drc_mode_exit again.
            return;
        }
        if (!drcSessionStore.restoreAfterFailedExit(session, previousState)) {
            log.error("Unable to restore DRC lease {} after exit failure", session.getGeneration());
        }
    }

    private DrcSessionStore.SessionState previousState(
            DrcSessionStore.ExitPreparation preparation) {
        switch (preparation) {
            case STARTED_ACTIVE:
                return DrcSessionStore.SessionState.ACTIVE;
            case STARTED_ENTERING:
                return DrcSessionStore.SessionState.ENTERING;
            case STARTED_UNCERTAIN:
            case RECOVERED_UNKNOWN:
                return DrcSessionStore.SessionState.UNCERTAIN;
            case RETRY_EXITING:
            case REJECTED:
            default:
                return null;
        }
    }

    private boolean isSuccessfulReply(TopicServicesResponse<ServicesReplyData> reply) {
        return reply != null
                && reply.getData() != null
                && reply.getData().getResult() != null
                && reply.getData().getResult().isSuccess();
    }

    private Object replyResult(TopicServicesResponse<ServicesReplyData> reply) {
        return reply == null || reply.getData() == null
                ? "missing services_reply"
                : reply.getData().getResult();
    }
}
