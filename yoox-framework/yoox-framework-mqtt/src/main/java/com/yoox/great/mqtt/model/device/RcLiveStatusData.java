package com.yoox.great.mqtt.model.device;


import com.yoox.great.mqtt.enums.livestream.VideoQualityEnum;
import com.yoox.great.mqtt.enums.livestream.VideoTypeEnum;

public class RcLiveStatusData {

    private Boolean status;

    private VideoId videoId;

    private VideoQualityEnum videoQuality;

    private VideoTypeEnum videoType;

    private DockLiveErrorStatus errorStatus;

    public RcLiveStatusData() {
    }

    @Override
    public String toString() {
        return "RcLiveStatusData{" +
                "status=" + status +
                ", videoId=" + videoId +
                ", videoQuality=" + videoQuality +
                ", videoType=" + videoType +
                ", errorStatus=" + errorStatus +
                '}';
    }

    public Boolean getStatus() {
        return status;
    }

    public RcLiveStatusData setStatus(Boolean status) {
        this.status = status;
        return this;
    }

    public VideoId getVideoId() {
        return videoId;
    }

    public RcLiveStatusData setVideoId(VideoId videoId) {
        this.videoId = videoId;
        return this;
    }

    public VideoQualityEnum getVideoQuality() {
        return videoQuality;
    }

    public RcLiveStatusData setVideoQuality(VideoQualityEnum videoQuality) {
        this.videoQuality = videoQuality;
        return this;
    }

    public VideoTypeEnum getVideoType() {
        return videoType;
    }

    public RcLiveStatusData setVideoType(VideoTypeEnum videoType) {
        this.videoType = videoType;
        return this;
    }

    public DockLiveErrorStatus getErrorStatus() {
        return errorStatus;
    }

    public RcLiveStatusData setErrorStatus(DockLiveErrorStatus errorStatus) {
        this.errorStatus = errorStatus;
        return this;
    }
}
