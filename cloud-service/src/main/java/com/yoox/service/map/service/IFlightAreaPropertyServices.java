package com.yoox.service.map.service;

import com.yoox.service.map.model.dto.FlightAreaPropertyDTO;
import com.yoox.service.map.model.dto.FlightAreaPropertyUpdate;

import java.util.List;

public interface IFlightAreaPropertyServices {

    List<FlightAreaPropertyDTO> getPropertyByElementIds(List<String> elementIds);

    Integer saveProperty(FlightAreaPropertyDTO property);

    Integer deleteProperty(String elementId);

    Integer updateProperty(FlightAreaPropertyUpdate property);
}
