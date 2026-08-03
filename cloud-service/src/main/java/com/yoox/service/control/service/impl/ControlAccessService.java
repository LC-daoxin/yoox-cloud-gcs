package com.yoox.service.control.service.impl;

import com.yoox.great.context.model.CustomClaim;
import com.yoox.great.context.web.core.AuthInterceptor;
import com.yoox.service.manage.model.dto.DeviceDTO;
import com.yoox.service.manage.service.IDeviceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.servlet.http.HttpServletRequest;

/**
 * Fail-closed authorization boundary for real-device control endpoints.
 */
@Component
public class ControlAccessService {

    @Autowired
    private IDeviceService deviceService;

    public CustomClaim requireWorkspace(HttpServletRequest request, String workspaceId) {
        CustomClaim claim = requireClaim(request);
        if (!StringUtils.hasText(workspaceId)
                || !workspaceId.equals(claim.getWorkspaceId())) {
            throw new SecurityException("The workspace is not authorized for this user.");
        }
        return claim;
    }

    public CustomClaim requireDevice(HttpServletRequest request, String deviceSn) {
        CustomClaim claim = requireClaim(request);
        requireDevice(claim.getWorkspaceId(), deviceSn);
        return claim;
    }

    public DeviceDTO requireDevice(String workspaceId, String deviceSn) {
        if (!StringUtils.hasText(workspaceId) || !StringUtils.hasText(deviceSn)) {
            throw new SecurityException("The device is not authorized for this workspace.");
        }
        DeviceDTO device = deviceService.getDeviceBySn(deviceSn)
                .orElseThrow(() -> new SecurityException(
                        "The device is not authorized for this workspace."));
        if (!workspaceId.equals(device.getWorkspaceId())) {
            throw new SecurityException("The device is not authorized for this workspace.");
        }
        return device;
    }

    private CustomClaim requireClaim(HttpServletRequest request) {
        Object value = request == null
                ? null
                : request.getAttribute(AuthInterceptor.TOKEN_CLAIM);
        if (!(value instanceof CustomClaim)) {
            throw new SecurityException("Authentication information is unavailable.");
        }
        CustomClaim claim = (CustomClaim) value;
        if (!StringUtils.hasText(claim.getId())
                || !StringUtils.hasText(claim.getWorkspaceId())) {
            throw new SecurityException("Authentication information is incomplete.");
        }
        return claim;
    }
}
