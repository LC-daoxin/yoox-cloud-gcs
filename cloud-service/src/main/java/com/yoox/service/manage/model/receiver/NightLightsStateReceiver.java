package com.yoox.service.manage.model.receiver;

import com.yoox.great.mqtt.enums.device.SwitchActionEnum;
import com.yoox.great.mqtt.model.device.OsdDockDrone;
import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Objects;

public class NightLightsStateReceiver extends BasicDeviceProperty {

    private SwitchActionEnum nightLightsState;

    @JsonCreator
    public NightLightsStateReceiver(Integer nightLightsState) {
        this.nightLightsState = SwitchActionEnum.find(nightLightsState);
    }

    @Override
    public boolean valid() {
        return Objects.nonNull(nightLightsState);
    }

    @Override
    public boolean canPublish(OsdDockDrone osd) {
        return nightLightsState != osd.getNightLightsState();
    }
}
