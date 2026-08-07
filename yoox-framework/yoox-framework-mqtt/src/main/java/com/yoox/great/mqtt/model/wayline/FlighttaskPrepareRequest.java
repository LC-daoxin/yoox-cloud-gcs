package com.yoox.great.mqtt.model.wayline;

import com.yoox.great.context.annotations.CloudSDKVersion;
import com.yoox.great.context.base.BaseModel;
import com.yoox.great.context.enums.version.CloudSDKVersionEnum;
import com.yoox.great.context.enums.version.GatewayTypeEnum;
import com.yoox.great.mqtt.enums.device.ExitWaylineWhenRcLostEnum;
import com.yoox.great.mqtt.enums.wayline.*;

import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;

public class FlighttaskPrepareRequest extends BaseModel {

    /**
     * Task ID
     */
    @NotNull
    @Pattern(regexp = "^[^<>:\"/|?*._\\\\]+$")
    private String flightId;

    /**
     * Time to execute
     * Millisecond timestamp of task execution time. Optional field.
     * When the `task_type` is 0 or 1, it is required. When the `task_type` is 2, it is not required.
     */
    @Min(123456789012L)
    private Long executeTime;

    /**
     * Task type
     * The execution time of immediate task and timed task are defined by `execute_time`.
     * The conditional task supports the task readiness condition defined by `ready_conditions`.
     * The task can be executed if conditions are satisfied within a specified period.
     * Immediate task has the highest priority. Timed task and conditional task have the same priority.
     */
    @NotNull
    private TaskTypeEnum taskType;

    /**
     * Wayline type
     */
    @NotNull
    private WaylineTypeEnum waylineType;

    /**
     * Wayline file object
     */
    @NotNull
    @Valid
    private FlighttaskFile file;

    /**
     * Task readiness condition
     */
    @Valid
    private ReadyConditions readyConditions;

    /**
     * Task executable condition
     */
    @Valid
    private ExecutableConditions executableConditions;

    /**
     * Wayline breakpoint information
     */
    @Valid
    private FlighttaskBreakPoint breakPoint;

    /**
     * Height for RTH
     */
    @NotNull
    @Min(20)
    @Max(1500)
    private Integer rthAltitude;

    /**
     * Remote controller out of control action
     * Out of control action: the current fixed transmitted value is 0, meaning Return-to-Home (RTH).
     * Note that this enumeration value definition is inconsistent with the flight control and dock definitions,
     * and a conversion exists at the dock end.
     */
    @NotNull
    private OutOfControlActionEnum outOfControlAction;

    /**
     * wayline out of control action
     * consistent with the KMZ file
     */
    @NotNull
    private ExitWaylineWhenRcLostEnum exitWaylineWhenRcLost;

    @CloudSDKVersion(since = CloudSDKVersionEnum.V1_0_0)
    private RthModeEnum rthMode = RthModeEnum.PRESET_HEIGHT;

    @Valid
    @CloudSDKVersion(since = CloudSDKVersionEnum.V1_0_0)
    private SimulateMission simulateMission;

    @NotNull
    @CloudSDKVersion(since = CloudSDKVersionEnum.V1_0_1, include = GatewayTypeEnum.DOCK2)
    private WaylinePrecisionTypeEnum waylinePrecisionType;

    /**
     * 避障开关状态（Autel：0 关闭避障，1 打开避障）
     */
    private BarrierSwitchStateEnum barrierSwitchState;

    /**
     * 起飞高度（米）
     */
    private Integer takeoffAltitude;

    /**
     * 飞往首航点的速度（m/s）
     */
    private Integer firstWaypointSpeed;

    /**
     * 返航速度（m/s）
     */
    private Integer returnSpeed;

    /**
     * 媒体文件上传方式（Autel：0 落地上传，1 边飞边传）
     */
    private MediaUploadMethodEnum mediaUploadMethod;

    /**
     * 备降点信息
     */
    @Valid
    private AlternateLandPoint alternateLandPoint;

    public FlighttaskPrepareRequest() {
    }

    @Override
    public String toString() {
        return "FlighttaskPrepareRequest{" +
                "flightId='" + flightId + '\'' +
                ", executeTime=" + executeTime +
                ", taskType=" + taskType +
                ", waylineType=" + waylineType +
                ", file=" + file +
                ", readyConditions=" + readyConditions +
                ", executableConditions=" + executableConditions +
                ", breakPoint=" + breakPoint +
                ", rthAltitude=" + rthAltitude +
                ", outOfControlAction=" + outOfControlAction +
                ", exitWaylineWhenRcLost=" + exitWaylineWhenRcLost +
                ", rthMode=" + rthMode +
                ", simulateMission=" + simulateMission +
                ", waylinePrecisionType=" + waylinePrecisionType +
                ", barrierSwitchState=" + barrierSwitchState +
                ", takeoffAltitude=" + takeoffAltitude +
                ", firstWaypointSpeed=" + firstWaypointSpeed +
                ", returnSpeed=" + returnSpeed +
                ", mediaUploadMethod=" + mediaUploadMethod +
                ", alternateLandPoint=" + alternateLandPoint +
                '}';
    }

