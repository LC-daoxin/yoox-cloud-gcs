package com.yoox.service.control.model.dto;

import com.yoox.great.mqtt.enums.device.AirConditionerStateEnum;
import com.yoox.service.control.service.impl.RemoteDebugHandler;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class AirConditionerMode extends RemoteDebugHandler {

    private AirConditionerStateEnum action;
}
