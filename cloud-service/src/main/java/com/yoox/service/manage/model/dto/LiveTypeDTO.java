package com.yoox.service.manage.model.dto;

import com.yoox.great.mqtt.enums.livestream.LensChangeVideoTypeEnum;
import com.yoox.great.mqtt.enums.livestream.UrlTypeEnum;
import com.yoox.great.mqtt.enums.livestream.VideoQualityEnum;
import com.yoox.great.mqtt.model.device.VideoId;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class LiveTypeDTO {

    @JsonProperty("url_type")
    private UrlTypeEnum urlType;

    @JsonProperty("video_id")
    private VideoId videoId;

    @JsonProperty("video_quality")
    private VideoQualityEnum videoQuality;

    private LensChangeVideoTypeEnum videoType;

    /** 自定义 RTSP 推流地址（可选）。非空时覆盖服务端配置的默认地址下发给设备。 */
    @JsonProperty("url")
    private String url;

}