    public String getFlightId() {
        return flightId;
    }

    public FlighttaskPrepareRequest setFlightId(String flightId) {
        this.flightId = flightId;
        return this;
    }

    public Long getExecuteTime() {
        return executeTime;
    }

    public FlighttaskPrepareRequest setExecuteTime(Long executeTime) {
        this.executeTime = executeTime;
        return this;
    }

    public TaskTypeEnum getTaskType() {
        return taskType;
    }

    public FlighttaskPrepareRequest setTaskType(TaskTypeEnum taskType) {
        this.taskType = taskType;
        return this;
    }

    public WaylineTypeEnum getWaylineType() {
        return waylineType;
    }

    public FlighttaskPrepareRequest setWaylineType(WaylineTypeEnum waylineType) {
        this.waylineType = waylineType;
        return this;
    }

    public FlighttaskFile getFile() {
        return file;
    }

    public FlighttaskPrepareRequest setFile(FlighttaskFile file) {
        this.file = file;
        return this;
    }

    public ReadyConditions getReadyConditions() {
        return readyConditions;
    }

    public FlighttaskPrepareRequest setReadyConditions(ReadyConditions readyConditions) {
        this.readyConditions = readyConditions;
        return this;
    }

    public ExecutableConditions getExecutableConditions() {
        return executableConditions;
    }

    public FlighttaskPrepareRequest setExecutableConditions(ExecutableConditions executableConditions) {
        this.executableConditions = executableConditions;
        return this;
    }

    public FlighttaskBreakPoint getBreakPoint() {
        return breakPoint;
    }

    public FlighttaskPrepareRequest setBreakPoint(FlighttaskBreakPoint breakPoint) {
        this.breakPoint = breakPoint;
        return this;
    }

    public Integer getRthAltitude() {
        return rthAltitude;
    }

    public FlighttaskPrepareRequest setRthAltitude(Integer rthAltitude) {
        this.rthAltitude = rthAltitude;
        return this;
    }

    public OutOfControlActionEnum getOutOfControlAction() {
        return outOfControlAction;
    }

    public FlighttaskPrepareRequest setOutOfControlAction(OutOfControlActionEnum outOfControlAction) {
        this.outOfControlAction = outOfControlAction;
        return this;
    }

    public ExitWaylineWhenRcLostEnum getExitWaylineWhenRcLost() {
        return exitWaylineWhenRcLost;
    }

    public FlighttaskPrepareRequest setExitWaylineWhenRcLost(ExitWaylineWhenRcLostEnum exitWaylineWhenRcLost) {
        this.exitWaylineWhenRcLost = exitWaylineWhenRcLost;
        return this;
    }

    public RthModeEnum getRthMode() {
        return rthMode;
    }

    public FlighttaskPrepareRequest setRthMode(RthModeEnum rthMode) {
        this.rthMode = rthMode;
        return this;
    }

    public SimulateMission getSimulateMission() {
        return simulateMission;
    }

    public FlighttaskPrepareRequest setSimulateMission(SimulateMission simulateMission) {
        this.simulateMission = simulateMission;
        return this;
    }

    public WaylinePrecisionTypeEnum getWaylinePrecisionType() {
        return waylinePrecisionType;
    }

    public FlighttaskPrepareRequest setWaylinePrecisionType(WaylinePrecisionTypeEnum waylinePrecisionType) {
        this.waylinePrecisionType = waylinePrecisionType;
        return this;
    }

    public BarrierSwitchStateEnum getBarrierSwitchState() {
        return barrierSwitchState;
    }

    public FlighttaskPrepareRequest setBarrierSwitchState(BarrierSwitchStateEnum barrierSwitchState) {
        this.barrierSwitchState = barrierSwitchState;
        return this;
    }

    public Integer getTakeoffAltitude() {
        return takeoffAltitude;
    }

    public FlighttaskPrepareRequest setTakeoffAltitude(Integer takeoffAltitude) {
        this.takeoffAltitude = takeoffAltitude;
        return this;
    }

    public Integer getFirstWaypointSpeed() {
        return firstWaypointSpeed;
    }

    public FlighttaskPrepareRequest setFirstWaypointSpeed(Integer firstWaypointSpeed) {
        this.firstWaypointSpeed = firstWaypointSpeed;
        return this;
    }

    public Integer getReturnSpeed() {
        return returnSpeed;
    }

    public FlighttaskPrepareRequest setReturnSpeed(Integer returnSpeed) {
        this.returnSpeed = returnSpeed;
        return this;
    }

    public MediaUploadMethodEnum getMediaUploadMethod() {
        return mediaUploadMethod;
    }

    public FlighttaskPrepareRequest setMediaUploadMethod(MediaUploadMethodEnum mediaUploadMethod) {
        this.mediaUploadMethod = mediaUploadMethod;
        return this;
    }

    public AlternateLandPoint getAlternateLandPoint() {
        return alternateLandPoint;
    }

    public FlighttaskPrepareRequest setAlternateLandPoint(AlternateLandPoint alternateLandPoint) {
        this.alternateLandPoint = alternateLandPoint;
        return this;
    }
}