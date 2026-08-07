package com.yoox.great.mqtt.model.wayline;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

/**
 * 备降点信息（Autel 航线管理 flighttask_prepare.alternate_land_point）
 */
public class AlternateLandPoint {

    /**
     * 经度
     */
    @Min(-180)
    @Max(180)
    private Double longitude;

    /**
     * 纬度
     */
    @Min(-90)
    @Max(90)
    private Double latitude;

    /**
     * 安全降落高度（米）
     */
    private Float safeLandHeight;

    /**
     * 是否设置备降点：0 未设置，1 已设置
     */
    @NotNull
    @Min(0)
    @Max(1)
    private Integer isConfigured;

    public AlternateLandPoint() {
    }

    @Override
    public String toString() {
        return "AlternateLandPoint{" +
                "longitude=" + longitude +
                ", latitude=" + latitude +
                ", safeLandHeight=" + safeLandHeight +
                ", isConfigured=" + isConfigured +
                '}';
    }

    public Double getLongitude() {
        return longitude;
    }

    public AlternateLandPoint setLongitude(Double longitude) {
        this.longitude = longitude;
        return this;
    }

    public Double getLatitude() {
        return latitude;
    }

    public AlternateLandPoint setLatitude(Double latitude) {
        this.latitude = latitude;
        return this;
    }

    public Float getSafeLandHeight() {
        return safeLandHeight;
    }

    public AlternateLandPoint setSafeLandHeight(Float safeLandHeight) {
        this.safeLandHeight = safeLandHeight;
        return this;
    }

    public Integer getIsConfigured() {
        return isConfigured;
    }

    public AlternateLandPoint setIsConfigured(Integer isConfigured) {
        this.isConfigured = isConfigured;
        return this;
    }
}
