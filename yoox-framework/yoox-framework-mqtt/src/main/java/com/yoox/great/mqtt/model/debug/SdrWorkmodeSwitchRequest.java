package com.yoox.great.mqtt.model.debug;

import com.yoox.great.context.base.BaseModel;
import com.yoox.great.mqtt.enums.device.LinkWorkModeEnum;

import javax.validation.constraints.NotNull;

public class SdrWorkmodeSwitchRequest extends BaseModel {

    @NotNull
    private LinkWorkModeEnum linkWorkmode;

    public SdrWorkmodeSwitchRequest() {
    }

    @Override
    public String toString() {
        return "SdrWorkmodeSwitchRequest{" +
                "linkWorkmode=" + linkWorkmode +
                '}';
    }

    public LinkWorkModeEnum getLinkWorkmode() {
        return linkWorkmode;
    }

    public SdrWorkmodeSwitchRequest setLinkWorkmode(LinkWorkModeEnum linkWorkmode) {
        this.linkWorkmode = linkWorkmode;
        return this;
    }
}
