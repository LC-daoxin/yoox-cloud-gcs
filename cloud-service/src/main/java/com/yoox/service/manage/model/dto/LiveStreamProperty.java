package com.yoox.service.manage.model.dto;

import com.yoox.great.mqtt.enums.livestream.UrlTypeEnum;
import com.yoox.great.mqtt.model.livestream.ILivestreamUrl;
import com.yoox.great.mqtt.model.livestream.LivestreamRtspUrl;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties("livestream.url")
public class LiveStreamProperty {

    private static LivestreamRtspUrl rtsp;

    public void setRtsp(LivestreamRtspUrl rtsp) {
        LiveStreamProperty.rtsp = rtsp;
    }

    public static ILivestreamUrl get(UrlTypeEnum type) {
        if (type != UrlTypeEnum.RTSP) {
            throw new IllegalArgumentException("YOOX Cloud GCS P0 only supports RTSP live streaming.");
        }
        return rtsp;
    }
}
