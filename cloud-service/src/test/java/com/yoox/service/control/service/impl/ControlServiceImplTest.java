package com.yoox.service.control.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yoox.api.control.AbstractControlService;
import com.yoox.api.wayline.AbstractWaylineService;
import com.yoox.great.context.base.Common;
import com.yoox.great.context.enums.version.GatewayManager;
import com.yoox.great.context.enums.version.GatewayTypeEnum;
import com.yoox.great.context.response.HttpResultResponse;
import com.yoox.great.mqtt.core.SDKManager;
import com.yoox.great.mqtt.enums.control.ControlSourceEnum;
import com.yoox.great.mqtt.enums.device.DroneModeCodeEnum;
import com.yoox.great.mqtt.enums.device.RcLostActionEnum;
import com.yoox.great.mqtt.handle.services.ServicesErrorCode;
import com.yoox.great.mqtt.handle.services.ServicesReplyData;
import com.yoox.great.mqtt.handle.services.TopicServicesResponse;
import com.yoox.great.mqtt.model.control.Point;
import com.yoox.great.mqtt.model.control.TakeoffToPointRequest;
import com.yoox.great.mqtt.model.wayline.FlighttaskUndoRequest;
import com.yoox.service.control.model.enums.DroneAuthorityEnum;
import com.yoox.service.control.model.param.DronePayloadParam;
import com.yoox.service.control.model.param.FlyToPointParam;
import com.yoox.service.control.model.param.TakeoffToPointParam;
import com.yoox.service.manage.model.dto.DeviceDTO;
import com.yoox.service.manage.service.IDevicePayloadService;
import com.yoox.service.manage.service.IDeviceRedisService;
import com.yoox.service.manage.service.IDeviceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ControlServiceImplTest {

    private static final String GATEWAY_SN = "test-rc";
    private static final String AIRCRAFT_SN = "test-aircraft";
    private static final String PAYLOAD_INDEX = "10806-0-0";

    @Mock
    private IDeviceService deviceService;

    @Mock
    private IDeviceRedisService deviceRedisService;

    @Mock
    private IDevicePayloadService devicePayloadService;

    @Mock
    private AbstractControlService abstractControlService;

    @Mock
    private AbstractWaylineService abstractWaylineService;

    @Mock
    private PayloadAuthorityCacheService payloadAuthorityCacheService;

    @Mock
    private PointFlightTaskStore pointFlightTaskStore;

    @Spy
    private ObjectMapper mapper = Common.getObjectMapper();

    @InjectMocks
    private ControlServiceImpl controlService;

    @BeforeEach
    void registerGateway() {
        SDKManager.registerDevice(GATEWAY_SN, AIRCRAFT_SN, GatewayTypeEnum.RC, "1.0.0", null);
    }

    @AfterEach
    void unregisterGateway() {
        SDKManager.logoutDevice(GATEWAY_SN);
    }

    @Test
    void successfulFlightAuthorityReplyImmediatelyUpdatesCachedAuthority() {
        DeviceDTO gateway = DeviceDTO.builder()
                .deviceSn(GATEWAY_SN)
                .childDeviceSn(AIRCRAFT_SN)
                .build();
        when(deviceService.checkAuthorityFlight(GATEWAY_SN)).thenReturn(false);
        when(deviceRedisService.getDeviceOnline(GATEWAY_SN)).thenReturn(Optional.of(gateway));
        when(deviceRedisService.getDeviceOnline(AIRCRAFT_SN))
                .thenReturn(Optional.of(DeviceDTO.builder().deviceSn(AIRCRAFT_SN).build()));
        when(abstractControlService.flightAuthorityGrab(any(GatewayManager.class)))
                .thenReturn(new TopicServicesResponse<ServicesReplyData>()
                        .setData(new ServicesReplyData<>().setResult(new ServicesErrorCode(0))));

        HttpResultResponse result = controlService.seizeAuthority(
                GATEWAY_SN, DroneAuthorityEnum.FLIGHT, null);

        assertEquals(HttpResultResponse.CODE_SUCCESS, result.getCode());
        verify(deviceService).updateFlightControl(gateway, ControlSourceEnum.A);
    }

    @Test
    void forcedFlightAuthorityAlwaysPublishesEvenWhenCacheCouldBeCloudOwned() {
        DeviceDTO gateway = DeviceDTO.builder()
                .deviceSn(GATEWAY_SN)
                .childDeviceSn(AIRCRAFT_SN)
                .build();
        when(deviceRedisService.getDeviceOnline(GATEWAY_SN)).thenReturn(Optional.of(gateway));
        when(deviceRedisService.getDeviceOnline(AIRCRAFT_SN))
                .thenReturn(Optional.of(DeviceDTO.builder().deviceSn(AIRCRAFT_SN).build()));
        when(abstractControlService.flightAuthorityGrab(any(GatewayManager.class)))
                .thenReturn(new TopicServicesResponse<ServicesReplyData>()
                        .setData(new ServicesReplyData<>().setResult(new ServicesErrorCode(0))));

        HttpResultResponse result = controlService.seizeAuthority(
                GATEWAY_SN, DroneAuthorityEnum.FLIGHT, null, true);

        assertEquals(HttpResultResponse.CODE_SUCCESS, result.getCode());
        verify(deviceService, never()).checkAuthorityFlight(GATEWAY_SN);
        verify(abstractControlService).flightAuthorityGrab(any(GatewayManager.class));
        verify(deviceService).updateFlightControl(gateway, ControlSourceEnum.A);
    }

    @Test
    void successfulPayloadAuthorityReplyCreatesMissingConfirmedCacheEntry() {
        DeviceDTO gateway = DeviceDTO.builder()
                .deviceSn(GATEWAY_SN)
                .childDeviceSn(AIRCRAFT_SN)
                .build();
        DeviceDTO aircraft = DeviceDTO.builder().deviceSn(AIRCRAFT_SN).build();
        when(deviceRedisService.getDeviceOnline(GATEWAY_SN)).thenReturn(Optional.of(gateway));
        when(deviceRedisService.getDeviceOnline(AIRCRAFT_SN)).thenReturn(Optional.of(aircraft));
        when(devicePayloadService.checkAuthorityPayload(AIRCRAFT_SN, PAYLOAD_INDEX)).thenReturn(false);
        when(abstractControlService.payloadAuthorityGrab(any(GatewayManager.class), any()))
                .thenReturn(new TopicServicesResponse<ServicesReplyData>()
                        .setData(new ServicesReplyData<>().setResult(new ServicesErrorCode(0))));
        DronePayloadParam param = new DronePayloadParam();
        param.setPayloadIndex(PAYLOAD_INDEX);

        HttpResultResponse result = controlService.seizeAuthority(
                GATEWAY_SN, DroneAuthorityEnum.PAYLOAD, param);

        assertEquals(HttpResultResponse.CODE_SUCCESS, result.getCode());
        verify(payloadAuthorityCacheService).confirm(GATEWAY_SN, PAYLOAD_INDEX);
    }

    @Test
    void authorityGrabFailsClosedWhenAircraftIsOffline() {
        DeviceDTO gateway = DeviceDTO.builder()
                .deviceSn(GATEWAY_SN)
                .childDeviceSn(AIRCRAFT_SN)
                .build();
        when(deviceRedisService.getDeviceOnline(GATEWAY_SN)).thenReturn(Optional.of(gateway));
        when(deviceRedisService.getDeviceOnline(AIRCRAFT_SN)).thenReturn(Optional.empty());

        RuntimeException exception = assertThrows(RuntimeException.class, () ->
                controlService.seizeAuthority(GATEWAY_SN, DroneAuthorityEnum.FLIGHT, null));

        assertEquals("The aircraft is offline, please reconnect the aircraft.", exception.getMessage());
    }

    @Test
    void existingOrUncertainPointFlightTaskBlocksDuplicateFlyTo() {
        when(pointFlightTaskStore.hasPotentiallyActiveTask(GATEWAY_SN)).thenReturn(true);

        HttpResultResponse result = controlService.flyToPoint(GATEWAY_SN, flyToParam());

        assertEquals(HttpResultResponse.CODE_FAILED, result.getCode());
        assertEquals(
                "A point-flight command is already active or awaiting confirmation. Stop it before starting another one.",
                result.getMessage());
        verify(abstractControlService, never()).flyToPoint(any(), any());
        verify(pointFlightTaskStore, never()).tryRecordPending(any(), any(), any());
    }

    @Test
    void atomicGenerationLoserCannotPublishFlyTo() {
        when(pointFlightTaskStore.hasPotentiallyActiveTask(GATEWAY_SN)).thenReturn(false);
        when(pointFlightTaskStore.tryRecordPending(
                eq(GATEWAY_SN), eq("flyto"), any())).thenReturn(false);

        HttpResultResponse result = controlService.flyToPoint(GATEWAY_SN, flyToParam());

        assertEquals(HttpResultResponse.CODE_FAILED, result.getCode());
        verify(abstractControlService, never()).flyToPoint(any(), any());
        verify(deviceRedisService, never()).getDeviceOnline(any());
    }

    @Test
    void atomicGenerationLoserCannotPublishTakeoff() {
        when(pointFlightTaskStore.hasPotentiallyActiveTask(GATEWAY_SN)).thenReturn(false);
        when(pointFlightTaskStore.tryRecordPending(
                eq(GATEWAY_SN), eq("takeoff"), any())).thenReturn(false);

        HttpResultResponse result = controlService.takeoffToPoint(
                GATEWAY_SN, takeoffParam());

        assertEquals(HttpResultResponse.CODE_FAILED, result.getCode());
        verify(abstractControlService, never()).takeoffToPoint(any(), any());
        verify(abstractControlService, never()).takeoffToPointRc(any(), any());
        verify(deviceRedisService, never()).getDeviceOnline(any());
    }

    @Test
    void rcTakeoffSuppliesRequiredSafetyDefaults() {
        DeviceDTO gateway = DeviceDTO.builder()
                .deviceSn(GATEWAY_SN)
                .childDeviceSn(AIRCRAFT_SN)
                .domain(com.yoox.great.context.enums.device.DeviceDomainEnum.REMOTER_CONTROL)
                .build();
        when(pointFlightTaskStore.hasPotentiallyActiveTask(GATEWAY_SN)).thenReturn(false);
        when(pointFlightTaskStore.tryRecordPending(
                eq(GATEWAY_SN), eq("takeoff"), any())).thenReturn(true);
        when(deviceRedisService.getDeviceOnline(GATEWAY_SN)).thenReturn(Optional.of(gateway));
        when(deviceRedisService.getDeviceOnline(AIRCRAFT_SN))
                .thenReturn(Optional.of(DeviceDTO.builder().deviceSn(AIRCRAFT_SN).build()));
        when(deviceService.getDeviceMode(AIRCRAFT_SN)).thenReturn(DroneModeCodeEnum.IDLE);
        when(deviceService.checkAuthorityFlight(GATEWAY_SN)).thenReturn(true);
        when(abstractControlService.takeoffToPointRc(any(GatewayManager.class), any()))
                .thenReturn(new TopicServicesResponse<ServicesReplyData>()
                        .setData(new ServicesReplyData<>().setResult(new ServicesErrorCode(0))));

        HttpResultResponse result = controlService.takeoffToPoint(GATEWAY_SN, takeoffParam());

        assertEquals(HttpResultResponse.CODE_SUCCESS, result.getCode());
        ArgumentCaptor<TakeoffToPointRequest> request =
                ArgumentCaptor.forClass(TakeoffToPointRequest.class);
        verify(abstractControlService).takeoffToPointRc(any(GatewayManager.class), request.capture());
        assertEquals(20F, request.getValue().getSecurityTakeoffHeight());
        assertEquals(20F, request.getValue().getRthAltitude());
        assertEquals(RcLostActionEnum.RETURN_HOME, request.getValue().getRcLostAction());
        verify(pointFlightTaskStore).recordAccepted(
                eq(GATEWAY_SN), eq("takeoff"), any());
    }

    @Test
    void emptyDeviceReplyRecordsUnknownFlyToState() {
        DeviceDTO gateway = DeviceDTO.builder()
                .deviceSn(GATEWAY_SN)
                .childDeviceSn(AIRCRAFT_SN)
                .build();
        when(pointFlightTaskStore.hasPotentiallyActiveTask(GATEWAY_SN)).thenReturn(false);
        when(pointFlightTaskStore.tryRecordPending(
                eq(GATEWAY_SN), eq("flyto"), any())).thenReturn(true);
        when(deviceRedisService.getDeviceOnline(GATEWAY_SN)).thenReturn(Optional.of(gateway));
        when(deviceRedisService.getDeviceOnline(AIRCRAFT_SN))
                .thenReturn(Optional.of(DeviceDTO.builder().deviceSn(AIRCRAFT_SN).build()));
        when(deviceService.getDeviceMode(AIRCRAFT_SN)).thenReturn(DroneModeCodeEnum.MANUAL);
        when(deviceService.checkAuthorityFlight(GATEWAY_SN)).thenReturn(true);
        when(abstractControlService.flyToPoint(any(GatewayManager.class), any())).thenReturn(null);
        FlyToPointParam param = flyToParam();

        HttpResultResponse result = controlService.flyToPoint(GATEWAY_SN, param);

        assertEquals(HttpResultResponse.CODE_FAILED, result.getCode());
        assertEquals(
                "FlyTo command status is unknown. Check task status before retrying.",
                result.getMessage());
        verify(pointFlightTaskStore).tryRecordPending(GATEWAY_SN, "flyto", param.getFlyToId());
        verify(pointFlightTaskStore).recordUnknown(
                GATEWAY_SN, "flyto", param.getFlyToId(), "The device reply was empty.");
        verify(pointFlightTaskStore, never()).recordAccepted(
                eq(GATEWAY_SN), eq("flyto"), any());
    }

    @Test
    void activeTakeoffIsCanceledWithFlighttaskUndoAndItsFlightId() {
        String flightId = "takeoff-flight-id";
        DeviceDTO gateway = DeviceDTO.builder()
                .deviceSn(GATEWAY_SN)
                .childDeviceSn(AIRCRAFT_SN)
                .domain(com.yoox.great.context.enums.device.DeviceDomainEnum.REMOTER_CONTROL)
                .build();
        when(deviceRedisService.getDeviceOnline(GATEWAY_SN)).thenReturn(Optional.of(gateway));
        when(deviceRedisService.getDeviceOnline(AIRCRAFT_SN))
                .thenReturn(Optional.of(DeviceDTO.builder().deviceSn(AIRCRAFT_SN).build()));
        when(deviceService.checkAuthorityFlight(GATEWAY_SN)).thenReturn(true);
        when(pointFlightTaskStore.get(GATEWAY_SN)).thenReturn(Optional.of(Map.of(
                "kind", "takeoff", "flight_id", flightId, "active", true)));
        when(abstractWaylineService.flighttaskUndoRc(any(GatewayManager.class), any()))
                .thenReturn(new TopicServicesResponse<ServicesReplyData>()
                        .setData(new ServicesReplyData<>().setResult(new ServicesErrorCode(0))));

        HttpResultResponse result = controlService.flyToPointStop(GATEWAY_SN);

        assertEquals(HttpResultResponse.CODE_SUCCESS, result.getCode());
        ArgumentCaptor<FlighttaskUndoRequest> request =
                ArgumentCaptor.forClass(FlighttaskUndoRequest.class);
        verify(abstractWaylineService).flighttaskUndoRc(any(GatewayManager.class), request.capture());
        assertEquals(List.of(flightId), request.getValue().getFlightIds());
        verify(abstractControlService, never()).flyToPointStopRc(any());
        verify(pointFlightTaskStore).recordCancelConfirmed(
                GATEWAY_SN, "Cancel command accepted.");
        // 设备已确认 undo：RC 内部任务已清，同步清掉 last-takeoff 记录。
        verify(pointFlightTaskStore).clearLastAcceptedTakeoffId(GATEWAY_SN, flightId);
    }

    @Test
    void staleSessionCleanupRefusesWhileTaskIsActive() {
        when(pointFlightTaskStore.hasPotentiallyActiveTask(GATEWAY_SN)).thenReturn(true);

        HttpResultResponse result = controlService.releaseStaleFlightSessions(GATEWAY_SN);

        assertEquals(HttpResultResponse.CODE_FAILED, result.getCode());
        verify(abstractControlService, never()).flyToPointStopRc(any());
        verify(abstractControlService, never()).flyToPointStop(any());
        verify(abstractWaylineService, never()).flighttaskUndoRc(any(), any());
        verify(abstractWaylineService, never()).flighttaskUndo(any(), any());
    }

    @Test
    void staleSessionCleanupStopsFlyToAndUndoesRememberedTakeoff() {
        String flightId = "stale-takeoff-id";
        stubIdleRcGatewayWithFlightAuthority();
        when(pointFlightTaskStore.getLastAcceptedTakeoffId(GATEWAY_SN))
                .thenReturn(Optional.of(flightId));
        when(abstractControlService.flyToPointStopRc(any(GatewayManager.class)))
                .thenReturn(new TopicServicesResponse<ServicesReplyData>()
                        .setData(new ServicesReplyData<>().setResult(new ServicesErrorCode(0))));
        when(abstractWaylineService.flighttaskUndoRc(any(GatewayManager.class), any()))
                .thenReturn(new TopicServicesResponse<ServicesReplyData>()
                        .setData(new ServicesReplyData<>().setResult(new ServicesErrorCode(0))));

        HttpResultResponse result = controlService.releaseStaleFlightSessions(GATEWAY_SN);

        assertEquals(HttpResultResponse.CODE_SUCCESS, result.getCode());
        verify(abstractControlService).flyToPointStopRc(any(GatewayManager.class));
        ArgumentCaptor<FlighttaskUndoRequest> request =
                ArgumentCaptor.forClass(FlighttaskUndoRequest.class);
        verify(abstractWaylineService).flighttaskUndoRc(any(GatewayManager.class), request.capture());
        assertEquals(List.of(flightId), request.getValue().getFlightIds());
        verify(pointFlightTaskStore).clearLastAcceptedTakeoffId(GATEWAY_SN, flightId);
    }

    @Test
    void staleSessionCleanupSkipsUndoWithoutRememberedTakeoff() {
        stubIdleRcGatewayWithFlightAuthority();
        when(pointFlightTaskStore.getLastAcceptedTakeoffId(GATEWAY_SN))
                .thenReturn(Optional.empty());
        when(abstractControlService.flyToPointStopRc(any(GatewayManager.class)))
                .thenReturn(new TopicServicesResponse<ServicesReplyData>()
                        .setData(new ServicesReplyData<>().setResult(new ServicesErrorCode(0))));

        HttpResultResponse result = controlService.releaseStaleFlightSessions(GATEWAY_SN);

        assertEquals(HttpResultResponse.CODE_SUCCESS, result.getCode());
        verify(abstractWaylineService, never()).flighttaskUndoRc(any(), any());
        verify(pointFlightTaskStore, never()).clearLastAcceptedTakeoffId(any(), any());
    }

    @Test
    void flyToStopFailureStillUndoesRememberedTakeoffAndRejectedUndoKeepsRecord() {
        String flightId = "stale-takeoff-id";
        stubIdleRcGatewayWithFlightAuthority();
        when(pointFlightTaskStore.getLastAcceptedTakeoffId(GATEWAY_SN))
                .thenReturn(Optional.of(flightId));
        when(abstractControlService.flyToPointStopRc(any(GatewayManager.class)))
                .thenThrow(new RuntimeException("stop reply timed out"));
        when(abstractWaylineService.flighttaskUndoRc(any(GatewayManager.class), any()))
                .thenReturn(new TopicServicesResponse<ServicesReplyData>()
                        .setData(new ServicesReplyData<>().setResult(new ServicesErrorCode(104))));

        HttpResultResponse result = controlService.releaseStaleFlightSessions(GATEWAY_SN);

        assertEquals(HttpResultResponse.CODE_SUCCESS, result.getCode());
        verify(abstractWaylineService).flighttaskUndoRc(any(GatewayManager.class), any());
        // 设备未接受 undo：保留记录供下次重试。
        verify(pointFlightTaskStore, never()).clearLastAcceptedTakeoffId(any(), any());
    }

    private void stubIdleRcGatewayWithFlightAuthority() {
        DeviceDTO gateway = DeviceDTO.builder()
                .deviceSn(GATEWAY_SN)
                .childDeviceSn(AIRCRAFT_SN)
                .domain(com.yoox.great.context.enums.device.DeviceDomainEnum.REMOTER_CONTROL)
                .build();
        when(pointFlightTaskStore.hasPotentiallyActiveTask(GATEWAY_SN)).thenReturn(false);
        when(deviceRedisService.getDeviceOnline(GATEWAY_SN)).thenReturn(Optional.of(gateway));
        when(deviceRedisService.getDeviceOnline(AIRCRAFT_SN))
                .thenReturn(Optional.of(DeviceDTO.builder().deviceSn(AIRCRAFT_SN).build()));
        when(deviceService.checkAuthorityFlight(GATEWAY_SN)).thenReturn(true);
    }

    private FlyToPointParam flyToParam() {
        Point point = new Point()
                .setLatitude(22.6085F)
                .setLongitude(113.8320F)
                .setHeight(50F);
        return new FlyToPointParam(null, 5, List.of(point));
    }

    private TakeoffToPointParam takeoffParam() {
        TakeoffToPointParam param = new TakeoffToPointParam();
        param.setTargetLongitude(113.8320);
        param.setTargetLatitude(22.6085);
        param.setTargetHeight(50.0);
        param.setMaxSpeed(5.0);
        return param;
    }
}
