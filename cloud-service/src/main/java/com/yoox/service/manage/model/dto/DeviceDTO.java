package com.yoox.service.manage.model.dto;

import com.yoox.great.context.enums.device.DeviceDomainEnum;
import com.yoox.great.context.enums.device.DeviceSubTypeEnum;
import com.yoox.great.context.enums.device.DeviceTypeEnum;
import com.yoox.great.mqtt.enums.control.ControlSourceEnum;
import com.yoox.great.mqtt.model.tsa.DeviceIconUrl;
import com.yoox.service.manage.model.enums.DeviceFirmwareStatusEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class DeviceDTO {

    private String deviceSn;

    private String deviceName;

    private String workspaceId;

    private ControlSourceEnum controlSource;

    private String deviceDesc;

    private String childDeviceSn;

    /**
     * Explicit topology fields for web/API consumers. In the device list,
     * deviceSn identifies the gateway (remote controller or dock), so callers
     * should not have to guess whether it is an aircraft serial number.
     */
    private String aircraftSn;

    private String remoteControllerSn;

    private DeviceDomainEnum domain;

    private DeviceTypeEnum type;

    private DeviceSubTypeEnum subType;

    private List<DevicePayloadDTO> payloadsList;

    private DeviceIconUrl iconUrl;

    private Boolean status;

    private Boolean boundStatus;

    private LocalDateTime loginTime;

    private LocalDateTime boundTime;

    private String nickname;

    private String userId;

    private String firmwareVersion;

    private String workspaceName;

    private DeviceDTO children;

    private DeviceFirmwareStatusEnum firmwareStatus;

    private Integer firmwareProgress;

    private String parentSn;

    private String thingVersion;
}
