package com.yoox.great.mqtt.handle.drc;


import com.yoox.great.mqtt.enums.wayline.WaylineErrorCodeEnum;

public class DrcUpData<T> {

    private WaylineErrorCodeEnum result;

    private T output;

    public DrcUpData() {
    }

    @Override
    public String toString() {
        return "DrcUpData{" +
                "result=" + result +
                ", output=" + output +
                '}';
    }

    public WaylineErrorCodeEnum getResult() {
        return result;
    }

    public DrcUpData<T> setResult(WaylineErrorCodeEnum result) {
        this.result = result;
        return this;
    }

    public T getOutput() {
        return output;
    }

    public DrcUpData<T> setOutput(T output) {
        this.output = output;
        return this;
    }
}