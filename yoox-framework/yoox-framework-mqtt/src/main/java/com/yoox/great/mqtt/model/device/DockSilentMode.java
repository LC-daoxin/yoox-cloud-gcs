package com.yoox.great.mqtt.model.device;

import com.yoox.great.context.base.BaseModel;
import com.yoox.great.mqtt.enums.property.SilentModeEnum;
import com.fasterxml.jackson.annotation.JsonProperty;

import javax.validation.constraints.NotNull;

public class DockSilentMode extends BaseModel {

    @NotNull
    @JsonProperty("silent_mode")
    private SilentModeEnum silentMode;

    public DockSilentMode() {
    }

    @Override
    public String toString() {
        return "DockSilentMode{" +
                "silentMode=" + silentMode +
                '}';
    }

    public SilentModeEnum getSilentMode() {
        return silentMode;
    }

    public DockSilentMode setSilentMode(SilentModeEnum silentMode) {
        this.silentMode = silentMode;
        return this;
    }
}
