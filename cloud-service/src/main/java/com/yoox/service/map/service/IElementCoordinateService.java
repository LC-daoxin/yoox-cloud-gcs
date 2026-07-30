package com.yoox.service.map.service;


import com.yoox.great.mqtt.model.map.ElementCoordinate;

import java.util.List;

public interface IElementCoordinateService {

    List<ElementCoordinate> getCoordinateByElementId(String elementId);

    Boolean saveCoordinate(List<ElementCoordinate> coordinate, String elementId);

    Boolean deleteCoordinateByElementId(String elementId);
}
