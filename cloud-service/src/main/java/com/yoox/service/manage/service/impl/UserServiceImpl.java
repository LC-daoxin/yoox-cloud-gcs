package com.yoox.service.manage.service.impl;

import com.yoox.great.context.response.HttpResultResponse;
import com.yoox.great.context.page.Pagination;
import com.yoox.great.context.page.PaginationData;
import com.yoox.great.context.model.CustomClaim;
import com.yoox.great.context.utils.JwtUtil;
import com.yoox.great.websocket.enums.UserTypeEnum;
import com.yoox.great.mqtt.autoconfiguration.MqttPropertyConfiguration;
import com.auth0.jwt.JWT;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yoox.service.manage.dao.IUserMapper;
import com.yoox.service.manage.model.dto.UserDTO;
import com.yoox.service.manage.model.dto.UserListDTO;
import com.yoox.service.manage.model.dto.WorkspaceDTO;
import com.yoox.service.manage.model.entity.UserEntity;
import com.yoox.service.manage.service.IUserService;
import com.yoox.service.manage.service.IWorkspaceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional
public class UserServiceImpl implements IUserService {

    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    @Autowired
    private IUserMapper mapper;

    @Autowired
    private MqttPropertyConfiguration mqttPropertyConfiguration;

    @Autowired
    private IWorkspaceService workspaceService;

    @Override
    public HttpResultResponse getUserByUsername(String username, String workspaceId) {

        UserEntity userEntity = this.getUserByUsername(username);
        if (userEntity == null) {
            return new HttpResultResponse()
                    .setCode(HttpStatus.UNAUTHORIZED.value())
                    .setMessage("invalid username");
        }

        UserDTO user = this.entityConvertToDTO(userEntity);
        user.setWorkspaceId(workspaceId);

        return HttpResultResponse.success(user);
    }

