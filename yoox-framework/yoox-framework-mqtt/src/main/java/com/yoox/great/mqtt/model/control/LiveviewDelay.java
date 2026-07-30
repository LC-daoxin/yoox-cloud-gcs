package com.yoox.great.mqtt.model.control;


import com.yoox.great.mqtt.model.device.VideoId;

public class LiveviewDelay {

    private Integer liveviewDelayTime;

    private VideoId videoId;

    public LiveviewDelay() {
    }

    @Override
    public String toString() {
        return "LiveviewDelay{" +
                "liveviewDelayTime=" + liveviewDelayTime +
                ", videoId=" + videoId +
                '}';
    }

    public Integer getLiveviewDelayTime() {
        return liveviewDelayTime;
    }

    public LiveviewDelay setLiveviewDelayTime(Integer liveviewDelayTime) {
        this.liveviewDelayTime = liveviewDelayTime;
        return this;
    }
}
