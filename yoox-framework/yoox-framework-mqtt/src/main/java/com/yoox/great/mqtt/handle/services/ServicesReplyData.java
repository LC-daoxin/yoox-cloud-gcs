package com.yoox.great.mqtt.handle.services;

public class ServicesReplyData<T> {

    private ServicesErrorCode result;

    private T output;

    /** 设备返回的附加信息，如 RTSP 模式下设备返回的 RTSP 拉流地址 */
    private T info;

    public ServicesReplyData() {
    }

    @Override
    public String toString() {
        return "DrcUpData{" +
                "result=" + result +
                ", output=" + output +
                ", info=" + info +
                '}';
    }

    public ServicesErrorCode getResult() {
        return result;
    }

    public ServicesReplyData<T> setResult(ServicesErrorCode result) {
        this.result = result;
        return this;
    }

    public T getOutput() {
        return output;
    }

    public ServicesReplyData<T> setOutput(T output) {
        this.output = output;
        return this;
    }

    public T getInfo() {
        return info;
    }

    public ServicesReplyData<T> setInfo(T info) {
        this.info = info;
        return this;
    }
}