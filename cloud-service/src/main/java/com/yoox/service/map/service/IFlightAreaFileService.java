package com.yoox.service.map.service;

import com.yoox.service.map.model.dto.FlightAreaDTO;
import com.yoox.service.map.model.dto.FlightAreaFileDTO;

import java.util.List;
import java.util.Optional;

public interface IFlightAreaFileService {

    Optional<FlightAreaFileDTO> getFlightAreaFileByFileId(String fileId);

    Integer saveFlightAreaFile(FlightAreaFileDTO file);

    Integer setNonLatestByWorkspaceId(String workspaceId);

    Optional<FlightAreaFileDTO> getLatestByWorkspaceId(String workspaceId);

    FlightAreaFileDTO packageFlightAreaFile(String workspaceId, List<FlightAreaDTO> flightAreas);
}
