package com.yoox.service.manage.controller;

import com.yoox.great.context.response.HttpResultResponse;
import com.yoox.great.context.page.PaginationData;
import com.yoox.great.context.model.CustomClaim;
import com.yoox.great.context.web.core.AuthInterceptor;
import com.yoox.service.manage.model.dto.UserListDTO;
import com.yoox.service.manage.model.param.ChangePasswordParam;
import com.yoox.service.manage.service.IUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;


@RestController
@RequestMapping("${url.manage.prefix}${url.manage.version}/users")
public class UserController {

    @Autowired
    private IUserService userService;

    @GetMapping("/current")
    public HttpResultResponse getCurrentUserInfo(HttpServletRequest request) {
        CustomClaim customClaim = (CustomClaim)request.getAttribute(AuthInterceptor.TOKEN_CLAIM);
        return userService.getUserByUsername(customClaim.getUsername(), customClaim.getWorkspaceId());
    }

    @PutMapping("/current/password")
    public HttpResultResponse changeCurrentPassword(HttpServletRequest request,
                                                    @Valid @RequestBody ChangePasswordParam param) {
        CustomClaim customClaim = (CustomClaim) request.getAttribute(AuthInterceptor.TOKEN_CLAIM);
        userService.changePassword(customClaim.getUsername(), param.getOldPassword(), param.getNewPassword());
        return HttpResultResponse.success();
    }

    @GetMapping("/{workspace_id}/users")
    public HttpResultResponse<PaginationData<UserListDTO>> getUsers(@RequestParam(defaultValue = "1") Long page,
                                                                    @RequestParam(value = "page_size", defaultValue = "50") Long pageSize,
                                                                    @PathVariable("workspace_id") String workspaceId) {
        PaginationData<UserListDTO> paginationData = userService.getUsersByWorkspaceId(page, pageSize, workspaceId);
        return HttpResultResponse.success(paginationData);
    }
    @PutMapping("/{workspace_id}/users/{user_id}")
    public HttpResultResponse updateUser(@RequestBody UserListDTO user,
                                         @PathVariable("workspace_id") String workspaceId,
                                         @PathVariable("user_id") String userId) {

        userService.updateUser(workspaceId, userId, user);
        return HttpResultResponse.success();
    }
}
