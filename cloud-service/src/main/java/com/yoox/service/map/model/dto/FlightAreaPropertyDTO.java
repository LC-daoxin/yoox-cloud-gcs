package com.yoox.service.map.model.dto;

import com.yoox.great.mqtt.enums.flightarea.GeofenceTypeEnum;
import com.yoox.great.mqtt.enums.flightarea.GeometrySubTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FlightAreaPropertyDTO {

    private String elementId;

    private Boolean status;

    private GeofenceTypeEnum type;

    private Float radius;

    private GeometrySubTypeEnum subType;
}
