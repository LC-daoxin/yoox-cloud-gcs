package com.yoox.service.wayline.controller;

import com.yoox.great.context.response.HttpResultResponse;
import com.yoox.great.context.page.PaginationData;
import com.yoox.great.context.model.CustomClaim;
import com.yoox.great.context.web.core.AuthInterceptor;
import com.yoox.service.wayline.model.param.CreateJobParam;
import com.yoox.service.wayline.model.param.UpdateJobParam;
import com.yoox.service.wayline.service.IFlightTaskService;
import com.yoox.service.wayline.service.IWaylineJobService;
import com.yoox.service.wayline.model.dto.WaylineJobDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.sql.SQLException;
import java.util.Set;

@RequestMapping("${url.wayline.prefix}${url.wayline.version}/workspaces")
@RestController
public class WaylineJobController {

    @Autowired
    private IWaylineJobService waylineJobService;

    @Autowired
    private IFlightTaskService flighttaskService;

    @PostMapping("/{workspace_id}/flight-tasks")
    public HttpResultResponse createJob(HttpServletRequest request, @Valid @RequestBody CreateJobParam param,
                                        @PathVariable(name = "workspace_id") String workspaceId) throws SQLException {
        CustomClaim customClaim = (CustomClaim)request.getAttribute(AuthInterceptor.TOKEN_CLAIM);
        customClaim.setWorkspaceId(workspaceId);

        return flighttaskService.publishFlightTask(param, customClaim);
    }

    @GetMapping("/{workspace_id}/jobs")
    public HttpResultResponse<PaginationData<WaylineJobDTO>> getJobs(@RequestParam(defaultValue = "1") Long page,
                                                                     @RequestParam(name = "page_size", defaultValue = "10") Long pageSize,
                                                                     @PathVariable(name = "workspace_id") String workspaceId) {
        PaginationData<WaylineJobDTO> data = waylineJobService.getJobsByWorkspaceId(workspaceId, page, pageSize);
        return HttpResultResponse.success(data);
    }

    @DeleteMapping("/{workspace_id}/jobs")
    public HttpResultResponse publishCancelJob(@RequestParam(name = "job_id") Set<String> jobIds,
                                               @PathVariable(name = "workspace_id") String workspaceId) throws SQLException {
        flighttaskService.cancelFlightTask(workspaceId, jobIds);
        return HttpResultResponse.success();
    }

    @PostMapping("/{workspace_id}/jobs/{job_id}/media-highest")
    public HttpResultResponse uploadMediaHighestPriority(@PathVariable(name = "workspace_id") String workspaceId,
                                                         @PathVariable(name = "job_id") String jobId) {
        flighttaskService.uploadMediaHighestPriority(workspaceId, jobId);
        return HttpResultResponse.success();
    }

    @PutMapping("/{workspace_id}/jobs/{job_id}")
    public HttpResultResponse updateJobStatus(@PathVariable(name = "workspace_id") String workspaceId,
                                              @PathVariable(name = "job_id") String jobId,
                                              @Valid @RequestBody UpdateJobParam param) {
        flighttaskService.updateJobStatus(workspaceId, jobId, param);
        return HttpResultResponse.success();
    }

    @DeleteMapping("/{workspace_id}/jobs/{job_id}")
    public HttpResultResponse deleteJob(@PathVariable(name = "workspace_id") String workspaceId,
                                        @PathVariable(name = "job_id") String jobId) {
        boolean isDel = waylineJobService.deleteFinishedJob(workspaceId, jobId);
        return isDel ? HttpResultResponse.success() : HttpResultResponse.error("任务不存在或仍在进行中，无法删除。");
    }
}
