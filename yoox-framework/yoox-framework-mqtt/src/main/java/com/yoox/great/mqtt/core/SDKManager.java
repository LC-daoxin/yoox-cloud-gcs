package com.yoox.great.mqtt.core;


import com.yoox.great.context.enums.device.DeviceDomainEnum;
import com.yoox.great.context.enums.device.DeviceEnum;
import com.yoox.great.context.enums.device.DeviceSubTypeEnum;
import com.yoox.great.context.enums.device.DeviceTypeEnum;
import com.yoox.great.context.enums.version.GatewayManager;
import com.yoox.great.context.enums.version.GatewayTypeEnum;
import com.yoox.great.context.exception.CloudSDKErrorEnum;
import com.yoox.great.context.exception.CloudSDKException;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class SDKManager {

    private SDKManager() {
    }

    private static final ConcurrentHashMap<String, GatewayManager> SDK_MAP = new ConcurrentHashMap<>(16);

    // 未注册时的恢复钩子：由业务层注入「按网关 SN 从持久化数据重建注册」的逻辑。
    // API 重启或定时任务误判离线清掉内存注册后，只要设备仍在通信即可自愈，
    // 避免出现「Redis 在线但 SDK 未注册」导致的 210001/211001。
    private static volatile Function<String, GatewayManager> registrationRecovery;

    public static void setRegistrationRecovery(Function<String, GatewayManager> recovery) {
        registrationRecovery = recovery;
    }

    public static GatewayManager getDeviceSDK(String gatewaySn) {
        GatewayManager gateway = SDK_MAP.get(gatewaySn);
        if (gateway != null) {
            return gateway;
        }
        Function<String, GatewayManager> recovery = registrationRecovery;
        if (recovery != null && gatewaySn != null) {
            GatewayManager recovered = recovery.apply(gatewaySn);
            if (recovered != null) {
                return recovered;
            }
        }
        throw new CloudSDKException(CloudSDKErrorEnum.NOT_REGISTERED,
                "The device has not been registered, please call the 'SDKManager.registerDevice()' method to register the device first.");
    }

    public static boolean isRegistered(String gatewaySn) {
        return SDK_MAP.containsKey(gatewaySn);
    }

    public static GatewayManager registerDevice(String gatewaySn, String droneSn,
                                                DeviceDomainEnum domain, DeviceTypeEnum type, DeviceSubTypeEnum subType, String gatewayThingVersion, String droneThingVersion) {
        return registerDevice(gatewaySn, droneSn, GatewayTypeEnum.find(DeviceEnum.find(domain, type, subType)), gatewayThingVersion, droneThingVersion);
    }

    public static GatewayManager registerDevice(String gatewaySn, String droneSn, GatewayTypeEnum type, String gatewayThingVersion, String droneThingVersion) {
        return registerDevice(new GatewayManager(Objects.requireNonNull(gatewaySn), droneSn, type, gatewayThingVersion, droneThingVersion));
    }

    public static GatewayManager registerDevice(GatewayManager gateway) {
        SDK_MAP.put(gateway.getGatewaySn(), gateway);
        return gateway;
    }

    public static void logoutDevice(String gatewaySn) {
        SDK_MAP.remove(gatewaySn);
    }
}
