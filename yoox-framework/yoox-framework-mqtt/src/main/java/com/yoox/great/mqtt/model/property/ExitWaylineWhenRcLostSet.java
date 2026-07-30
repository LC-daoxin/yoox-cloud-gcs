package com.yoox.great.mqtt.model.property;

import com.yoox.great.context.base.BaseModel;
import com.yoox.great.mqtt.enums.device.ExitWaylineWhenRcLostEnum;
import com.fasterxml.jackson.annotation.JsonProperty;

import javax.validation.constraints.NotNull;

public class ExitWaylineWhenRcLostSet extends BaseModel {

    @NotNull
    @JsonProperty("exit_wayline_when_rc_lost")
    private ExitWaylineWhenRcLostEnum exitWaylineWhenRcLost;

    public ExitWaylineWhenRcLostSet() {
    }

    @Override
    public String toString() {
        return "ExitWaylineWhenRcLostSet{" +
                "exitWaylineWhenRcLost=" + exitWaylineWhenRcLost +
                '}';
    }

    public ExitWaylineWhenRcLostEnum getExitWaylineWhenRcLost() {
        return exitWaylineWhenRcLost;
    }

    public ExitWaylineWhenRcLostSet setExitWaylineWhenRcLost(ExitWaylineWhenRcLostEnum exitWaylineWhenRcLost) {
        this.exitWaylineWhenRcLost = exitWaylineWhenRcLost;
        return this;
    }
}
