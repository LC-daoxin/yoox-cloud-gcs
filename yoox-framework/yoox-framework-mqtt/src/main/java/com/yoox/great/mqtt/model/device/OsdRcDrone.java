package com.yoox.great.mqtt.model.device;

import com.yoox.great.mqtt.enums.device.DroneModeCodeEnum;
import com.yoox.great.mqtt.enums.device.GearEnum;
import com.yoox.great.mqtt.enums.device.RcLostActionEnum;
import com.yoox.great.mqtt.enums.device.WindDirectionEnum;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class OsdRcDrone {

    private Float attitudeHead;

    private Double attitudePitch;

    private Double attitudeRoll;

    private Float elevation;

    private DroneBattery battery;

    private String firmwareVersion;

    private GearEnum gear;

    private Float height;

    private Float homeDistance;

    private Float horizontalSpeed;

    private Double latitude;

    private Double longitude;
    @JsonProperty(value = "mode_code")
    private DroneModeCodeEnum modeCode;

    @JsonProperty(value = "rc_lost_action")
    private RcLostActionEnum rcLostAction;

    private Double totalFlightDistance;

    private Float totalFlightTime;

    private Float verticalSpeed;

    private WindDirectionEnum windDirection;

    private Float windSpeed;

    private DronePositionState positionState;

    /**
     * Camera state reported by RC-connected aircraft. This carries the
     * lens-specific zoom_factor and ir_zoom_factor values used by the cockpit.
     */
    private List<OsdCamera> cameras;

    @JsonProperty(PayloadModelConst.PAYLOAD_KEY)
    private List<RcDronePayload> payloads;

    private Storage storage;

    private Integer heightLimit;

    private RcDistanceLimitStatus distanceLimitStatus;

    private String trackId;

    public OsdRcDrone() {
    }

    @Override
    public String toString() {
        return "OsdRcDrone{" +
                "attitudeHead=" + attitudeHead +
                ", attitudePitch=" + attitudePitch +
                ", attitudeRoll=" + attitudeRoll +
                ", elevation=" + elevation +
                ", battery=" + battery +
                ", firmwareVersion='" + firmwareVersion + '\'' +
                ", gear=" + gear +
                ", height=" + height +
                ", homeDistance=" + homeDistance +
                ", horizontalSpeed=" + horizontalSpeed +
                ", latitude=" + latitude +
                ", longitude=" + longitude +
                ", modeCode=" + modeCode +
                ", rcLostAction=" + rcLostAction +
                ", totalFlightDistance=" + totalFlightDistance +
                ", totalFlightTime=" + totalFlightTime +
                ", verticalSpeed=" + verticalSpeed +
                ", windDirection=" + windDirection +
                ", windSpeed=" + windSpeed +
                ", positionState=" + positionState +
                ", cameras=" + cameras +
                ", payloads=" + payloads +
                ", storage=" + storage +
                ", heightLimit=" + heightLimit +
                ", distanceLimitStatus=" + distanceLimitStatus +
                ", trackId='" + trackId + '\'' +
                '}';
    }

    public Float getAttitudeHead() {
        return attitudeHead;
    }

    public OsdRcDrone setAttitudeHead(Float attitudeHead) {
        this.attitudeHead = attitudeHead;
        return this;
    }

    public Double getAttitudePitch() {
        return attitudePitch;
    }

    public OsdRcDrone setAttitudePitch(Double attitudePitch) {
        this.attitudePitch = attitudePitch;
        return this;
    }

    public Double getAttitudeRoll() {
        return attitudeRoll;
    }

    public OsdRcDrone setAttitudeRoll(Double attitudeRoll) {
        this.attitudeRoll = attitudeRoll;
        return this;
    }

    public Float getElevation() {
        return elevation;
    }

    public OsdRcDrone setElevation(Float elevation) {
        this.elevation = elevation;
        return this;
    }

    public DroneBattery getBattery() {
        return battery;
    }

    public OsdRcDrone setBattery(DroneBattery battery) {
        this.battery = battery;
        return this;
    }

    public String getFirmwareVersion() {
        return firmwareVersion;
    }

    public OsdRcDrone setFirmwareVersion(String firmwareVersion) {
        this.firmwareVersion = firmwareVersion;
        return this;
    }

    public GearEnum getGear() {
        return gear;
    }

    public OsdRcDrone setGear(GearEnum gear) {
        this.gear = gear;
        return this;
    }

    public Float getHeight() {
        return height;
    }

    public OsdRcDrone setHeight(Float height) {
        this.height = height;
        return this;
    }

    public Float getHomeDistance() {
        return homeDistance;
    }

    public OsdRcDrone setHomeDistance(Float homeDistance) {
        this.homeDistance = homeDistance;
        return this;
    }

    public Float getHorizontalSpeed() {
        return horizontalSpeed;
    }

    public OsdRcDrone setHorizontalSpeed(Float horizontalSpeed) {
        this.horizontalSpeed = horizontalSpeed;
        return this;
    }

    public Double getLatitude() {
        return latitude;
    }

    public OsdRcDrone setLatitude(Double latitude) {
        this.latitude = latitude;
        return this;
    }

    public Double getLongitude() {
        return longitude;
    }

    public OsdRcDrone setLongitude(Double longitude) {
        this.longitude = longitude;
        return this;
    }

    public DroneModeCodeEnum getModeCode() {
        return modeCode;
    }

    public OsdRcDrone setModeCode(DroneModeCodeEnum modeCode) {
        this.modeCode = modeCode;
        return this;
    }

    public RcLostActionEnum getRcLostAction() {
        return rcLostAction;
    }

    public OsdRcDrone setRcLostAction(RcLostActionEnum rcLostAction) {
        this.rcLostAction = rcLostAction;
        return this;
    }

    public Double getTotalFlightDistance() {
        return totalFlightDistance;
    }

    public OsdRcDrone setTotalFlightDistance(Double totalFlightDistance) {
        this.totalFlightDistance = totalFlightDistance;
        return this;
    }

    public Float getTotalFlightTime() {
        return totalFlightTime;
    }

    public OsdRcDrone setTotalFlightTime(Float totalFlightTime) {
        this.totalFlightTime = totalFlightTime;
        return this;
    }

    public Float getVerticalSpeed() {
        return verticalSpeed;
    }

    public OsdRcDrone setVerticalSpeed(Float verticalSpeed) {
        this.verticalSpeed = verticalSpeed;
        return this;
    }

    public WindDirectionEnum getWindDirection() {
        return windDirection;
    }

    public OsdRcDrone setWindDirection(WindDirectionEnum windDirection) {
        this.windDirection = windDirection;
        return this;
    }

    public Float getWindSpeed() {
        return windSpeed;
    }

    public OsdRcDrone setWindSpeed(Float windSpeed) {
        this.windSpeed = windSpeed;
        return this;
    }

    public DronePositionState getPositionState() {
        return positionState;
    }

    public OsdRcDrone setPositionState(DronePositionState positionState) {
        this.positionState = positionState;
        return this;
    }

    public List<OsdCamera> getCameras() {
        return cameras;
    }

    public OsdRcDrone setCameras(List<OsdCamera> cameras) {
        this.cameras = cameras;
        return this;
    }

    public List<RcDronePayload> getPayloads() {
        return payloads;
    }

    public OsdRcDrone setPayloads(List<RcDronePayload> payloads) {
        this.payloads = payloads;
        return this;
    }

    public Storage getStorage() {
        return storage;
    }

    public OsdRcDrone setStorage(Storage storage) {
        this.storage = storage;
        return this;
    }

    public Integer getHeightLimit() {
        return heightLimit;
    }

    public OsdRcDrone setHeightLimit(Integer heightLimit) {
        this.heightLimit = heightLimit;
        return this;
    }

    public RcDistanceLimitStatus getDistanceLimitStatus() {
        return distanceLimitStatus;
    }

    public OsdRcDrone setDistanceLimitStatus(RcDistanceLimitStatus distanceLimitStatus) {
        this.distanceLimitStatus = distanceLimitStatus;
        return this;
    }

    public String getTrackId() {
        return trackId;
    }

    public OsdRcDrone setTrackId(String trackId) {
        this.trackId = trackId;
        return this;
    }
}
