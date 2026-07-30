package com.yoox.great.mqtt.model.property;

import com.yoox.great.context.base.BaseModel;
import com.yoox.great.mqtt.enums.device.SwitchActionEnum;
import com.fasterxml.jackson.annotation.JsonProperty;

import javax.validation.constraints.NotNull;

public class NightLightsStateSet extends BaseModel {

    @NotNull
    @JsonProperty("night_lights_state")
    private SwitchActionEnum nightLightsState;

    public NightLightsStateSet() {
    }

    @Override
    public String toString() {
        return "NightLightsStateSet{" +
                "nightLightsState=" + nightLightsState +
                '}';
    }

    public SwitchActionEnum getNightLightsState() {
        return nightLightsState;
    }

    public NightLightsStateSet setNightLightsState(SwitchActionEnum nightLightsState) {
        this.nightLightsState = nightLightsState;
        return this;
    }
}
