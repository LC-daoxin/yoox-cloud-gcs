package com.yoox.service.control.model.param;

import com.yoox.great.mqtt.enums.control.CameraTypeEnum;
import com.yoox.great.mqtt.enums.control.LensStorageSettingsEnum;
import com.yoox.great.mqtt.enums.device.CameraModeEnum;
import com.yoox.service.control.model.enums.GimbalResetModeEnum;
import lombok.Data;
import org.hibernate.validator.constraints.Range;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import java.util.List;

@Data
public class DronePayloadParam {

    @Pattern(regexp = "\\d+-\\d+-\\d+")
    @NotNull
    private String payloadIndex;

    private CameraTypeEnum cameraType;

    @Range(min = 1, max = 160)
    private Float zoomFactor;

    @Range(min = 0, max = 2)
    private Integer zoomType;

    private CameraModeEnum cameraMode;

    private Boolean locked;

    private Double pitchSpeed;

    private Double yawSpeed;

    @Range(min = 0, max = 1)
    private Double x;

    @Range(min = 0, max = 1)
    private Double y;

    private GimbalResetModeEnum resetMode;

    private List<LensStorageSettingsEnum> photoStorageSettings;

    private List<LensStorageSettingsEnum> videoStorageSettings;

    @Range(min = -90, max = 90)
    private Float latitude;

    @Range(min = -180, max = 180)
    private Float longitude;

    @Range(min = 2, max = 10000)
    private Float height;
}
