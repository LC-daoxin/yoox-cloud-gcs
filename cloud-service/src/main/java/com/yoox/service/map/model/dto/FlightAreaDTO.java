package com.yoox.service.map.model.dto;

import com.yoox.great.mqtt.enums.flightarea.GeofenceTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FlightAreaDTO {

    private String areaId;

    private String name;

    private GeofenceTypeEnum type;

    private FlightAreaContent content;

    private Boolean status;

    private String username;

    private Long createTime;

    private Long updateTime;
}
