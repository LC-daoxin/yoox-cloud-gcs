package com.yoox.service.manage.model.receiver;

import com.yoox.great.mqtt.model.device.PayloadIndex;
import lombok.Data;

import java.util.List;

@Data
public class CapacityCameraReceiver {

    private Integer availableVideoNumber;

    private Integer coexistVideoNumberMax;

    private PayloadIndex cameraIndex;

    private List<CapacityVideoReceiver> videoList;

}