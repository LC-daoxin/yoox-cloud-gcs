package com.yoox.service.wayline.model.param;

import com.yoox.great.mqtt.enums.wayline.BarrierSwitchStateEnum;
import com.yoox.great.mqtt.enums.wayline.MediaUploadMethodEnum;
import com.yoox.great.mqtt.enums.wayline.OutOfControlActionEnum;
import com.yoox.great.mqtt.enums.wayline.TaskTypeEnum;
import com.yoox.great.mqtt.enums.wayline.WaylinePrecisionTypeEnum;
import com.yoox.great.mqtt.enums.wayline.WaylineTypeEnum;
import com.yoox.great.mqtt.model.wayline.AlternateLandPoint;
import lombok.Data;
import org.hibernate.validator.constraints.Range;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.util.List;

@Data
public class CreateJobParam {

    @NotBlank
    private String name;

    @NotBlank
    private String fileId;

    @NotBlank
    private String dockSn;

    @NotNull
    private WaylineTypeEnum waylineType;

    @NotNull
    private TaskTypeEnum taskType;

    @Range(min = 20, max = 500)
    @NotNull
    private Integer rthAltitude;

    @NotNull
    private OutOfControlActionEnum outOfControlAction;

    @Range(min = 50, max = 90)
    private Integer minBatteryCapacity;

    private Integer minStorageCapacity;

    private List<Long> taskDays;

    private List<List<Long>> taskPeriods;

    // 以下为 Autel 航线管理扩展参数（可选，未传时后端使用默认值）

    /**
     * 航线精度类型：0 GPS 任务，1 高精度 RTK 任务
     */
    private WaylinePrecisionTypeEnum waylinePrecisionType;

    /**
     * 避障开关：0 关闭，1 打开
     */
    private BarrierSwitchStateEnum barrierSwitchState;

    /**
     * 起飞高度（米）
     */
    @Range(min = 1, max = 1500)
    private Integer takeoffAltitude;

    /**
     * 飞往首航点的速度（m/s）
     */
    @Range(min = 1, max = 25)
    private Integer firstWaypointSpeed;

    /**
     * 返航速度（m/s）
     */
    @Range(min = 1, max = 25)
    private Integer returnSpeed;

    /**
     * 媒体上传方式：0 落地上传，1 边飞边传
     */
    private MediaUploadMethodEnum mediaUploadMethod;

    /**
     * 备降点（经纬度、安全降落高度、是否已配置）
     */
    @Valid
    private AlternateLandPoint alternateLandPoint;
}
