package com.yoox.service.manage.service.impl;

import com.yoox.api.organization.AbstractOrganizationService;
import com.yoox.great.context.enums.device.DeviceDomainEnum;
import com.yoox.great.context.enums.device.DeviceEnum;
import com.yoox.great.context.error.CommonErrorEnum;
import com.yoox.great.mqtt.core.consume.MqttReply;
import com.yoox.great.mqtt.enums.tsa.IconUrlEnum;
import com.yoox.great.mqtt.model.organization.*;
import com.yoox.great.mqtt.model.tsa.DeviceIconUrl;
import com.yoox.great.mqtt.handle.requests.TopicRequestsRequest;
import com.yoox.great.mqtt.handle.requests.TopicRequestsResponse;
import com.yoox.service.manage.model.dto.DeviceDTO;
import com.yoox.service.manage.model.dto.DeviceDictionaryDTO;
import com.yoox.service.manage.model.dto.WorkspaceDTO;
import com.yoox.service.manage.service.IDeviceDictionaryService;
import com.yoox.service.manage.service.IDeviceService;
import com.yoox.service.manage.service.IWorkspaceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.MessageHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
@Slf4j
public class SDKOrganizationService extends AbstractOrganizationService {

    @Autowired
    private IDeviceService deviceService;

    @Autowired
    private IDeviceDictionaryService dictionaryService;

    @Autowired
    private IWorkspaceService workspaceService;

    @Override
    public TopicRequestsResponse<MqttReply<AirportBindStatusResponse>> airportBindStatus(TopicRequestsRequest<AirportBindStatusRequest> request, MessageHeaders headers) {
        List<BindStatusResponseDevice> data = request.getData().getDevices();

        List<BindStatusRequestDevice> bindStatusResult = new ArrayList<>();
        for (BindStatusResponseDevice bindStatus : data) {
            Optional<DeviceDTO> deviceOpt = deviceService.getDeviceBySn(bindStatus.getSn());
            bindStatusResult.add(deviceOpt.isPresent() ? dto2BindStatus(deviceOpt.get()) :
                    new BindStatusRequestDevice().setSn(bindStatus.getSn()).setDeviceBindOrganization(false));
        }
        return new TopicRequestsResponse<MqttReply<AirportBindStatusResponse>>()
                .setData(MqttReply.success(new AirportBindStatusResponse().setBindStatus(bindStatusResult)));
    }

    @Override
    public TopicRequestsResponse<MqttReply<AirportOrganizationGetResponse>> airportOrganizationGet(TopicRequestsRequest<AirportOrganizationGetRequest> request, MessageHeaders headers) {
        AirportOrganizationGetRequest organizationGet = request.getData();
        if (organizationGet == null || !StringUtils.hasText(organizationGet.getDeviceBindingCode())) {
            return new TopicRequestsResponse().setData(MqttReply.error(CommonErrorEnum.ILLEGAL_ARGUMENT));
        }

        Optional<WorkspaceDTO> workspace = workspaceService.getWorkspaceNameByBindCode(organizationGet.getDeviceBindingCode());
        if (workspace.isEmpty()) {
            return new TopicRequestsResponse().setData(MqttReply.error(CommonErrorEnum.GET_ORGANIZATION_FAILED));
        }

        return new TopicRequestsResponse<MqttReply<AirportOrganizationGetResponse>>()
                .setData(MqttReply.success(new AirportOrganizationGetResponse()
                        .setOrganizationName(workspace.get().getWorkspaceName())));
    }

