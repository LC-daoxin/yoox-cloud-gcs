package com.yoox.great.mqtt.model.property;

import com.yoox.great.context.base.BaseModel;
import com.yoox.great.mqtt.enums.device.RcLostActionEnum;
import com.fasterxml.jackson.annotation.JsonProperty;

import javax.validation.constraints.NotNull;

public class RcLostActionSet extends BaseModel {

    @NotNull
    @JsonProperty("rc_lost_action")
    private RcLostActionEnum rcLostAction;

    public RcLostActionSet() {
    }

    @Override
    public String toString() {
        return "RcLostActionSet{" +
                "rcLostAction=" + rcLostAction +
                '}';
    }

    public RcLostActionEnum getRcLostAction() {
        return rcLostAction;
    }

    public RcLostActionSet setRcLostAction(RcLostActionEnum rcLostAction) {
        this.rcLostAction = rcLostAction;
        return this;
    }
}
