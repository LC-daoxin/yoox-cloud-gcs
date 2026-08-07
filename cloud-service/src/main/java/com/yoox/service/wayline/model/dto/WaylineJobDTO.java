package com.yoox.service.wayline.model.dto;

import com.yoox.great.mqtt.enums.wayline.BarrierSwitchStateEnum;
import com.yoox.great.mqtt.enums.wayline.MediaUploadMethodEnum;
import com.yoox.great.mqtt.enums.wayline.OutOfControlActionEnum;
import com.yoox.great.mqtt.enums.wayline.TaskTypeEnum;
import com.yoox.great.mqtt.enums.wayline.WaylinePrecisionTypeEnum;
import com.yoox.great.mqtt.enums.wayline.WaylineTypeEnum;
import com.yoox.great.mqtt.model.wayline.AlternateLandPoint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class WaylineJobDTO {

    private String jobId;

    private String jobName;

    private String fileId;

    private String fileName;

    private String dockSn;

    private String dockName;

    private String workspaceId;

    private WaylineTypeEnum waylineType;

    private TaskTypeEnum taskType;

    private LocalDateTime executeTime;

    private LocalDateTime beginTime;

    private LocalDateTime endTime;

    private LocalDateTime completedTime;

    private Integer status;

    private Integer progress;

    private String username;

    private Integer code;

    private Integer rthAltitude;

    private OutOfControlActionEnum outOfControlAction;

    private Integer mediaCount;

    private Integer uploadedCount;

    private Boolean uploading;

    private WaylineTaskConditionDTO conditions;

    private String parentId;

    // Autel 航线管理扩展参数，仅内存/Redis 传递，不落库

    private WaylinePrecisionTypeEnum waylinePrecisionType;

    private BarrierSwitchStateEnum barrierSwitchState;

    private Integer takeoffAltitude;

    private Integer firstWaypointSpeed;

    private Integer returnSpeed;

    private MediaUploadMethodEnum mediaUploadMethod;

    private AlternateLandPoint alternateLandPoint;
}
