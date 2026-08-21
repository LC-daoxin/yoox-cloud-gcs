package com.yoox.service.control.service;

import com.yoox.great.context.response.HttpResultResponse;
import com.yoox.service.control.model.enums.DroneAuthorityEnum;
import com.yoox.service.control.model.enums.RemoteDebugMethodEnum;
import com.yoox.service.control.model.param.*;
import com.yoox.great.mqtt.model.control.TargetDetectOpenRequest;

public interface IControlService {

    HttpResultResponse controlDockDebug(String sn, RemoteDebugMethodEnum serviceIdentifier, RemoteDebugParam param);

    HttpResultResponse flyToPoint(String sn, FlyToPointParam param);

    HttpResultResponse flyToPointStop(String sn);

    /**
     * 释放设备侧残留的点飞会话（已完成但 RC 固件未自行终结的 fly_to_point /
     * takeoff_to_point），供航线执行被 104 拒绝时自动清理；点飞任务仍活跃时拒绝清理。
     */
    HttpResultResponse releaseStaleFlightSessions(String sn);

    HttpResultResponse getPointFlightState(String sn);

    //    CommonTopicReceiver handleFlyToPointProgress(CommonTopicReceiver receiver, MessageHeaders headers);
    HttpResultResponse takeoffToPoint(String sn, TakeoffToPointParam param);

    HttpResultResponse seizeAuthority(String sn, DroneAuthorityEnum authority, DronePayloadParam param);

    /**
     * Seize control authority, optionally bypassing the local authority cache.
     *
     * <p>Most commands can trust a recently confirmed cache entry. DRC entry
     * cannot: the remote controller may have taken authority without the state
     * event reaching the cloud yet, so it must force a fresh device command.</p>
     */
    default HttpResultResponse seizeAuthority(
            String sn,
            DroneAuthorityEnum authority,
            DronePayloadParam param,
            boolean force) {
        return seizeAuthority(sn, authority, param);
    }

    HttpResultResponse payloadCommands(PayloadCommandsParam param) throws Exception;

    HttpResultResponse openTargetDetection(String sn, TargetDetectOpenRequest param);

    HttpResultResponse closeTargetDetection(String sn);
}
