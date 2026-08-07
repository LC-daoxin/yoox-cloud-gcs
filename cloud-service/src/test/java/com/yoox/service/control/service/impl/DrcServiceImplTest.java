package com.yoox.service.control.service.impl;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.yoox.api.control.AbstractControlService;
import com.yoox.great.context.enums.device.DeviceDomainEnum;
import com.yoox.great.context.enums.version.GatewayManager;
import com.yoox.great.context.enums.version.GatewayTypeEnum;
import com.yoox.great.context.response.HttpResultResponse;
import com.yoox.great.context.utils.JwtUtil;
import com.yoox.great.mqtt.autoconfiguration.MqttPropertyConfiguration;
import com.yoox.great.mqtt.core.EventsReceiver;
import com.yoox.great.mqtt.core.SDKManager;
import com.yoox.great.mqtt.enums.base.MqttProtocolEnum;
import com.yoox.great.mqtt.enums.base.MqttUseEnum;
import com.yoox.great.mqtt.enums.device.DockModeCodeEnum;
import com.yoox.great.mqtt.enums.device.DroneModeCodeEnum;
import com.yoox.great.mqtt.handle.services.ServicesErrorCode;
import com.yoox.great.mqtt.handle.services.ServicesReplyData;
import com.yoox.great.mqtt.handle.services.TopicServicesResponse;
import com.yoox.great.mqtt.model.control.DrcModeEnterRequest;
import com.yoox.great.mqtt.model.device.OsdDockDrone;
import com.yoox.great.mqtt.model.device.OsdRcDrone;
import com.yoox.great.mqtt.model.wayline.FlighttaskProgress;
import com.yoox.great.mqtt.property.DrcModeMqttBroker;
import com.yoox.great.mqtt.property.MqttClientOptions;
import com.yoox.service.control.model.dto.DrcSession;
import com.yoox.service.control.model.dto.JwtAclDTO;
import com.yoox.service.control.model.enums.DroneAuthorityEnum;
import com.yoox.service.control.model.param.DrcConnectParam;
import com.yoox.service.control.model.param.DrcModeParam;
import com.yoox.service.control.service.IControlService;
import com.yoox.service.manage.model.dto.DeviceDTO;
import com.yoox.service.manage.service.IDeviceRedisService;
import com.yoox.service.manage.service.IDeviceService;
import com.yoox.service.wayline.model.enums.WaylineJobStatusEnum;
import com.yoox.service.wayline.model.enums.WaylineTaskStatusEnum;
import com.yoox.service.wayline.service.IFlightTaskService;
import com.yoox.service.wayline.service.IWaylineJobService;
import com.yoox.service.wayline.service.IWaylineRedisService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DrcServiceImplTest {

    private static final String WORKSPACE_ID = "test-workspace";
    private static final String OTHER_WORKSPACE_ID = "other-workspace";
    private static final String USER_ID = "test-user";
    private static final String OTHER_USER_ID = "other-user";
    private static final String GATEWAY_SN = "test-rc";
    private static final String AIRCRAFT_SN = "test-aircraft";
    private static final String CLIENT_ID = "test-client";
    private static final String OTHER_CLIENT_ID = "other-client";
    private static final String JOB_ID = "test-job";
    private static final String DOCK_SN = "test-dock";
    private static final String DOCK_AIRCRAFT_SN = "test-dock-aircraft";

    @Mock
    private IWaylineJobService waylineJobService;

    @Mock
    private IFlightTaskService flighttaskService;

    @Mock
    private IDeviceService deviceService;

    @Mock
    private IControlService controlService;

    @Mock
    private IDeviceRedisService deviceRedisService;

    @Mock
    private IWaylineRedisService waylineRedisService;

    @Mock
    private AbstractControlService abstractControlService;

    @Mock
    private DrcSessionStore drcSessionStore;

    @InjectMocks
    private DrcServiceImpl drcService;

    @BeforeEach
    void registerGatewayAndConfigureDrcBroker() {
        SDKManager.registerDevice(GATEWAY_SN, AIRCRAFT_SN, GatewayTypeEnum.RC, "1.0.0", null);
        MqttClientOptions options = new MqttClientOptions();
        options.setProtocol(MqttProtocolEnum.WS);
        options.setHost("localhost");
        options.setPort(8083);
        new MqttPropertyConfiguration().setMqtt(Map.of(MqttUseEnum.DRC, options));
        JwtUtil.algorithm = Algorithm.HMAC256("unit-test-secret");
        lenient().when(drcSessionStore.deviceClientId(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0) + "-" + invocation.getArgument(1));
    }

    @AfterEach
    void unregisterGateway() {
        SDKManager.logoutDevice(GATEWAY_SN);
    }

    @Test
    void crossWorkspaceDeviceCannotEnterDrc() {
        when(deviceService.getDeviceBySn(GATEWAY_SN)).thenReturn(Optional.of(
                gateway(OTHER_WORKSPACE_ID)));

        assertThrows(SecurityException.class,
                () -> drcService.deviceDrcEnter(WORKSPACE_ID, USER_ID, drcParam(CLIENT_ID)));

        verifyNoInteractions(abstractControlService, controlService);
        verify(drcSessionStore, never()).acquireSession(any());
    }

    @Test
    void secondOwnerCannotEnterExistingGatewayLease() {
        stubRegisteredGateway();
        DrcSession existing = session(OTHER_USER_ID, OTHER_CLIENT_ID, null);
        when(drcSessionStore.getSession(GATEWAY_SN)).thenReturn(Optional.of(existing));

        assertThrows(SecurityException.class,
                () -> drcService.deviceDrcEnter(WORKSPACE_ID, USER_ID, drcParam(CLIENT_ID)));

        verify(drcSessionStore, never()).grantUserTopics(any(), any(), any());
        verifyNoInteractions(abstractControlService, controlService);
    }

    @Test
    void activeLegacyAircraftTopicLeaseMustExitBeforeGatewayTopicMigration() {
        String replacementAircraftSn = "replacement-aircraft";
        DeviceDTO gateway = gateway(WORKSPACE_ID);
        gateway.setChildDeviceSn(replacementAircraftSn);
        when(deviceService.getDeviceBySn(GATEWAY_SN)).thenReturn(Optional.of(gateway));
        when(deviceRedisService.getDeviceOnline(GATEWAY_SN)).thenReturn(Optional.of(gateway));
        DrcSession existing = session(USER_ID, CLIENT_ID, null);
        existing.setControlTopicSn(AIRCRAFT_SN);
        when(drcSessionStore.getSession(GATEWAY_SN)).thenReturn(Optional.of(existing));
        when(drcSessionStore.getState(existing))
                .thenReturn(Optional.of(DrcSessionStore.SessionState.ACTIVE));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> drcService.deviceDrcEnter(
                        WORKSPACE_ID, USER_ID, drcParam(CLIENT_ID)));

        assertEquals(
                "The active DRC session uses an outdated control topic. Exit DRC and enter again.",
                exception.getMessage());
        verifyNoInteractions(controlService, abstractControlService);
    }

    @Test
    void repeatedEnterForActiveLeaseStillForcesFreshFlightAuthorityGrab() {
        stubRegisteredGateway();
        DrcSession existing = session(USER_ID, CLIENT_ID, null);
        existing.setControlTopicSn(GATEWAY_SN);
        when(drcSessionStore.getSession(GATEWAY_SN)).thenReturn(Optional.of(existing));
        when(drcSessionStore.getState(existing))
                .thenReturn(Optional.of(DrcSessionStore.SessionState.ACTIVE));
        stubOnlineGateway(new OsdRcDrone()
                .setElevation(10f)
                .setModeCode(DroneModeCodeEnum.MANUAL));
        when(controlService.seizeAuthority(
                GATEWAY_SN, DroneAuthorityEnum.FLIGHT, null, true))
                .thenReturn(HttpResultResponse.success());

        JwtAclDTO browserAcl = drcService.deviceDrcEnter(
                WORKSPACE_ID, USER_ID, drcParam(CLIENT_ID));

        assertEquals("thing/product/" + GATEWAY_SN + "/drc/down",
                browserAcl.getPub().get(0));
        verify(controlService).seizeAuthority(
                GATEWAY_SN, DroneAuthorityEnum.FLIGHT, null, true);
        verify(abstractControlService, never()).drcModeEnter(any(), any());
        verify(drcSessionStore).refreshSession(existing);
    }

    @Test
    void refreshedBrowserAtomicallyTakesOverSamePrincipalActiveLease() {
        stubRegisteredGateway();
        DrcSession existing = session(USER_ID, OTHER_CLIENT_ID, null);
        existing.setControlTopicSn(GATEWAY_SN);
        when(drcSessionStore.getSession(GATEWAY_SN)).thenReturn(Optional.of(existing));
        when(drcSessionStore.getState(existing))
                .thenReturn(Optional.of(DrcSessionStore.SessionState.ACTIVE));
        when(drcSessionStore.rebindBrowserClient(existing, CLIENT_ID)).thenReturn(true);
        stubOnlineGateway(new OsdRcDrone()
                .setElevation(10f)
                .setModeCode(DroneModeCodeEnum.MANUAL));
        when(controlService.seizeAuthority(
                GATEWAY_SN, DroneAuthorityEnum.FLIGHT, null, true))
                .thenReturn(HttpResultResponse.success());

        JwtAclDTO browserAcl = drcService.deviceDrcEnter(
                WORKSPACE_ID, USER_ID, drcParam(CLIENT_ID));

        assertEquals("thing/product/" + GATEWAY_SN + "/drc/down",
                browserAcl.getPub().get(0));
        verify(drcSessionStore).rebindBrowserClient(existing, CLIENT_ID);
        verify(drcSessionStore).grantUserTopics(
                CLIENT_ID,
                "thing/product/" + GATEWAY_SN + "/drc/down",
                "thing/product/" + GATEWAY_SN + "/drc/up");
        verify(drcSessionStore).refreshSession(existing);
        verify(abstractControlService, never()).drcModeEnter(any(), any());
    }

    @Test
    void callerSuppliedClientIdIsAlwaysRejected() {
        DrcConnectParam param = new DrcConnectParam();
        param.setClientId(CLIENT_ID);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> drcService.userDrcAuth(
                        WORKSPACE_ID, USER_ID, "operator", param));

        assertEquals(
                "Reusing a DRC MQTT client ID is not supported. Request a new client instead.",
                exception.getMessage());
        verify(drcSessionStore, never()).createOwnedBrowserClient(any(), any());
        verify(drcSessionStore, never()).expireOwnedBrowserClient(any(), any(), any());
        verify(drcSessionStore, never()).deleteOwnedBrowserClient(any(), any(), any());
    }

    @Test
    void browserBrokerUsesReservedUsernameInsteadOfPlatformLoginName() {
        when(drcSessionStore.createOwnedBrowserClient(WORKSPACE_ID, USER_ID))
                .thenReturn(CLIENT_ID);
        DrcConnectParam param = new DrcConnectParam();

        DrcModeMqttBroker broker = drcService.userDrcAuth(
                WORKSPACE_ID, USER_ID, "pilot", param);

        assertEquals("drc-browser-" + CLIENT_ID, broker.getUsername());
        assertEquals(CLIENT_ID, broker.getClientId());
    }

    @Test
    void brokerCreationFailureDeletesNewBrowserClient() {
        when(drcSessionStore.createOwnedBrowserClient(WORKSPACE_ID, USER_ID))
                .thenReturn(CLIENT_ID);
        new MqttPropertyConfiguration().setMqtt(Map.of());

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> drcService.userDrcAuth(
                        WORKSPACE_ID, USER_ID, "operator", new DrcConnectParam()));

        assertEquals(
                "Please configure the drc link parameters of mqtt in the backend configuration file first.",
                exception.getMessage());
        verify(drcSessionStore).deleteOwnedBrowserClient(
                WORKSPACE_ID, USER_ID, CLIENT_ID);
    }

    @Test
    void ownerOnlyExitRejectsDifferentSessionOwner() {
        stubRegisteredGateway();
        when(drcSessionStore.getSession(GATEWAY_SN)).thenReturn(Optional.of(
                session(OTHER_USER_ID, OTHER_CLIENT_ID, null)));

        assertThrows(SecurityException.class,
                () -> drcService.deviceDrcExit(
                        WORKSPACE_ID, USER_ID, drcParam(CLIENT_ID)));

        verify(abstractControlService, never()).drcModeExit(any());
        verify(drcSessionStore, never()).releaseSession(any());
    }

    @Test
    void staleBrowserIdentityCannotExitAnotherTabSessionForSamePrincipal() {
        stubRegisteredGateway();
        DrcSession staleSession = session(USER_ID, OTHER_CLIENT_ID, null);
        when(drcSessionStore.getSession(GATEWAY_SN)).thenReturn(Optional.of(staleSession));

        assertThrows(SecurityException.class,
                () -> drcService.deviceDrcExit(
                        WORKSPACE_ID, USER_ID, drcParam(CLIENT_ID)));

        verify(abstractControlService, never()).drcModeExit(any(GatewayManager.class));
        verify(drcSessionStore, never()).releaseSession(staleSession);
    }

    @Test
    void standbyAircraftCanEstablishDrcBeforeTakeoff() {
        stubRegisteredGateway();
        stubNewLease();
        stubOnlineGateway(new OsdRcDrone()
                .setElevation(0f)
                .setModeCode(DroneModeCodeEnum.IDLE));
        when(controlService.seizeAuthority(
                GATEWAY_SN, DroneAuthorityEnum.FLIGHT, null, true))
                .thenReturn(HttpResultResponse.success());
        when(abstractControlService.drcModeEnter(
                any(GatewayManager.class), any(DrcModeEnterRequest.class)))
                .thenReturn(reply(0));
        when(drcSessionStore.markActive(any(DrcSession.class))).thenReturn(true);

        JwtAclDTO acl = drcService.deviceDrcEnter(
                WORKSPACE_ID, USER_ID, drcParam(CLIENT_ID));

        assertEquals(List.of(
                "thing/product/" + GATEWAY_SN + "/drc/down",
                "thing/product/" + AIRCRAFT_SN + "/drc/down"), acl.getPub());
        assertEquals(List.of(
                "thing/product/" + GATEWAY_SN + "/drc/up",
                "thing/product/" + AIRCRAFT_SN + "/drc/up",
                "thing/product/" + GATEWAY_SN + "/services_reply"), acl.getSub());
        verify(controlService).seizeAuthority(
                GATEWAY_SN, DroneAuthorityEnum.FLIGHT, null, true);
        verify(abstractControlService).drcModeEnter(
                any(GatewayManager.class), any(DrcModeEnterRequest.class));
    }

    @Test
    void takeoffPreparedAircraftCanEstablishDrcHeartbeatChannel() {
        stubRegisteredGateway();
        stubNewLease();
        stubOnlineGateway(new OsdRcDrone()
                .setElevation(0f)
                .setModeCode(DroneModeCodeEnum.TAKEOFF_PREPARE));
        when(controlService.seizeAuthority(
                GATEWAY_SN, DroneAuthorityEnum.FLIGHT, null, true))
                .thenReturn(HttpResultResponse.success());
        when(abstractControlService.drcModeEnter(
                any(GatewayManager.class), any(DrcModeEnterRequest.class)))
                .thenReturn(reply(0));
        when(drcSessionStore.markActive(any(DrcSession.class))).thenReturn(true);

        JwtAclDTO acl = drcService.deviceDrcEnter(
                WORKSPACE_ID, USER_ID, drcParam(CLIENT_ID));

        assertEquals(List.of(
                "thing/product/" + GATEWAY_SN + "/drc/up",
                "thing/product/" + AIRCRAFT_SN + "/drc/up",
                "thing/product/" + GATEWAY_SN + "/services_reply"), acl.getSub());
        verify(drcSessionStore).markActive(argThat(
                session -> GATEWAY_SN.equals(session.getControlTopicSn())));
    }

    @Test
    void browserAclFailureAfterDeviceEnterCompensatesAndResumesWayline() {
        stubRegisteredGateway();
        stubNewLease();
        stubValidConditionsAndRunningWayline();
        when(abstractControlService.drcModeEnter(
                any(GatewayManager.class), any(DrcModeEnterRequest.class)))
                .thenReturn(reply(0));
        when(abstractControlService.drcModeExit(any(GatewayManager.class)))
                .thenReturn(reply(0));
        org.mockito.Mockito.doThrow(new RuntimeException("acl failed"))
                .when(drcSessionStore)
                .grantUserTopics(eq(CLIENT_ID), any(), any());

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> drcService.deviceDrcEnter(
                        WORKSPACE_ID, USER_ID, drcParam(CLIENT_ID)));

        assertEquals("acl failed", exception.getMessage());
        verify(abstractControlService).drcModeExit(any(GatewayManager.class));
        verify(drcSessionStore).releaseSession(argThat(
                session -> JOB_ID.equals(session.getPausedJobId())));
        verify(flighttaskService).updateJobStatus(
                eq(WORKSPACE_ID), eq(JOB_ID),
                argThat(param -> param.getStatus() == WaylineTaskStatusEnum.RESUME));
    }

    @Test
    void failedEnterCompensationKeepsRecoverableLeaseAndPausedWayline() {
        stubRegisteredGateway();
        stubNewLease();
        stubValidConditionsAndRunningWayline();
        when(abstractControlService.drcModeEnter(
                any(GatewayManager.class), any(DrcModeEnterRequest.class)))
                .thenReturn(reply(0));
        when(abstractControlService.drcModeExit(any(GatewayManager.class)))
                .thenThrow(new RuntimeException("compensating exit failed"));
        org.mockito.Mockito.doThrow(new RuntimeException("acl failed"))
                .when(drcSessionStore)
                .grantUserTopics(eq(CLIENT_ID), any(), any());
        when(drcSessionStore.markUncertain(any(DrcSession.class))).thenReturn(true);

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> drcService.deviceDrcEnter(
                        WORKSPACE_ID, USER_ID, drcParam(CLIENT_ID)));

        assertEquals("acl failed", exception.getMessage());
        assertEquals(1, exception.getSuppressed().length);
        verify(drcSessionStore).markUncertain(any(DrcSession.class));
        verify(drcSessionStore).revokeSessionAcls(any(DrcSession.class));
        verify(drcSessionStore, never()).releaseSession(any());
        verify(flighttaskService, never()).updateJobStatus(
                eq(WORKSPACE_ID), eq(JOB_ID),
                argThat(param -> param.getStatus() == WaylineTaskStatusEnum.RESUME));
    }

    @Test
    void enterReplyTimeoutStillCompensatesBeforeReleasingLease() {
        stubRegisteredGateway();
        stubNewLease();
        stubValidConditionsAndRunningWayline();
        when(abstractControlService.drcModeEnter(
                any(GatewayManager.class), any(DrcModeEnterRequest.class)))
                .thenThrow(new RuntimeException("services_reply timeout"));
        when(abstractControlService.drcModeExit(any(GatewayManager.class)))
                .thenReturn(reply(0));

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> drcService.deviceDrcEnter(
                        WORKSPACE_ID, USER_ID, drcParam(CLIENT_ID)));

        assertEquals("services_reply timeout", exception.getMessage());
        verify(abstractControlService).drcModeExit(any(GatewayManager.class));
        verify(drcSessionStore).releaseSession(argThat(
                session -> JOB_ID.equals(session.getPausedJobId())));
        verify(flighttaskService).updateJobStatus(
                eq(WORKSPACE_ID), eq(JOB_ID),
                argThat(param -> param.getStatus() == WaylineTaskStatusEnum.RESUME));
    }

    @Test
    void enterReplyTimeoutAndFailedCompensationKeepUncertainLease() {
        stubRegisteredGateway();
        stubNewLease();
        stubValidConditionsAndRunningWayline();
        when(abstractControlService.drcModeEnter(
                any(GatewayManager.class), any(DrcModeEnterRequest.class)))
                .thenThrow(new RuntimeException("services_reply timeout"));
        when(abstractControlService.drcModeExit(any(GatewayManager.class)))
                .thenThrow(new RuntimeException("exit timeout"));
        when(drcSessionStore.markUncertain(any(DrcSession.class))).thenReturn(true);

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> drcService.deviceDrcEnter(
                        WORKSPACE_ID, USER_ID, drcParam(CLIENT_ID)));

        assertEquals("services_reply timeout", exception.getMessage());
        verify(drcSessionStore).markUncertain(any(DrcSession.class));
        verify(drcSessionStore).revokeSessionAcls(any(DrcSession.class));
        verify(drcSessionStore, never()).releaseSession(any());
        verify(flighttaskService, never()).updateJobStatus(
                eq(WORKSPACE_ID), eq(JOB_ID),
                argThat(param -> param.getStatus() == WaylineTaskStatusEnum.RESUME));
    }

    @Test
    void resumeFailureOnExitStillRevokesSessionAndBothAcls() {
        stubRegisteredGateway();
        DrcSession session = session(USER_ID, CLIENT_ID, JOB_ID);
        when(drcSessionStore.getSession(GATEWAY_SN)).thenReturn(Optional.of(session));
        when(drcSessionStore.prepareExit(session))
                .thenReturn(DrcSessionStore.ExitPreparation.STARTED_ACTIVE);
        when(abstractControlService.drcModeExit(any(GatewayManager.class))).thenReturn(reply(0));
        org.mockito.Mockito.doThrow(new RuntimeException("resume failed"))
                .when(flighttaskService)
                .updateJobStatus(eq(WORKSPACE_ID), eq(JOB_ID), any());
        when(drcSessionStore.releaseSession(session)).thenReturn(true);

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> drcService.deviceDrcExit(
                        WORKSPACE_ID, USER_ID, drcParam(CLIENT_ID)));

        assertEquals("resume failed", exception.getMessage());
        verify(drcSessionStore).releaseSession(session);
    }

    @Test
    void exitingOwnerRetryActuallySendsDeviceExitAgain() {
        stubRegisteredGateway();
        DrcSession session = session(USER_ID, CLIENT_ID, null);
        when(drcSessionStore.getSession(GATEWAY_SN)).thenReturn(Optional.of(session));
        when(drcSessionStore.prepareExit(session))
                .thenReturn(DrcSessionStore.ExitPreparation.RETRY_EXITING);
        when(abstractControlService.drcModeExit(any(GatewayManager.class))).thenReturn(reply(0));
        when(drcSessionStore.releaseSession(session)).thenReturn(true);

        drcService.deviceDrcExit(WORKSPACE_ID, USER_ID, drcParam(CLIENT_ID));

        verify(abstractControlService).drcModeExit(any(GatewayManager.class));
        verify(drcSessionStore).releaseSession(session);
        verify(drcSessionStore, never()).restoreAfterFailedExit(any(), any());
    }

    @Test
    void missingPrimaryMetadataUsesOwnerRecoveryAndStillExitsDevice() {
        stubRegisteredGateway();
        DrcSession recovered = session(USER_ID, CLIENT_ID, JOB_ID);
        when(drcSessionStore.getSession(GATEWAY_SN)).thenReturn(Optional.empty());
        when(drcSessionStore.recoverSessionForOwner(
                GATEWAY_SN, WORKSPACE_ID, USER_ID, CLIENT_ID))
                .thenReturn(Optional.of(recovered));
        when(drcSessionStore.prepareExit(recovered))
                .thenReturn(DrcSessionStore.ExitPreparation.RECOVERED_UNKNOWN);
        when(abstractControlService.drcModeExit(any(GatewayManager.class))).thenReturn(reply(0));
        when(drcSessionStore.releaseSession(recovered)).thenReturn(true);

        drcService.deviceDrcExit(WORKSPACE_ID, USER_ID, drcParam(CLIENT_ID));

        verify(abstractControlService).drcModeExit(any(GatewayManager.class));
        verify(drcSessionStore).releaseSession(recovered);
    }

    @Test
    void successfulEnterPausesOnlyAfterSafetyAndAuthorityChecks() {
        stubRegisteredGateway();
        stubNewLease();
        stubValidConditionsAndRunningWayline();
        when(abstractControlService.drcModeEnter(
                any(GatewayManager.class), any(DrcModeEnterRequest.class)))
                .thenReturn(reply(0));
        when(drcSessionStore.markActive(any())).thenReturn(true);

        JwtAclDTO browserAcl = drcService.deviceDrcEnter(
                WORKSPACE_ID, USER_ID, drcParam(CLIENT_ID));

        assertEquals(List.of(
                        "thing/product/" + GATEWAY_SN + "/drc/down",
                        "thing/product/" + AIRCRAFT_SN + "/drc/down"),
                browserAcl.getPub());
        assertEquals(List.of(
                        "thing/product/" + GATEWAY_SN + "/drc/up",
                        "thing/product/" + AIRCRAFT_SN + "/drc/up",
                        "thing/product/" + GATEWAY_SN + "/services_reply"),
                browserAcl.getSub());
        assertTrue(browserAcl.getPub().stream().noneMatch(
                topic -> topic.contains("+") || topic.contains("#")));

        InOrder order = inOrder(controlService, flighttaskService, abstractControlService);
        order.verify(controlService).seizeAuthority(
                GATEWAY_SN, DroneAuthorityEnum.FLIGHT, null, true);
        order.verify(flighttaskService).updateJobStatus(
                eq(WORKSPACE_ID), eq(JOB_ID),
                argThat(param -> param.getStatus() == WaylineTaskStatusEnum.PAUSE));
        ArgumentCaptor<DrcModeEnterRequest> enterRequest =
                ArgumentCaptor.forClass(DrcModeEnterRequest.class);
        order.verify(abstractControlService).drcModeEnter(
                any(GatewayManager.class), enterRequest.capture());
        DrcModeMqttBroker deviceBroker = enterRequest.getValue().getMqttBroker();
        assertTrue(deviceBroker.getUsername().startsWith("drc-device-"));
        assertFalse(JWT.decode(deviceBroker.getPassword()).getClaims().containsKey("acl"));
        verify(drcSessionStore).grantDeviceTopics(
                org.mockito.ArgumentMatchers.anyString(),
                eq("thing/product/" + GATEWAY_SN + "/drc/down"),
                eq("thing/product/" + GATEWAY_SN + "/drc/up"));
        verify(drcSessionStore).grantDeviceTopics(
                org.mockito.ArgumentMatchers.anyString(),
                eq("thing/product/" + AIRCRAFT_SN + "/drc/down"),
                eq("thing/product/" + AIRCRAFT_SN + "/drc/up"));
        verify(drcSessionStore, org.mockito.Mockito.atLeastOnce()).saveSession(argThat(
                session -> GATEWAY_SN.equals(session.getControlTopicSn())
                        && Long.valueOf(150L).equals(
                        session.getDeviceTimestampWatermark())));
    }

    @Test
    void dockUsesGatewayDrcTopicOnly() {
        SDKManager.registerDevice(
                DOCK_SN, DOCK_AIRCRAFT_SN, GatewayTypeEnum.DOCK, "1.0.0", null);
        try {
            DeviceDTO dock = DeviceDTO.builder()
                    .deviceSn(DOCK_SN)
                    .childDeviceSn(DOCK_AIRCRAFT_SN)
                    .domain(DeviceDomainEnum.DOCK)
                    .workspaceId(WORKSPACE_ID)
                    .build();
            when(deviceService.getDeviceBySn(DOCK_SN)).thenReturn(Optional.of(dock));
            when(deviceRedisService.getDeviceOnline(DOCK_SN)).thenReturn(Optional.of(dock));
            when(deviceRedisService.getDeviceOsd(DOCK_AIRCRAFT_SN)).thenReturn(Optional.of(
                    new OsdDockDrone()
                            .setElevation(10f)
                            .setModeCode(DroneModeCodeEnum.MANUAL)));
            when(deviceService.getDockMode(DOCK_SN)).thenReturn(DockModeCodeEnum.WORKING);
            when(drcSessionStore.getSession(DOCK_SN)).thenReturn(Optional.empty());
            when(drcSessionStore.acquireSession(any(DrcSession.class))).thenReturn(true);
            when(controlService.seizeAuthority(
                    DOCK_SN, DroneAuthorityEnum.FLIGHT, null, true))
                    .thenReturn(HttpResultResponse.success());
            when(abstractControlService.drcModeEnter(
                    any(GatewayManager.class), any(DrcModeEnterRequest.class)))
                    .thenReturn(reply(0));
            when(drcSessionStore.markActive(any())).thenReturn(true);

            JwtAclDTO browserAcl = drcService.deviceDrcEnter(
                    WORKSPACE_ID, USER_ID,
                    DrcModeParam.builder().dockSn(DOCK_SN).clientId(CLIENT_ID).build());

            assertEquals(List.of("thing/product/" + DOCK_SN + "/drc/down"),
                    browserAcl.getPub());
            assertEquals(List.of(
                            "thing/product/" + DOCK_SN + "/drc/up",
                            "thing/product/" + DOCK_SN + "/services_reply"),
                    browserAcl.getSub());
            verify(drcSessionStore, org.mockito.Mockito.atLeastOnce()).saveSession(argThat(
                    session -> DOCK_SN.equals(session.getControlTopicSn())));
        } finally {
            SDKManager.logoutDevice(DOCK_SN);
        }
    }

    private void stubRegisteredGateway() {
        when(deviceService.getDeviceBySn(GATEWAY_SN))
                .thenReturn(Optional.of(gateway(WORKSPACE_ID)));
    }

    private void stubNewLease() {
        when(drcSessionStore.getSession(GATEWAY_SN)).thenReturn(Optional.empty());
        when(drcSessionStore.acquireSession(any(DrcSession.class))).thenReturn(true);
    }

    private void stubValidConditionsAndRunningWayline() {
        stubOnlineGateway(new OsdRcDrone()
                .setElevation(10f)
                .setHeight(0f)
                .setModeCode(DroneModeCodeEnum.MANUAL));
        when(controlService.seizeAuthority(
                GATEWAY_SN, DroneAuthorityEnum.FLIGHT, null, true))
                .thenReturn(HttpResultResponse.success());
        when(waylineRedisService.getRunningWaylineJob(GATEWAY_SN))
                .thenReturn(Optional.of(
                        new EventsReceiver<FlighttaskProgress>().setBid(JOB_ID)));
        when(waylineJobService.getWaylineState(GATEWAY_SN))
                .thenReturn(WaylineJobStatusEnum.IN_PROGRESS);
    }

    private void stubOnlineGateway(OsdRcDrone osd) {
        when(deviceRedisService.getDeviceOnline(GATEWAY_SN))
                .thenReturn(Optional.of(gateway(WORKSPACE_ID)));
        when(deviceRedisService.getDeviceOsd(AIRCRAFT_SN)).thenReturn(Optional.of(osd));
    }

    private DeviceDTO gateway(String workspaceId) {
        return DeviceDTO.builder()
                .deviceSn(GATEWAY_SN)
                .childDeviceSn(AIRCRAFT_SN)
                .domain(DeviceDomainEnum.REMOTER_CONTROL)
                .workspaceId(workspaceId)
                .build();
    }

    private DrcSession session(String userId, String clientId, String jobId) {
        return DrcSession.builder()
                .gatewaySn(GATEWAY_SN)
                .workspaceId(WORKSPACE_ID)
                .userId(userId)
                .browserClientId(clientId)
                .deviceClientId("device-client")
                .generation("generation")
                .pausedJobId(jobId)
                .createdAt(100L)
                .build();
    }

    private DrcModeParam drcParam(String clientId) {
        return DrcModeParam.builder()
                .dockSn(GATEWAY_SN)
                .clientId(clientId)
                .build();
    }

    private TopicServicesResponse<ServicesReplyData> reply(int code) {
        return new TopicServicesResponse<ServicesReplyData>()
                .setTimestamp(150L)
                .setData(new ServicesReplyData<>().setResult(new ServicesErrorCode(code)));
    }
}
