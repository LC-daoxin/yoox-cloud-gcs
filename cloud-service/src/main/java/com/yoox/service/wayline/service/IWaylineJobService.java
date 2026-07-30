package com.yoox.service.wayline.service;

import com.yoox.great.context.page.PaginationData;
import com.yoox.service.wayline.model.dto.WaylineJobDTO;
import com.yoox.service.wayline.model.enums.WaylineJobStatusEnum;
import com.yoox.service.wayline.model.param.CreateJobParam;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface IWaylineJobService {

    Optional<WaylineJobDTO> createWaylineJob(CreateJobParam param, String workspaceId, String username, Long beginTime, Long endTime);

    Optional<WaylineJobDTO> createWaylineJobByParent(String workspaceId, String parentId);

    List<WaylineJobDTO> getJobsByConditions(String workspaceId, Collection<String> jobIds, WaylineJobStatusEnum status);

    Optional<WaylineJobDTO> getJobByJobId(String workspaceId, String jobId);

    Boolean updateJob(WaylineJobDTO dto);

    PaginationData<WaylineJobDTO> getJobsByWorkspaceId(String workspaceId, long page, long pageSize);

    WaylineJobStatusEnum getWaylineState(String dockSn);
}
