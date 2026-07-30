package com.yoox.great.mqtt.model.livestream;

import com.yoox.great.context.base.BaseModel;
import com.yoox.great.mqtt.enums.livestream.UrlTypeEnum;
import com.yoox.great.mqtt.enums.livestream.VideoQualityEnum;
import lombok.Data;

@Data
public class LiveStartPushRequest2 extends BaseModel {
    private UrlTypeEnum urlType;
    private String url;
    private String videoId;
    private VideoQualityEnum videoQuality;
}