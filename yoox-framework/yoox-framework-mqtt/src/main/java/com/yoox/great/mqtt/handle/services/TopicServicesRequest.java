package com.yoox.great.mqtt.handle.services;


import com.yoox.great.mqtt.core.CommonTopicRequest;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public class TopicServicesRequest<T> extends CommonTopicRequest<T> {

    private String method;

    @JsonProperty("device_list")
    private List<Map<String, String>> deviceList;

    public TopicServicesRequest() {
    }

    @Override
    public String toString() {
        return "TopicServicesRequest{" +
                "method='" + method + '\'' +
                ", tid='" + tid + '\'' +
                ", bid='" + bid + '\'' +
                ", timestamp=" + timestamp +
                ", deviceList=" + deviceList +
                ", data=" + data +
                '}';
    }

    public String getMethod() {
        return method;
    }

    public TopicServicesRequest<T> setMethod(String method) {
        this.method = method;
        return this;
    }

    public List<Map<String, String>> getDeviceList() {
        return deviceList;
    }

    public TopicServicesRequest<T> setDeviceList(List<Map<String, String>> deviceList) {
        this.deviceList = deviceList;
        return this;
    }

    public String getTid() {
        return tid;
    }

    public TopicServicesRequest<T> setTid(String tid) {
        this.tid = tid;
        return this;
    }

    public String getBid() {
        return bid;
    }

    public TopicServicesRequest<T> setBid(String bid) {
        this.bid = bid;
        return this;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public TopicServicesRequest<T> setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
        return this;
    }

    // 道通上云 API 要求无参数的 services 指令（如 return_home）发送 "data": null。
    // 全局 ObjectMapper 配置了 NON_ABSENT 会省略 null 字段，此处强制始终序列化。
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public T getData() {
        return data;
    }

    public TopicServicesRequest<T> setData(T data) {
        this.data = data;
        return this;
    }

}
