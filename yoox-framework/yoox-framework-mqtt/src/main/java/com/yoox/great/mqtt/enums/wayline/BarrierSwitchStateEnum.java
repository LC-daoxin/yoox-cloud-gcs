package com.yoox.great.mqtt.enums.wayline;

import com.yoox.great.context.exception.CloudSDKException;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/**
 * 避障开关状态（Autel 航线管理 flighttask_prepare.barrier_switch_state）
 */
public enum BarrierSwitchStateEnum {

    /**
     * 关闭避障
     */
    DISABLE(0),

    /**
     * 打开避障
     */
    ENABLE(1),
    ;

    private final int state;

    BarrierSwitchStateEnum(int state) {
        this.state = state;
    }

    @JsonValue
    public int getState() {
        return state;
    }

    @JsonCreator
    public static BarrierSwitchStateEnum find(int state) {
        return Arrays.stream(values()).filter(stateEnum -> stateEnum.state == state).findAny()
            .orElseThrow(() -> new CloudSDKException(BarrierSwitchStateEnum.class, state));
    }

}
