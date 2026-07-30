package com.yoox.service.control.model.dto;

import com.yoox.great.mqtt.enums.device.SwitchActionEnum;
import com.yoox.service.control.service.impl.RemoteDebugHandler;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class AlarmState extends RemoteDebugHandler {

    private SwitchActionEnum action;

}
