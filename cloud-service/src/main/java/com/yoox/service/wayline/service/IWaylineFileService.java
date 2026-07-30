package com.yoox.service.wayline.service;

import com.yoox.great.context.page.PaginationData;
import com.yoox.great.mqtt.model.wayline.GetWaylineListRequest;
import com.yoox.great.mqtt.model.wayline.GetWaylineListResponse;
import com.yoox.service.wayline.model.dto.WaylineFileDTO;
import org.springframework.web.multipart.MultipartFile;

import java.net.URL;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public interface IWaylineFileService {

    PaginationData<GetWaylineListResponse> getWaylinesByParam(String workspaceId, GetWaylineListRequest param);

    Optional<GetWaylineListResponse> getWaylineByWaylineId(String workspaceId, String waylineId);

    URL getObjectUrl(String workspaceId, String waylineId) throws SQLException;

    Integer saveWaylineFile(String workspaceId, WaylineFileDTO metadata);

    Boolean markFavorite(String workspaceId, List<String> ids, Boolean isFavorite);

    List<String> getDuplicateNames(String workspaceId, List<String> names);

    Boolean deleteByWaylineId(String workspaceId, String waylineId);

    void importKmzFile(MultipartFile file, String workspaceId, String creator);
}
