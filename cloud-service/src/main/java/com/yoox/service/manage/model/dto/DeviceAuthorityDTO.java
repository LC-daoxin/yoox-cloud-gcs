package com.yoox.service.manage.model.dto;

import com.yoox.great.mqtt.enums.control.ControlSourceEnum;
import com.yoox.service.control.model.enums.DroneAuthorityEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DeviceAuthorityDTO {

    private String sn;

    private DroneAuthorityEnum type;

    private ControlSourceEnum controlSource;

}
