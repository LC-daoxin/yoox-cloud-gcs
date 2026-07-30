package com.yoox.great.context.autoconfiguration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.ComponentScan;

import javax.annotation.PostConstruct;

@Slf4j
@ComponentScan("com.yoox.great.context")
public class ContextAssemblyAutoConfiguration {
    @PostConstruct
    public void init() {
        log.info("【context】 assembly load");
    }
}
