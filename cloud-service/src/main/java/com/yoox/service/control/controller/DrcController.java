package com.yoox.service.control.controller;

import com.yoox.great.context.response.HttpResultResponse;
import com.yoox.great.context.model.CustomClaim;
import com.yoox.great.mqtt.property.DrcModeMqttBroker;
import com.yoox.service.control.model.dto.JwtAclDTO;
import com.yoox.service.control.model.param.DrcConnectParam;
import com.yoox.service.control.model.param.DrcModeParam;
import com.yoox.service.control.service.IDrcService;
import com.yoox.service.control.service.impl.ControlAccessService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

@RestController
@Slf4j
@RequestMapping("${url.control.prefix}${url.control.version}")
public class DrcController {

    @Autowired
    private IDrcService drcService;

    @Autowired
    private ControlAccessService controlAccessService;

    @PostMapping("/workspaces/{workspace_id}/drc/connect")
    public HttpResultResponse drcConnect(@PathVariable("workspace_id") String workspaceId, HttpServletRequest request, @Valid @RequestBody DrcConnectParam param) {
        CustomClaim claims = controlAccessService.requireWorkspace(request, workspaceId);

        DrcModeMqttBroker brokerDTO = drcService.userDrcAuth(workspaceId, claims.getId(), claims.getUsername(), param);
        return HttpResultResponse.success(brokerDTO);
    }

    @PostMapping("/workspaces/{workspace_id}/drc/enter")
    public HttpResultResponse drcEnter(@PathVariable("workspace_id") String workspaceId,
                                       @Valid @RequestBody DrcModeParam param,
                                       HttpServletRequest request) {
        CustomClaim claims = controlAccessService.requireWorkspace(request, workspaceId);
        controlAccessService.requireDevice(workspaceId, param.getDockSn());
        JwtAclDTO acl = drcService.deviceDrcEnter(workspaceId, claims.getId(), param);

        return HttpResultResponse.success(acl);
    }

    @PostMapping("/workspaces/{workspace_id}/drc/exit")
    public HttpResultResponse drcExit(@PathVariable("workspace_id") String workspaceId,
                                      @Valid @RequestBody DrcModeParam param,
                                      HttpServletRequest request) {
        CustomClaim claims = controlAccessService.requireWorkspace(request, workspaceId);
        controlAccessService.requireDevice(workspaceId, param.getDockSn());
        drcService.deviceDrcExit(workspaceId, claims.getId(), param);

        return HttpResultResponse.success();
    }


}
