package com.yoox.service.wayline.model.dto;

import com.yoox.great.mqtt.model.wayline.ExecutableConditions;
import com.yoox.great.mqtt.model.wayline.ReadyConditions;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WaylineTaskConditionDTO {

    private ReadyConditions readyConditions;

    private ExecutableConditions executableConditions;
}
