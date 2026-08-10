package com.yoox.service.manage.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LiveDTO {

    private String url;

    private Boolean reused;

    /**
     * Whether this HTTP request dispatched the device start command that made
     * the publisher available. {@code false} means the publisher predated the
     * request and must not be claimed by a new browser session.
     */
    private Boolean startedByRequest;

    private String username;

    private String password;
}
