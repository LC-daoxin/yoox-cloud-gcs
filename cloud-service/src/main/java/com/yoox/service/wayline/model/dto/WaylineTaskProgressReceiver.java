package com.yoox.service.wayline.model.dto;

import lombok.Data;

@Data
public class WaylineTaskProgressReceiver {

    private FlighttaskProgressExt ext;

    private FlighttaskProgressProgress progress;

    private String status;

}
