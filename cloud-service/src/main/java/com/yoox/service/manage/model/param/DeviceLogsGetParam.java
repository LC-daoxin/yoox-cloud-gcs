package com.yoox.service.manage.model.param;

import com.yoox.great.mqtt.enums.log.LogModuleEnum;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
@Data
public class DeviceLogsGetParam {

    @JsonProperty("domain_list")
    List<LogModuleEnum> domainList;
}
