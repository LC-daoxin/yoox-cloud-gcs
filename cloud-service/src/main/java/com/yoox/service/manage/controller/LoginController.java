package com.yoox.service.manage.controller;

import com.yoox.great.context.response.HttpResultResponse;
import com.yoox.great.context.error.CommonErrorEnum;
import com.yoox.great.context.web.core.AuthInterceptor;
import com.yoox.service.manage.model.dto.UserDTO;
import com.yoox.service.manage.model.dto.UserLoginDTO;
import com.yoox.service.manage.service.IUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Optional;

@RestController
@RequestMapping("${url.manage.prefix}${url.manage.version}")
public class LoginController {

    @Autowired
    private IUserService userService;

    @PostMapping("/login")
    public HttpResultResponse login(@RequestBody UserLoginDTO loginDTO) {

        String username = loginDTO.getUsername();
        String password = loginDTO.getPassword();
        return userService.userLogin(username, password, loginDTO.getFlag());
    }

    @PostMapping("/token/refresh")
    public HttpResultResponse refreshToken(HttpServletRequest request, HttpServletResponse response) {
        String token = request.getHeader(AuthInterceptor.PARAM_TOKEN);
        Optional<UserDTO> user = userService.refreshToken(token);

        if (user.isEmpty()) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return HttpResultResponse.error(CommonErrorEnum.NO_TOKEN.getMessage());
        }

        return HttpResultResponse.success(user.get());
    }
}
