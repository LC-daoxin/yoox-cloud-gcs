package com.yoox.service.wayline.service.impl;

import com.yoox.great.context.enums.device.DeviceDomainEnum;
import com.yoox.great.mqtt.core.EventsReceiver;
import com.yoox.great.mqtt.enums.device.DockModeCodeEnum;
import com.yoox.great.mqtt.enums.device.DroneModeCodeEnum;
import com.yoox.great.mqtt.model.device.OsdDock;
import com.yoox.great.mqtt.model.device.OsdDockDrone;
import com.yoox.great.mqtt.model.device.OsdRcDrone;
import com.yoox.great.mqtt.model.wayline.FlighttaskProgress;
import com.yoox.service.manage.model.dto.DeviceDTO;
import com.yoox.service.manage.service.IDeviceRedisService;
import com.yoox.service.wayline.dao.IWaylineJobMapper;
import com.yoox.service.wayline.model.dto.WaylineJobDTO;
import com.yoox.service.wayline.model.entity.WaylineJobEntity;
import com.yoox.service.wayline.model.enums.WaylineJobStatusEnum;
import com.yoox.service.wayline.service.IWaylineRedisService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WaylineJobServiceImplTest {

    private static final String GATEWAY_SN = "gateway";
    private static final String AIRCRAFT_SN = "aircraft";
    private static final String JOB_ID = "job-1";

    @Mock
    private IDeviceRedisService deviceRedisService;

    @Mock
    private IWaylineRedisService waylineRedisService;

    @Mock
    private IWaylineJobMapper mapper;

    @InjectMocks
    private WaylineJobServiceImpl waylineJobService;

    @Test
    void rcGatewayUsesRcAircraftOsdToDetectRunningWayline() {
        when(deviceRedisService.getDeviceOnline(GATEWAY_SN)).thenReturn(Optional.of(
                gateway(DeviceDomainEnum.REMOTER_CONTROL)));
        when(deviceRedisService.getDeviceOsd(AIRCRAFT_SN, OsdRcDrone.class)).thenReturn(Optional.of(
                new OsdRcDrone().setModeCode(DroneModeCodeEnum.WAYLINE)));
        when(waylineRedisService.getPausedWaylineJobId(GATEWAY_SN)).thenReturn(null);
        when(waylineRedisService.getRunningWaylineJob(GATEWAY_SN)).thenReturn(Optional.of(
                EventsReceiver.<FlighttaskProgress>builder().bid(JOB_ID).sn(GATEWAY_SN).build()));

        assertEquals(WaylineJobStatusEnum.IN_PROGRESS,
                waylineJobService.getWaylineState(GATEWAY_SN));
    }

    @Test
    void rcGatewayUsesPausedCacheToDetectPausedWayline() {
        when(deviceRedisService.getDeviceOnline(GATEWAY_SN)).thenReturn(Optional.of(
                gateway(DeviceDomainEnum.REMOTER_CONTROL)));
        when(deviceRedisService.getDeviceOsd(AIRCRAFT_SN, OsdRcDrone.class)).thenReturn(Optional.of(
                new OsdRcDrone().setModeCode(DroneModeCodeEnum.WAYLINE)));
        when(waylineRedisService.getPausedWaylineJobId(GATEWAY_SN)).thenReturn(JOB_ID);

        assertEquals(WaylineJobStatusEnum.PAUSED,
                waylineJobService.getWaylineState(GATEWAY_SN));
    }

    @Test
    void autelKmlRouteModeIsRecognizedAsRunningWayline() {
        when(deviceRedisService.getDeviceOnline(GATEWAY_SN)).thenReturn(Optional.of(
                gateway(DeviceDomainEnum.REMOTER_CONTROL)));
        when(deviceRedisService.getDeviceOsd(AIRCRAFT_SN, OsdRcDrone.class)).thenReturn(Optional.of(
                new OsdRcDrone().setModeCode(DroneModeCodeEnum.KML_ROUTE_MODE)));
        when(waylineRedisService.getPausedWaylineJobId(GATEWAY_SN)).thenReturn(null);
        when(waylineRedisService.getRunningWaylineJob(GATEWAY_SN)).thenReturn(Optional.of(
                EventsReceiver.<FlighttaskProgress>builder().bid(JOB_ID).sn(GATEWAY_SN).build()));

        assertEquals(WaylineJobStatusEnum.IN_PROGRESS,
                waylineJobService.getWaylineState(GATEWAY_SN));
    }

    @Test
    void rcGatewayWithoutRcAircraftOsdIsUnknown() {
        when(deviceRedisService.getDeviceOnline(GATEWAY_SN)).thenReturn(Optional.of(
                gateway(DeviceDomainEnum.REMOTER_CONTROL)));
        when(deviceRedisService.getDeviceOsd(AIRCRAFT_SN, OsdRcDrone.class))
                .thenReturn(Optional.empty());

        assertEquals(WaylineJobStatusEnum.UNKNOWN,
                waylineJobService.getWaylineState(GATEWAY_SN));
    }

    @Test
    void dockGatewayStillRequiresWorkingDockAndDockAircraftOsd() {
        when(deviceRedisService.getDeviceOnline(GATEWAY_SN)).thenReturn(Optional.of(
                gateway(DeviceDomainEnum.DOCK)));
        when(deviceRedisService.getDeviceOsd(GATEWAY_SN, OsdDock.class)).thenReturn(Optional.of(
                new OsdDock().setModeCode(DockModeCodeEnum.WORKING)));
        when(deviceRedisService.getDeviceOsd(AIRCRAFT_SN, OsdDockDrone.class)).thenReturn(Optional.of(
                new OsdDockDrone().setModeCode(DroneModeCodeEnum.WAYLINE)));
        when(waylineRedisService.getPausedWaylineJobId(GATEWAY_SN)).thenReturn(null);
        when(waylineRedisService.getRunningWaylineJob(GATEWAY_SN)).thenReturn(Optional.of(
                EventsReceiver.<FlighttaskProgress>builder().bid(JOB_ID).sn(GATEWAY_SN).build()));

        assertEquals(WaylineJobStatusEnum.IN_PROGRESS,
                waylineJobService.getWaylineState(GATEWAY_SN));
    }

    @Test
    void terminalTransitionUsesAtomicNonterminalStatusCondition() {
        when(mapper.update(any(WaylineJobEntity.class), any(LambdaUpdateWrapper.class)))
                .thenReturn(1);
        WaylineJobDTO update = WaylineJobDTO.builder()
                .jobId(JOB_ID)
                .status(WaylineJobStatusEnum.CANCEL.getVal())
                .build();

        assertTrue(waylineJobService.updateJobIfNotEnded(update));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<WaylineJobEntity>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(any(WaylineJobEntity.class), wrapperCaptor.capture());
        LambdaUpdateWrapper<WaylineJobEntity> wrapper = wrapperCaptor.getValue();
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                WaylineJobEntity.class);
        assertTrue(wrapper.getSqlSegment().toUpperCase(Locale.ROOT).contains("NOT IN"));
        assertTrue(wrapper.getParamNameValuePairs().values().containsAll(List.of(
                WaylineJobStatusEnum.SUCCESS.getVal(),
                WaylineJobStatusEnum.CANCEL.getVal(),
                WaylineJobStatusEnum.FAILED.getVal())));
    }

    @Test
    void batchCancellationUsesSingleWorkspaceScopedAtomicUpdate() {
        when(mapper.update(any(WaylineJobEntity.class), any(LambdaUpdateWrapper.class)))
                .thenReturn(2);

        assertEquals(2, waylineJobService.cancelJobsIfNotEnded(
                "workspace", List.of(JOB_ID, "job-2")));

        ArgumentCaptor<WaylineJobEntity> entityCaptor = ArgumentCaptor.forClass(WaylineJobEntity.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<WaylineJobEntity>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(entityCaptor.capture(), wrapperCaptor.capture());
        assertEquals(WaylineJobStatusEnum.CANCEL.getVal(), entityCaptor.getValue().getStatus());
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                WaylineJobEntity.class);
        String sql = wrapperCaptor.getValue().getSqlSegment().toUpperCase(Locale.ROOT);
        assertTrue(sql.contains("WORKSPACE_ID"));
        assertTrue(sql.contains(" IN "));
        assertTrue(sql.contains("NOT IN"));
    }

    private DeviceDTO gateway(DeviceDomainEnum domain) {
        return DeviceDTO.builder()
                .deviceSn(GATEWAY_SN)
                .childDeviceSn(AIRCRAFT_SN)
                .domain(domain)
                .build();
    }
}
