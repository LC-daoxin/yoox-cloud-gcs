package com.yoox.great.mqtt.model.control;

import com.yoox.great.mqtt.enums.wayline.WaylineErrorCodeEnum;
import com.yoox.great.mqtt.enums.control.FlyToStatusEnum;
import java.util.List;

public class FlyToPointProgress {

    private WaylineErrorCodeEnum result;

    private FlyToStatusEnum status;

    private String flyToId;

    private Integer wayPointIndex;

    private Float remainingDistance;

    private Float remainingTime;

    private List<Point> plannedPathPoints;

    public FlyToPointProgress() {
    }

    @Override
    public String toString() {
        return "FlyToPointProgress{" +
                "result=" + result +
                ", status=" + status +
                ", flyToId='" + flyToId + '\'' +
                ", wayPointIndex=" + wayPointIndex +
                '}';
    }

    public WaylineErrorCodeEnum getResult() {
        return result;
    }

    public FlyToPointProgress setResult(WaylineErrorCodeEnum result) {
        this.result = result;
        return this;
    }

    public FlyToStatusEnum getStatus() {
        return status;
    }

    public FlyToPointProgress setStatus(FlyToStatusEnum status) {
        this.status = status;
        return this;
    }

    public String getFlyToId() {
        return flyToId;
    }

    public FlyToPointProgress setFlyToId(String flyToId) {
        this.flyToId = flyToId;
        return this;
    }

    public Integer getWayPointIndex() {
        return wayPointIndex;
    }

    public FlyToPointProgress setWayPointIndex(Integer wayPointIndex) {
        this.wayPointIndex = wayPointIndex;
        return this;
    }

    public Float getRemainingDistance() {
        return remainingDistance;
    }

    public FlyToPointProgress setRemainingDistance(Float remainingDistance) {
        this.remainingDistance = remainingDistance;
        return this;
    }

    public Float getRemainingTime() {
        return remainingTime;
    }

    public FlyToPointProgress setRemainingTime(Float remainingTime) {
        this.remainingTime = remainingTime;
        return this;
    }

    public List<Point> getPlannedPathPoints() {
        return plannedPathPoints;
    }

    public FlyToPointProgress setPlannedPathPoints(List<Point> plannedPathPoints) {
        this.plannedPathPoints = plannedPathPoints;
        return this;
    }
}
