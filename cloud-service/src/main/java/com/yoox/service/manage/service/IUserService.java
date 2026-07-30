package com.yoox.service.manage.service;

import com.yoox.great.context.response.HttpResultResponse;
import com.yoox.great.context.page.PaginationData;
import com.yoox.service.manage.model.dto.UserDTO;
import com.yoox.service.manage.model.dto.UserListDTO;

import java.util.Optional;

public interface IUserService {

    HttpResultResponse getUserByUsername(String username, String workspaceId);

    HttpResultResponse userLogin(String username, String password, Integer flag);

    Optional<UserDTO> refreshToken(String token);

    PaginationData<UserListDTO> getUsersByWorkspaceId(long page, long pageSize, String workspaceId);

    Boolean updateUser(String workspaceId, String userId, UserListDTO user);

    void changePassword(String username, String oldPassword, String newPassword);
}
