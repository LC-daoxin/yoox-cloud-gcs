package com.yoox.great.mqtt.model.livestream;


import com.yoox.great.context.base.BaseModel;

import javax.validation.constraints.NotNull;

public class LivestreamRtspUrl extends BaseModel implements ILivestreamUrl {

    /**
     * RTSP publish URL prefix. Some YOOX devices expect a complete RTSP
     * destination instead of the legacy userName/password/port parameter list.
     */
    private String url;

    /**
     * Browser playback URL template for the MediaMTX WebRTC/WHEP endpoint.
     */
    private String payUrl;

    @NotNull
    private String username;

    @NotNull
    private String password;

    @NotNull
    private Integer port;

    public LivestreamRtspUrl() {
    }

    @Override
    public String toString() {
        if (url != null && !url.trim().isEmpty()) {
            return url;
        }
        return "userName=" + username +
                "&password=" + password +
                "&port=" + port;
    }

    @Override
    public LivestreamRtspUrl clone() {
        try {
            return (LivestreamRtspUrl) super.clone();
        } catch (CloneNotSupportedException e) {
            return new LivestreamRtspUrl()
                    .setUrl(url)
                    .setPayUrl(payUrl)
                    .setUsername(username)
                    .setPassword(password)
                    .setPort(port);
        }
    }

    public String getUrl() {
        return url;
    }

    public LivestreamRtspUrl setUrl(String url) {
        this.url = url;
        return this;
    }

    public String getPayUrl() {
        return payUrl;
    }

    public LivestreamRtspUrl setPayUrl(String payUrl) {
        this.payUrl = payUrl;
        return this;
    }

    public String getUsername() {
        return username;
    }

    public LivestreamRtspUrl setUsername(String username) {
        this.username = username;
        return this;
    }

    public String getPassword() {
        return password;
    }

    public LivestreamRtspUrl setPassword(String password) {
        this.password = password;
        return this;
    }

    public Integer getPort() {
        return port;
    }

    public LivestreamRtspUrl setPort(Integer port) {
        this.port = port;
        return this;
    }
}
