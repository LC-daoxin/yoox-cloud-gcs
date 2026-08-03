package com.yoox.great.mqtt.model.control;

import lombok.Data;

import java.util.List;

@Data
public class TargetDetectResultReport {

    private Long time;
    private String uavId;
    private Integer cameraId;
    private GlobalPosition globalPos;
    private Camera camera;
    private Integer objCnt;
    private List<DetectedObject> objs;

    @Data
    public static class DetectedObject {
        private String trackerId;
        private Integer clsId;
        private BoundingBox bbox;
        private GlobalPosition pos;
        private String pic;
        private Long timestamp;
    }

    @Data
    public static class BoundingBox {
        private Float x;
        private Float y;
        private Float w;
        private Float h;
    }

    @Data
    public static class Camera {
        private Float roll;
        private Float pitch;
        private Float yaw;
        private Float longitude;
        private Float latitude;
        private Float height;
        private List<Float> fov;
        private List<Integer> resolution;
        private String focalType;
    }

    @Data
    public static class GlobalPosition {
        private Float latitude;
        private Float longitude;
        private Float altitude;
    }
}
