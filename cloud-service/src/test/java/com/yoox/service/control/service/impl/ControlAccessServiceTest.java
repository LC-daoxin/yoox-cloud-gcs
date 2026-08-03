package com.yoox.service.control.service.impl;

import com.yoox.great.context.model.CustomClaim;
import com.yoox.great.context.web.core.AuthInterceptor;
import com.yoox.service.manage.model.dto.DeviceDTO;
import com.yoox.service.manage.service.IDeviceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.servlet.http.HttpServletRequest;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ControlAccessServiceTest {

    private static final String WORKSPACE_ID = "workspace";
    private static final String DEVICE_SN = "gateway";

    @Mock
    private IDeviceService deviceService;

    @Mock
    private HttpServletRequest request;

    @InjectMocks
    private ControlAccessService accessService;

    @Test
    void pathWorkspaceMustMatchAuthenticatedWorkspace() {
        when(request.getAttribute(AuthInterceptor.TOKEN_CLAIM))
                .thenReturn(claim(WORKSPACE_ID));

        assertThrows(SecurityException.class,
                () -> accessService.requireWorkspace(request, "other-workspace"));

        verifyNoInteractions(deviceService);
    }

    @Test
    void deviceMustBelongToAuthenticatedWorkspace() {
        when(request.getAttribute(AuthInterceptor.TOKEN_CLAIM))
                .thenReturn(claim(WORKSPACE_ID));
        when(deviceService.getDeviceBySn(DEVICE_SN)).thenReturn(Optional.of(
                DeviceDTO.builder()
                        .deviceSn(DEVICE_SN)
                        .workspaceId("other-workspace")
                        .build()));

        assertThrows(SecurityException.class,
                () -> accessService.requireDevice(request, DEVICE_SN));
    }

    @Test
    void authorizedWorkspaceDevicePasses() {
        when(request.getAttribute(AuthInterceptor.TOKEN_CLAIM))
                .thenReturn(claim(WORKSPACE_ID));
        when(deviceService.getDeviceBySn(DEVICE_SN)).thenReturn(Optional.of(
                DeviceDTO.builder()
                        .deviceSn(DEVICE_SN)
                        .workspaceId(WORKSPACE_ID)
                        .build()));

        assertDoesNotThrow(() -> accessService.requireDevice(request, DEVICE_SN));
    }

    private CustomClaim claim(String workspaceId) {
        CustomClaim claim = new CustomClaim();
        claim.setId("user");
        claim.setUsername("operator");
        claim.setWorkspaceId(workspaceId);
        return claim;
    }
}
