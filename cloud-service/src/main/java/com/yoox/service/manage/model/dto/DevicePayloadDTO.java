package com.yoox.service.manage.model.dto;

import com.yoox.great.mqtt.enums.control.ControlSourceEnum;
import com.yoox.great.mqtt.model.device.PayloadIndex;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DevicePayloadDTO {

    private String payloadSn;

    private String payloadName;

    private Integer index;

    private String payloadDesc;

    private ControlSourceEnum controlSource;

    private PayloadIndex payloadIndex;
}