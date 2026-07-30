package com.yoox.great.context.web.mvc;

import com.yoox.great.context.web.core.AuthInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class GlobalMVCConfigurer implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    @Value("${url.manage.prefix}")
    private String managePrefix;

    @Value("${url.manage.version}")
    private String manageVersion;

    public GlobalMVCConfigurer(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        List<String> excludePaths = List.of(
                "/" + managePrefix + manageVersion + "/login",
                "/" + managePrefix + manageVersion + "/token/refresh",
                "/actuator/**",
                "/swagger-ui.html",
                "/swagger-ui/**",
                "/v3/**",
                "/ui/**",
                "/test/**"
        );
        registry.addInterceptor(authInterceptor).addPathPatterns("/**").excludePathPatterns(excludePaths);
    }
}
