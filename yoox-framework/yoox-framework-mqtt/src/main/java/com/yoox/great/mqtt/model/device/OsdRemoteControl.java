package com.yoox.great.mqtt.model.device;

import com.yoox.great.mqtt.model.livestream.RcLiveCapacityDevice;

import java.util.List;

public class OsdRemoteControl {

    private Double latitude;

    private Double longitude;

    private Float height;

    private Integer capacityPercent;

    private WirelessLink wirelessLink;

    private List<RcLiveStatusData> liveStatus;

    /**
     * Livestream sources currently exposed by the aircraft connected to this
     * remote controller. Some RC firmware reports this continuously in OSD
     * instead of sending a separate live_capacity state message.
     */
    private List<RcLiveCapacityDevice> deviceList;

    public OsdRemoteControl() {
    }

    @Override
    public String toString() {
        return "OsdRemoteControl{" +
                "latitude=" + latitude +
                ", longitude=" + longitude +
                ", height=" + height +
                ", capacityPercent=" + capacityPercent +
                ", wirelessLink=" + wirelessLink +
                ", liveStatus=" + liveStatus +
                ", deviceList=" + deviceList +
                '}';
    }

    public Double getLatitude() {
        return latitude;
    }

    public OsdRemoteControl setLatitude(Double latitude) {
        this.latitude = latitude;
        return this;
    }

    public Double getLongitude() {
        return longitude;
    }

    public OsdRemoteControl setLongitude(Double longitude) {
        this.longitude = longitude;
        return this;
    }

    public Float getHeight() {
        return height;
    }

    public OsdRemoteControl setHeight(Float height) {
        this.height = height;
        return this;
    }

    public Integer getCapacityPercent() {
        return capacityPercent;
    }

    public OsdRemoteControl setCapacityPercent(Integer capacityPercent) {
        this.capacityPercent = capacityPercent;
        return this;
    }

    public WirelessLink getWirelessLink() {
        return wirelessLink;
    }

    public OsdRemoteControl setWirelessLink(WirelessLink wirelessLink) {
        this.wirelessLink = wirelessLink;
        return this;
    }

    public List<RcLiveStatusData> getLiveStatus() {
        return liveStatus;
    }

    public OsdRemoteControl setLiveStatus(List<RcLiveStatusData> liveStatus) {
        this.liveStatus = liveStatus;
        return this;
    }

    public List<RcLiveCapacityDevice> getDeviceList() {
        return deviceList;
    }

    public OsdRemoteControl setDeviceList(List<RcLiveCapacityDevice> deviceList) {
        this.deviceList = deviceList;
        return this;
    }
}
