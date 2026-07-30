package com.yoox.great.mqtt.model.control;


import com.yoox.great.mqtt.enums.control.DrcStatusErrorEnum;
import com.yoox.great.mqtt.enums.device.DrcStateEnum;

public class DrcStatusNotify {

    private DrcStatusErrorEnum result;

    private DrcStateEnum drcState;

    public DrcStatusNotify() {
    }

    @Override
    public String toString() {
        return "DrcStatusNotify{" +
                "result=" + result +
                ", drcState=" + drcState +
                '}';
    }

    public DrcStatusErrorEnum getResult() {
        return result;
    }

    public DrcStatusNotify setResult(DrcStatusErrorEnum result) {
        this.result = result;
        return this;
    }

    public DrcStateEnum getDrcState() {
        return drcState;
    }

    public DrcStatusNotify setDrcState(DrcStateEnum drcState) {
        this.drcState = drcState;
        return this;
    }
}
