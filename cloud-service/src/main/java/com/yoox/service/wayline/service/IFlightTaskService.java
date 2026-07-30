package com.yoox.service.wayline.service;

import com.yoox.great.context.response.HttpResultResponse;
import com.yoox.great.context.model.CustomClaim;
import com.yoox.service.wayline.model.dto.ConditionalWaylineJobKey;
import com.yoox.service.wayline.model.dto.WaylineJobDTO;
import com.yoox.service.wayline.model.param.CreateJobParam;
import com.yoox.service.wayline.model.param.UpdateJobParam;

import java.sql.SQLException;
import java.util.Collection;
import java.util.List;

public interface IFlightTaskService {

    HttpResultResponse publishFlightTask(CreateJobParam param, CustomClaim customClaim) throws SQLException;

    HttpResultResponse publishOneFlightTask(WaylineJobDTO waylineJob) throws SQLException;

    Boolean executeFlightTask(String workspaceId, String jobId);

    void cancelFlightTask(String workspaceId, Collection<String> jobIds);

    void publishCancelTask(String workspaceId, String dockSn, List<String> jobIds);

    void uploadMediaHighestPriority(String workspaceId, String jobId);

    void updateJobStatus(String workspaceId, String jobId, UpdateJobParam param);

    void retryPrepareJob(ConditionalWaylineJobKey jobKey, WaylineJobDTO waylineJob);
}
