package com.yoox.great.mqtt.model.control;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * DRC obstacle-sensing telemetry.
 *
 * <p>Autel reports every distance in metres. A value of {@code -1} means that
 * the corresponding radar did not detect an obstacle. The legacy DJI-style
 * enable/work and around-distance fields remain for protocol compatibility.</p>
 */
@Data
@Accessors(chain = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class HsiInfoPush {

    private Number upDistance;

    private Number downDistance;

    private Number front1Distance;

    private Number front2Distance;

    private Number front3Distance;

    private Number front4Distance;

    private Number left1Distance;

    private Number left2Distance;

    private Number left3Distance;

    private Number rear1Distance;

    private Number rear2Distance;

    private Number rear3Distance;

    private Number rear4Distance;

    private Number right1Distance;

    private Number right2Distance;

    private Number right3Distance;

    private Boolean radarEnable;

    private List<Integer> aroundDistance;

    private Boolean upEnable;

    private Boolean upWork;

    private Boolean downEnable;

    private Boolean downWork;

    private Boolean leftEnable;

    private Boolean leftWork;

    private Boolean rightEnable;

    private Boolean rightWork;

    private Boolean frontEnable;

    private Boolean frontWork;

    private Boolean backEnable;

    private Boolean backWork;

    private Boolean verticalEnable;

    private Boolean verticalWork;

    private Boolean horizontalEnable;

    private Boolean horizontalWork;
}
