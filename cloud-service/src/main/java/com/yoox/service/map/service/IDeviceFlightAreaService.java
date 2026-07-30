package com.yoox.service.map.service;

import com.yoox.service.map.model.dto.DeviceFlightAreaDTO;

import java.util.Optional;

public interface IDeviceFlightAreaService {

    Optional<DeviceFlightAreaDTO> getDeviceFlightAreaByDevice(String workspaceId, String deviceSn);

    Boolean updateDeviceFile(DeviceFlightAreaDTO deviceFile);

    Boolean updateOrSaveDeviceFile(DeviceFlightAreaDTO deviceFile);
}
