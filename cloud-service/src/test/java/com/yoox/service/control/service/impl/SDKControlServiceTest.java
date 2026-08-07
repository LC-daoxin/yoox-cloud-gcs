package com.yoox.service.control.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yoox.great.context.base.Common;
import com.yoox.great.context.enums.device.DeviceDomainEnum;
import com.yoox.great.mqtt.core.consume.MqttReply;
import com.yoox.great.mqtt.enums.control.ControlSourceEnum;
import com.yoox.great.mqtt.enums.control.DrcStatusErrorEnum;
import com.yoox.great.mqtt.enums.control.FlyToStatusEnum;
import com.yoox.great.mqtt.enums.control.JoystickInvalidReasonEnum;
import com.yoox.great.mqtt.enums.control.TakeoffStatusEnum;
import com.yoox.great.mqtt.enums.device.DrcStateEnum;
import com.yoox.great.mqtt.handle.drc.TopicDrcRequest;
import com.yoox.great.mqtt.handle.events.TopicEventsRequest;
import com.yoox.great.mqtt.handle.events.TopicEventsResponse;
import com.yoox.great.mqtt.model.control.DrcStatusNotify;
import com.yoox.great.mqtt.model.control.FlyToPointProgress;
import com.yoox.great.mqtt.model.control.HsiInfoPush;
import com.yoox.great.mqtt.model.control.JoystickInvalidNotify;
import com.yoox.great.mqtt.model.control.TakeoffToPointProgress;
import com.yoox.great.websocket.enums.BizCodeEnum;
import com.yoox.great.websocket.enums.UserTypeEnum;
import com.yoox.great.websocket.service.IWebSocketMessageService;
import com.yoox.service.control.model.dto.DrcSession;
import com.yoox.service.manage.model.dto.DeviceDTO;
import com.yoox.service.manage.service.IDeviceRedisService;
import com.yoox.service.manage.service.IDeviceService;
import com.yoox.service.wayline.model.enums.WaylineTaskStatusEnum;
import com.yoox.service.wayline.service.IFlightTaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.MessageHeaders;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SDKControlServiceTest {

    private static final String GATEWAY_SN = "test-gateway";
    private static final String WORKSPACE_ID = "test-workspace";

    @Mock
    private IWebSocketMessageService webSocketMessageService;

    @Mock
    private IDeviceRedisService deviceRedisService;

    @Mock
    private IDeviceService deviceService;

    @Mock
    private DrcSessionStore drcSessionStore;

    @Mock
    private IFlightTaskService flightTaskService;

    @Mock
    private PointFlightTaskStore pointFlightTaskStore;

    @Spy
    private ObjectMapper mapper = Common.getObjectMapper();

    @InjectMocks
    private SDKControlService controlService;

    @Test
    void flyToProgressFallsBackToRegisteredGatewayAndPreservesReplyCorrelation() {
        mockRegisteredGatewayFallback();
        TopicEventsRequest<FlyToPointProgress> request = eventRequest(
                "fly_to_point_progress", new FlyToPointProgress()
                        .setFlyToId("flyto-1")
                        .setStatus(FlyToStatusEnum.WAYLINE_PROGRESS)
                        .setRemainingDistance(25.5F));

        TopicEventsResponse<MqttReply> response = controlService.flyToPointProgress(request, null);

        assertAcknowledgement(request, response);
        verify(webSocketMessageService).sendBatch(
                org.mockito.ArgumentMatchers.eq(WORKSPACE_ID),
                org.mockito.ArgumentMatchers.eq(UserTypeEnum.WEB.getVal()),
                org.mockito.ArgumentMatchers.eq(BizCodeEnum.FLY_TO_POINT_PROGRESS.getCode()),
                org.mockito.ArgumentMatchers.any());
        ArgumentCaptor<Map<String, Object>> persisted = ArgumentCaptor.forClass(Map.class);
        verify(pointFlightTaskStore).recordProgress(
                org.mockito.ArgumentMatchers.eq(GATEWAY_SN),
                org.mockito.ArgumentMatchers.eq("flyto"), persisted.capture());
        assertEquals("flyto-1", persisted.getValue().get("fly_to_id"));
        assertEquals("wayline_progress", persisted.getValue().get("status"));
        assertEquals(GATEWAY_SN, persisted.getValue().get("sn"));
        assertEquals(request.getTimestamp(), persisted.getValue().get("timestamp"));
    }

    @Test
    void takeoffProgressFallsBackToRegisteredGatewayAndPreservesReplyCorrelation() {
        mockRegisteredGatewayFallback();
        TopicEventsRequest<TakeoffToPointProgress> request = eventRequest(
                "takeoff_to_point_progress", new TakeoffToPointProgress()
                        .setFlightId("takeoff-1")
                        .setStatus(TakeoffStatusEnum.TASK_READY));

        TopicEventsResponse<MqttReply> response = controlService.takeoffToPointProgress(request, null);

        assertAcknowledgement(request, response);
        verify(webSocketMessageService).sendBatch(
                org.mockito.ArgumentMatchers.eq(WORKSPACE_ID),
                org.mockito.ArgumentMatchers.eq(UserTypeEnum.WEB.getVal()),
                org.mockito.ArgumentMatchers.eq(BizCodeEnum.TAKE_OFF_TO_POINT_PROGRESS.getCode()),
                org.mockito.ArgumentMatchers.any());
        verify(pointFlightTaskStore).recordProgress(
                org.mockito.ArgumentMatchers.eq(GATEWAY_SN),
                org.mockito.ArgumentMatchers.eq("takeoff"),
                org.mockito.ArgumentMatchers.argThat(payload ->
                        "takeoff-1".equals(payload.get("flight_id"))
                                && "task_ready".equals(payload.get("status"))));
    }

    @Test
    void hsiInfoPushBroadcastsAutelRadarFieldsInMetres() {
        String gatewaySn = "testgateway";
        DeviceDTO gateway = DeviceDTO.builder()
                .deviceSn(gatewaySn)
                .workspaceId(WORKSPACE_ID)
                .build();
        when(deviceRedisService.getDeviceOnline(gatewaySn)).thenReturn(Optional.of(gateway));
        TopicDrcRequest<HsiInfoPush> request = new TopicDrcRequest<HsiInfoPush>()
                .setMethod("hsi_info_push")
                .setTid("tid")
                .setBid("bid")
                .setTimestamp(100L)
                .setData(new HsiInfoPush()
                        .setFront1Distance(1.5)
                        .setRear4Distance(-1)
                        .setRadarEnable(true));
        MessageHeaders headers = new MessageHeaders(Map.of(
                MqttHeaders.RECEIVED_TOPIC,
                "thing/product/" + gatewaySn + "/drc/up"));

        controlService.hsiInfoPush(request, headers);

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(webSocketMessageService).sendBatch(
                org.mockito.ArgumentMatchers.eq(WORKSPACE_ID),
                org.mockito.ArgumentMatchers.eq(UserTypeEnum.WEB.getVal()),
                org.mockito.ArgumentMatchers.eq(BizCodeEnum.DRC_HSI_INFO_PUSH.getCode()),
                payload.capture());
        assertEquals(gatewaySn, payload.getValue().get("sn"));
        assertEquals(1.5, ((Number) payload.getValue().get("front1_distance")).doubleValue());
        assertEquals(-1, ((Number) payload.getValue().get("rear4_distance")).intValue());
        assertEquals(true, payload.getValue().get("radar_enable"));
    }

    @Test
    void nullFlyToProgressIsNotPersistedButStillAcknowledged() {
        mockRegisteredGatewayFallback();
        TopicEventsRequest<FlyToPointProgress> request = eventRequest(
                "fly_to_point_progress", null);

        TopicEventsResponse<MqttReply> response = controlService.flyToPointProgress(request, null);

        assertAcknowledgement(request, response);
        verify(pointFlightTaskStore, never()).recordProgress(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyMap());
        verify(webSocketMessageService).sendBatch(
                org.mockito.ArgumentMatchers.eq(WORKSPACE_ID),
                org.mockito.ArgumentMatchers.eq(UserTypeEnum.WEB.getVal()),
                org.mockito.ArgumentMatchers.eq(BizCodeEnum.FLY_TO_POINT_PROGRESS.getCode()),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void joystickInvalidFallsBackToRegisteredGatewayAndPreservesReplyCorrelation() {
        mockRegisteredGatewayFallback();
        TopicEventsRequest<JoystickInvalidNotify> request = eventRequest(
                "joystick_invalid_notify",
                new JoystickInvalidNotify().setReason(JoystickInvalidReasonEnum.RC_LOST));

        TopicEventsResponse<MqttReply> response = controlService.joystickInvalidNotify(request, null);

        assertAcknowledgement(request, response);
        ArgumentCaptor<Map<String, Object>> notification = ArgumentCaptor.forClass(Map.class);
        verify(webSocketMessageService).sendBatch(
                org.mockito.ArgumentMatchers.eq(WORKSPACE_ID),
                org.mockito.ArgumentMatchers.eq(UserTypeEnum.WEB.getVal()),
                org.mockito.ArgumentMatchers.eq(BizCodeEnum.JOYSTICK_INVALID_NOTIFY.getCode()),
                notification.capture());
        assertEquals(GATEWAY_SN, notification.getValue().get("sn"));
        assertEquals(0, notification.getValue().get("reason"));
        assertEquals(0, notification.getValue().get("result"));
        assertEquals("遥控器失联，Joystick 控制已失效", notification.getValue().get("message"));
        assertEquals(request.getTimestamp(), notification.getValue().get("event_timestamp"));
    }

    @Test
    void remoteControllerAuthorityRejectionUpdatesGatewayCacheToControllerOwned() {
        DeviceDTO gateway = DeviceDTO.builder()
                .deviceSn(GATEWAY_SN)
                .domain(DeviceDomainEnum.REMOTER_CONTROL)
                .workspaceId(WORKSPACE_ID)
                .controlSource(ControlSourceEnum.A)
                .build();
        when(deviceRedisService.getDeviceOnline(GATEWAY_SN)).thenReturn(Optional.of(gateway));
        TopicEventsRequest<JoystickInvalidNotify> request = eventRequest(
                "joystick_invalid_notify",
                new JoystickInvalidNotify().setReason(
                        JoystickInvalidReasonEnum.RC_AUTHORITY));

        TopicEventsResponse<MqttReply> response =
                controlService.joystickInvalidNotify(request, null);

        assertAcknowledgement(request, response);
        verify(deviceService).updateFlightControl(gateway, ControlSourceEnum.B);
    }

    @Test
    void drcSuccessStatusIsBroadcastWithConnectionState() {
        assertDrcStatusBroadcast(
                DrcStatusErrorEnum.SUCCESS, DrcStateEnum.CONNECTED,
                0, "success", 2);
    }

    @Test
    void drcFailureStatusIsBroadcastWithConnectionState() {
        assertDrcStatusBroadcast(
                DrcStatusErrorEnum.HEARTBEAT_TIMEOUT, DrcStateEnum.DISCONNECTED,
                514301, "The heartbeat times out and the dock disconnects.", 0);
    }

    @Test
    void offlineCacheFallsBackToRegisteredGatewayAndStillAcknowledgesEvent() {
        DeviceDTO registeredGateway = DeviceDTO.builder()
                .domain(DeviceDomainEnum.REMOTER_CONTROL)
                .workspaceId(WORKSPACE_ID)
                .build();
        when(deviceRedisService.getDeviceOnline(GATEWAY_SN)).thenReturn(Optional.empty());
        when(deviceService.getDeviceBySn(GATEWAY_SN)).thenReturn(Optional.of(registeredGateway));
        TopicEventsRequest<DrcStatusNotify> request = request(
                DrcStatusErrorEnum.MQTT_LOST, DrcStateEnum.DISCONNECTED);
        DrcSession session = session(null, 100L);
        when(drcSessionStore.getSessionForEvent(GATEWAY_SN)).thenReturn(Optional.of(session));
        when(drcSessionStore.claimEventCleanup(session)).thenReturn(true);
        when(drcSessionStore.releaseSession(session)).thenReturn(true);

        TopicEventsResponse<MqttReply> response = controlService.drcStatusNotify(request, null);

        assertAcknowledgement(request, response);
        verify(drcSessionStore).releaseSession(session);
        verify(webSocketMessageService).sendBatch(
                org.mockito.ArgumentMatchers.eq(WORKSPACE_ID),
                org.mockito.ArgumentMatchers.eq(UserTypeEnum.WEB.getVal()),
                org.mockito.ArgumentMatchers.eq(BizCodeEnum.DRC_STATUS_NOTIFY.getCode()),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void staleDrcStatusCannotClearNewGeneration() {
        DeviceDTO gateway = DeviceDTO.builder()
                .domain(DeviceDomainEnum.REMOTER_CONTROL)
                .workspaceId(WORKSPACE_ID)
                .build();
        when(deviceRedisService.getDeviceOnline(GATEWAY_SN)).thenReturn(Optional.of(gateway));
        DrcSession session = session(null, 300L);
        when(drcSessionStore.getSessionForEvent(GATEWAY_SN)).thenReturn(Optional.of(session));
        TopicEventsRequest<DrcStatusNotify> request = request(
                DrcStatusErrorEnum.MQTT_LOST, DrcStateEnum.DISCONNECTED)
                .setTimestamp(200L);

        TopicEventsResponse<MqttReply> response = controlService.drcStatusNotify(request, null);

        assertAcknowledgement(request, response);
        verify(drcSessionStore, never()).claimEventCleanup(session);
        verify(drcSessionStore, never()).releaseSession(session);
    }

    @Test
    void emptyDrcStatusIsAcknowledgedWithoutClearingCurrentSession() {
        DeviceDTO gateway = DeviceDTO.builder()
                .domain(DeviceDomainEnum.REMOTER_CONTROL)
                .workspaceId(WORKSPACE_ID)
                .build();
        when(deviceRedisService.getDeviceOnline(GATEWAY_SN)).thenReturn(Optional.of(gateway));
        TopicEventsRequest<DrcStatusNotify> request = request(
                DrcStatusErrorEnum.MQTT_LOST, DrcStateEnum.DISCONNECTED)
                .setData(null);

        TopicEventsResponse<MqttReply> response = controlService.drcStatusNotify(request, null);

        assertAcknowledgement(request, response);
        verify(drcSessionStore, never()).getSessionForEvent(GATEWAY_SN);
        verify(drcSessionStore, never()).releaseSession(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void missingDeviceWatermarkNeverAutoClearsCurrentGeneration() {
        DeviceDTO gateway = DeviceDTO.builder()
                .domain(DeviceDomainEnum.REMOTER_CONTROL)
                .workspaceId(WORKSPACE_ID)
                .build();
        when(deviceRedisService.getDeviceOnline(GATEWAY_SN)).thenReturn(Optional.of(gateway));
        DrcSession session = session(null, 100L);
        session.setDeviceTimestampWatermark(null);
        when(drcSessionStore.getSessionForEvent(GATEWAY_SN)).thenReturn(Optional.of(session));
        TopicEventsRequest<DrcStatusNotify> request = request(
                DrcStatusErrorEnum.MQTT_LOST, DrcStateEnum.DISCONNECTED)
                .setTimestamp(500L);

        controlService.drcStatusNotify(request, null);

        verify(drcSessionStore, never()).claimEventCleanup(session);
        verify(drcSessionStore, never()).releaseSession(session);
    }

    @Test
    void deviceWatermarkRatherThanServerCreatedAtRejectsDelayedStatus() {
        DeviceDTO gateway = DeviceDTO.builder()
                .domain(DeviceDomainEnum.REMOTER_CONTROL)
                .workspaceId(WORKSPACE_ID)
                .build();
        when(deviceRedisService.getDeviceOnline(GATEWAY_SN)).thenReturn(Optional.of(gateway));
        DrcSession session = session(null, 100L);
        session.setDeviceTimestampWatermark(300L);
        when(drcSessionStore.getSessionForEvent(GATEWAY_SN)).thenReturn(Optional.of(session));
        TopicEventsRequest<DrcStatusNotify> request = request(
                DrcStatusErrorEnum.MQTT_LOST, DrcStateEnum.DISCONNECTED)
                .setTimestamp(200L);

        controlService.drcStatusNotify(request, null);

        verify(drcSessionStore, never()).claimEventCleanup(session);
        verify(drcSessionStore, never()).releaseSession(session);
    }

    @Test
    void disconnectResumesPausedWaylineBeforeRevokingSession() {
        DeviceDTO gateway = DeviceDTO.builder()
                .domain(DeviceDomainEnum.REMOTER_CONTROL)
                .workspaceId(WORKSPACE_ID)
                .build();
        when(deviceRedisService.getDeviceOnline(GATEWAY_SN)).thenReturn(Optional.of(gateway));
        DrcSession session = session("paused-job", 100L);
        when(drcSessionStore.getSessionForEvent(GATEWAY_SN)).thenReturn(Optional.of(session));
        when(drcSessionStore.claimEventCleanup(session)).thenReturn(true);
        when(drcSessionStore.releaseSession(session)).thenReturn(true);
        TopicEventsRequest<DrcStatusNotify> request = request(
                DrcStatusErrorEnum.HEARTBEAT_TIMEOUT, DrcStateEnum.DISCONNECTED);

        controlService.drcStatusNotify(request, null);

        InOrder order = inOrder(flightTaskService, drcSessionStore);
        order.verify(drcSessionStore).claimEventCleanup(session);
        order.verify(flightTaskService).updateJobStatus(
                org.mockito.ArgumentMatchers.eq(WORKSPACE_ID),
                org.mockito.ArgumentMatchers.eq("paused-job"),
                org.mockito.ArgumentMatchers.argThat(
                        param -> param.getStatus() == WaylineTaskStatusEnum.RESUME));
        order.verify(drcSessionStore).releaseSession(session);
    }

    @Test
    void changedGenerationCannotResumeOldPausedWayline() {
        DeviceDTO gateway = DeviceDTO.builder()
                .domain(DeviceDomainEnum.REMOTER_CONTROL)
                .workspaceId(WORKSPACE_ID)
                .build();
        when(deviceRedisService.getDeviceOnline(GATEWAY_SN)).thenReturn(Optional.of(gateway));
        DrcSession session = session("paused-job", 100L);
        when(drcSessionStore.getSessionForEvent(GATEWAY_SN)).thenReturn(Optional.of(session));
        when(drcSessionStore.claimEventCleanup(session)).thenReturn(false);
        TopicEventsRequest<DrcStatusNotify> request = request(
                DrcStatusErrorEnum.HEARTBEAT_TIMEOUT, DrcStateEnum.DISCONNECTED);

        TopicEventsResponse<MqttReply> response = controlService.drcStatusNotify(request, null);

        assertAcknowledgement(request, response);
        verify(flightTaskService, never()).updateJobStatus(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
        verify(drcSessionStore, never()).releaseSession(session);
    }

    @Test
    void resumeFailureRevokesControlButDoesNotAcknowledgeOrReleaseLease() {
        DeviceDTO gateway = DeviceDTO.builder()
                .domain(DeviceDomainEnum.REMOTER_CONTROL)
                .workspaceId(WORKSPACE_ID)
                .build();
        when(deviceRedisService.getDeviceOnline(GATEWAY_SN)).thenReturn(Optional.of(gateway));
        DrcSession session = session("paused-job", 100L);
        when(drcSessionStore.getSessionForEvent(GATEWAY_SN)).thenReturn(Optional.of(session));
        when(drcSessionStore.claimEventCleanup(session)).thenReturn(true);
        doThrow(new IllegalStateException("resume failed"))
                .when(flightTaskService).updateJobStatus(
                        org.mockito.ArgumentMatchers.eq(WORKSPACE_ID),
                        org.mockito.ArgumentMatchers.eq("paused-job"),
                        org.mockito.ArgumentMatchers.any());
        TopicEventsRequest<DrcStatusNotify> request = request(
                DrcStatusErrorEnum.HEARTBEAT_TIMEOUT, DrcStateEnum.DISCONNECTED);

        assertThrows(IllegalStateException.class,
                () -> controlService.drcStatusNotify(request, null));

        verify(drcSessionStore).revokeSessionAcls(session);
        verify(drcSessionStore, never()).releaseSession(session);
        verify(webSocketMessageService, never()).sendBatch(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void leaseReleaseFailureDoesNotReturnSuccessAcknowledgement() {
        DeviceDTO gateway = DeviceDTO.builder()
                .domain(DeviceDomainEnum.REMOTER_CONTROL)
                .workspaceId(WORKSPACE_ID)
                .build();
        when(deviceRedisService.getDeviceOnline(GATEWAY_SN)).thenReturn(Optional.of(gateway));
        DrcSession session = session(null, 100L);
        when(drcSessionStore.getSessionForEvent(GATEWAY_SN)).thenReturn(Optional.of(session));
        when(drcSessionStore.claimEventCleanup(session)).thenReturn(true);
        when(drcSessionStore.releaseSession(session)).thenReturn(false);
        TopicEventsRequest<DrcStatusNotify> request = request(
                DrcStatusErrorEnum.MQTT_LOST, DrcStateEnum.DISCONNECTED);

        assertThrows(IllegalStateException.class,
                () -> controlService.drcStatusNotify(request, null));

        verify(webSocketMessageService, never()).sendBatch(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void untrustedPayloadCannotRedirectAnEventToAnotherGateway() {
        String victimGateway = "victim-gateway";
        DeviceDTO victim = DeviceDTO.builder()
                .deviceSn(victimGateway)
                .workspaceId("victim-workspace")
                .childDeviceSn("victim-aircraft")
                .build();
        when(deviceRedisService.getDeviceOnline(victimGateway))
                .thenReturn(Optional.of(victim));
        TopicEventsRequest<DrcStatusNotify> request = request(
                DrcStatusErrorEnum.MQTT_LOST, DrcStateEnum.DISCONNECTED)
                .setGateway(victimGateway);

        assertThrows(SecurityException.class,
                () -> controlService.drcStatusNotify(request, null));

        verify(drcSessionStore, never()).getSessionForEvent(
                org.mockito.ArgumentMatchers.anyString());
        verify(webSocketMessageService, never()).sendBatch(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void registeredAircraftTopicCanReportItsParentGateway() {
        String aircraftSn = "aircraft-sn";
        DeviceDTO gateway = DeviceDTO.builder()
                .deviceSn(GATEWAY_SN)
                .domain(DeviceDomainEnum.REMOTER_CONTROL)
                .workspaceId(WORKSPACE_ID)
                .childDeviceSn(aircraftSn)
                .build();
        when(deviceRedisService.getDeviceOnline(GATEWAY_SN)).thenReturn(Optional.of(gateway));
        DrcSession session = session(null, 100L);
        when(drcSessionStore.getSessionForEvent(GATEWAY_SN)).thenReturn(Optional.of(session));
        when(drcSessionStore.claimEventCleanup(session)).thenReturn(true);
        when(drcSessionStore.releaseSession(session)).thenReturn(true);
        TopicEventsRequest<DrcStatusNotify> request = request(
                DrcStatusErrorEnum.MQTT_LOST, DrcStateEnum.DISCONNECTED)
                .setFrom(aircraftSn)
                .setGateway(GATEWAY_SN);

        TopicEventsResponse<MqttReply> response = controlService.drcStatusNotify(request, null);

        assertAcknowledgement(request, response);
        verify(drcSessionStore).getSessionForEvent(GATEWAY_SN);
    }

    @SuppressWarnings("unchecked")
    private void assertDrcStatusBroadcast(
            DrcStatusErrorEnum result,
            DrcStateEnum state,
            int expectedResult,
            String expectedMessage,
            int expectedState) {
        DeviceDTO gateway = DeviceDTO.builder()
                .domain(DeviceDomainEnum.REMOTER_CONTROL)
                .workspaceId(WORKSPACE_ID)
                .build();
        when(deviceRedisService.getDeviceOnline(GATEWAY_SN)).thenReturn(Optional.of(gateway));
        TopicEventsRequest<DrcStatusNotify> request = request(result, state);
        DrcSession session = null;
        if (result != DrcStatusErrorEnum.SUCCESS || state == DrcStateEnum.DISCONNECTED) {
            session = session(null, 100L);
            when(drcSessionStore.getSessionForEvent(GATEWAY_SN)).thenReturn(Optional.of(session));
            when(drcSessionStore.claimEventCleanup(session)).thenReturn(true);
            when(drcSessionStore.releaseSession(session)).thenReturn(true);
        }

        TopicEventsResponse<MqttReply> response = controlService.drcStatusNotify(request, null);

        assertAcknowledgement(request, response);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(webSocketMessageService).sendBatch(
                org.mockito.ArgumentMatchers.eq(WORKSPACE_ID),
                org.mockito.ArgumentMatchers.eq(UserTypeEnum.WEB.getVal()),
                org.mockito.ArgumentMatchers.eq(BizCodeEnum.DRC_STATUS_NOTIFY.getCode()),
                payload.capture());
        Map<String, Object> notification = (Map<String, Object>) payload.getValue();
        assertEquals(GATEWAY_SN, notification.get("sn"));
        assertEquals(expectedResult, notification.get("result"));
        assertEquals(expectedMessage, notification.get("message"));
        assertEquals(expectedState, notification.get("drc_state"));
        if (result == DrcStatusErrorEnum.SUCCESS && state != DrcStateEnum.DISCONNECTED) {
            verify(drcSessionStore, never()).getSessionForEvent(GATEWAY_SN);
        } else {
            verify(drcSessionStore).claimEventCleanup(session);
            verify(drcSessionStore).releaseSession(session);
        }
    }

    private TopicEventsRequest<DrcStatusNotify> request(
            DrcStatusErrorEnum result, DrcStateEnum state) {
        return new TopicEventsRequest<DrcStatusNotify>()
                .setFrom(GATEWAY_SN)
                .setTid("drc-tid")
                .setBid("drc-bid")
                .setMethod("drc_status_notify")
                .setTimestamp(200L)
                .setNeedReply(true)
                .setData(new DrcStatusNotify().setResult(result).setDrcState(state));
    }

    private DrcSession session(String pausedJobId, long createdAt) {
        return DrcSession.builder()
                .gatewaySn(GATEWAY_SN)
                .workspaceId(WORKSPACE_ID)
                .userId("user")
                .browserClientId("browser-client")
                .deviceClientId("device-client")
                .generation("generation")
                .pausedJobId(pausedJobId)
                .createdAt(createdAt)
                .deviceTimestampWatermark(createdAt)
                .build();
    }

    private void mockRegisteredGatewayFallback() {
        DeviceDTO registeredGateway = DeviceDTO.builder()
                .workspaceId(WORKSPACE_ID)
                .build();
        when(deviceRedisService.getDeviceOnline(GATEWAY_SN)).thenReturn(Optional.empty());
        when(deviceService.getDeviceBySn(GATEWAY_SN)).thenReturn(Optional.of(registeredGateway));
    }

    private <T> TopicEventsRequest<T> eventRequest(String method, T data) {
        return new TopicEventsRequest<T>()
                .setFrom(GATEWAY_SN)
                .setTid("event-tid")
                .setBid("event-bid")
                .setMethod(method)
                .setTimestamp(1_785_000_000_123L)
                .setNeedReply(true)
                .setData(data);
    }

    private void assertAcknowledgement(
            TopicEventsRequest<?> request, TopicEventsResponse<MqttReply> response) {
        assertNotNull(response);
        assertNotNull(response.getData());
        assertEquals(MqttReply.CODE_SUCCESS, response.getData().getResult());
        assertEquals(request.getTid(), response.getTid());
        assertEquals(request.getBid(), response.getBid());
        assertEquals(request.getMethod(), response.getMethod());
        assertEquals(request.getTimestamp(), response.getTimestamp());
    }
}
