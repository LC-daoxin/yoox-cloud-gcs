package com.yoox.service.manage.service.impl;

import com.yoox.api.device.AbstractDeviceService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yoox.great.context.enums.device.DeviceDomainEnum;
import com.yoox.great.context.enums.version.GatewayManager;
import com.yoox.great.mqtt.core.consume.MqttReply;
import com.yoox.great.mqtt.core.SDKManager;
import com.yoox.great.mqtt.enums.control.ControlSourceEnum;
import com.yoox.great.mqtt.enums.device.DroneModeCodeEnum;
import com.yoox.great.mqtt.enums.tsa.IconUrlEnum;
import com.yoox.great.mqtt.handle.osd.TopicOsdRequest;
import com.yoox.great.mqtt.handle.state.TopicStateRequest;
import com.yoox.great.mqtt.handle.status.TopicStatusRequest;
import com.yoox.great.mqtt.handle.status.TopicStatusResponse;
import com.yoox.great.mqtt.model.device.*;
import com.yoox.great.mqtt.model.control.TargetDetectResultReport;
import com.yoox.great.mqtt.model.livestream.RcLiveCapacityDevice;
import com.yoox.great.mqtt.model.tsa.DeviceIconUrl;
import com.yoox.great.websocket.enums.BizCodeEnum;
import com.yoox.great.websocket.enums.UserTypeEnum;
import com.yoox.great.websocket.service.IWebSocketMessageService;
import com.yoox.service.manage.model.dto.DeviceDTO;
import com.yoox.service.control.service.impl.PointFlightTaskStore;
import com.yoox.service.manage.model.dto.DevicePayloadReceiver;
import com.yoox.service.manage.model.enums.DeviceFirmwareStatusEnum;
import com.yoox.service.manage.model.param.DeviceQueryParam;
import com.yoox.service.manage.model.receiver.CapacityCameraReceiver;
import com.yoox.service.manage.service.ICapacityCameraService;
import com.yoox.service.manage.service.IDeviceDictionaryService;
import com.yoox.service.manage.service.IDevicePayloadService;
import com.yoox.service.manage.service.IDeviceRedisService;
import com.yoox.service.manage.service.IDeviceService;
import com.yoox.service.manage.service.IWorkspaceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.MessageHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class SDKDeviceService extends AbstractDeviceService {

    private static final long MISSING_CAPACITY_DEVICE_SN_LOG_INTERVAL_MS = 60_000L;

    private final Map<String, AtomicLong> missingCapacityDeviceSnLogAt = new ConcurrentHashMap<>();

    @Autowired
    private IDeviceRedisService deviceRedisService;

    @Autowired
    private IDeviceService deviceService;

    @Autowired
    private IDeviceDictionaryService dictionaryService;

    @Autowired
    private IWebSocketMessageService webSocketMessageService;

    @Autowired
    private IDevicePayloadService devicePayloadService;

    @Autowired
    private IWorkspaceService workspaceService;

    @Autowired
    private ICapacityCameraService capacityCameraService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PointFlightTaskStore pointFlightTaskStore;

    @Value("${device.auto-registration.enabled:true}")
    private boolean autoRegistrationEnabled;

    @Value("${device.auto-registration.workspace-id:}")
    private String autoRegistrationWorkspaceId;

    @Override
    public void targetDetectResult(TopicStateRequest<TargetDetectResultReport> request, MessageHeaders headers) {
        String gatewaySn = request.getGateway() == null ? request.getFrom() : request.getGateway();
        Optional<DeviceDTO> gateway = deviceRedisService.getDeviceOnline(gatewaySn);
        if (gateway.isEmpty()) {
            log.warn("Ignoring target detection report from offline gateway {}", gatewaySn);
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>(
                objectMapper.convertValue(request.getData(), new TypeReference<Map<String, Object>>() { }));
        data.put("sn", gatewaySn);
        data.put("method", "target_detect_result_report");
        data.put("timestamp", request.getTimestamp());
        webSocketMessageService.sendBatch(
                gateway.get().getWorkspaceId(), UserTypeEnum.WEB.getVal(),
                BizCodeEnum.TARGET_DETECT_RESULT_REPORT.getCode(), data);
    }

    @Override
    public TopicStatusResponse<MqttReply> updateTopoOnline(TopicStatusRequest<UpdateTopo> request, MessageHeaders headers) {
        if (request.getData() == null || request.getData().getSubDevices() == null ||
                request.getData().getSubDevices().isEmpty()) {
            log.warn("Gateway {} reported an online topology without an aircraft.", request.getFrom());
            return new TopicStatusResponse<MqttReply>().setData(MqttReply.error("aircraft topology missing"));
        }
        UpdateTopoSubDevice updateTopoSubDevice = request.getData().getSubDevices().get(0);
        String deviceSn = updateTopoSubDevice.getSn();
        String gatewaySn = request.getFrom();

        Optional<DeviceDTO> registeredGateway = deviceService.getDeviceBySn(gatewaySn);
        Optional<DeviceDTO> registeredAircraft = deviceService.getDeviceBySn(deviceSn);
        Optional<String> workspaceIdOpt = resolveRegistrationWorkspace(registeredGateway, registeredAircraft);
        if (workspaceIdOpt.isEmpty()) {
            log.warn("Unable to auto-register gateway {} and aircraft {}: no valid workspace configured.",
                    gatewaySn, deviceSn);
            return new TopicStatusResponse<MqttReply>().setData(MqttReply.error("auto registration unavailable"));
        }
        String workspaceId = workspaceIdOpt.get();

        Optional<DeviceDTO> deviceOpt = deviceRedisService.getDeviceOnline(deviceSn);
        Optional<DeviceDTO> gatewayOpt = deviceRedisService.getDeviceOnline(gatewaySn);
        GatewayManager gatewayManager = SDKManager.registerDevice(gatewaySn, deviceSn,
                request.getData().getDomain(), request.getData().getType(),
                request.getData().getSubType(), request.getData().getThingVersion(), updateTopoSubDevice.getThingVersion());

        if (deviceOpt.isPresent() && gatewayOpt.isPresent()) {
            ensureDeviceBinding(gatewaySn, workspaceId);
            ensureDeviceBinding(deviceSn, workspaceId);
            // MQTT clean sessions do not retain the runtime topics after a
            // reconnect. Re-apply all gateway and aircraft subscriptions for
            // every online topology notification; subscription is idempotent.
            deviceService.gatewayOnlineSubscribeTopic(gatewayManager);
            deviceService.subDeviceOnlineSubscribeTopic(gatewayManager);
            deviceOnlineAgain(workspaceId, gatewaySn, deviceSn);
            return new TopicStatusResponse<MqttReply>().setData(MqttReply.success());
        }

        changeSubDeviceParent(deviceSn, gatewaySn);

        DeviceDTO gateway = deviceGatewayConvertToDevice(gatewaySn, request.getData());
        bindConnectedDevice(gateway, workspaceId);
        Optional<DeviceDTO> gatewayEntityOpt = onlineSaveDevice(gateway, deviceSn, null);
        if (gatewayEntityOpt.isEmpty()) {
            log.error("Failed to go online, please check the status data or code logic.");
            return null;
        }
        DeviceDTO subDevice = subDeviceConvertToDevice(updateTopoSubDevice);
        bindConnectedDevice(subDevice, workspaceId);
        Optional<DeviceDTO> subDeviceEntityOpt = onlineSaveDevice(subDevice, null, gateway.getDeviceSn());
        if (subDeviceEntityOpt.isEmpty()) {
            log.error("Failed to go online, please check the status data or code logic.");
            return null;
        }
        subDevice = subDeviceEntityOpt.get();
        gateway = gatewayEntityOpt.get();
        dockGoOnline(gateway, subDevice);
        deviceService.gatewayOnlineSubscribeTopic(gatewayManager);

        if (!StringUtils.hasText(subDevice.getWorkspaceId())) {
            return new TopicStatusResponse<MqttReply>().setData(MqttReply.success());
        }

        deviceService.subDeviceOnlineSubscribeTopic(gatewayManager);
        deviceService.pushDeviceOnlineTopo(gateway.getWorkspaceId(), gateway.getDeviceSn(), subDevice.getDeviceSn());

        log.debug("{} online.", subDevice.getDeviceSn());
        return new TopicStatusResponse<MqttReply>().setData(MqttReply.success());
    }

    @Override
    public TopicStatusResponse<MqttReply> updateTopoOffline(TopicStatusRequest<UpdateTopo> request, MessageHeaders headers) {
        Optional<DeviceDTO> registeredGateway = deviceService.getDeviceBySn(request.getFrom());
        Optional<String> workspaceIdOpt = resolveRegistrationWorkspace(registeredGateway, Optional.empty());
        if (workspaceIdOpt.isEmpty()) {
            log.warn("Unable to auto-register gateway {}: no valid workspace configured.", request.getFrom());
            return new TopicStatusResponse<MqttReply>().setData(MqttReply.error("auto registration unavailable"));
        }
        GatewayManager gatewayManager = SDKManager.registerDevice(request.getFrom(), null,
                request.getData().getDomain(), request.getData().getType(),
                request.getData().getSubType(), request.getData().getThingVersion(), null);
        deviceService.gatewayOnlineSubscribeTopic(gatewayManager);
        Optional<DeviceDTO> deviceOpt = deviceRedisService.getDeviceOnline(request.getFrom());
        if (deviceOpt.isEmpty()) {
            DeviceDTO gatewayDevice = deviceGatewayConvertToDevice(request.getFrom(), request.getData());
            bindConnectedDevice(gatewayDevice, workspaceIdOpt.get());
            Optional<DeviceDTO> gatewayDeviceOpt = onlineSaveDevice(gatewayDevice, null, null);
            if (gatewayDeviceOpt.isEmpty()) {
                return null;
            }
            deviceService.pushDeviceOnlineTopo(gatewayDeviceOpt.get().getWorkspaceId(), request.getFrom(), null);
            return new TopicStatusResponse<MqttReply>().setData(MqttReply.success());
        }
        ensureDeviceBinding(request.getFrom(), workspaceIdOpt.get());

        String deviceSn = deviceOpt.get().getChildDeviceSn();
        if (!StringUtils.hasText(deviceSn)) {
            return new TopicStatusResponse<MqttReply>().setData(MqttReply.success());
        }

        deviceService.subDeviceOffline(deviceSn);
        return new TopicStatusResponse<MqttReply>().setData(MqttReply.success());
    }

    @Override
    public void osdDock(TopicOsdRequest<OsdDock> request, MessageHeaders headers) {
        String from = request.getFrom();
        boolean wasOnline = deviceRedisService.checkDeviceOnline(from);
        Optional<DeviceDTO> deviceOpt = deviceRedisService.getDeviceOnline(from);
        if (deviceOpt.isEmpty() || !StringUtils.hasText(deviceOpt.get().getWorkspaceId())) {
            deviceOpt = deviceService.getDeviceBySn(from);
            if (deviceOpt.isEmpty()) {
                log.error("Please restart the drone.");
                return;
            }
        }

        DeviceDTO device = deviceOpt.get();
        if (!StringUtils.hasText(device.getWorkspaceId())) {
            log.error("Please bind the dock first.");
        }
        if (StringUtils.hasText(device.getChildDeviceSn())) {
            deviceService.getDeviceBySn(device.getChildDeviceSn()).ifPresent(device::setChildren);
        }

        markOnlineFromOsd(device, request.getGateway(), wasOnline);
        fillDockOsd(from, request.getData());

        deviceService.pushOsdDataToWeb(device.getWorkspaceId(), BizCodeEnum.DOCK_OSD, from, request.getData());
    }

    @Override
    public void osdDockDrone(TopicOsdRequest<OsdDockDrone> request, MessageHeaders headers) {
        String from = request.getFrom();
        boolean wasOnline = deviceRedisService.checkDeviceOnline(from);
        Optional<DeviceDTO> deviceOpt = deviceRedisService.getDeviceOnline(from);
        if (deviceOpt.isEmpty()) {
            deviceOpt = deviceService.getDeviceBySn(from);
            if (deviceOpt.isEmpty()) {
                log.error("Please restart the drone.");
                return;
            }
        }

        if (!StringUtils.hasText(deviceOpt.get().getWorkspaceId())) {
            log.error("Please restart the drone.");
        }

        DeviceDTO device = deviceOpt.get();
        markOnlineFromOsd(device, request.getGateway(), wasOnline);
        deviceRedisService.setDeviceOsd(from, request.getData());

        finishPointFlightWhenIdle(
                request.getGateway(), request.getData().getModeCode(), device.getWorkspaceId());

        deviceService.pushOsdDataToWeb(device.getWorkspaceId(), BizCodeEnum.DEVICE_OSD, from, request.getData());
    }

    @Override
    public void osdRemoteControl(TopicOsdRequest<OsdRemoteControl> request, MessageHeaders headers) {
        String from = request.getFrom();
        boolean wasOnline = deviceRedisService.checkDeviceOnline(from);
        Optional<DeviceDTO> deviceOpt = deviceRedisService.getDeviceOnline(from);
        if (deviceOpt.isEmpty()) {
            deviceOpt = deviceService.getDeviceBySn(from);
            if (deviceOpt.isEmpty()) {
                log.error("Please restart the drone.");
                return;
            }
        }
        DeviceDTO device = deviceOpt.get();
        if (StringUtils.hasText(device.getChildDeviceSn())) {
            deviceService.getDeviceBySn(device.getChildDeviceSn()).ifPresent(device::setChildren);
        }
        markOnlineFromOsd(device, request.getGateway(), wasOnline);

        OsdRemoteControl data = request.getData();
        if (data.getDeviceList() != null) {
            data.getDeviceList().forEach(capacityDevice -> {
                String capacityDeviceSn = resolveCapacityDeviceSn(device, capacityDevice, from);
                if (!StringUtils.hasText(capacityDeviceSn)) {
                    return;
                }
                List<CapacityCameraReceiver> cameras = objectMapper.convertValue(
                        capacityDevice.getCameraList(),
                        new TypeReference<List<CapacityCameraReceiver>>() {
                        });
                capacityCameraService.saveCapacityCameraReceiverList(cameras, capacityDeviceSn);
            });
        }
        deviceService.pushOsdDataToPilot(device.getWorkspaceId(), from,
                new DeviceOsdHost()
                        .setLatitude(data.getLatitude())
                        .setLongitude(data.getLongitude())
                        .setHeight(data.getHeight()));
        deviceService.pushOsdDataToWeb(device.getWorkspaceId(), BizCodeEnum.RC_OSD, from, data);

    }

    private String resolveCapacityDeviceSn(
            DeviceDTO gateway, RcLiveCapacityDevice capacityDevice, String gatewaySn) {
        if (capacityDevice != null && StringUtils.hasText(capacityDevice.getSn())) {
            return capacityDevice.getSn().trim();
        }
        if (capacityDevice != null && StringUtils.hasText(gateway.getChildDeviceSn())) {
            return gateway.getChildDeviceSn().trim();
        }

        warnMissingCapacityDeviceSn(gatewaySn, capacityDevice == null);
        return null;
    }

    private void warnMissingCapacityDeviceSn(String gatewaySn, boolean nullEntry) {
        long now = System.currentTimeMillis();
        String logKey = StringUtils.hasText(gatewaySn) ? gatewaySn : "unknown";
        AtomicLong logAt = missingCapacityDeviceSnLogAt.computeIfAbsent(logKey, key -> new AtomicLong());
        long previous = logAt.get();
        if (previous != 0 && now - previous < MISSING_CAPACITY_DEVICE_SN_LOG_INTERVAL_MS) {
            return;
        }
        if (!logAt.compareAndSet(previous, now)) {
            return;
        }
        log.warn("Skipping RC live-capacity entry from gateway {}: {} and no bound child device SN is available.",
                gatewaySn, nullEntry ? "entry is null" : "device SN is missing");
    }

    @Override
    public void osdRcDrone(TopicOsdRequest<OsdRcDrone> request, MessageHeaders headers) {
        String from = request.getFrom();
        boolean wasOnline = deviceRedisService.checkDeviceOnline(from);
        Optional<DeviceDTO> deviceOpt = deviceRedisService.getDeviceOnline(from);
        if (deviceOpt.isEmpty()) {
            deviceOpt = deviceService.getDeviceBySn(from);
            if (deviceOpt.isEmpty()) {
                log.error("Please restart the drone.");
                return;
            }
        }
        DeviceDTO device = deviceOpt.get();
        if (!StringUtils.hasText(device.getWorkspaceId())) {
            log.error("Please bind the drone first.");
        }

        markOnlineFromOsd(device, request.getGateway(), wasOnline);

        OsdRcDrone data = request.getData();
        deviceRedisService.setDeviceOsd(from, data);
        finishPointFlightWhenIdle(request.getGateway(), data.getModeCode(), device.getWorkspaceId());
        deviceService.pushOsdDataToPilot(device.getWorkspaceId(), from,
                new DeviceOsdHost()
                        .setLatitude(data.getLatitude())
                        .setLongitude(data.getLongitude())
                        .setElevation(data.getElevation())
                        .setHeight(data.getHeight())
                        .setAttitudeHead(data.getAttitudeHead())
                        .setElevation(data.getElevation())
                        .setHorizontalSpeed(data.getHorizontalSpeed())
                        .setVerticalSpeed(data.getVerticalSpeed()));
        deviceService.pushOsdDataToWeb(device.getWorkspaceId(), BizCodeEnum.DEVICE_OSD, from, data);
    }

    private void finishPointFlightWhenIdle(
            String gatewaySn, DroneModeCodeEnum modeCode, String workspaceId) {
        if (modeCode != DroneModeCodeEnum.IDLE || !StringUtils.hasText(gatewaySn)
                || !StringUtils.hasText(workspaceId)) {
            return;
        }
        pointFlightTaskStore.finishProgressingTaskOnIdle(gatewaySn).ifPresent(state -> {
            String kind = String.valueOf(state.getOrDefault("kind", ""));
            BizCodeEnum bizCode = "takeoff".equals(kind)
                    ? BizCodeEnum.TAKE_OFF_TO_POINT_PROGRESS
                    : BizCodeEnum.FLY_TO_POINT_PROGRESS;
            webSocketMessageService.sendBatch(
                    workspaceId, UserTypeEnum.WEB.getVal(), bizCode.getCode(), state);
            log.info("Finished {} point-flight task for gateway {} because aircraft is idle.",
                    kind, gatewaySn);
        });
    }

    private void markOnlineFromOsd(DeviceDTO device, String gatewaySn, boolean wasOnline) {
        deviceRedisService.setDeviceOnline(device);
        if (wasOnline || !StringUtils.hasText(device.getWorkspaceId())) {
            return;
        }

        boolean isAircraft = DeviceDomainEnum.DRONE == device.getDomain();
        String resolvedGatewaySn = isAircraft ? gatewaySn : device.getDeviceSn();
        String resolvedAircraftSn = isAircraft ? device.getDeviceSn() : device.getChildDeviceSn();
        deviceService.pushDeviceOnlineTopo(
                device.getWorkspaceId(), resolvedGatewaySn, resolvedAircraftSn);
        log.info("Recovered device online state from OSD: gateway={}, device={}",
                resolvedGatewaySn, device.getDeviceSn());
    }

    @Override
    public void dockLiveStatusUpdate(TopicStateRequest<DockLiveStatus> request, MessageHeaders headers) {
        pushLiveStatus(request.getFrom(), request.getData());
    }

    @Override
    public void rcLiveStatusUpdate(TopicStateRequest<RcLiveStatus> request, MessageHeaders headers) {
        pushLiveStatus(request.getFrom(), request.getData());
    }

    private void pushLiveStatus(String gatewaySn, Object status) {
        deviceService.getDeviceBySn(gatewaySn)
                .filter(device -> StringUtils.hasText(device.getWorkspaceId()))
                .ifPresent(device -> deviceService.pushOsdDataToWeb(
                        device.getWorkspaceId(), BizCodeEnum.LIVE_STATUS, gatewaySn, status));
    }

    @Override
    public void dockFirmwareVersionUpdate(TopicStateRequest<DockFirmwareVersion> request, MessageHeaders headers) {
        if (!StringUtils.hasText(request.getData().getFirmwareVersion())) {
            return;
        }
        DeviceDTO device = DeviceDTO.builder()
                .deviceSn(request.getFrom())
                .firmwareVersion(request.getData().getFirmwareVersion())
                .firmwareStatus(request.getData().getNeedCompatibleStatus() ?
                        DeviceFirmwareStatusEnum.UNKNOWN : DeviceFirmwareStatusEnum.CONSISTENT_UPGRADE)
                .build();
        boolean isUpd = deviceService.updateDevice(device);
        if (!isUpd) {
            log.error("Data update of firmware version failed. SN: {}", request.getFrom());
        }
    }

    @Override
    public void rcAndDroneFirmwareVersionUpdate(TopicStateRequest<FirmwareVersion> request, MessageHeaders headers) {
        // If the reported version is empty, it will not be processed to prevent misleading page.
        if (!StringUtils.hasText(request.getData().getFirmwareVersion())) {
            return;
        }

        DeviceDTO device = DeviceDTO.builder()
                .deviceSn(request.getFrom())
                .firmwareVersion(request.getData().getFirmwareVersion())
                .build();
        boolean isUpd = deviceService.updateDevice(device);
        if (!isUpd) {
            log.error("Data update of firmware version failed. SN: {}", request.getFrom());
        }
    }

    @Override
    public void rcPayloadFirmwareVersionUpdate(TopicStateRequest<PayloadFirmwareVersion> request, MessageHeaders headers) {
        // If the reported version is empty, it will not be processed to prevent misleading page.
        if (!StringUtils.hasText(request.getData().getFirmwareVersion())) {
            return;
        }

        boolean isUpd = devicePayloadService.updateFirmwareVersion(request.getFrom(), request.getData());
        if (!isUpd) {
            log.error("Data update of payload firmware version failed. SN: {}", request.getFrom());
        }
    }

    @Override
    public void dockControlSourceUpdate(TopicStateRequest<DockDroneControlSource> request, MessageHeaders headers) {
        // If the control source is empty, it will not be processed.
        if (ControlSourceEnum.UNKNOWN == request.getData().getControlSource()) {
            return;
        }
        Optional<DeviceDTO> deviceOpt = deviceRedisService.getDeviceOnline(request.getFrom());
        if (deviceOpt.isEmpty()) {
            return;
        }
        Optional<DeviceDTO> dockOpt = deviceRedisService.getDeviceOnline(request.getGateway());
        if (dockOpt.isEmpty()) {
            return;
        }

        deviceService.updateFlightControl(dockOpt.get(), request.getData().getControlSource());
        devicePayloadService.updatePayloadControl(deviceOpt.get(),
                request.getData().getPayloads().stream()
                        .map(p -> DevicePayloadReceiver.builder()
                                .controlSource(p.getControlSource())
                                .payloadIndex(p.getPayloadIndex())
                                .sn(p.getSn())
                                .deviceSn(request.getFrom())
                                .build()).collect(Collectors.toList()));
    }

    @Override
    public void rcControlSourceUpdate(TopicStateRequest<RcDroneControlSource> request, MessageHeaders headers) {
        // If the control source is empty, it will not be processed.
        if (ControlSourceEnum.UNKNOWN == request.getData().getControlSource()) {
            return;
        }
        Optional<DeviceDTO> deviceOpt = deviceRedisService.getDeviceOnline(request.getFrom());
        if (deviceOpt.isEmpty()) {
            return;
        }
        Optional<DeviceDTO> dockOpt = deviceRedisService.getDeviceOnline(request.getGateway());
        if (dockOpt.isEmpty()) {
            return;
        }

        deviceService.updateFlightControl(dockOpt.get(), request.getData().getControlSource());
        devicePayloadService.updatePayloadControl(deviceOpt.get(),
                request.getData().getPayloads().stream()
                        .map(p -> DevicePayloadReceiver.builder()
                                .controlSource(p.getControlSource())
                                .payloadIndex(p.getPayloadIndex())
                                .sn(p.getSn())
                                .deviceSn(request.getFrom())
                                .build()).collect(Collectors.toList()));
    }

    private void dockGoOnline(DeviceDTO gateway, DeviceDTO subDevice) {
        if (DeviceDomainEnum.DOCK != gateway.getDomain()) {
            return;
        }
        if (!StringUtils.hasText(gateway.getWorkspaceId())) {
            log.error("The dock is not bound, please bind it first and then go online.");
            return;
        }
        if (!Objects.requireNonNullElse(subDevice.getBoundStatus(), false)) {
            // Directly bind the drone of the dock to the same workspace as the dock.
            deviceService.bindDevice(DeviceDTO.builder().deviceSn(subDevice.getDeviceSn()).workspaceId(gateway.getWorkspaceId()).build());
            subDevice.setWorkspaceId(gateway.getWorkspaceId());
        }
        deviceRedisService.setDeviceOnline(subDevice);
    }

    private void changeSubDeviceParent(String deviceSn, String gatewaySn) {
        List<DeviceDTO> gatewaysList = deviceService.getDevicesByParams(
                DeviceQueryParam.builder()
                        .childSn(deviceSn)
                        .build());
        gatewaysList.stream()
                .filter(gateway -> !gateway.getDeviceSn().equals(gatewaySn))
                .forEach(gateway -> {
                    gateway.setChildDeviceSn("");
                    deviceService.updateDevice(gateway);
                    deviceRedisService.getDeviceOnline(gateway.getDeviceSn())
                            .ifPresent(device -> {
                                device.setChildDeviceSn(null);
                                deviceRedisService.setDeviceOnline(device);
                            });
                });
    }


    public void deviceOnlineAgain(String workspaceId, String gatewaySn, String deviceSn) {
        DeviceDTO device = DeviceDTO.builder().loginTime(LocalDateTime.now()).deviceSn(deviceSn).build();
        DeviceDTO gateway = DeviceDTO.builder()
                .loginTime(LocalDateTime.now())
                .deviceSn(gatewaySn)
                .childDeviceSn(deviceSn).build();
        deviceService.updateDevice(gateway);
        deviceService.updateDevice(device);
        gateway = deviceRedisService.getDeviceOnline(gatewaySn).map(g -> {
            g.setChildDeviceSn(deviceSn);
            return g;
        }).get();
        device = deviceRedisService.getDeviceOnline(deviceSn).map(d -> {
            d.setParentSn(gatewaySn);
            return d;
        }).get();
        deviceRedisService.setDeviceOnline(gateway);
        deviceRedisService.setDeviceOnline(device);
        if (StringUtils.hasText(workspaceId)) {
            deviceService.subDeviceOnlineSubscribeTopic(SDKManager.getDeviceSDK(gatewaySn));
        }

        log.warn("{} is already online.", deviceSn);
    }

    /**
     * Convert the received gateway device object into a database entity object.
     *
     * @param gateway
     * @return
     */
    private DeviceDTO deviceGatewayConvertToDevice(String gatewaySn, UpdateTopo gateway) {
        if (null == gateway) {
            throw new IllegalArgumentException();
        }
        return DeviceDTO.builder()
                .deviceSn(gatewaySn)
                .subType(gateway.getSubType())
                .type(gateway.getType())
                .thingVersion(gateway.getThingVersion())
                .domain(gateway.getDomain())
                .controlSource(gateway.getSubDevices().isEmpty() ? null :
                        ControlSourceEnum.find(gateway.getSubDevices().get(0).getIndex().getControlSource()))
                .build();
    }

    /**
     * Convert the received drone device object into a database entity object.
     *
     * @param device
     * @return
     */
    private DeviceDTO subDeviceConvertToDevice(UpdateTopoSubDevice device) {
        if (null == device) {
            throw new IllegalArgumentException();
        }
        return DeviceDTO.builder()
                .deviceSn(device.getSn())
                .type(device.getType())
                .subType(device.getSubType())
                .thingVersion(device.getThingVersion())
                .domain(device.getDomain())
                .build();
    }

    private Optional<DeviceDTO> onlineSaveDevice(DeviceDTO device, String childSn, String parentSn) {

        device.setChildDeviceSn(childSn);
        device.setLoginTime(LocalDateTime.now());

        Optional<DeviceDTO> deviceOpt = deviceService.getDeviceBySn(device.getDeviceSn());

        if (deviceOpt.isEmpty()) {
            device.setIconUrl(new DeviceIconUrl());
            // Set the icon of the gateway device displayed in the pilot's map, required in the TSA module.
            device.getIconUrl().setNormalIconUrl(IconUrlEnum.NORMAL_PERSON.getUrl());
            // Set the icon of the gateway device displayed in the pilot's map when it is selected, required in the TSA module.
            device.getIconUrl().setSelectIconUrl(IconUrlEnum.SELECT_PERSON.getUrl());
            if (device.getBoundStatus() == null) {
                device.setBoundStatus(false);
            }

            // Query the model information of this gateway device.
            dictionaryService.getOneDictionaryInfoByTypeSubType(
                            device.getDomain().getDomain(), device.getType().getType(), device.getSubType().getSubType())
                    .ifPresent(entity -> {
                        device.setDeviceName(entity.getDeviceName());
                        device.setNickname(entity.getDeviceName());
                        device.setDeviceDesc(entity.getDeviceDesc());
                    });
        }
        boolean success = deviceService.saveOrUpdateDevice(device);
        if (!success) {
            return Optional.empty();
        }

        deviceOpt = deviceService.getDeviceBySn(device.getDeviceSn());
        DeviceDTO redisDevice = deviceOpt.get();
        redisDevice.setStatus(true);
        redisDevice.setParentSn(parentSn);

        deviceRedisService.setDeviceOnline(redisDevice);
        return deviceOpt;
    }

    /**
     * Resolve the workspace without silently moving an already-bound device.
     * A bound gateway/aircraft wins; otherwise first-connect registration uses
     * the explicitly configured workspace.
     */
    private Optional<String> resolveRegistrationWorkspace(Optional<DeviceDTO> gateway,
                                                          Optional<DeviceDTO> aircraft) {
        Optional<String> gatewayWorkspace = boundWorkspace(gateway);
        Optional<String> aircraftWorkspace = boundWorkspace(aircraft);
        if (gatewayWorkspace.isPresent() && aircraftWorkspace.isPresent() &&
                !gatewayWorkspace.get().equals(aircraftWorkspace.get())) {
            log.warn("Gateway and aircraft are already bound to different workspaces.");
            return Optional.empty();
        }
        if (gatewayWorkspace.isPresent()) {
            return gatewayWorkspace;
        }
        if (aircraftWorkspace.isPresent()) {
            return aircraftWorkspace;
        }
        if (!autoRegistrationEnabled || !StringUtils.hasText(autoRegistrationWorkspaceId)) {
            return Optional.empty();
        }
        return workspaceService.getWorkspaceByWorkspaceId(autoRegistrationWorkspaceId)
                .map(workspace -> workspace.getWorkspaceId());
    }

    private Optional<String> boundWorkspace(Optional<DeviceDTO> device) {
        return device
                .filter(value -> Boolean.TRUE.equals(value.getBoundStatus()))
                .map(DeviceDTO::getWorkspaceId)
                .filter(StringUtils::hasText);
    }

    private void bindConnectedDevice(DeviceDTO device, String workspaceId) {
        device.setWorkspaceId(workspaceId);
        device.setBoundStatus(true);
        device.setBoundTime(LocalDateTime.now());
    }

    private void ensureDeviceBinding(String deviceSn, String workspaceId) {
        Optional<DeviceDTO> deviceOpt = deviceService.getDeviceBySn(deviceSn);
        if (deviceOpt.isPresent() && Boolean.TRUE.equals(deviceOpt.get().getBoundStatus()) &&
                workspaceId.equals(deviceOpt.get().getWorkspaceId())) {
            return;
        }
        DeviceDTO binding = DeviceDTO.builder()
                .deviceSn(deviceSn)
                .workspaceId(workspaceId)
                .boundStatus(true)
                .boundTime(LocalDateTime.now())
                .build();
        deviceService.saveOrUpdateDevice(binding);
    }

    private void fillDockOsd(String dockSn, OsdDock dock) {
        Optional<OsdDock> oldDockOpt = deviceRedisService.getDeviceOsd(dockSn, OsdDock.class);
        if (Objects.nonNull(dock.getJobNumber())) {
            return;
        }
        if (oldDockOpt.isEmpty()) {
            deviceRedisService.setDeviceOsd(dockSn, dock);
            return;
        }
        OsdDock oldDock = oldDockOpt.get();
        if (Objects.nonNull(dock.getModeCode())) {
            dock.setDrcState(oldDock.getDrcState());
            deviceRedisService.setDeviceOsd(dockSn, dock);
            return;
        }
        if (Objects.nonNull(dock.getDrcState())) {
            oldDock.setDrcState(dock.getDrcState());
            deviceRedisService.setDeviceOsd(dockSn, oldDock);
        }
    }
}
