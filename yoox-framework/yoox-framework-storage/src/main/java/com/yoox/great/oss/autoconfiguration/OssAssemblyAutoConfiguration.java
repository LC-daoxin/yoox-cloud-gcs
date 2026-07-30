package com.yoox.great.oss.autoconfiguration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.ComponentScan;

import javax.annotation.PostConstruct;

@Slf4j
@ComponentScan("com.yoox.great.oss")
public class OssAssemblyAutoConfiguration {
    @PostConstruct
    public void init() {
        log.info("【oss】 assembly load");
    }
}