    @Override
    public HttpResultResponse userLogin(String username, String password, Integer flag) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password) || flag == null) {
            return HttpResultResponse.error("Username, password and account type are required.");
        }
        // check user
        UserEntity userEntity = this.getUserByUsername(username);
        if (userEntity == null) {
            return new HttpResultResponse()
                    .setCode(HttpStatus.UNAUTHORIZED.value())
                    .setMessage("invalid username");
        }
        if (flag.intValue() != userEntity.getUserType().intValue()) {
            return HttpResultResponse.error("The account type does not match.");
        }
        if (!PASSWORD_ENCODER.matches(password, userEntity.getPassword())) {
            return new HttpResultResponse()
                    .setCode(HttpStatus.UNAUTHORIZED.value())
                    .setMessage("invalid password");
        }

        Optional<WorkspaceDTO> workspaceOpt = workspaceService.getWorkspaceByWorkspaceId(userEntity.getWorkspaceId());
        if (workspaceOpt.isEmpty()) {
            return new HttpResultResponse()
                    .setCode(HttpStatus.UNAUTHORIZED.value())
                    .setMessage("invalid workspace id");
        }

        CustomClaim customClaim = new CustomClaim(userEntity.getUserId(),
                userEntity.getUsername(), userEntity.getUserType(),
                workspaceOpt.get().getWorkspaceId());

        String token = JwtUtil.createToken(customClaim.convertToMap());

        UserDTO userDTO = entityConvertToDTO(userEntity);
        userDTO.setMqttAddr(MqttPropertyConfiguration.getBasicMqttAddress());
        userDTO.setAccessToken(token);
        userDTO.setWorkspaceId(workspaceOpt.get().getWorkspaceId());
        return HttpResultResponse.success(userDTO);
    }

    @Override
    public Optional<UserDTO> refreshToken(String token) {
        if (!StringUtils.hasText(token)) {
            return Optional.empty();
        }
        CustomClaim customClaim;
        try {
            DecodedJWT jwt = JwtUtil.verifyToken(token);
            customClaim = new CustomClaim(jwt.getClaims());
        } catch (TokenExpiredException e) {
            customClaim = new CustomClaim(JWT.decode(token).getClaims());
        } catch (Exception e) {
            log.warn("Failed to refresh token", e);
            return Optional.empty();
        }
        String refreshToken = JwtUtil.createToken(customClaim.convertToMap());

        UserDTO user = entityConvertToDTO(this.getUserByUsername(customClaim.getUsername()));
        if (Objects.isNull(user)) {
            return Optional.empty();
        }
        user.setWorkspaceId(customClaim.getWorkspaceId());
        user.setAccessToken(refreshToken);
        return Optional.of(user);
    }

    @Override
    public PaginationData<UserListDTO> getUsersByWorkspaceId(long page, long pageSize, String workspaceId) {
        Page<UserEntity> userEntityPage = mapper.selectPage(
                new Page<>(page, pageSize),
                new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getWorkspaceId, workspaceId));

        List<UserListDTO> usersList = userEntityPage.getRecords()
                .stream()
                .map(this::entity2UserListDTO)
                .collect(Collectors.toList());
        return new PaginationData<>(usersList, new Pagination(userEntityPage.getCurrent(), userEntityPage.getSize(), userEntityPage.getTotal()));
    }

    @Override
    public Boolean updateUser(String workspaceId, String userId, UserListDTO user) {
        UserEntity userEntity = mapper.selectOne(
                new LambdaQueryWrapper<UserEntity>()
                        .eq(UserEntity::getUserId, userId)
                        .eq(UserEntity::getWorkspaceId, workspaceId));
        if (userEntity == null) {
            return false;
        }
        userEntity.setMqttUsername(user.getMqttUsername());
        userEntity.setMqttPassword(user.getMqttPassword());
        userEntity.setUpdateTime(System.currentTimeMillis());
        int id = mapper.update(userEntity, new LambdaUpdateWrapper<UserEntity>()
                .eq(UserEntity::getUserId, userId)
                .eq(UserEntity::getWorkspaceId, workspaceId));

        return id > 0;
    }

    @Override
    public void changePassword(String username, String oldPassword, String newPassword) {
        UserEntity userEntity = this.getUserByUsername(username);
        if (userEntity == null || !PASSWORD_ENCODER.matches(oldPassword, userEntity.getPassword())) {
            throw new IllegalArgumentException("The current password is incorrect.");
        }
        boolean hasUpper = newPassword.chars().anyMatch(Character::isUpperCase);
        boolean hasLower = newPassword.chars().anyMatch(Character::isLowerCase);
        boolean hasDigit = newPassword.chars().anyMatch(Character::isDigit);
        boolean hasSymbol = newPassword.chars().anyMatch(value -> !Character.isLetterOrDigit(value));
        if (!(hasUpper && hasLower && hasDigit && hasSymbol)) {
            throw new IllegalArgumentException(
                    "The new password must contain uppercase, lowercase, digit and special characters.");
        }
        if (PASSWORD_ENCODER.matches(newPassword, userEntity.getPassword())) {
            throw new IllegalArgumentException("The new password must differ from the current password.");
        }
        userEntity.setPassword(PASSWORD_ENCODER.encode(newPassword));
        userEntity.setUpdateTime(System.currentTimeMillis());
        mapper.updateById(userEntity);
    }

    /**
     * Convert database entity objects into user data transfer object.
     * @param entity
     * @return
     */
    private UserListDTO entity2UserListDTO(UserEntity entity) {
        UserListDTO.UserListDTOBuilder builder = UserListDTO.builder();
        if (entity != null) {
            builder.userId(entity.getUserId())
                    .username(entity.getUsername())
                    .mqttUsername(entity.getMqttUsername())
                    .mqttPassword(entity.getMqttPassword())
                    .userType(UserTypeEnum.find(entity.getUserType()).getDesc())
                    .createTime(LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(entity.getCreateTime()), ZoneId.systemDefault()));
            Optional<WorkspaceDTO> workspaceOpt = workspaceService.getWorkspaceByWorkspaceId(entity.getWorkspaceId());
            workspaceOpt.ifPresent(workspace -> builder.workspaceName(workspace.getWorkspaceName()));
        }

        return builder.build();
    }

    private UserEntity getUserByUsername(String username) {
        return mapper.selectOne(new QueryWrapper<UserEntity>()
                .eq("username", username));
    }

    private UserDTO entityConvertToDTO(UserEntity entity) {
        if (entity == null) {
            return null;
        }
        return UserDTO.builder()
                .userId(entity.getUserId())
                .username(entity.getUsername())
                .userType(entity.getUserType())
                .mqttUsername(entity.getMqttUsername())
                .mqttPassword(entity.getMqttPassword())
                .mqttAddr(MqttPropertyConfiguration.getBasicMqttAddress())
                .build();
    }
}
