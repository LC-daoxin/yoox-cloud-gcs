package com.yoox.service.manage.model.dto;

import com.yoox.great.mqtt.enums.livestream.UrlTypeEnum;
import com.yoox.great.mqtt.model.livestream.ILivestreamUrl;
import com.yoox.great.mqtt.model.livestream.LivestreamRtmpUrl;
import com.yoox.great.mqtt.model.livestream.LivestreamRtspUrl;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties("livestream.url")
public class LiveStreamProperty {

    private static LivestreamRtmpUrl rtmp;

    private static LivestreamRtspUrl rtsp;

    public void setRtmp(LivestreamRtmpUrl rtmp) {
        LiveStreamProperty.rtmp = rtmp;
    }

    public void setRtsp(LivestreamRtspUrl rtsp) {
        LiveStreamProperty.rtsp = rtsp;
    }

    public static ILivestreamUrl get(UrlTypeEnum type) {
        return switch (type) {
            case RTMP -> rtmp;
            case RTSP -> rtsp;
            default -> throw new IllegalArgumentException("Unsupported live streaming protocol.");
        };
    }
}
