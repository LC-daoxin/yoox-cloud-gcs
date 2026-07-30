package com.yoox.service.manage.model.param;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class ChangePasswordParam {

    @NotBlank
    @JsonProperty("old_password")
    private String oldPassword;

    @NotBlank
    @Size(min = 12, max = 72)
    @JsonProperty("new_password")
    private String newPassword;
}
