package com.yoox.great.context.web.core;

import com.yoox.great.context.error.CommonErrorEnum;
import com.yoox.great.context.model.CustomClaim;
import com.yoox.great.context.utils.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Optional;

@Slf4j
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String PARAM_TOKEN = "x-auth-token";
    public static final String TOKEN_CLAIM = "customClaim";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String uri = request.getRequestURI();
        log.debug("request uri: {}, IP: {}", uri, request.getRemoteAddr());

        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            response.setStatus(HttpStatus.OK.value());
            return false;
        }

        String token = request.getHeader(PARAM_TOKEN);
        if (!StringUtils.hasText(token)) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            log.warn(CommonErrorEnum.NO_TOKEN.getMessage());
            return false;
        }

        Optional<CustomClaim> customClaim = JwtUtil.parseToken(token);
        if (customClaim.isEmpty()) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return false;
        }

        request.setAttribute(TOKEN_CLAIM, customClaim.get());
        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response,
                           Object handler, ModelAndView modelAndView) {
        request.removeAttribute(TOKEN_CLAIM);
    }
}