    @Override
    public TopicRequestsResponse<MqttReply<AirportOrganizationBindResponse>> airportOrganizationBind(TopicRequestsRequest<AirportOrganizationBindRequest> request, MessageHeaders headers) {
        if (request.getData() == null || request.getData().getBindDevices() == null ||
                request.getData().getBindDevices().isEmpty()) {
            return new TopicRequestsResponse<MqttReply<AirportOrganizationBindResponse>>()
                    .setData(MqttReply.error(CommonErrorEnum.ILLEGAL_ARGUMENT));
        }

        List<OrganizationBindDevice> devices = request.getData().getBindDevices();
        OrganizationBindDevice gateway = null;
        OrganizationBindDevice drone = null;
        for (OrganizationBindDevice device : devices) {
            if (device == null || device.getDeviceModelKey() == null) {
                continue;
            }
            DeviceDomainEnum val = device.getDeviceModelKey().getDomain();
            if (Objects.equals(device.getSn(), request.getGateway()) ||
                    val == DeviceDomainEnum.DOCK || val == DeviceDomainEnum.REMOTER_CONTROL) {
                gateway = device;
            }
            if (val == DeviceDomainEnum.DRONE) {
                drone = device;
            }
        }

        Optional<DeviceDTO> gatewayOpt = bindDevice2Dto(gateway);
        Optional<DeviceDTO> droneOpt = bindDevice2Dto(drone);
        if (gatewayOpt.isEmpty()) {
            log.warn("Organization binding from {} did not contain a gateway device.", request.getGateway());
            return new TopicRequestsResponse<MqttReply<AirportOrganizationBindResponse>>()
                    .setData(MqttReply.error(CommonErrorEnum.ILLEGAL_ARGUMENT));
        }
        List<OrganizationBindInfo> bindResult = new ArrayList<>();

        droneOpt.ifPresent(droneDto -> {
            gatewayOpt.get().setChildDeviceSn(droneDto.getDeviceSn());
            boolean success = deviceService.saveOrUpdateDevice(droneDto);
            bindResult.add(success ?
                    OrganizationBindInfo.success(droneDto.getDeviceSn()) :
                    new OrganizationBindInfo(droneDto.getDeviceSn(),
                            CommonErrorEnum.DEVICE_BINDING_FAILED.getCode())
            );
        });
        boolean success = deviceService.saveOrUpdateDevice(gatewayOpt.get());

        bindResult.add(success ?
                OrganizationBindInfo.success(gatewayOpt.get().getDeviceSn()) :
                new OrganizationBindInfo(gatewayOpt.get().getDeviceSn(),
                        CommonErrorEnum.DEVICE_BINDING_FAILED.getCode()));

        return new TopicRequestsResponse<MqttReply<AirportOrganizationBindResponse>>()
                .setData(MqttReply.success(new AirportOrganizationBindResponse().setErrInfos(bindResult)));
    }

    /**
     * Convert device binding data object into database entity object.
     *
     * @param receiver
     * @return
     */
    public Optional<DeviceDTO> bindDevice2Dto(OrganizationBindDevice receiver) {
        if (receiver == null || receiver.getDeviceModelKey() == null ||
                !StringUtils.hasText(receiver.getSn())) {
            return Optional.empty();
        }

        DeviceEnum deviceModelKey = receiver.getDeviceModelKey();
        Optional<DeviceDictionaryDTO> dictionaryOpt = dictionaryService.getOneDictionaryInfoByTypeSubType(
                deviceModelKey.getDomain().getDomain(), deviceModelKey.getType().getType(),
                deviceModelKey.getSubType().getSubType());
        DeviceDTO.DeviceDTOBuilder builder = DeviceDTO.builder();

        dictionaryOpt.ifPresent(entity ->
                builder.deviceName(entity.getDeviceName())
                        .nickname(entity.getDeviceName())
                        .deviceDesc(entity.getDeviceDesc()));
        Optional<WorkspaceDTO> workspace = workspaceService.getWorkspaceNameByBindCode(receiver.getDeviceBindingCode());

        DeviceDTO dto = builder
                .workspaceId(workspace.map(WorkspaceDTO::getWorkspaceId).orElse(receiver.getOrganizationId()))
                .domain(deviceModelKey.getDomain())
                .type(deviceModelKey.getType())
                .subType(deviceModelKey.getSubType())
                .deviceSn(receiver.getSn())
                .boundStatus(true)
                .loginTime(LocalDateTime.now())
                .boundTime(LocalDateTime.now())
                .iconUrl(new DeviceIconUrl()
                        .setSelectIconUrl(IconUrlEnum.SELECT_EQUIPMENT.getUrl())
                        .setNormalIconUrl(IconUrlEnum.NORMAL_EQUIPMENT.getUrl()))
                .build();
        if (StringUtils.hasText(receiver.getDeviceCallsign())) {
            dto.setNickname(receiver.getDeviceCallsign());
        } else {
            Optional<DeviceDTO> deviceOpt = deviceService.getDeviceBySn(receiver.getSn());
            dto.setNickname(deviceOpt.map(DeviceDTO::getNickname).orElse(dto.getNickname()));
        }
        return Optional.of(dto);
    }

    /**
     * Convert device data transfer object into device binding status data object.
     *
     * @param device
     * @return
     */
    private BindStatusRequestDevice dto2BindStatus(DeviceDTO device) {
        if (device == null) {
            return null;
        }
        return new BindStatusRequestDevice()
                .setSn(device.getDeviceSn())
                .setDeviceCallsign(device.getNickname())
                .setDeviceBindOrganization(device.getBoundStatus())
                .setOrganizationId(device.getWorkspaceId())
                .setOrganizationName(device.getWorkspaceName());
    }
}
