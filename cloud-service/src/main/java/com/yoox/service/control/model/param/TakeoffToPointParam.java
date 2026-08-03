package com.yoox.service.control.model.param;

import com.yoox.great.mqtt.enums.control.CommanderFlightModeEnum;
import com.yoox.great.mqtt.enums.control.CommanderModeLostActionEnum;
import com.yoox.great.mqtt.enums.device.ExitWaylineWhenRcLostEnum;
import com.yoox.great.mqtt.enums.device.RcLostActionEnum;
import com.yoox.great.mqtt.enums.wayline.RthModeEnum;
import lombok.Data;
import org.hibernate.validator.constraints.Range;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

@Data
public class TakeoffToPointParam {

    private String flightId;

    @Range(min = -180, max = 180)
    @NotNull
    private Double targetLongitude;

    @Range(min = -90, max = 90)
    @NotNull
    private Double targetLatitude;

    @Range(min = 2, max = 1500)
    @NotNull
    private Double targetHeight;

    @Range(min = 2, max = 1500)
    private Double securityTakeoffHeight;

    @Range(min = 2, max = 1500)
    private Double rthAltitude;

    private RcLostActionEnum rcLostAction;

    private ExitWaylineWhenRcLostEnum exitWaylineWhenRcLost;

    @Range(min = 1, max = 15)
    @NotNull
    private Double maxSpeed;

    private RthModeEnum rthMode;

    private CommanderModeLostActionEnum commanderModeLostAction;

    private CommanderFlightModeEnum commanderFlightMode;

    @Min(2)
    @Max(3000)
    private Float commanderFlightHeight;
}
