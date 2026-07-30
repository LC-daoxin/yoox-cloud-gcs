package com.yoox.great.mqtt.model.livestream;

import com.yoox.great.context.base.BaseModel;
import com.yoox.great.mqtt.enums.livestream.VideoQualityEnum;
import com.yoox.great.mqtt.model.device.VideoId;

import javax.validation.constraints.NotNull;

public class LiveSetQualityRequest extends BaseModel {

    @NotNull
    private VideoId videoId;

    @NotNull
    private VideoQualityEnum videoQuality;

    public LiveSetQualityRequest() {
    }

    @Override
    public String toString() {
        return "LiveSetQualityRequest{" +
                "videoId=" + videoId +
                ", videoQuality=" + videoQuality +
                '}';
    }

    public VideoId getVideoId() {
        return videoId;
    }

    public LiveSetQualityRequest setVideoId(VideoId videoId) {
        this.videoId = videoId;
        return this;
    }

    public VideoQualityEnum getVideoQuality() {
        return videoQuality;
    }

    public LiveSetQualityRequest setVideoQuality(VideoQualityEnum videoQuality) {
        this.videoQuality = videoQuality;
        return this;
    }
}
